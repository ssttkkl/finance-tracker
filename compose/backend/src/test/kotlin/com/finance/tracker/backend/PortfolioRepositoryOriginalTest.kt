package com.finance.tracker.backend

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class PortfolioRepositoryOriginalTest {
    private fun fixture(block: (Database, String) -> Unit) {
        val config = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_HOLDINGS_CONTRACT_DIR")), "database.json").readText()).jsonObject
        val db = Database(config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"))
        val spaces = WorkspaceService(db); val name = "持仓原读取"; val workspace = spaces.create("synthetic-admin", name).activeWorkspaceId!!
        try {
            db.write { c ->
                deletionInsert(c, db, "accounts", linkedMapOf("workspace_id" to workspace, "name" to "IBKR", "type" to "security", "active" to true, "revision" to 1L, "currencies" to JsonArray(listOf(JsonPrimitive("USD"))), "metadata_json" to JsonObject(emptyMap()), "created_at" to deletionTime, "updated_at" to deletionTime))
                CashSqlContext(c, workspace, db.sqlite).snapshot.save(CashSnapshotDocument(Json.parseToJsonElement("""{"accounts":{"security":{"IBKR":{"currency":"USD","positions":{"aapl.us":{"shares":"1","total_cost":"100","cost_currency":"USD"}}}}}}""").jsonObject))
                val account = c.prepareStatement("SELECT id FROM accounts WHERE workspace_id=? AND name='IBKR'").use { s -> s.setString(1, workspace); s.executeQuery().use { it.next(); it.getLong(1) } }
                deletionInsert(c, db, "investment_events", linkedMapOf("workspace_id" to workspace, "account_id" to account, "occurred_at" to deletionTime, "record_type" to "trade", "record_subtype" to "security", "currency" to "USD", "note" to "", "from_ticker" to "usd", "from_amount" to "100".toBigDecimal(), "to_ticker" to "aapl.us", "to_amount" to "1".toBigDecimal(), "commission" to "0".toBigDecimal(), "commission_asset" to "usd", "payload" to JsonObject(emptyMap()), "source_type" to "test", "record_id" to "trade-99", "source_payload" to buildJsonObject { put("ticker", "aapl.us"); put("name", "Apple Inc.") }, "created_at" to deletionTime))
            }
            block(db, workspace)
        } finally { spaces.delete("synthetic-admin", name); spaces.select("synthetic-admin", "fixture-a") }
    }
    @Test fun repositoryLoadHoldingsKeepsNamesWithoutHistoryPayload() = fixture { db, workspace ->
        val raw = db.read { RelationalPortfolioRepository(CashSqlContext(it, workspace, db.sqlite)).loadHoldings() }
        assertEquals(JsonArray(emptyList()), raw["investment_events"])
        assertEquals(JsonPrimitive("Apple Inc."), raw.getValue("accounts").jsonObject.getValue("IBKR").jsonObject.getValue("positions").jsonObject.getValue("aapl.us").jsonObject["display_name"])
    }
    @Test fun repositoryExposesWorkspaceBoundInvestmentEvents() = fixture { db, workspace ->
        val raw = db.read { RelationalPortfolioRepository(CashSqlContext(it, workspace, db.sqlite)).loadPortfolio() }
        val events = raw.getValue("investment_events").jsonArray
        assertEquals(1, events.size); val event = events[0].jsonObject
        assertEquals(JsonPrimitive("aapl.us"), event["to_ticker"])
        assertEquals(JsonPrimitive("Apple Inc."), raw.getValue("accounts").jsonObject.getValue("IBKR").jsonObject.getValue("positions").jsonObject.getValue("aapl.us").jsonObject["display_name"])
        assertEquals(JsonPrimitive("IBKR"), event["account"]); assertEquals(JsonPrimitive("trade"), event["record_type"])
        assertEquals(JsonPrimitive("100"), event["from_amount"]); assertEquals(JsonPrimitive("1"), event["to_amount"])
    }
}
