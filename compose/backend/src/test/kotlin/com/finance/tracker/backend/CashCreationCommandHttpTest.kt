package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.CASH_TRANSACTIONS
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.net.URI
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*

class CashCreationCommandHttpTest {
    @Test fun sameCommandHasOneEffectOnSqlite() {
        val directory = Files.createTempDirectory("ft-cash-command-")
        try { exercise(Database("jdbc:sqlite:${directory.resolve("cash_test.db")}")) }
        finally { directory.toFile().deleteRecursively() }
    }

    @Test fun sameCommandHasOneEffectOnPostgres() {
        val configured = System.getenv("FT_TEST_POSTGRES_URL")
        org.junit.Assume.assumeTrue("FT_TEST_POSTGRES_URL is not configured", configured != null)
        val uri = URI(configured!!.replaceFirst("postgres://", "postgresql://"))
        require(uri.scheme == "postgresql" && uri.path.endsWith("_test"))
        val credentials = uri.userInfo?.split(':', limit = 2).orEmpty()
        exercise(Database("jdbc:postgresql://${uri.host}:${if (uri.port < 0) 5432 else uri.port}${uri.path}",
            credentials.firstOrNull(), credentials.getOrNull(1)))
    }

    private fun exercise(database: Database) {
        database.bootstrapSchema()
        val token = AuthenticationService(database).register("creation-${UUID.randomUUID()}@example.invalid", "synthetic secure password").accessToken
        val workspace = WorkspaceService(database).create(token, "Cash commands").activeWorkspaceId!!
        val accounts = AccountService(database)
        fun account(name: String, key: String = "create-$name") = accounts.create(token,
            Json.parseToJsonElement("""{"name":"$name","type":"cash","currencies":["CNY"]}""").jsonObject, key)
            .getValue("account").jsonObject.getValue("account_id").jsonPrimitive.content
        val wallet = account("Wallet")
        val bank = account("Bank")
        val record = """{"account_name":"Wallet","amount":"-10","currency":"CNY","occurred_at":"2026-11-19T00:00:00+00:00","record_type":"consumption","record_subtype":"not_applicable","note":"sensitive note"}"""
        val transfer = """{"from_account":"Wallet","to_account":"Bank","amount":"10","from_currency":"CNY","to_currency":"CNY","date":"2026-11-19","time":"00:00:01","note":"sensitive note"}"""
        fun count(): Int = database.read { connection ->
            val sql = connection.jooq(database.sqlite)
            sql.fetchCount(CASH_TRANSACTIONS, CASH_TRANSACTIONS.WORKSPACE_ID.eq(workspace))
        }
        testApplication {
            application { financeModule(database) }
            suspend fun send(path: String, body: String, key: String?, scope: String? = workspace,
                ids: String = if (path.contains("transfer")) "$wallet,$bank" else wallet) = client.post(path) {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                key?.let { header("Idempotency-Key", it) }
                scope?.let { header("X-FT-Workspace-ID", it) }
                header("X-FT-Account-IDs", ids)
                setBody(body)
            }
            suspend fun receipt(path: String, body: String, key: String): String {
                val response = send(path, body, key)
                assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
                val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals(JsonPrimitive(true), json["committed"])
                assertFalse("sensitive note" in json.toString())
                if (path.contains("cash-records")) {
                    val recordId = json.getValue("retained_transaction_id").jsonPrimitive.content
                    val stored = database.read { connection ->
                        connection.jooq(database.sqlite).fetchExists(CASH_TRANSACTIONS,
                            CASH_TRANSACTIONS.WORKSPACE_ID.eq(workspace).and(CASH_TRANSACTIONS.ID.eq(recordId.toLong())))
                    }
                    assertTrue(stored)
                    val replay = client.get("/api/v1/cash-mutations/receipts/by-key/$key") {
                        bearerAuth(token)
                        header("X-FT-Workspace-ID", workspace)
                    }
                    assertEquals(HttpStatusCode.OK, replay.status)
                    val restored = Json.parseToJsonElement(replay.bodyAsText()).jsonObject
                    assertEquals(json["receipt_id"], restored["receipt_id"])
                    assertEquals(JsonPrimitive(recordId), restored["retained_transaction_id"])
                }
                return json.getValue("receipt_id").jsonPrimitive.content
            }
            val manual = "/api/v1/cash-records/commands"
            val transfers = "/api/v1/cashflow/transfer/commands"
            assertEquals(HttpStatusCode.BadRequest, send(manual, record, null).status)
            assertEquals(HttpStatusCode.BadRequest, send(manual, record, "key", scope = null).status)
            assertEquals(0, count())
            val first = receipt(manual, record, "one-record")
            assertEquals(first, receipt(manual, record, "one-record"))
            assertEquals(1, count())
            assertEquals(HttpStatusCode.Conflict, send(manual, record.replace("-10", "-20"), "one-record").status)
            assertEquals(HttpStatusCode.Conflict, send(transfers, transfer, "one-record").status)
            assertEquals(1, count())
            assertNotEquals(first, receipt(manual, record, "separate-record"))
            val transferReceipts = coroutineScope {
                val one = async { receipt(transfers, transfer, "one-transfer") }
                val two = async { receipt(transfers, transfer, "one-transfer") }
                listOf(one.await(), two.await())
            }
            assertEquals(1, transferReceipts.distinct().size)
            assertEquals(4, count())
            val denied = send(manual, record, "inactive", ids = "9223372036854775807")
            assertEquals(HttpStatusCode.Conflict, denied.status)
            assertEquals(JsonPrimitive("not_committed"), Json.parseToJsonElement(denied.bodyAsText()).jsonObject["commit_state"])
            assertEquals(HttpStatusCode.Conflict, send(manual, record, "inactive", ids = "9223372036854775807").status)
            assertEquals(4, count())
            val invalid = record.replace("-10", "-1.1234567890123456789")
            val rejection = send(manual, invalid, "invalid-input")
            assertEquals(HttpStatusCode.Conflict, rejection.status)
            assertEquals(JsonPrimitive("not_committed"), Json.parseToJsonElement(rejection.bodyAsText()).jsonObject["commit_state"])
            assertEquals(HttpStatusCode.Conflict, send(manual, record, "invalid-input").status)
            assertEquals(4, count())
            accounts.update(token, wallet, buildJsonObject { put("name", "Renamed Wallet") }, 1, "rename-wallet")
            val replacement = account("Wallet", "create-replacement")
            assertNotEquals(wallet, replacement)
            receipt(manual, record, "original-wallet")
            val lastAccount = database.read { connection ->
                val table = CASH_TRANSACTIONS
                connection.jooq(database.sqlite).select(table.ACCOUNT_ID).from(table)
                    .where(table.WORKSPACE_ID.eq(workspace)).orderBy(table.ID.desc()).limit(1).fetchSingle(table.ACCOUNT_ID)
            }
            assertEquals(wallet.toLong(), lastAccount)
            assertEquals(HttpStatusCode.Forbidden, send(manual, record, "different-scope", scope = UUID.randomUUID().toString()).status)
        }
    }
}
