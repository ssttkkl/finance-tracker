package com.finance.tracker.backend

/** 统一准备计划／提交所需的当前来源对应；预览不写入业务状态。 */
internal data class PreparedCashSourcePlan(val rows: SourceBatchRows, val records: List<SourceRecord>,
    val plan: SourceBatchPlan, val evidence: List<SourceDocumentEvidence>)

internal class CashImportSourcePlanner(private val context: CashSqlContext) {
    fun prepare(files: List<ParsedImportFile>,
        resolvedFields: Map<String, Map<Int, Map<String, Any?>>> = emptyMap()): PreparedCashSourcePlan {
        var rows = CashSourceRows.sourceBatchRows(files, resolvedFields)
        val records = CashSourceReadRepository(context).recordsForFile(rows.rows.values)
        val candidates = records.flatMap { it.documentEvidence }
        val saved = CashSourceDocumentReadRepository(context).read(files, candidateEvidence = candidates)
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
            CashSourceBatch.resolve(rows, lifted, knownDocumentEvidence = evidence), evidence)
    }
}
