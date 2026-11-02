package com.finance.tracker.backend

import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class SyncServiceContractTest {
    @Test fun allActualPythonImportResultsAndFinancialColumnsMatch() {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_SYNC_CONTRACT_DIR")))
        val config = Json.parseToJsonElement(root.resolve("database.json").readText()).jsonObject
        val db = Database(config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val helper = CashProjectionDatasetContractTest()
        val service = SyncService(db, now = { Instant.parse("2026-10-01T09:00:00Z") })
        val cases = Json.parseToJsonElement(root.resolve("sync.json").readText()).jsonArray
        assertEquals(53, cases.size)
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
            val calls = mutableListOf<String?>()
            val responses = case["responses"]?.takeUnless { it == JsonNull }?.jsonArray
            val connector = responses?.let { sequence -> object : InvestmentConnector {
                override val sourceType = "test_api"
                override fun fetch(since: String?): ConnectorResult {
                    calls += since
                    val input = sequence[calls.lastIndex].jsonObject
                    input["error"]?.let { error ->
                        val message = input.getValue("message").jsonPrimitive.content
                        throw when (error.jsonPrimitive.content) { "auth" -> ConnectorAuthFailure(message); "data" -> ConnectorDataFailure(message); else -> ConnectorFailure(message) }
                    }
                    return ConnectorResult(input["events"]?.jsonArray?.map { it.jsonObject }.orEmpty(), input["cursor"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content, input["raw"]?.jsonPrimitive?.int ?: 0)
                }
            } }
            val response = service.sync("synthetic-admin", case.getValue("provider").jsonPrimitive.content, case.getValue("account").jsonPrimitive.content,
                case.getValue("full").jsonPrimitive.boolean, case.getValue("batch").jsonPrimitive.int, connector)
            val context = "$index ${case["provider"]} ${case["account"]}"
            assertEquals(case.getValue("calls"), JsonArray(calls.map { it?.let(::JsonPrimitive) ?: JsonNull }), context)
            assertEquals(case.getValue("expected"), response, context)
            val expected = case.getValue("effects").jsonObject
            val actual = db.read { c -> expected.keys.associateWith { table -> helper.raw(c, table).map { normalizeRow(it, db.sqlite, true) } } }
            for ((table, rows) in expected) {
                val normalized = rows.jsonArray.map { normalizeRow(it.jsonObject, db.sqlite, false) }
                assertEquals(normalized.groupingBy { it }.eachCount(), actual.getValue(table).groupingBy { it }.eachCount(), "$context complete $table")
            }
        }
    }
}
