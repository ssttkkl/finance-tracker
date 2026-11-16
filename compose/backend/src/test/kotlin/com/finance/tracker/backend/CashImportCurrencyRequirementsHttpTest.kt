package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.LEDGER_SNAPSHOTS
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.sql.DriverManager
import java.util.Properties
import java.util.UUID
import kotlin.test.*
import kotlinx.serialization.json.*

class CashImportCurrencyRequirementsHttpTest {
    @Test fun currencyRequirementsContractOnSqlite() = onSqlite(::exerciseCurrencies)
    @Test fun currencyRequirementsContractOnExplicitPostgres() = onPostgres(::exerciseCurrencies)

    private fun exerciseCurrencies(database: Database) {
        database.bootstrapSchema()
        val token = AuthenticationService(database).register("currencies@example.invalid", "synthetic secure password").accessToken
        val workspace = WorkspaceService(database).create(token, "独立账户币种回归").activeWorkspaceId!!
        var preflightUnavailable = false
        var loseResponse = false
        var commitsUntilBug: Int? = null
        val accounts = AccountService(database,
            beforeWorkspaceLock = { if (preflightUnavailable) throw StorageFailure("storage.read", IllegalStateException("private provider details")) },
            afterCommit = { if (loseResponse) { loseResponse = false; throw StorageFailure("storage.write", IllegalStateException("private SQL")) } },
            beforeCommit = { commitsUntilBug?.let { remaining ->
                commitsUntilBug = remaining - 1
                if (remaining == 1) error("synthetic internal bug")
            } })
        fun create(name: String) = accounts.create(token, buildJsonObject {
            put("name", name); put("type", "cash"); put("currencies", buildJsonArray { add("CNY"); add("EUR") })
        }, "create-$name").getValue("account").jsonObject.text("account_id")
        val first = create("first"); val stale = create("stale"); val last = create("last")
        fun pockets(id: String): JsonObject = database.read { c ->
            val s = LEDGER_SNAPSHOTS
            c.jooq(database.sqlite).select(s.PAYLOAD).from(s).where(s.WORKSPACE_ID.eq(workspace)).fetchOne(s.PAYLOAD)!!
                .jsonObject.getValue("accounts").jsonObject.getValue("cash").jsonObject.getValue(id).jsonObject
        }
        val originalBalances = buildJsonObject { put("CNY", "123.45"); put("EUR", "-6.75") }
        database.write { c ->
            val s = LEDGER_SNAPSHOTS
            val snapshot = c.jooq(database.sqlite).select(s.PAYLOAD).from(s).where(s.WORKSPACE_ID.eq(workspace)).fetchOne(s.PAYLOAD)!!.jsonObject
            val buckets = snapshot.getValue("accounts").jsonObject
            val cash = JsonObject(buckets.getValue("cash").jsonObject + (first to originalBalances))
            c.jooq(database.sqlite).update(s).set(s.PAYLOAD, JsonObject(snapshot + ("accounts" to JsonObject(buckets + ("cash" to cash)))))
                .where(s.WORKSPACE_ID.eq(workspace)).execute()
        }
        fun item(id: String, key: String, revision: Int = 1, currencies: List<String> = listOf("usd")) = buildJsonObject {
            put("account_id", id); put("idempotency_key", key); put("expected_revision", revision)
            put("currencies", JsonArray(currencies.map(::JsonPrimitive)))
        }
        fun body(vararg items: JsonObject) = buildJsonObject { put("items", JsonArray(items.toList())) }
        val command = body(item(first, "ensure-first"), item(stale, "ensure-stale", 99), item(last, "ensure-last", currencies = listOf("JPY")))
        fun receiptCount() = database.read { c -> c.createStatement().use { s ->
            s.executeQuery("SELECT count(*) FROM cash_mutation_receipts").use { it.next(); it.getInt(1) }
        } }
        testApplication {
            application { financeModule(database, accounts = accounts) }
            suspend fun post(raw: String, expected: Int = 200, bearer: String? = token): JsonObject = response(client.post("/api/v1/cash-import/accounts/currency-requirements") {
                bearer?.let { bearerAuth(it) }; contentType(ContentType.Application.Json); setBody(raw)
            }, expected)
            val result = post(command.toString()).getValue("results").jsonArray.map { it.jsonObject }
            assertEquals(listOf(first, stale, last), result.map { it.text("account_id") })
            assertEquals(listOf("updated", "failed", "updated"), result.map { it.text("status") })
            val saved = result[0].getValue("account").jsonObject
            assertEquals(listOf("CNY", "EUR", "USD"), saved.getValue("currencies").jsonArray.map { it.jsonPrimitive.content })
            originalBalances.forEach { (currency, amount) -> assertEquals(amount, pockets(first)[currency], "Existing nonzero balances must be preserved") }
            assertEquals(JsonPrimitive("0"), pockets(first)["USD"])
            assertEquals(JsonPrimitive(2), saved["account_revision"]); assertEquals(JsonPrimitive(true), saved["committed"])
            assertEquals("account_changed", result[1].getValue("error").jsonObject.text("code"))
            assertEquals(JsonPrimitive(409), result[1].getValue("error").jsonObject["status"])
            assertEquals(listOf("CNY", "EUR", "JPY"), accounts.get(token, last).currencies)
            assertEquals(1L, accounts.get(token, stale).accountRevision)
            val count = receiptCount()
            assertEquals(5, count, "Three creates and two independent updates")
            assertEquals(JsonArray(result), post(command.toString())["results"], "Replay returns the original per-item accounts and receipts")
            assertEquals(count, receiptCount())
            val ordinaryReplay = response(client.patch("/api/v1/accounts/by-id/$first") {
                bearerAuth(token); header("Idempotency-Key", "ensure-first"); contentType(ContentType.Application.Json)
                setBody("""{"expected_revision":1,"patch":{"currencies":["USD"]}}""")
            }, 409)
            assertEquals("idempotency_conflict", ordinaryReplay.getValue("error").jsonObject.text("code"))
            val revision = accounts.get(token, first).accountRevision
            val invalid = listOf(
                "{}", """{"items":[]}""", body(item(first, "duplicate"), item(first, "other")).toString(),
                body(item(first, "duplicate"), item(last, "duplicate")).toString(),
                body(item(first, "invalid", currencies = listOf("US"))).toString(),
                body(item(first, "invalid", currencies = emptyList())).toString(),
                body(JsonObject(item(first, "invalid") + ("active" to JsonPrimitive(false)))).toString(),
                body(JsonObject(item(first, "invalid") + ("expected_revision" to JsonPrimitive(2.0)))).toString(),
                body(item(first, "valid-prefix", 2, listOf("HKD")), item(last, "invalid-tail", 2, emptyList())).toString(),
                """{"items":[],"items":[]} """, " ".repeat(65_537) + command,
                body(*Array(101) { item((it + 1).toString(), "limit-$it") }).toString(),
            )
            for (raw in invalid) {
                assertEquals("account_payload_invalid", post(raw, 400).getValue("error").jsonObject.text("code"))
                assertEquals(revision, accounts.get(token, first).accountRevision)
                assertEquals(count, receiptCount(), "Protocol rejection must precede every independent write")
            }
            post(command.toString(), 401, null)
            assertEquals(count, receiptCount())
            val noOp = post(body(item(first, "already-supported", 2, listOf("USD"))).toString())
                .getValue("results").jsonArray.single().jsonObject.getValue("account").jsonObject
            assertEquals(JsonPrimitive(3), noOp["account_revision"], "Independent same-value commands retain the ordinary revision contract")
            fun role(value: String) = database.write { c ->
                c.prepareStatement("UPDATE workspace_memberships SET role = ? WHERE workspace_id = ?").use {
                    it.setString(1, value); it.setString(2, workspace); it.executeUpdate()
                }
            }
            val beforeViewer = receiptCount()
            role("viewer")
            assertEquals("workspace_forbidden", post(body(item(stale, "viewer")).toString(), 403).getValue("error").jsonObject.text("code"))
            assertEquals(beforeViewer, receiptCount()); role("admin")
            val lostCommand = body(item(first, "lost-result", 3, listOf("HKD")), item(last, "after-unknown", 2, listOf("AUD")))
            loseResponse = true
            val unknown = post(lostCommand.toString()).getValue("results").jsonArray
            val error = unknown[0].jsonObject.getValue("error").jsonObject
            assertEquals("account_result_unknown", error.text("code")); assertEquals("unknown", error.text("commit_state"))
            assertEquals(JsonPrimitive(true), error["retryable"]); assertEquals(JsonPrimitive(503), error["status"])
            assertFalse(unknown.toString().contains("private"))
            assertEquals("updated", unknown[1].jsonObject.text("status"))
            assertEquals(4L, accounts.get(token, first).accountRevision, "The lost response followed a real committed account command")
            val unknownCount = receiptCount()
            val recovered = post(lostCommand.toString()).getValue("results").jsonArray
            assertEquals(JsonPrimitive(4), recovered[0].jsonObject.getValue("account").jsonObject["account_revision"])
            assertEquals(unknown[1], recovered[1]); assertEquals(unknownCount, receiptCount())
            preflightUnavailable = true
            val unavailable = post(body(item(stale, "preflight")).toString(), 503)
            preflightUnavailable = false
            assertEquals("account_unavailable", unavailable.getValue("error").jsonObject.text("code"))
            assertEquals("not_committed", unavailable.text("commit_state")); assertFalse(unavailable.toString().contains("private"))
            assertEquals(unknownCount, receiptCount()); assertEquals(1L, accounts.get(token, stale).accountRevision)
            commitsUntilBug = 2
            val bug = client.post("/api/v1/cash-import/accounts/currency-requirements") {
                bearerAuth(token); contentType(ContentType.Application.Json)
                setBody(body(item(stale, "before-bug"), item(first, "bug", 4), item(last, "after-bug", 3)).toString())
            }
            assertEquals(500, bug.status.value, "Program errors must stop the batch, rather than become a failed item")
            commitsUntilBug = null
            assertEquals(2L, accounts.get(token, stale).accountRevision, "Earlier independent success remains committed")
            assertEquals(4L, accounts.get(token, first).accountRevision, "The program-error item rolls back")
            assertEquals(3L, accounts.get(token, last).accountRevision, "Later items must not run after a program error")
            assertEquals(unknownCount + 1, receiptCount())
        }
    }

    private fun onSqlite(action: (Database) -> Unit) {
        val temporary = Files.createTempDirectory("ft-cash-slice-")
        try {
            action(Database("jdbc:sqlite:${temporary.resolve("import_test.db")}"))
        } finally {
            Files.walk(temporary).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun onPostgres(action: (Database) -> Unit) {
        val configured = System.getenv("FT_TEST_POSTGRES_URL")
        org.junit.Assume.assumeTrue("FT_TEST_POSTGRES_URL is not configured", configured != null)
        val uri = URI(configured!!.replaceFirst("postgresql+psycopg://", "postgresql://").replaceFirst("postgres://", "postgresql://"))
        require(uri.path.removePrefix("/").endsWith("_test")) { "Import tests require an explicit _test database" }
        val credentials = uri.rawUserInfo?.split(':', limit = 2).orEmpty()
        fun credential(index: Int) = credentials.getOrNull(index)?.let { URLDecoder.decode(it.replace("+", "%2B"), StandardCharsets.UTF_8) }
        val user = credential(0)
        val password = credential(1)
        val server = "jdbc:postgresql://${requireNotNull(uri.host)}:${if (uri.port < 0) 5432 else uri.port}/"
        val properties = Properties().apply {
            user?.let { setProperty("user", it) }
            password?.let { setProperty("password", it) }
        }
        val name = "ft_cash_slice_${UUID.randomUUID().toString().replace("-", "")}_test"
        fun administration(sql: String) = DriverManager.getConnection(server + uri.path.removePrefix("/"), properties).use { connection ->
            connection.createStatement().use { it.execute(sql) }
        }
        administration("CREATE DATABASE \"$name\"")
        try {
            action(Database(server + name, user, password))
        } finally {
            administration("DROP DATABASE IF EXISTS \"$name\" WITH (FORCE)")
        }
    }

    private suspend fun response(value: HttpResponse, expectedStatus: Int = 200): JsonObject {
        assertEquals(expectedStatus, value.status.value, "Import contract HTTP status")
        return Json.parseToJsonElement(value.bodyAsText()).jsonObject
    }
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
}
