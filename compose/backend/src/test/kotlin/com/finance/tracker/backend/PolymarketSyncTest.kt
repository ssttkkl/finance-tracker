package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class PolymarketSyncTest {
    private val wallet = "0x" + "a".repeat(40)
    private fun activities() = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/polymarket-activities.json")).bufferedReader().use { it.readText() }).jsonArray
    private fun fixture(block: (Database, String, Long) -> Unit) {
        val config = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")), "database.json").readText()).jsonObject
        val db = Database(config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val spaces = WorkspaceService(db); val name = "预测市场同步验证"; val workspace = spaces.create("synthetic-admin", name).activeWorkspaceId!!
        try {
            val id = db.write { c ->
                deletionInsert(c, db, "accounts", linkedMapOf("workspace_id" to workspace, "name" to "Polymarket", "type" to "security", "active" to true, "revision" to 1L,
                    "currencies" to JsonArray(emptyList()), "metadata_json" to JsonObject(emptyMap()), "created_at" to deletionTime, "updated_at" to deletionTime))
                c.jooq(db.sqlite).select(ACCOUNTS.ID).from(ACCOUNTS).where(ACCOUNTS.WORKSPACE_ID.eq(workspace)).and(ACCOUNTS.NAME.eq("Polymarket")).fetchSingle(ACCOUNTS.ID)!!
            }
            block(db, workspace, id)
        } finally { spaces.select("synthetic-admin", workspace); spaces.delete("synthetic-admin", name); spaces.select("synthetic-admin", "fixture-a") }
    }
    private fun connector(rows: JsonArray = JsonArray(activities().take(4))) = PolymarketConnector(mapOf("proxy_wallet" to wallet), fetch = { rows })
    private fun chain(malformed: Boolean = false) = PolymarketConnector(mapOf("proxy_wallet" to wallet), fetch = { JsonArray(listOf(activities()[0])) }, rpc = { method, _ ->
        when (method) { "eth_blockNumber" -> JsonPrimitive("0xc"); "eth_getBlockByNumber" -> buildJsonObject { put("timestamp", "0x" + 1777667210.toString(16)) }; "eth_call" -> JsonPrimitive(if (malformed) "not-hex" else "0x12d687"); else -> error(method) }
    })
    private fun sync(db: Database, connector: InvestmentConnector) = SyncService(db).sync("synthetic-admin", "polymarket", "Polymarket", connector = connector)
    private fun ok(value: JsonObject, count: Int? = null) { assertEquals(JsonPrimitive(true), value["ok"], value.toString()); if (count != null) assertEquals(JsonPrimitive(count), value["count"]) }
    private fun cursor(db: Database, workspace: String, id: Long) = db.read { SyncCursorRepository(CashSqlContext(it, workspace, db.sqlite)).get(id, "polymarket_api") }
    private fun records(db: Database, workspace: String) = db.read { c -> c.jooq(db.sqlite).select(INVESTMENT_EVENTS.RECORD_ID, INVESTMENT_EVENTS.RECORD_TYPE, INVESTMENT_EVENTS.SOURCE_TYPE).from(INVESTMENT_EVENTS).where(INVESTMENT_EVENTS.WORKSPACE_ID.eq(workspace)).fetch() }
    @Test fun originalSyncTrades() = fixture { db, _, _ -> ok(sync(db, connector()), 3) }
    @Test fun originalIdempotenceAndPostgresTrades() = fixture { db, _, _ -> ok(sync(db, connector()), 3); ok(sync(db, connector()), 0) }
    @Test fun originalSourceType() = fixture { db, workspace, _ -> sync(db, connector()); val rows = records(db, workspace); assertEquals(3, rows.size); rows.forEach { assertEquals("polymarket_api", it[INVESTMENT_EVENTS.SOURCE_TYPE]) } }
    @Test fun originalRedeemYieldIdempotenceBothBackends() = fixture { db, workspace, _ ->
        val rows = Json.parseToJsonElement("""[{"type":"REDEEM","slug":"market","outcome":"Yes","size":"2","usdcSize":"2","timestamp":1700000000,"transactionHash":"redeem-1"},{"type":"YIELD","usdcSize":"0.5","timestamp":1700000001,"transactionHash":"yield-1"},{"type":"DEPOSIT","timestamp":1700000002}]""").jsonArray
        ok(sync(db, connector(rows)), 2); ok(sync(db, connector(rows)), 0); assertEquals(listOf("income", "trade"), records(db, workspace).map { it[INVESTMENT_EVENTS.RECORD_TYPE]!! }.sorted())
    }
    @Test fun originalActivityAndCheckinCommitTogetherBothBackends() = fixture { db, workspace, id ->
        ok(sync(db, chain()), 2)
        val rows = records(db, workspace); assertEquals(2, rows.size); assertTrue("checkin:12" in rows.map { it[INVESTMENT_EVENTS.RECORD_ID] })
        assertEquals((activities()[0].jsonObject.getValue("timestamp").jsonPrimitive.long + 1).toString(), cursor(db, workspace, id))
        val snapshot = db.read { CashSqlContext(it, workspace, db.sqlite).snapshot.load().value }
        val positions = snapshot.getValue("accounts").jsonObject.getValue("security").jsonObject.getValue("Polymarket").jsonObject.getValue("positions").jsonObject
        assertEquals(JsonPrimitive("1.234567"), positions.getValue("usd").jsonObject["shares"]); assertEquals(JsonPrimitive("100"), positions.getValue("pm:will-trump-win-2024:yes").jsonObject["shares"])
        ok(sync(db, chain()), 0)
    }
    @Test fun originalMalformedBalanceLeavesEventsAndCursorUnwrittenBothBackends() = fixture { db, workspace, id ->
        val before = db.read { CashSqlContext(it, workspace, db.sqlite).snapshot.load().value }
        assertEquals(JsonPrimitive(false), sync(db, chain(true))["ok"]); assertTrue(records(db, workspace).isEmpty()); assertNull(cursor(db, workspace, id))
        assertEquals(before, db.read { CashSqlContext(it, workspace, db.sqlite).snapshot.load().value })
    }
    @Test fun originalEmptyConnectorOpaqueCursorBothBackends() = fixture { db, workspace, id ->
        val opaque = """{"activity_since":1782226943,"pusd_block":90000000}"""
        val connector = object : InvestmentConnector { override val sourceType = "polymarket_api"; override fun fetch(since: String?) = ConnectorResult(nextCursor = opaque) }
        ok(sync(db, connector)); assertEquals(opaque, cursor(db, workspace, id))
    }
}
