package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.CASH_SOURCE_EMPTY_PAGES

/** 认证空页与当前业务页共同校验；调用方负责锁定工作区及提交。 */
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

    fun saveEmptyPages(files: List<ParsedImportFile>) {
        val existing = validateEvidence(CashSourceRows.fileDocumentEvidence(files))
        val known = existing.flatMap { item -> item.coverage.pageFingerprints.keys.map { item.namespace to it } }.toMutableSet()
        val table = CASH_SOURCE_EMPTY_PAGES
        for (file in files) {
            val document = file.document ?: continue
            val coverage = document.coverage
            if (file.status != "parsed" || file.isSubset || coverage.documentKey == null || document.observedCount != document.rows.size ||
                document.rows.any { it.documentPosition == null }) continue
            val occupied = document.rows.map { it.documentPosition!!.substringBefore(':').drop(1) }.toSet()
            for ((page, fingerprint) in coverage.pageFingerprints) {
                if (page in occupied || !known.add(DocumentNamespace(document.sourceType, document.parserVersion, coverage.documentKey) to page)) continue
                val proof = coverage.copy(complete = false, evidence = "", pageFingerprints = mapOf(page to fingerprint))
                context.sql.insertInto(table).set(table.WORKSPACE_ID, context.workspace).set(table.SOURCE_TYPE, document.sourceType)
                    .set(table.PARSER_VERSION, document.parserVersion).set(table.DOCUMENT_KEY, coverage.documentKey).set(table.PAGE_NUMBER, page.toLong())
                    .set(table.COVERAGE, sourceJson(cashSourceCoverageFields(proof))).execute()
            }
        }
    }
}
