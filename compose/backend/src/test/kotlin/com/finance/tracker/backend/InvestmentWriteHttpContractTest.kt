package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class InvestmentWriteHttpContractTest {
    @Test fun allActualPythonResponsesAndFinancialColumnsMatch() = testApplication {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_INVESTMENT_WRITE_DIR")))
        val config = Json.parseToJsonElement(root.resolve("database.json").readText()).jsonObject
        val db = Database(config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val helper = CashProjectionDatasetContractTest()
        application { financeModule(db, clock = { Instant.parse("2026-10-01T09:00:00Z") }) }
        val cases = Json.parseToJsonElement(root.resolve("investment-write.json").readText()).jsonArray
        assertEquals(76, cases.size)
        fun normalizeRow(row: JsonObject, sqlite: Boolean, stored: Boolean): JsonObject = JsonObject(row.mapValues { (key, value) ->
            if (value == JsonNull) value else when {
                key in setOf("created_at", "updated_at", "occurred_at") -> JsonPrimitive(if (stored) requireNotNull(storedCashInstant(value.jsonPrimitive.content, sqlite)).toString() else Instant.parse(value.jsonPrimitive.content).toString())
                stored && key in setOf("payload", "source_payload") -> Json.parseToJsonElement(value.jsonPrimitive.content)
                stored && key in setOf("id", "account_id", "version") -> JsonPrimitive(value.jsonPrimitive.long)
                stored && key in setOf("from_amount", "to_amount", "commission") -> JsonPrimitive(fixedCashDecimal(storedCashDecimal(value.jsonPrimitive.content, sqlite)))
                else -> value
            }
        })
        for ((index, raw) in cases.withIndex()) {
            val case = raw.jsonObject
            val response = client.post("/api/v1/investment-events") {
                case.getValue("headers").jsonObject.forEach { (key, value) -> header(key, value.jsonPrimitive.content) }
                setBody(case.getValue("raw").jsonPrimitive.content)
            }
            val context = "$index ${case["raw"]}"
            assertEquals(case.getValue("status").jsonPrimitive.int, response.status.value, context + response.bodyAsText())
            val expectedBody = case.getValue("body")
            if (expectedBody is JsonPrimitive) assertEquals(expectedBody.content, response.bodyAsText(), context)
            else assertEquals(expectedBody, Json.parseToJsonElement(response.bodyAsText()), context)
            val expected = case.getValue("effects").jsonObject
            val actual = db.read { c -> expected.keys.associateWith { table -> helper.raw(c, table).map { normalizeRow(it, db.sqlite, true) } } }
            for ((table, rows) in expected) {
                val normalized = rows.jsonArray.map { normalizeRow(it.jsonObject, db.sqlite, false) }
                assertEquals(normalized.groupingBy { it }.eachCount(), actual.getValue(table).groupingBy { it }.eachCount(), "$context complete $table")
            }
        }
    }
}
