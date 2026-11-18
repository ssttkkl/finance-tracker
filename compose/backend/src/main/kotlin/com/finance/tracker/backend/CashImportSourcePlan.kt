package com.finance.tracker.backend

internal typealias PreparedCashSourcePlan = com.finance.tracker.business.domain.PreparedCashSourcePlan

/** Read the current workspace; all source proof preparation uses the shared import rules. */
internal class CashImportSourcePlanner(context: CashSqlContext) {
    private val rules = com.finance.tracker.business.domain.CashImportSourcePlanningRules(
        { rows -> CashSourceReadRepository(context).recordsForFile(rows) },
        { files, candidates -> CashSourceDocumentReadRepository(context).read(files, candidateEvidence = candidates) },
        { rows, records, evidence -> CashSourceBatch.resolve(rows, records, knownDocumentEvidence = evidence) },
    )
    fun prepare(files: List<ParsedImportFile>,
        resolvedFields: Map<String, Map<Int, Map<String, Any?>>> = emptyMap()): PreparedCashSourcePlan =
        rules.prepare(files, resolvedFields)
}
