package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class InvestmentWriteTest {
    private val tables = listOf("cash_transactions", "cash_transaction_components", "transaction_relations", "investment_events", "ledger_snapshots", "cash_entries", "cash_entry_members", "cash_projection_states", "cash_projection_datasets", "cash_projections", "cash_projection_members", "cash_projection_relations", "cash_relation_candidates")
    private fun database(): Database {
        val config = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")), "database.json").readText()).jsonObject
        return Database(config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
    }
    private fun state(db: Database, workspace: String) = db.read { c -> tables.associateWith { table -> c.prepareStatement("SELECT * FROM $table WHERE workspace_id=?").use { s -> s.setString(1, workspace); s.executeQuery().use { r -> buildSet { while (r.next()) add((1..r.metaData.columnCount).associate { r.metaData.getColumnName(it) to r.getString(it) }) } } } } }
    private fun account(db: Database, workspace: String, type: String = "security") = db.write { c -> deletionInsert(c, db, "accounts", linkedMapOf("workspace_id" to workspace, "name" to "response-investment", "type" to type, "active" to false, "revision" to 1L, "currencies" to JsonArray(listOf(JsonPrimitive("USD"))), "metadata_json" to JsonObject(emptyMap()), "created_at" to deletionTime, "updated_at" to deletionTime)) }
    @Test fun responseEncodingFailureRollsBackCompleteWrite() = testApplication {
        val db = database(); val workspaces = WorkspaceService(db); val name = "投资响应事务回归"
        val workspace = workspaces.create("synthetic-admin", name).activeWorkspaceId!!
        application { financeModule(db) }
        try {
            account(db, workspace); setAccountTestRole(db, workspace, "editor")
            suspend fun deposit(amount: String, date: String) = client.post("/api/v1/investment-events") { bearerAuth("synthetic-admin"); contentType(ContentType.Application.Json); setBody("""{"operation":"deposit","amount":"$amount","currency":"USD","account":"response-investment","date":"$date"}""") }
            val seed = deposit("5.2500", "2026-08-04T12:00"); assertEquals(201, seed.status.value, seed.bodyAsText())
            for ((index, separator) in listOf("\\ud800", "\\udfff").withIndex()) {
                val before = state(db, workspace); assertTrue(before.getValue("investment_events").isNotEmpty())
                val response = deposit("10.1250", "2026-08-05${separator}12:00")
                assertEquals(400, response.status.value, response.bodyAsText())
                val error = Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject
                assertContains(error.getValue("code").jsonPrimitive.content, "surrogates not allowed")
                assertEquals(JsonPrimitive("请求参数无效。"), error["message"]); assertEquals(before, state(db, workspace))
                val success = deposit("10.1250", "2026-08-05😀12:00"); assertEquals(201, success.status.value, success.bodyAsText())
                val row = Json.parseToJsonElement(success.bodyAsText()).jsonObject.getValue("details").jsonObject.getValue("row").jsonObject
                assertEquals(JsonPrimitive("2026-08-05😀12:00"), row["date"]); assertEquals(JsonPrimitive("10.1250"), row["to_amount"])
                assertEquals(before.getValue("investment_events").size + 1, state(db, workspace).getValue("investment_events").size)
                val position = db.read { c -> CashSqlContext(c, workspace, db.sqlite).snapshot.load().value
                    .getValue("accounts").jsonObject.getValue("security").jsonObject.getValue("response-investment").jsonObject
                    .getValue("positions").jsonObject.getValue("usd").jsonObject }
                val expected = JsonPrimitive(listOf("15.3750", "25.5000")[index])
                assertEquals(expected, position["shares"])
                assertEquals(expected, position["total_cost"])
            }
        } finally { setAccountTestRole(db, workspace, "admin"); workspaces.delete("synthetic-admin", name); workspaces.select("synthetic-admin", "fixture-a") }
    }
    @Test fun completeResultPreflightRejectsAnAdditionalUnencodableField() = testApplication {
        val db = database(); val workspaces = WorkspaceService(db); val name = "投资完整响应检查"; val workspace = workspaces.create("synthetic-admin", name).activeWorkspaceId!!
        try {
            account(db, workspace, "crypto"); val before = state(db, workspace)
            val service = InvestmentWriteService(db, resultTransform = { result -> JsonObject(result + ("details" to
                JsonObject(result.getValue("details").jsonObject + ("extra" to JsonPrimitive("\ud800"))))) })
            application { financeModule(db, investmentWrites = service) }
            val response = client.post("/api/v1/investment-events") {
                bearerAuth("synthetic-admin"); contentType(ContentType.Application.Json)
                setBody("""{"operation":"deposit","amount":"10","currency":"USD","account":"response-investment","date":"2026-08-05T12:00"}""")
            }
            assertEquals(400, response.status.value, response.bodyAsText())
            val error = Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject
            assertContains(error.getValue("code").jsonPrimitive.content, "surrogates not allowed")
            assertEquals(before, state(db, workspace))
        } finally { workspaces.delete("synthetic-admin", name); workspaces.select("synthetic-admin", "fixture-a") }
    }
    @Test fun test_postgres_investment_commands_write_events_and_projection_atomically() {
        val db = database(); val workspaces = WorkspaceService(db); val name = "投资原应用事务"; val workspace = workspaces.create("synthetic-admin", name).activeWorkspaceId!!
        try {
            account(db, workspace)
            val service = InvestmentCommandApplication { InvestmentWriteService(db).execute("synthetic-admin", it) }
            val account = "response-investment"; val date = "2026-07-17"
            for (result in listOf(service.deposit("100", "USD", account, "seed", date), service.buy("AAPL.US", "2", "10", "1", "USD", account, "buy", date), service.sell("AAPL.US", "1", "12", "1", "USD", account, "sell", date), service.dividend("AAPL.US", "2", "USD", account, "dividend", date), service.checkinTicker("AAPL.US", "3", "8", "USD", account, "check", date), service.swap(account, "AAPL.US", "1", "MSFT.US", "2", "USD", "swap", date), service.withdraw("2", "USD", account, "withdraw", date), service.checkinCash("8.75", "USD", account, "cash", date))) assertEquals(JsonPrimitive(true), result["ok"])
            assertEquals(8, state(db, workspace).getValue("investment_events").size)
            val positions = db.read { c -> CashSqlContext(c, workspace, db.sqlite).snapshot.load().value.getValue("accounts").jsonObject.getValue("security").jsonObject.getValue(account).jsonObject.getValue("positions").jsonObject }
            fun field(ticker: String, key: String) = positions.getValue(ticker).jsonObject.getValue(key).jsonPrimitive.content
            assertEquals("8.75", field("usd", "shares")); assertEquals("2", field("aapl.us", "shares")); assertEquals("16".toBigDecimal(), field("aapl.us", "total_cost").toBigDecimal().stripTrailingZeros()); assertEquals("USD", field("aapl.us", "cost_currency"))
            assertEquals("2", field("msft.us", "shares")); assertEquals("8".toBigDecimal(), field("msft.us", "total_cost").toBigDecimal().stripTrailingZeros()); assertEquals("USD", field("msft.us", "cost_currency"))
        } finally { workspaces.delete("synthetic-admin", name); workspaces.select("synthetic-admin", "fixture-a") }
    }
    @Test fun test_computed_investment_projection_released_cost_quantized_to_18dp() {
        val db = database(); val workspaces = WorkspaceService(db)
        for (action in listOf("sell", "swap")) {
            val name = "投资释放成本$action"; val workspace = workspaces.create("synthetic-admin", name).activeWorkspaceId!!
            try {
                account(db, workspace)
                db.write { c -> val context = CashSqlContext(c, workspace, db.sqlite); val document = context.snapshot.load(lock = true); document.value = Json.parseToJsonElement("""{"accounts":{"security":{"response-investment":{"currency":"USD","positions":{"aapl.us":{"shares":"3","total_cost":"1","cost_currency":"USD"}}}}}}""").jsonObject; context.snapshot.save(document) }
                val service = InvestmentCommandApplication { InvestmentWriteService(db).execute("synthetic-admin", it) }
                val result = if (action == "sell") service.sell("AAPL.US", "1", "1", "0", "USD", "response-investment") else service.swap("response-investment", "AAPL.US", "1", "MSFT.US", "1", "USD")
                assertEquals(JsonPrimitive(true), result["ok"]); assertEquals(1, state(db, workspace).getValue("investment_events").size)
                val cost = db.read { c -> CashSqlContext(c, workspace, db.sqlite).snapshot.load().value.getValue("accounts").jsonObject.getValue("security").jsonObject.getValue("response-investment").jsonObject.getValue("positions").jsonObject.getValue("aapl.us").jsonObject.getValue("total_cost").jsonPrimitive.content.toBigDecimal() }
                assertTrue(cost.scale() <= 18)
            } finally { workspaces.delete("synthetic-admin", name); workspaces.select("synthetic-admin", "fixture-a") }
        }
    }
    @Test fun commandRepositoryRollsBackLateFailureAndPreservesPrecisePosition() {
        val db = database(); val workspaces = WorkspaceService(db); val name = "投资写入边界回归"; val workspace = workspaces.create("synthetic-admin", name).activeWorkspaceId!!
        try {
            account(db, workspace, "crypto")
            val service = InvestmentWriteService(db, now = { deletionTime })
            val command = InvestmentCommand("deposit", "response-investment", "USD", amount = exactCashAmount("12.345678901234567890"), date = "2026-08-04T12:00")
            val result = service.execute("synthetic-admin", command)
            assertEquals(JsonPrimitive(true), result["ok"])
            val before = state(db, workspace)
            assertFailsWith<IllegalStateException> { InvestmentWriteService(db, beforeReturn = { error("late failure") }).execute("synthetic-admin", command) }
            assertEquals(before, state(db, workspace))
            val position = db.read { c -> CashSqlContext(c, workspace, db.sqlite).snapshot.load().value.getValue("accounts").jsonObject.getValue("security").jsonObject.getValue("response-investment").jsonObject.getValue("positions").jsonObject.getValue("usd").jsonObject }
            assertEquals(JsonPrimitive("12.345678901234567890"), position["shares"]); assertEquals(position["shares"], position["total_cost"])
        } finally { workspaces.delete("synthetic-admin", name); workspaces.select("synthetic-admin", "fixture-a") }
    }
    @Test fun unstoredPriceKeepsOriginalQuantizationFailureWithoutWrites() = testApplication {
        val db = database(); val spaces = WorkspaceService(db); val name = "投资价格量化边界"
        val workspace = spaces.create("synthetic-admin", name).activeWorkspaceId!!
        application { financeModule(db) }
        try {
            account(db, workspace)
            for (operation in listOf("buy", "sell", "checkin")) {
                val before = state(db, workspace)
                val response = client.post("/api/v1/investment-events") {
                    bearerAuth("synthetic-admin"); contentType(ContentType.Application.Json)
                    setBody("""{"operation":"$operation","account":"response-investment","currency":"USD","date":"2026-08-04","ticker":"AAPL.US","shares":"1","price":"10000000000.0000000000000000000","avg_cost":"10000000000.0000000000000000000"}""")
                }
                assertEquals(500, response.status.value, response.bodyAsText())
                assertEquals("Internal Server Error", response.bodyAsText())
                assertEquals(before, state(db, workspace))
            }
        } finally { spaces.delete("synthetic-admin", name); spaces.select("synthetic-admin", "fixture-a") }
    }

    @Test fun rawSurrogatePairsAreRejectedWithoutRejectingValidUnicode() = testApplication {
        val db = database(); val spaces = WorkspaceService(db); val name = "投资原始编码边界"
        val workspace = spaces.create("synthetic-admin", name).activeWorkspaceId!!
        application { financeModule(db) }
        try {
            account(db, workspace)
            suspend fun request(field: String, bytes: ByteArray) = client.post("/api/v1/investment-events") {
                bearerAuth("synthetic-admin"); contentType(ContentType.Application.Json)
                setBody(("{\"operation\":\"deposit\",\"amount\":\"1\",\"account\":\"response-investment\",\"date\":\"2026-08-04\",\"currency\":\"USD\",\"" + field + "\":\"").toByteArray() + bytes + "\"}".toByteArray())
            }
            for ((field, bytes) in listOf("note" to byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0xbd.toByte(), 0xed.toByte(), 0xb8.toByte(), 0x80.toByte()),
                "currency" to byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x81.toByte(), 0xed.toByte(), 0xb0.toByte(), 0xa8.toByte(), 65, 65))) {
                val before = state(db, workspace)
                val failed = request(field, bytes)
                assertEquals(400, failed.status.value, failed.bodyAsText()); assertEquals(before, state(db, workspace))
            }
            for ((field, value) in listOf("note" to "😀", "currency" to "𐐨AA")) {
                val success = request(field, value.toByteArray())
                assertEquals(201, success.status.value, success.bodyAsText())
            }
        } finally { spaces.delete("synthetic-admin", name); spaces.select("synthetic-admin", "fixture-a") }
    }

}
