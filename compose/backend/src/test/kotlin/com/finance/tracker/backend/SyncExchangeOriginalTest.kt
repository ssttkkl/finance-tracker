package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.math.BigInteger
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class SyncExchangeOriginalTest {
    private val input = Json.parseToJsonElement(javaClass.getResource("/python-sync-exchange-inputs.json")!!.readText()).jsonObject
    private fun rows(key: String) = input.getValue(key).jsonArray.map { it.jsonObject }
    private fun fixture(block: (Database, String, Long) -> Unit) {
        val config = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")), "database.json").readText()).jsonObject
        val db = Database(config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"))
        val spaces = WorkspaceService(db); val workspace = spaces.create("synthetic-admin", "交易所同步原断言").activeWorkspaceId!!
        try {
            val id = db.write { c ->
                deletionInsert(c, db, "accounts", linkedMapOf("workspace_id" to workspace, "name" to "Binance", "type" to "crypto", "active" to true,
                    "revision" to 1L, "currencies" to JsonArray(emptyList()), "metadata_json" to JsonObject(emptyMap()), "created_at" to deletionTime, "updated_at" to deletionTime))
                c.jooq(db.sqlite).select(ACCOUNTS.ID).from(ACCOUNTS).where(ACCOUNTS.WORKSPACE_ID.eq(workspace)).fetchSingle(ACCOUNTS.ID)!!
            }
            block(db, workspace, id)
        } finally { spaces.select("synthetic-admin", workspace); spaces.delete("synthetic-admin", "交易所同步原断言"); spaces.select("synthetic-admin", "fixture-a") }
    }
    private fun connector(raw: Boolean = false): InvestmentConnector = CcxtExchangeConnector(if (raw) "kraken" else "binance", object : CcxtClient {
        override fun trades(since: BigInteger?, limit: Int) = rows(if (raw) "raw_trades" else "trades")
        override fun ledger(since: BigInteger?, limit: Int, params: Map<String, Int>) = if (raw) rows("raw_ledger") else emptyList()
    })
    private fun sync(db: Database, connector: InvestmentConnector, provider: String = "binance") = SyncService(db).sync("synthetic-admin", provider, "Binance", connector = connector)
    private fun ok(value: JsonObject, count: Int) { assertEquals(JsonPrimitive(true), value["ok"]); assertEquals(JsonPrimitive(count), value["count"]) }
    private fun shares(db: Database, workspace: String, ticker: String): String = db.read {
        CashSqlContext(it, workspace, db.sqlite).snapshot.load().value.getValue("accounts").jsonObject.getValue("security").jsonObject
            .getValue("Binance").jsonObject.getValue("positions").jsonObject.getValue(ticker).jsonObject.getValue("shares").jsonPrimitive.content
    }
    @Test fun sqliteSyncFromFixture() = fixture { db, _, _ -> ok(sync(db, connector()), 4) }
    @Test fun sqliteIdempotentResync() = fixture { db, _, _ ->
        assertEquals(JsonPrimitive(4), sync(db, connector())["count"]); assertEquals(JsonPrimitive(0), sync(db, connector())["count"])
    }
    @Test fun sqliteEventsPersisted() = fixture { db, workspace, _ ->
        sync(db, connector())
        val events = db.read { c -> c.jooq(db.sqlite).selectFrom(INVESTMENT_EVENTS).where(INVESTMENT_EVENTS.WORKSPACE_ID.eq(workspace)).fetch() }
        assertEquals(4, events.size); assertEquals(setOf("binance_api"), events.map { it[INVESTMENT_EVENTS.SOURCE_TYPE] }.toSet())
    }
    private fun ledger(db: Database, workspace: String, cursor: String?) {
        val connector = object : InvestmentConnector {
            override val sourceType = "binance_api"
            override fun fetch(since: String?) = ConnectorResult(rows("ledger_events"), cursor, 4)
        }
        ok(sync(db, connector), 4); ok(sync(db, connector), 0); assertEquals("59", shares(db, workspace, "usd"))
    }
    @Test fun sqliteLedgerEventsArePersistedIdempotently() = fixture { db, workspace, _ -> ledger(db, workspace, null) }
    @Test fun postgresLedgerEventsArePersistedIdempotently() = fixture { db, workspace, _ -> ledger(db, workspace, "200") }
    @Test fun postgresSyncAndIdempotent() = fixture { db, _, _ -> ok(sync(db, connector()), 4); ok(sync(db, connector()), 0) }
    private fun raw(db: Database, workspace: String) {
        ok(sync(db, connector(true), "kraken"), 5); ok(sync(db, connector(true), "kraken"), 0); assertEquals("1", shares(db, workspace, "btc"))
    }
    @Test fun sqliteRawCcxtTradeAndLedgerAreAtomicAndIdempotent() = fixture { db, workspace, _ -> raw(db, workspace) }
    @Test fun postgresRawCcxtTradeAndLedgerAreAtomicAndIdempotent() = fixture { db, workspace, _ -> raw(db, workspace) }
    private fun lateFailure(db: Database, workspace: String, account: Long) {
        val reached = mutableListOf<String>()
        val result = SyncService(db, applyEvent = { snapshot, event, currency ->
            val id = event.getValue("record_id").jsonPrimitive.content; reached.add(id)
            require(id != "reward:fee") { "late replay failure" }
            InvestmentProjection.applyEvent(snapshot, event, currency)
        }).sync("synthetic-admin", "kraken", "Binance", batchSize = 1, connector = connector(true))
        assertEquals(JsonPrimitive(false), result["ok"])
        assertEquals(listOf("trade", "deposit", "reward", "reward:fee"), reached)
        db.read { c ->
            assertEquals(0, c.jooq(db.sqlite).fetchCount(INVESTMENT_EVENTS, INVESTMENT_EVENTS.WORKSPACE_ID.eq(workspace)))
            val context = CashSqlContext(c, workspace, db.sqlite)
            assertEquals(JsonObject(emptyMap()), context.snapshot.load().value.getValue("accounts").jsonObject.getValue("security"))
            assertNull(SyncCursorRepository(context).get(account, "kraken_api"))
        }
    }
    @Test fun sqliteRawCcxtLateReplayFailureRollsBackEverything() = fixture(::lateFailure)
    @Test fun postgresRawCcxtLateReplayFailureRollsBackEverything() = fixture(::lateFailure)
}
