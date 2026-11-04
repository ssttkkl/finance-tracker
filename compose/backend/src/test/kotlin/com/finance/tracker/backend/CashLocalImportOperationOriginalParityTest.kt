package com.finance.tracker.backend

import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.domain.CASH_RULES_VERSION
import com.finance.tracker.business.domain.encodeLocalCashValue
import com.finance.tracker.business.domain.prepareLocalCashImportOperations
import java.math.BigDecimal
import java.math.BigInteger
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import kotlin.test.*

/** The old fixture decoder emits JVM integer objects; translate only their JSON representation. */
class CashLocalImportOperationOriginalParityTest {
    @OptIn(ExperimentalSerializationApi::class)
    private fun local(value: Any?): Any? = when (value) {
        null, is String, is Boolean -> value
        is Byte, is Short, is Int, is Long, is BigInteger -> JsonUnquotedLiteral(value.toString())
        is Double, is Float -> JsonUnquotedLiteral(pythonFloatText((value as Number).toDouble()))
        is BigDecimal -> value.toString()
        is Map<*, *> -> value.entries.associate { (key, item) -> require(key is String); key to local(item) }
        is List<*> -> value.map(::local)
        else -> error("unsupported original fixture scalar ${value.javaClass}")
    }
    private fun localFiles(files: List<ParsedImportFile>): List<ParsedImportFile> = files.map { file -> file.copy(
        document = file.document?.let { document -> document.copy(rows = document.rows.map { row ->
            @Suppress("UNCHECKED_CAST") val fields = local(row.fields) as? Map<String, Any?>
            @Suppress("UNCHECKED_CAST") val explanation = local(row.sourceExplanation) as Map<String, Any?>
            row.copy(fields = fields, sourceExplanation = explanation)
        }) }) }

    @Test fun allOriginalScenarioFilesMatchTheProductionSnapshotComposition() {
        val scenarios = listOf("base", "rename", "bound", "protected", "protected_old", "protected_inactive", "source_status",
            "source_status_time", "ambiguous", "currency", "inactive", "draft", "draft_inactive")
        val variants = listOf("default", "wallet", "repeated", "with_bad", "two", "unidentified", "missing_time", "status_batch",
            "status_older", "changed", "fixed_mixed", "alias", "defer", "different", "bad_amount", "missing_finance")
        var compared = 0
        for (scenario in scenarios) {
            val fixture = CashImportOriginalApiFixture(scenario, "FT_KOTLIN_IMPORT_ORIGINAL_OPERATION_CONTRACT_DIR")
            val before = fixture.snapshot()
            fixture.database.read { connection ->
                val context = CashSqlContext(connection, fixture.workspace, fixture.database.sqlite)
                val snapshot = LedgerSnapshotDto(fixture.workspace, "0", CASH_RULES_VERSION,
                    LedgerSnapshotRepository(connection, fixture.workspace, fixture.database.sqlite).facts())
                for (variant in variants) for (selected in listOf(false, true)) {
                    val files = fixture.files(variant)
                    val selections = if (!selected) emptyList() else CashImportFunding.scan(files).groups.map {
                        mapOf("source_account_ref" to it.sourceAccountRef, "account_id" to "101", "expected_account_revision" to 1)
                    }
                    // Unidentified rows allocate once; both paths receive exactly the same saved intent IDs.
                    val ids = CashSourceRows.sourceBatchRows(files).rows.values.filter { it.providerBindingKey == null }
                        .associate { it.occurrenceKey to "saved:${it.occurrenceKey}" }
                    val native = runCatching { CashImportOperationPlanner(context).prepare(files, selections, recordIds = ids) }
                    val client = runCatching { prepareLocalCashImportOperations(localFiles(files), snapshot, sourceJson(selections), recordIds = ids) }
                    val label = "$scenario/$variant/selected=$selected"
                    if (native.isSuccess) {
                        val a = native.getOrThrow(); val b = client.getOrElse { throw AssertionError(label, it) }
                        assertEquals(sourceJson(a.rows), encodeLocalCashValue(b.rows), "$label rows")
                        assertEquals(sourceJson(a.operations), encodeLocalCashValue(b.operations), "$label operations")
                        assertEquals(sourceJson(a.candidateSets), encodeLocalCashValue(b.candidateSets), "$label candidates")
                    } else {
                        assertEquals(native.exceptionOrNull()?.javaClass, client.exceptionOrNull()?.javaClass, label)
                        assertEquals(native.exceptionOrNull()?.message, client.exceptionOrNull()?.message, label)
                    }
                    compared++
                }
            }
            assertEquals(before, fixture.snapshot(), "$scenario plans must not write")
        }
        assertEquals(416, compared)
    }
}
