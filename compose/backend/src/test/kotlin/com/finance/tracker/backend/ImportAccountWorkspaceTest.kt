package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ImportAccountWorkspaceTest(private val backend: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun backends(): List<Array<String>> = listOf(arrayOf("sqlite"), arrayOf("postgres"))
    }
    private val clock = { Instant.parse("2026-11-16T00:00:00Z") }
    private fun authenticated(action: (Database, String, String, String) -> Unit) = withCashWriteSetTestWorkspace(backend) { db, original, _ ->
        val later = UUID.randomUUID().toString(); val user = UUID.randomUUID().toString(); val token = "scope-$user"
        db.write { c ->
            val sql = c.jooq(db.sqlite)
            sql.insertInto(USERS).set(USERS.ID, user).set(USERS.EMAIL, "$user@example.invalid")
                .set(USERS.PASSWORD_HASH, "unused-synthetic-hash").set(USERS.CREATED_AT, clock()).execute()
            sql.insertInto(WORKSPACES).set(WORKSPACES.ID, later).set(WORKSPACES.NAME, "Later workspace").set(WORKSPACES.CREATED_AT, clock()).execute()
            for (workspace in listOf(original, later)) sql.insertInto(WORKSPACE_MEMBERSHIPS).set(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID, workspace)
                .set(WORKSPACE_MEMBERSHIPS.USER_ID, user).set(WORKSPACE_MEMBERSHIPS.ROLE, "admin").set(WORKSPACE_MEMBERSHIPS.CREATED_AT, clock()).execute()
            sql.insertInto(USER_SESSIONS).set(USER_SESSIONS.ID, UUID.randomUUID().toString()).set(USER_SESSIONS.USER_ID, user)
                .set(USER_SESSIONS.TOKEN_DIGEST, tokenDigest(token)).set(USER_SESSIONS.ACTIVE_WORKSPACE_ID, original)
                .set(USER_SESSIONS.CREATED_AT, clock()).set(USER_SESSIONS.EXPIRES_AT, Instant.parse("2099-01-01T00:00:00Z")).execute()
        }
        try { action(db, original, later, token) }
        finally { db.write { c ->
            val sql = c.jooq(db.sqlite)
            sql.deleteFrom(WORKSPACES).where(WORKSPACES.ID.eq(later)).execute()
            sql.deleteFrom(CASH_MUTATION_RECEIPTS).where(CASH_MUTATION_RECEIPTS.WORKSPACE_ID.eq(original)).execute()
            sql.deleteFrom(USER_SESSIONS).where(USER_SESSIONS.USER_ID.eq(user)).execute()
            sql.deleteFrom(WORKSPACE_MEMBERSHIPS).where(WORKSPACE_MEMBERSHIPS.USER_ID.eq(user)).execute()
            sql.deleteFrom(USERS).where(USERS.ID.eq(user)).execute()
        } }
    }
    private fun select(db: Database, token: String, workspace: String) = db.write { c ->
        assertEquals(1, c.jooq(db.sqlite).update(USER_SESSIONS).set(USER_SESSIONS.ACTIVE_WORKSPACE_ID, workspace)
            .where(USER_SESSIONS.TOKEN_DIGEST.eq(tokenDigest(token))).execute())
    }
    private val createBody = """{"name":"Original import account","type":"cash","currencies":["CNY"]}"""
    private suspend fun HttpClient.accountRequest(method: HttpMethod, path: String, token: String,
        body: String? = null, key: String? = null, status: Int = 200): JsonObject {
        val response = request(path) {
            this.method = method; bearerAuth(token)
            key?.let { header("Idempotency-Key", it) }
            body?.let { contentType(ContentType.Application.Json); setBody(it) }
        }
        val text = response.bodyAsText()
        assertEquals(status, response.status.value, "$method $path: $text")
        return Json.parseToJsonElement(text).jsonObject
    }
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun currencyBody(vararg ids: String) = buildJsonObject {
        put("items", JsonArray(ids.mapIndexed { index, id -> buildJsonObject {
            put("account_id", id); put("expected_revision", 1); put("idempotency_key", "currency-$index")
            put("currencies", JsonArray(listOf(JsonPrimitive("USD"))))
        } }))
    }.toString()
    @Test fun originalWorkspaceCrudKeepsRealCapabilitiesAndPermanentReceipts() = authenticated { db, original, _, token ->
        testApplication {
            application { financeModule(db, clock = clock) }
            val scope = "?workspace_id=$original"
            val created = client.accountRequest(HttpMethod.Post, "/api/v1/accounts$scope", token, createBody, "create", 201)
            val id = created.text("account_id"); val path = "/api/v1/accounts/by-id/$id$scope"
            assertEquals("1", created.text("account_revision"))
            val active = client.accountRequest(HttpMethod.Get, path, token)
            assertEquals(JsonPrimitive(true), active.getValue("capabilities").jsonObject["can_update"])
            assertEquals(JsonPrimitive(false), active.getValue("capabilities").jsonObject["can_delete"])
            assertTrue(client.accountRequest(HttpMethod.Get, "/api/v1/accounts$scope", token).getValue("items").jsonArray.any { it.jsonObject.text("id") == id })
            client.accountRequest(HttpMethod.Delete, path, token, """{"expected_revision":1}""", "active-delete", 409)
            val stopped = client.accountRequest(HttpMethod.Patch, path, token, """{"expected_revision":1,"patch":{"active":false}}""", "stop")
            assertEquals("2", stopped.text("account_revision"))
            val body = currencyBody(id).replace("\"expected_revision\":1", "\"expected_revision\":2")
            val currency = client.accountRequest(HttpMethod.Post, "/api/v1/cash-import/accounts/currency-requirements$scope", token, body)
                .getValue("results").jsonArray.single().jsonObject.getValue("account").jsonObject
            assertEquals("3", currency.text("account_revision"))
            assertEquals(JsonArray(listOf(JsonPrimitive("CNY"), JsonPrimitive("USD"))), currency["currencies"])
            assertEquals(JsonPrimitive(true), client.accountRequest(HttpMethod.Get, path, token).getValue("capabilities").jsonObject["can_delete"])
            val deleted = client.accountRequest(HttpMethod.Delete, path, token, """{"expected_revision":3}""", "delete")
            assertEquals(JsonPrimitive(true), deleted["deleted"]); assertEquals("4", deleted.text("account_revision"))
            val saved = client.accountRequest(HttpMethod.Get, "/api/v1/cash-mutations/receipts/by-key$scope&key=delete", token)
            assertEquals(deleted.text("receipt_id"), saved.text("receipt_id"))
            assertEquals(saved, client.accountRequest(HttpMethod.Get, "/api/v1/cash-mutations/receipts/${saved.text("receipt_id")}$scope", token))
            assertEquals(deleted - setOf("receipt_id", "committed"), saved.getValue("account").jsonObject)
            assertFalse(client.accountRequest(HttpMethod.Get, "/api/v1/accounts$scope", token).getValue("items").jsonArray.any { it.jsonObject.text("id") == id })
        }
    }
    @Test fun wrongWorkspaceAndMalformedScopeCannotReadWriteOrReplayLaterAccounts() = authenticated { db, original, later, token ->
        val service = AccountService(db, now = clock)
        service.create(token, Json.parseToJsonElement(createBody).jsonObject, "same-key")
        select(db, token, later)
        val laterReceipt = service.create(token, Json.parseToJsonElement(createBody).jsonObject, "same-key")
        val id = laterReceipt.getValue("account").jsonObject.text("account_id")
        val unchanged = detailDatabaseState(db)
        for (read in listOf<() -> Unit>({ service.list(token, "cash", original) }, { service.get(token, id, original) }, { service.authorizeWrite(token, original) })) {
            assertEquals("workspace_forbidden", assertFailsWith<AccountFailure> { read() }.code)
        }
        testApplication {
            application { financeModule(db, accounts = service, clock = clock) }
            val scope = "?workspace_id=$original"; val path = "/api/v1/accounts/by-id/$id$scope"
            val requests = listOf(
                Triple(HttpMethod.Get, "/api/v1/accounts$scope", null), Triple(HttpMethod.Get, path, null),
                Triple(HttpMethod.Post, "/api/v1/accounts$scope", createBody),
                Triple(HttpMethod.Patch, path, """{"expected_revision":1,"patch":{"name":"Wrong workspace"}}"""),
                Triple(HttpMethod.Delete, path, """{"expected_revision":1}"""),
                Triple(HttpMethod.Post, "/api/v1/cash-import/accounts/currency-requirements$scope", currencyBody(id)))
            for ((method, url, body) in requests) {
                val rejected = client.accountRequest(method, url, token, body, "same-key", 403)
                assertEquals("workspace_forbidden", rejected.getValue("error").jsonObject.text("code"))
            }
            assertEquals(unchanged, detailDatabaseState(db))
            select(db, token, original)
            for (query in listOf("workspace_id=", "workspace_id=$original&workspace_id=$original")) {
                assertEquals("account_payload_invalid", client.accountRequest(HttpMethod.Get, "/api/v1/accounts?$query", token, status = 400)
                    .getValue("error").jsonObject.text("code"))
            }
            assertEquals(service.list(token, "cash"), client.accountRequest(HttpMethod.Get, "/api/v1/accounts?view=investment&view=cash&unused=legacy", token))
            assertEquals("account_create", client.accountRequest(HttpMethod.Get, "/api/v1/cash-mutations/receipts/by-key$scope&key=same-key", token).text("operation_kind"))
        }
    }
    @Test fun transactionScopePrecedesUpdateDeleteCurrenciesAndReceiptReplay() = authenticated { db, original, later, token ->
        val setup = AccountService(db, now = clock)
        setup.create(token, Json.parseToJsonElement(createBody).jsonObject, "same-key")
        select(db, token, later)
        val id = setup.create(token, Json.parseToJsonElement(createBody).jsonObject, "same-key").getValue("account").jsonObject.text("account_id")
        setup.update(token, id, buildJsonObject { put("active", false) }, 1, "stop")
        select(db, token, original)
        var switched: Any? = null
        val service = AccountService(db, now = clock, beforeWrite = { select(db, token, later); switched = detailDatabaseState(db) })
        testApplication {
            application { financeModule(db, accounts = service, clock = clock) }
            val scope = "?workspace_id=$original"; val path = "/api/v1/accounts/by-id/$id$scope"
            for ((method, url, body) in listOf(
                Triple(HttpMethod.Post, "/api/v1/accounts$scope", createBody),
                Triple(HttpMethod.Patch, path, """{"expected_revision":2,"patch":{"name":"Wrong"}}"""),
                Triple(HttpMethod.Delete, path, """{"expected_revision":2}"""))) {
                select(db, token, original)
                assertEquals("workspace_forbidden", client.accountRequest(method, url, token, body, "same-key", 403).getValue("error").jsonObject.text("code"))
                assertEquals(switched, detailDatabaseState(db))
            }
            select(db, token, original)
            val result = client.accountRequest(HttpMethod.Post, "/api/v1/cash-import/accounts/currency-requirements$scope", token, currencyBody(id))
                .getValue("results").jsonArray.single().jsonObject
            assertEquals("failed", result.text("status")); assertEquals("workspace_forbidden", result.getValue("error").jsonObject.text("code"))
            assertEquals(switched, detailDatabaseState(db))
        }
    }
    @Test fun currencyBatchRetainsFirstOriginalCommitAndRejectsLaterWorkspaceItems() = authenticated { db, original, later, token ->
        val setup = AccountService(db, now = clock)
        val first = setup.create(token, Json.parseToJsonElement(createBody).jsonObject, "create").getValue("account").jsonObject.text("account_id")
        val second = setup.create(token, Json.parseToJsonElement(createBody.replace("Original import account", "Second")).jsonObject, "second").getValue("account").jsonObject.text("account_id")
        val service = AccountService(db, now = clock, afterCommit = { select(db, token, later) })
        testApplication {
            application { financeModule(db, accounts = service, clock = clock) }
            val results = client.accountRequest(HttpMethod.Post, "/api/v1/cash-import/accounts/currency-requirements?workspace_id=$original", token, currencyBody(first, second))
                .getValue("results").jsonArray.map { it.jsonObject }
            assertEquals("updated", results[0].text("status")); assertEquals("failed", results[1].text("status"))
            assertEquals("workspace_forbidden", results[1].getValue("error").jsonObject.text("code"))
            assertEquals(emptyList(), setup.list(token, "cash").getValue("items").jsonArray.toList())
            select(db, token, original)
            assertEquals(listOf("CNY", "USD"), setup.get(token, first).currencies)
            assertEquals(listOf("CNY"), setup.get(token, second).currencies)
            val receipt = client.accountRequest(HttpMethod.Get, "/api/v1/cash-mutations/receipts/by-key?workspace_id=$original&key=currency-0", token)
            assertEquals(results[0].getValue("account").jsonObject.text("receipt_id"), receipt.text("receipt_id"))
        }
    }
    @Test fun lostCreateResponseRecoversOriginalReceiptWithoutWritingToLaterWorkspace() = authenticated { db, original, later, token ->
        var lose = true
        val service = AccountService(db, now = clock, afterCommit = { if (lose) { lose = false; throw StorageFailure("storage.connect") } })
        testApplication {
            application { financeModule(db, accounts = service, clock = clock) }
            val path = "/api/v1/accounts?workspace_id=$original"
            val unknown = client.accountRequest(HttpMethod.Post, path, token, createBody, "lost", 503)
            assertEquals("unknown", unknown.text("commit_state"))
            select(db, token, later)
            client.accountRequest(HttpMethod.Post, path, token, createBody, "lost", 403)
            assertTrue(service.list(token, "cash").getValue("items").jsonArray.isEmpty())
            select(db, token, original)
            val receipt = client.accountRequest(HttpMethod.Get, "/api/v1/cash-mutations/receipts/by-key?workspace_id=$original&key=lost", token)
            val replay = client.accountRequest(HttpMethod.Post, path, token, createBody, "lost", 201)
            assertEquals(receipt.text("receipt_id"), replay.text("receipt_id"))
            assertEquals(receipt.getValue("account").jsonObject.text("account_id"), replay.text("account_id"))
            assertEquals(2, service.list(token, "cash").getValue("items").jsonArray.size)
        }
    }
    @Test fun originalWorkspaceIsCheckedInsideAccountCreationAfterMiddleware() = authenticated { db, original, later, token ->
        var afterSwitch: Any? = null
        val service = AccountService(db, now = clock, beforeWrite = {
            select(db, token, later)
            afterSwitch = detailDatabaseState(db)
        })
        testApplication {
            application { financeModule(db, accounts = service, clock = clock) }
            val response = client.post("/api/v1/accounts?workspace_id=$original") {
                bearerAuth(token); header("Idempotency-Key", "original-create"); contentType(ContentType.Application.Json)
                setBody("""{"name":"Original import account","type":"cash","currencies":["CNY"]}""")
            }
            assertEquals(403, response.status.value, response.bodyAsText())
            assertEquals("workspace_forbidden", Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            assertNotNull(afterSwitch)
            assertEquals(afterSwitch, detailDatabaseState(db))
        }
    }
}
