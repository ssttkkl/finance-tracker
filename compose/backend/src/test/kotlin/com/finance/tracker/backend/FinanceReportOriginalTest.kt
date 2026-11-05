package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.DefaultSchema
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class FinanceReportOriginalTest {
    private val directory = Path.of(requireNotNull(System.getenv("FT_KOTLIN_FINANCE_REPORT_CONTRACT_DIR")))
    private val cases = Json.parseToJsonElement(directory.resolve("scenarios.json").readText()).jsonObject
    private fun config(name: String) = Json.parseToJsonElement(directory.resolve(name).resolve("database.json").readText()).jsonObject
    private fun database(name: String): Database = Database(config(name)["jdbc_url"]!!.jsonPrimitive.content,
        System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
    private fun snapshot(database: Database) = database.read { connection -> DefaultSchema.DEFAULT_SCHEMA.tables.associate { table ->
        table.name to connection.createStatement().use { statement -> statement.executeQuery("SELECT * FROM \"${table.name}\"").use { rows ->
            buildList { while (rows.next()) add(JsonArray((1..rows.metaData.columnCount).map { rows.getString(it)?.let(::JsonPrimitive) ?: JsonNull }).toString()) }.sorted()
        } }
    } }
    private fun money(value: JsonElement) = value.jsonObject.mapValues { it.value.jsonPrimitive.content.toBigDecimal().stripTrailingZeros() }
    @Test fun originalProductionCompositionsUseCurrentPostedProjectionTotals() {
        val selected = cases.filterKeys { it.startsWith("report_") }; assertEquals(12, selected.size)
        for ((name, raw) in selected) {
            val case = raw.jsonObject; val args = case["args"]!!.jsonObject
            val month = args["month"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
            val database = database(name); val before = snapshot(database)
            var result: JsonObject? = null
            if (args["composition"] == JsonPrimitive("runtime")) result = FinanceQueryService(database).report("finance-original-viewer", month)
            else testApplication {
                application { financeModule(database) }
                val response = client.get("/api/v1/reports/finance") {
                    bearerAuth("finance-original-viewer"); month?.let { parameter("month", it) }
                }
                assertEquals(200, response.status.value, "$name ${response.bodyAsText()}")
                result = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            }
            val actual = requireNotNull(result)
            assertEquals(case["expected"], actual, name)
            assertEquals(if (month == "2026-08") emptyMap() else mapOf("CNY" to args["expense"]!!.jsonPrimitive.content.toBigDecimal().stripTrailingZeros()), money(actual["expenses"]!!), name)
            assertEquals(if (month == "2026-08") emptyMap() else mapOf("CNY" to "2000".toBigDecimal().stripTrailingZeros()), money(actual["income"]!!), name)
            val flows = actual["flows"]!!.jsonArray.map { row -> val item = row.jsonObject
                Triple(item["note"]!!.jsonPrimitive.content, item["currency"]!!.jsonPrimitive.content, item["amount"]!!.jsonPrimitive.content.toBigDecimal().stripTrailingZeros()) }
            assertEquals(listOf(Triple("投资", "CNY", "10".toBigDecimal().stripTrailingZeros())), flows, name)
            assertEquals(before, snapshot(database), name)
        }
    }
    @Test fun originalCurrentReportTotalsPreserveSupportedPrecision() {
        val database = database("precision"); val before = snapshot(database)
        val result = database.read { connection -> FinanceQueryRepository(CashSqlContext(connection, config("precision")["workspace"]!!.jsonPrimitive.content, database.sqlite)).report("2026-07") }
        assertEquals(cases["precision"]!!.jsonObject["expected"], result)
        assertEquals(mapOf("CNY" to "12345678901234567890.123456789012345679".toBigDecimal()), money(result["income"]!!))
        assertEquals(before, snapshot(database))
    }
    @Test fun originalHttpUninitializedStateNeverFallsBackToRawRows() = testApplication {
        val database = database("uninitialized")
        application { financeModule(database) }
        val before = snapshot(database)
        val response = client.get("/api/v1/reports/finance") { bearerAuth("finance-original-viewer") }
        val body = response.bodyAsText()
        assertEquals(503, response.status.value, body)
        assertEquals(JsonPrimitive("projection.unavailable"), Json.parseToJsonElement(body).jsonObject["error"]!!.jsonObject["code"])
        assertFalse("SQL" in body); assertFalse(config("uninitialized")["jdbc_url"]!!.jsonPrimitive.content.removePrefix("jdbc:") in body)
        assertEquals(before, snapshot(database))
        ProjectionRebuildService(database).rebuild("finance-original-owner")
        val rebuilt = snapshot(database)
        val invalid = client.get("/api/v1/reports/finance?month=invalid") { bearerAuth("finance-original-viewer") }
        assertEquals(400, invalid.status.value); assertEquals(rebuilt, snapshot(database))
        val ready = client.get("/api/v1/reports/finance?month=2026-07") { bearerAuth("finance-original-viewer") }
        assertEquals(200, ready.status.value, ready.bodyAsText())
        assertEquals(cases["uninitialized"]!!.jsonObject["expected"], Json.parseToJsonElement(ready.bodyAsText()))
        assertEquals(rebuilt, snapshot(database))
    }
}
