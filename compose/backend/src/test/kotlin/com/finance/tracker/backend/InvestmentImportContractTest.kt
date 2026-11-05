package com.finance.tracker.backend

import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class InvestmentImportContractTest {
    @Test fun allActualPythonImportResultsAndFinancialColumnsMatch() {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_INVESTMENT_IMPORT_DIR")))
        val config = Json.parseToJsonElement(root.resolve("database.json").readText()).jsonObject
        val db = Database(config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val helper = CashProjectionDatasetContractTest()
        val service = InvestmentImportService(db, now = { Instant.parse("2026-10-01T09:00:00Z") })
        val cases = Json.parseToJsonElement(root.resolve("investment-import.json").readText()).jsonArray
        assertEquals(19, cases.size)
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
            case["setup_metadata"]?.let { metadata ->
                db.write { c ->
                    val sql = c.jooq(db.sqlite)
                    val events = com.finance.tracker.backend.persistence.generated.tables.references.INVESTMENT_EVENTS
                    val snapshots = com.finance.tracker.backend.persistence.generated.tables.references.LEDGER_SNAPSHOTS
                    val accounts = com.finance.tracker.backend.persistence.generated.tables.references.ACCOUNTS
                    sql.deleteFrom(events).where(events.WORKSPACE_ID.eq("fixture-a")).execute()
                    sql.deleteFrom(snapshots).where(snapshots.WORKSPACE_ID.eq("fixture-a")).execute()
                    sql.update(accounts).set(accounts.METADATA_JSON, metadata).where(accounts.WORKSPACE_ID.eq("fixture-a")).and(accounts.ID.eq(202L)).execute()
                }
            }
            val response = service.importBytes("synthetic-admin", case.getValue("source").jsonPrimitive.content,
                case.getValue("filename").jsonPrimitive.content, java.util.Base64.getDecoder().decode(case.getValue("bytes").jsonPrimitive.content),
                case.getValue("account").jsonPrimitive.content, case["currency"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content)
            val context = "$index ${case["source"]} ${case["account"]}"
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
