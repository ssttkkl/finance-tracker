package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.CashSourceCoverage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CashSourceDocumentsTest {
    private fun page(number: String, fingerprint: String = "b".repeat(64)) = SourceDocumentEvidence(
        "icbc_credit", "pdf-v1", CashSourceCoverage("2026-08-01T00:00Z", "2026-09-01T00:00Z", false, "",
            emptyMap(), "pages-v1", documentKey = "a".repeat(64), pageTotal = 2,
            pageFingerprints = mapOf(number to fingerprint)))

    @Test
    fun completePageUnionProducesCoverageWithoutChangingInputs() {
        val first = page("1")
        val second = page("2")
        val input = listOf(first, second, first.copy())
        val before = input.map { it.copy(coverage = it.coverage.copy(pageFingerprints = it.coverage.pageFingerprints.toMap())) }
        val completeness = CashSourceDocuments.documentCompleteness(input)
        assertEquals(mapOf(first.namespace to true), completeness)
        val current = CashSourceDocuments.currentDocumentCoverage(first, completeness)
        assertTrue(current.complete)
        assertEquals("icbc-pdf-export-pages-count-v1", current.evidence)
        assertEquals(mapOf("1" to "b".repeat(64)), current.pageFingerprints)
        assertEquals(before, input)
        assertFalse(first.coverage.complete)
    }

    @Test
    fun missingConflictingOrInconsistentPagesCannotProveCompleteness() {
        val first = page("1")
        val second = page("2")
        val variants = listOf(listOf(first), listOf(first, second, page("1", "c".repeat(64))),
            listOf(first, second.copy(coverage = second.coverage.copy(filters = mapOf("kind" to "expense")))),
            listOf(first, second.copy(coverage = second.coverage.copy(endInclusive = true))),
            listOf(first, second.copy(coverage = second.coverage.copy(timePrecision = "day"))),
            listOf(first, second.copy(coverage = second.coverage.copy(strategyVersion = "v2"))),
            listOf(first, second.copy(coverage = second.coverage.copy(pageTotal = 3))))
        for (items in variants) {
            val result = CashSourceDocuments.documentCompleteness(items)
            assertEquals(mapOf(first.namespace to false), result)
            assertEquals("", CashSourceDocuments.currentDocumentCoverage(first, result).evidence)
        }
    }

    @Test
    fun parserAndSourceNamespacesDoNotSharePageProofs() {
        val first = page("1")
        val second = page("2")
        for (isolated in listOf(second.copy(parserVersion = "pdf-v2"), second.copy(sourceType = "icbc_debit"))) {
            assertEquals(mapOf(first.namespace to false, isolated.namespace to false),
                CashSourceDocuments.documentCompleteness(listOf(first, isolated)))
        }
    }

    @Test
    fun invalidRangesAndUnknownDocumentsAreNotMadeComplete() {
        for (bounds in listOf(null to null, "wrong" to "also wrong", "2026-09-01T00:00Z" to "2026-08-01T00:00Z")) {
            val items = listOf(page("1"), page("2")).map {
                it.copy(coverage = it.coverage.copy(start = bounds.first, end = bounds.second))
            }
            assertEquals(mapOf(items.first().namespace to false), CashSourceDocuments.documentCompleteness(items))
        }
        val unknown = SourceDocumentEvidence("bank", "v1", CashSourceCoverage(null, null, false, "", emptyMap(), "v1"))
        assertTrue(CashSourceDocuments.documentCompleteness(listOf(unknown)).isEmpty())
        assertEquals(unknown.coverage, CashSourceDocuments.currentDocumentCoverage(unknown, emptyMap()))
    }
}
