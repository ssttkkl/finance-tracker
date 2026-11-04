package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.CashSourceCoverage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CashSourceRowsTest {
    private val coverage = CashSourceCoverage(null, null, false, "", emptyMap(), "v1")
    private fun document(rows: List<ParsedCashRow> = listOf(ParsedCashRow(1, mapOf("amount" to "-20"), providerId = "001"))) =
        ParsedCashDocument("alipay", "csv:0", "p1", rows, "owner", "owner label", coverage, rows.size, rows.size)
    private fun file(ref: String = "f1") = ParsedImportFile(ref, "a".repeat(64), "parsed", document(), lastModifiedAtMs = 123L)

    @Test
    fun rowsUseResolvedFieldsWithoutMutatingEitherInput() {
        val nested = linkedMapOf<String, Any?>("text" to mutableListOf("source"))
        val fields = linkedMapOf<String, Any?>("amount" to "-20", "source_payload" to nested)
        val original = file().copy(document = document(listOf(ParsedCashRow(4, fields, providerId = "001"))))
        val chosenPayload = linkedMapOf<String, Any?>("chosen" to mutableListOf("value"))
        val resolved = mapOf(4 to mapOf("account_id" to "7", "source_payload" to chosenPayload))
        val result = CashSourceRows.sourceRows(original, resolved).getValue("f1:r4")
        assertEquals("7", result.fields["account_id"])
        assertEquals(mapOf("chosen" to listOf("value")), result.fields["source_payload"])
        assertEquals(mapOf("text" to listOf("source")), fields["source_payload"])
        @Suppress("UNCHECKED_CAST")
        (chosenPayload["chosen"] as MutableList<String>).add("later")
        assertEquals(mapOf("chosen" to listOf("value")), result.fields["source_payload"])
        assertEquals("001", result.providerId)
        assertEquals("alipay:transaction:v1", result.sourceAccountKey)
    }

    @Test
    fun unsuccessfulAndSkippedRowsDoNotCreateOccurrences() {
        val rows = listOf(ParsedCashRow(1, null), ParsedCashRow(2, mapOf("amount" to "-20"), disposition = "skipped"),
            ParsedCashRow(3, mapOf("amount" to "-30"), providerId = "003"))
        val original = file().copy(document = document(rows))
        assertEquals(listOf("f1:r3"), CashSourceRows.sourceRows(original).keys.toList())
        assertTrue(CashSourceRows.sourceRows(original.copy(status = "failed")).isEmpty())
        assertTrue(CashSourceRows.sourceRows(original.copy(document = null)).isEmpty())
    }

    @Test
    fun batchRejectsDuplicateFileReferencesAndPreservesFileMetadataOrder() {
        assertEquals("duplicate_source_file_ref", assertFailsWith<IllegalArgumentException> {
            CashSourceRows.sourceBatchRows(listOf(file(), file()))
        }.message)
        val result = CashSourceRows.sourceBatchRows(listOf(file("b"), file("a").copy(lastModifiedAtMs = null)))
        assertEquals(listOf("b:r1", "a:r1"), result.rows.keys.toList())
        assertEquals(mapOf("b" to (0 to 123L), "a" to (1 to null)), result.fileMetadata)
    }

    @Test
    fun subsetDoesNotClaimCompleteDocumentOrContributeCompletenessEvidence() {
        val cov = coverage.copy(complete = true, evidence = "complete proof", documentKey = "d".repeat(64), pageTotal = 1,
            pageFingerprints = mapOf("1" to "a".repeat(64)))
        val original = file().copy(document = document().copy(coverage = cov))
        val subset = original.copy(isSubset = true)
        assertTrue(CashSourceRows.fileDocumentEvidence(listOf(subset)).isEmpty())
        val result = CashSourceRows.sourceBatchRows(listOf(subset)).rows.getValue("f1:r1")
        assertFalse(result.coverage.complete)
        assertEquals("", result.coverage.evidence)
        assertTrue(original.document!!.coverage.complete)
    }

    @Test
    fun documentPagesCombineOnlyInTheirSourceAndParserNamespace() {
        val full = coverage.copy(start = "2026-08-01T00:00:00Z", end = "2026-09-01T00:00:00Z", complete = true, evidence = "proof", documentKey = "d".repeat(64), pageTotal = 2,
            pageFingerprints = mapOf("1" to "a".repeat(64), "2" to "b".repeat(64)))
        val first = file().copy(document = document().copy(coverage = full.copy(complete = false,
            pageFingerprints = mapOf("1" to "a".repeat(64)))))
        val second = file("f2").copy(document = document().copy(coverage = full.copy(complete = false,
            pageFingerprints = mapOf("2" to "b".repeat(64)))))
        val result = CashSourceRows.sourceBatchRows(listOf(first, second))
        assertTrue(result.rows.values.all { it.coverage.complete })
        assertEquals(2, result.documentEvidence.size)
        val other = second.copy(document = second.document!!.copy(parserVersion = "p2"))
        val separate = CashSourceRows.sourceBatchRows(listOf(first, other))
        assertTrue(separate.rows.values.none { it.coverage.complete })
    }
}
