package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.CASH_SOURCE_EMPTY_PAGES

/** 空页证据与当前业务页共同校验；调用方负责锁定工作区及提交。 */
internal class CashSourceDocumentWriteRepository(private val context: CashSqlContext) {
    fun validateEvidence(evidence: List<SourceDocumentEvidence>): List<SourceDocumentEvidence> {
        context.requireActive()
        val existing = CashSourceDocumentReadRepository(context).read(candidateEvidence = evidence)
        val combined = existing + evidence
        val declarations = linkedMapOf<DocumentNamespace, Map<String, Any?>>()
        for (item in combined) if (item.coverage.documentKey != null) {
            val declaration = cashSourceDeclaration(item.coverage)
            if (declarations.getOrPut(item.namespace) { declaration } != declaration) throw CashSourceBindingConflict("source_document_conflict")
        }
        if (CashSourceResolver.conflictingSourceDocumentPages(emptyList(), evidence = combined).isNotEmpty())
            throw CashSourceBindingConflict("source_document_conflict")
        return existing
    }

    fun saveEmptyEvidence(evidence: List<SourceDocumentEvidence>) {
        val existing = validateEvidence(evidence)
        val known = existing.flatMap { item -> item.coverage.pageFingerprints.keys.map { item.namespace to it } }.toMutableSet()
        for (item in evidence) for ((page, fingerprint) in item.coverage.pageFingerprints)
            if (known.add(item.namespace to page)) savePage(item, page, fingerprint)
    }

    private fun savePage(item: SourceDocumentEvidence, page: String, fingerprint: String) {
        val table = CASH_SOURCE_EMPTY_PAGES
        val proof = item.coverage.copy(complete = false, evidence = "", pageFingerprints = mapOf(page to fingerprint))
        context.connection.markLedgerChanged(context.workspace)
        context.sql.insertInto(table).set(table.WORKSPACE_ID, context.workspace).set(table.SOURCE_TYPE, item.sourceType)
            .set(table.PARSER_VERSION, item.parserVersion).set(table.DOCUMENT_KEY, item.coverage.documentKey).set(table.PAGE_NUMBER, page.toLong())
            .set(table.COVERAGE, sourceJson(cashSourceCoverageFields(proof))).execute()
    }

}
