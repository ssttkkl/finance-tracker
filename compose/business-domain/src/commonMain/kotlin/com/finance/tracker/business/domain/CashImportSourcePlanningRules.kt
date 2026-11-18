package com.finance.tracker.business.domain

/** Current source records and proof inputs used by a read-only import plan. */
data class PreparedCashSourcePlan(val rows: SourceBatchRows, val records: List<SourceRecord>,
    val plan: SourceBatchPlan, val evidence: List<SourceDocumentEvidence>)

/** Source proof preparation over explicit read ports; never publishes bindings or mutates records. */
class CashImportSourcePlanningRules(
    private val readRecords: (Collection<SourceOccurrence>) -> List<SourceRecord>,
    private val readDocumentEvidence: (List<ParsedImportFile>, List<SourceDocumentEvidence>) -> List<SourceDocumentEvidence>,
    private val resolveBatch: (SourceBatchRows, List<SourceRecord>, List<SourceDocumentEvidence>) -> SourceBatchPlan,
) {
    fun prepare(files: List<ParsedImportFile>,
        resolvedFields: Map<String, Map<Int, Map<String, Any?>>> = emptyMap()): PreparedCashSourcePlan {
        var rows = CashSourceRows.sourceBatchRows(files, resolvedFields)
        val records = readRecords(rows.rows.values)
        val candidates = records.flatMap { it.documentEvidence }
        val saved = readDocumentEvidence(files, candidates)
        val evidence = CashSourceRows.fileDocumentEvidence(files) + saved + candidates
        val completeness = CashSourceDocuments.documentCompleteness(evidence)
        if (saved.isNotEmpty()) rows = CashSourceRows.sourceBatchRows(files, resolvedFields, saved)
        val lifted = records.map { record ->
            val regular = record.coverages.filter { it.documentKey == null }
            val current = record.documentEvidence.map { CashSourceDocuments.currentDocumentCoverage(it, completeness) }
            // 缺少命名空间的旧认证标志不能单独授予完整证明。
            val unproven = if (record.documentEvidence.isEmpty()) record.coverages.filter { it.documentKey != null }
                .map { it.copy(complete = false, evidence = "") } else emptyList()
            record.copy(coverages = regular + current + unproven)
        }
        return PreparedCashSourcePlan(rows, lifted,
            resolveBatch(rows, lifted, evidence), evidence)
    }
}
