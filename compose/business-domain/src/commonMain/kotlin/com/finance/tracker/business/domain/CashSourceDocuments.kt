package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.CashSourceCoverage

data class SourceDocumentEvidence(val sourceType: String, val parserVersion: String,
    val coverage: CashSourceCoverage) {
    val namespace: DocumentNamespace get() = DocumentNamespace(sourceType, parserVersion, coverage.documentKey)
}

data class DocumentNamespace(val sourceType: String, val parserVersion: String, val documentKey: String?)

object CashSourceDocuments {
    fun documentCompleteness(evidence: List<SourceDocumentEvidence>): Map<DocumentNamespace, Boolean> {
        val groups = linkedMapOf<DocumentNamespace, MutableList<CashSourceCoverage>>()
        for (item in evidence) if (!item.coverage.documentKey.isNullOrEmpty())
            groups.getOrPut(item.namespace) { mutableListOf() } += item.coverage
        val result = linkedMapOf<DocumentNamespace, Boolean>()
        for ((namespace, coverages) in groups) {
            val first = coverages.first()
            var consistent = true
            val pages = linkedMapOf<String, String>()
            for (coverage in coverages) {
                consistent = consistent && declaration(first) == declaration(coverage)
                for ((page, fingerprint) in coverage.pageFingerprints) {
                    val previous = pages[page]
                    if (previous == null) pages[page] = fingerprint
                    consistent = consistent && (previous == null || previous == fingerprint)
                }
            }
            val rangeStart = first.start
            val rangeEnd = first.end
            val bounded = rangeStart != null && rangeEnd != null && try {
                parsePythonCashTimestamp(rangeStart) < parsePythonCashTimestamp(rangeEnd)
            } catch (_: IllegalArgumentException) {
                false
            }
            val total = first.pageTotal
            result[namespace] = consistent && bounded && total != null &&
                pages.keys == (1..total).map { it.toString() }.toSet()
        }
        return result
    }

    fun currentDocumentCoverage(item: SourceDocumentEvidence, completeness: Map<DocumentNamespace, Boolean>): CashSourceCoverage {
        if (item.coverage.documentKey.isNullOrEmpty()) return item.coverage
        val complete = completeness[item.namespace] == true
        return item.coverage.copy(complete = complete,
            evidence = if (complete) "icbc-pdf-export-pages-count-v1" else "")
    }

    private fun declaration(coverage: CashSourceCoverage): List<Any?> = listOf(coverage.start, coverage.end,
        coverage.filters, coverage.startInclusive, coverage.endInclusive, coverage.timePrecision,
        coverage.strategyVersion, coverage.documentKey, coverage.pageTotal)
}
