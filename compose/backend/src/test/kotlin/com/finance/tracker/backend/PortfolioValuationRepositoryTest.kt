package com.finance.tracker.backend

import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class PortfolioValuationRepositoryTest {
    private val now = QuoteTime.parse("2026-07-25T00:00:00+00:00")
    private fun fixture(block: (Database, String) -> Unit) {
        val config = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_HOLDINGS_CONTRACT_DIR")), "database.json").readText()).jsonObject
        val db = Database(config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER")); val spaces = WorkspaceService(db); val name = "组合估值原断言"
        val workspace = spaces.create("synthetic-admin", name).activeWorkspaceId!!
        try { block(db, workspace) } finally { spaces.delete("synthetic-admin", name); spaces.select("synthetic-admin", "fixture-a") }
    }
    private fun account(db: Database, name: String) {
        val result = AccountService(db).create("synthetic-admin", buildJsonObject { put("name", name); put("type", "security"); put("currencies", JsonArray(listOf(JsonPrimitive("USD")))) }, java.util.UUID.randomUUID().toString())
        assertNotNull(result)
    }
    private fun repo(db: Database, workspace: String) = object : PortfolioRepository { override fun loadPortfolio() = db.read { RelationalPortfolioRepository(CashSqlContext(it, workspace, db.sqlite)).loadPortfolio() } }
    private fun state(db: Database) = db.read { c -> com.finance.tracker.backend.persistence.generated.DefaultSchema.DEFAULT_SCHEMA.tables.associate { table -> table.name to c.createStatement().use { s -> s.executeQuery("SELECT * FROM \"${table.name}\"").use { rows -> buildList { while (rows.next()) add((1..rows.metaData.columnCount).map { rows.getString(it) }) }.sortedBy { it.toString() } } } } }
    @Test fun createdInvestmentCurrencyIsValuedAsCash() = fixture { db, workspace ->
        account(db, "Broker")
        val write = InvestmentCommandApplication { InvestmentWriteService(db).execute("synthetic-admin", it) }
        assertEquals(JsonPrimitive(true), write.deposit("100", "USD", "Broker")["ok"])
        val before = state(db)
        val value = PortfolioQueryService(repo(db, workspace), ValuationService(object : QuoteProvider { override fun rawQuote(identity: String, kind: AssetKind): ProviderTick = throw UnsupportedQuote(identity) })).getPortfolio()
        val p = value.getValue("accounts").jsonArray.single().jsonObject.getValue("positions").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("usd"), p["ticker"]); assertEquals(JsonPrimitive(true), p["is_cash"]); assertEquals(JsonPrimitive("100"), p["market_value"]); assertEquals(JsonPrimitive("complete"), p["quote_status"]); assertEquals(before, state(db))
    }
    @Test fun boundedQueryPreservesAllHoldingsWithoutWrites() = fixture { db, workspace ->
        account(db, "Polymarket")
        db.write { c ->
            val snapshot = CashSqlContext(c, workspace, db.sqlite).snapshot.load()
            for (index in 0..15) InvestmentProjection.applyEvent(snapshot, Json.parseToJsonElement("""{"date":"2026-07-27","record_type":"snapshot","record_subtype":"position","account_name":"Polymarket","currency":"USD","to_ticker":"pm:market-$index:yes","to_amount":"1","price":"1"}""").jsonObject, "USD")
            CashSqlContext(c, workspace, db.sqlite).snapshot.save(snapshot)
        }
        val repository = repo(db, workspace); val before = repository.loadPortfolio(); val tables = state(db); val release = CountDownLatch(1)
        val provider = object : QuoteProvider { override fun rawQuote(identity: String, kind: AssetKind): ProviderTick? { release.await(2, TimeUnit.SECONDS); return null } }
        try {
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable -> Thread(runnable).apply { isDaemon = true } }
            val result = try { executor.submit<JsonObject> { PortfolioQueryService(repository, ValuationService(provider), queryDeadlineSeconds = 0.05).getPortfolio() }.get(1, TimeUnit.SECONDS) } finally { executor.shutdownNow() }
            val positions = result.getValue("accounts").jsonArray.single().jsonObject.getValue("positions").jsonArray.map { it.jsonObject }
            assertEquals(16, positions.size)
            assertEquals((0..15).map { JsonPrimitive("pm:market-$it:yes") }.toSet(), positions.map { it["ticker"] }.toSet()); assertEquals(setOf(JsonPrimitive("partial")), positions.map { it["quote_status"] }.toSet())
            assertTrue(positions.all { it["market_value"] == JsonNull }); assertEquals(before, repository.loadPortfolio()); assertEquals(tables, state(db))
        } finally { release.countDown() }
    }
    @Test fun quoteContractDeduplicatesAndPreservesDisplayValues() = fixture { db, workspace ->
        account(db, "One"); account(db, "Two")
        db.write { c ->
            val context = CashSqlContext(c, workspace, db.sqlite); val snapshot = context.snapshot.load()
            for ((account, ticker, quantity) in listOf(Triple("One", "AAPL.US", "2"), Triple("One", "missing.us", "1"), Triple("Two", "aapl.us", "1"))) {
                InvestmentProjection.applyEvent(snapshot, buildJsonObject { put("date", "2026-07-27"); put("record_type", "snapshot"); put("record_subtype", "position"); put("account_name", account); put("currency", "USD"); put("to_ticker", ticker); put("to_amount", quantity); put("price", "1") }, "USD")
            }
            context.snapshot.save(snapshot)
        }
        val before = state(db); val calls = mutableListOf<List<AssetRef>>()
        val provider = object : BatchQuoteProvider {
            override fun rawQuote(identity: String, kind: AssetKind): ProviderTick = error("batch expected")
            override fun rawQuoteMany(refs: List<AssetRef>, timeout: Double?): Map<String, Any?> { calls += refs; return refs.associate { it.identity to if (pythonCashLower(it.identity) == "aapl.us") ProviderTick("5", "USD", now, "fake") else null } }
        }
        val fx = FxRateProvider(FxMidFetcher { _, base, quote -> if (base == "USD" && quote == "CNY") "7" else null })
        val value = PortfolioQueryService(repo(db, workspace), ValuationService(provider, clock = { now }), fx, clock = { now }).getPortfolio(displayCurrency = "CNY")
        val positions = value.getValue("accounts").jsonArray.flatMap { a -> a.jsonObject.getValue("positions").jsonArray.map { p -> (a.jsonObject.getValue("name").jsonPrimitive.content to p.jsonObject.getValue("ticker").jsonPrimitive.content) to p.jsonObject } }.toMap()
        assertEquals(1, calls.size); assertEquals(setOf("aapl.us", "missing.us"), calls.single().map { pythonCashLower(it.identity) }.toSet())
        val apple = positions.getValue("One" to "aapl.us"); assertEquals(JsonPrimitive("10"), apple["market_value"]); assertEquals(JsonPrimitive("USD"), apple["quote_currency"]); assertEquals(JsonPrimitive("70"), apple["display_market_value"])
        assertEquals(JsonPrimitive("5"), positions.getValue("Two" to "aapl.us")["market_value"]); assertEquals(JsonPrimitive("partial"), positions.getValue("One" to "missing.us")["quote_status"]); assertEquals(JsonNull, positions.getValue("One" to "missing.us")["display_market_value"])
        assertEquals(before, state(db))
    }
}
