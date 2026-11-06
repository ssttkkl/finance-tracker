package com.finance.tracker.backend

import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.domain.CASH_RULES_VERSION
import com.finance.tracker.business.domain.formatPythonCashTimestamp
import com.finance.tracker.business.domain.readLocalCashImportPreviewValues
import com.finance.tracker.business.domain.readLocalCashRelationContext
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class LocalCashImportPreviewReadParityTest {
    private fun database(): Database {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_IMPORT_PREPARATION_CONTRACT_DIR")))
        val config = Json.parseToJsonElement(root.resolve("database.json").readText()).jsonObject
        return Database(config.getValue("jdbc_url").jsonPrimitive.content,
            System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
    }
    private fun wire(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is java.time.Instant -> JsonPrimitive(ParsedImportCodec.timeText(value))
        is kotlin.time.Instant -> JsonPrimitive(formatPythonCashTimestamp(value))
        is CashAmount -> JsonPrimitive(value.toString())
        is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to wire(it.value) })
        is List<*> -> JsonArray(value.map(::wire))
        else -> sourceJson(value)
    }

    @Test fun completeStoredPreviewValuesMatchOriginalSqlWithoutWrites() {
        val db = database()
        val before = detailDatabaseState(db)
        db.read { connection ->
            val sqlContext = CashSqlContext(connection, "operation-workspace", db.sqlite)
            val snapshot = LedgerSnapshotDto(sqlContext.workspace, "0", CASH_RULES_VERSION,
                LedgerSnapshotRepository(connection, sqlContext.workspace, db.sqlite).facts())
            for (ids in listOf(emptySet(), setOf(7501L), setOf(7502L), setOf(7505L), setOf(7506L), setOf(7507L),
                snapshot.facts.cashTransactions.map { it.id.toLong() }.toSet())) {
                val native = CashRelationContextService(sqlContext).load(ids)
                val prepared = PreparedCashImportPreview(emptyList(), emptyMap(), emptySet(), emptySet(), native)
                val expected = CashImportPreviewSnapshotBuilder(sqlContext).build(prepared, emptyList()).values
                val context = readLocalCashRelationContext(snapshot, ids)
                assertEquals(wire(expected), wire(readLocalCashImportPreviewValues(snapshot, context)), ids.toString())
            }
        }
        assertEquals(before, detailDatabaseState(db))
    }
}
