package com.finance.tracker.backend

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class PortfolioHoldingsBoundaryTest {
    private fun invoke(target: Any, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? =
        try { method.invoke(target, *(args ?: emptyArray())) } catch (failure: InvocationTargetException) { throw failure.targetException }
    private fun database(log: MutableList<String>? = null): Database {
        val dir = Path.of(requireNotNull(System.getenv("FT_KOTLIN_HOLDINGS_ORACLE_DIR")))
        val config = Json.parseToJsonElement(dir.resolve("database.json").readText()).jsonObject
        return Database(config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER")) { c ->
            if (log == null) c else Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                val value = invoke(c, method, args)
                if (method.name == "prepareStatement" && value is PreparedStatement) {
                    val sql = args!![0] as String
                    Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PreparedStatement::class.java)) { _, operation, values ->
                        if (operation.name.startsWith("execute")) log.add(sql)
                        invoke(value, operation, values)
                    }
                } else value
            } as Connection
        }
    }
    private fun state(db: Database) = db.read { c -> com.finance.tracker.backend.persistence.generated.DefaultSchema.DEFAULT_SCHEMA.tables.associate { table ->
        table.name to c.createStatement().use { s -> s.executeQuery("SELECT * FROM \"${table.name}\"").use { rows ->
            buildList { while (rows.next()) add((1..rows.metaData.columnCount).map { rows.getString(it) }) }.sortedBy { it.toString() }
        } }
    } }
    private fun snapshot(db: Database, payload: JsonElement) = db.write { c ->
        val table = com.finance.tracker.backend.persistence.generated.tables.references.LEDGER_SNAPSHOTS
        c.jooq(db.sqlite).update(table).set(table.PAYLOAD, payload).where(table.WORKSPACE_ID.eq("fixture-a")).execute()
    }
    @Test fun holdingsExecutesOnlyBoundedMetadataAndLeavesEveryTableUnchanged() {
        val dir = Path.of(requireNotNull(System.getenv("FT_KOTLIN_HOLDINGS_ORACLE_DIR")))
        val cases = Json.parseToJsonElement(dir.resolve("holdings.json").readText()).jsonArray
        val normal = database(); snapshot(normal, cases[1].jsonObject.getValue("snapshot")); val before = state(normal)
        val log = mutableListOf<String>(); val observed = database(log)
        val result = observed.read { c -> PortfolioQueryService(RelationalPortfolioRepository(CashSqlContext(c, "fixture-a", observed.sqlite))).getHoldings() }
        assertEquals(cases[1].jsonObject["holdings"], result); assertEquals(before, state(normal))
        assertTrue(log.isNotEmpty()); assertTrue(log.all { it.trimStart().startsWith("select", true) })
        val eventSql = log.filter { Regex("\\bfrom\\s+[\"`]?investment_events", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        assertEquals(1, eventSql.size)
        val select = eventSql.single().substringBefore(" from ").lowercase()
        for (field in listOf("from_ticker", "to_ticker", "source_type", "source_payload", "note")) assertContains(select, field)
        for (field in listOf("from_amount", "to_amount", "occurred_at", "commission", "created_at", "record_id")) assertFalse(field in select, field)
        assertContains(eventSql.single().lowercase(), "lower("); assertContains(eventSql.single().lowercase(), "order by")
        assertFalse("Private label" in result.toString()); assertFalse("Unrelated name" in result.toString())
        snapshot(normal, Json.parseToJsonElement("""{"accounts":{"security":{"201":{"positions":{"usd":{"shares":"1","total_cost":"1"}}}}}}"""))
        log.clear(); observed.read { RelationalPortfolioRepository(CashSqlContext(it, "fixture-a", observed.sqlite)).loadHoldings() }
        assertTrue(log.none { "investment_events" in it })
    }
    @Test fun exactInputsRejectInvalidNonzeroPositionsAndSkipZeroBeforeCost() {
        fun result(shares: JsonElement, cost: JsonElement): JsonObject {
            val raw = buildJsonObject { put("accounts", buildJsonObject { put("Broker", buildJsonObject { put("positions", buildJsonObject { put("aapl.us", buildJsonObject { put("shares", shares); put("total_cost", cost) }) }) }) }) }
            return PortfolioQueryService(object : PortfolioRepository { override fun loadPortfolio() = raw }).getHoldings()
        }
        assertTrue(result(JsonPrimitive("-0.000"), JsonPrimitive("NaN")).getValue("accounts").jsonArray[0].jsonObject.getValue("positions").jsonArray.isEmpty())
        for ((input, message) in listOf(JsonNull to "shares must be decimal-compatible", JsonPrimitive("NaN") to "shares must be finite", JsonPrimitive("1e-19") to "shares exceeds NUMERIC(38,18): must have at most 18 decimal places", JsonPrimitive("1e20") to "shares exceeds NUMERIC(38,18)"))
            assertEquals(message, assertFailsWith<IllegalArgumentException> { result(input, JsonPrimitive("1")) }.message)
        assertEquals("total_cost must be finite", assertFailsWith<IllegalArgumentException> { result(JsonPrimitive("1"), JsonPrimitive("Infinity")) }.message)
        val position = result(JsonPrimitive("-0.000000000000000001"), JsonPrimitive("99999999999999999999")).getValue("accounts").jsonArray[0].jsonObject.getValue("positions").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("-0.000000000000000001"), position["shares"]); assertEquals(JsonPrimitive("99999999999999999999"), position["total_cost"])
        assertEquals(JsonPrimitive(""), position["cost_currency"]); assertEquals(JsonPrimitive(false), position["is_cash"])
    }
    @Test fun corruptStoredCurrencyStopsWithoutAnyWrite() {
        val db = database()
        snapshot(db, Json.parseToJsonElement("""{"accounts":{"security":{"201":{"currency":"USD","positions":{"aapl.us":{"shares":"1","total_cost":"1","cost_currency":["USD"]}}}}}}"""))
        val before = state(db)
        assertFailsWith<IllegalStateException> { db.read { PortfolioQueryService(RelationalPortfolioRepository(CashSqlContext(it, "fixture-a", db.sqlite))).getHoldings() } }
        assertEquals(before, state(db))
    }

}
