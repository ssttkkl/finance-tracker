package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*

/** Source metadata only. Relation problems and complete file-operation effects remain caller-owned. */
fun prepareCashImportWriteSetSourceMetadata(changes: CashWriteSetDto, facts: LedgerFactsDto): CashWriteSetDto {
    val normalized = prepareCashImportWriteSetBindings(changes, facts)
    fun fail(code: String, path: String): Nothing = throw CashPolicyError(code, listOf(path))
    val parents = facts.cashTransactions.associateBy { it.id }
    val writes = changes.transactions.associateBy { it.target.reference }
    val oldIssues = facts.cashTransactionIssues.associateBy { it.id }
    fun owner(reference: CashWriteReferenceDto, path: String): CashTransactionWriteValuesDto {
        val row = writes[reference] ?: fail("field_not_editable", path)
        val old = (reference as? ExistingCashWriteReferenceDto)?.id?.let(parents::get)
        if (old?.deletedAt != null || row.values.recordOrigin !in setOf("import", "legacy")) fail("source_target_unavailable", path)
        return row.values
    }
    val pages = changes.sourceEmptyPages.mapIndexed { index, page ->
        val path = "source_empty_pages[$index]"
        fun invalid(): Nothing = fail("invalid_source_coverage", path)
        if (page.sourceType !in setOf("alipay", "wechat", "icbc_credit", "icbc_debit", "icbc_asia", "ccb_debit") ||
            !metadataText(page.parserVersion, 64) || page.pageNumber !in 1..2000 || !Regex("[0-9a-f]{64}").matches(page.documentKey)) invalid()
        val value = normalizeCashImportSourceCoverage(page.coverage, ::invalid)
        val coverage = decodeStoredCashSourceCoverage(value)
        if (coverage.documentKey != page.documentKey || coverage.pageFingerprints.keys != setOf(page.pageNumber.toString())) invalid()
        page.copy(coverage = JsonObject(value.jsonObject + mapOf("complete" to JsonPrimitive(false), "evidence" to JsonPrimitive(""))))
    }
    validateCashImportWriteSetSourceDocuments(normalized, facts, pages.map {
        SourceDocumentEvidence(it.sourceType, it.parserVersion, decodeStoredCashSourceCoverage(it.coverage)) })
    val issues = changes.issues.mapIndexed { index, row ->
        val path = "issues[$index]"
        fun invalid(): Nothing = fail("invalid_source_issue", path)
        val old = (row.target.reference as? ExistingCashWriteReferenceDto)?.id?.let(oldIssues::get)
        if (old != null && (old.transactionId != (row.transaction.reference as? ExistingCashWriteReferenceDto)?.id || old.issueKey != row.issueKey))
            fail("source_identity_conflict", path)
        if (row.issueKey.startsWith("relation:")) return@mapIndexed row
        val value = owner(row.transaction.reference, "$path.transaction")
        if (old != null && (old.issueKey.startsWith("relation:") || old.code == "source_identity_ambiguous" &&
                (row.code != old.code || row.fieldPath != old.fieldPath))) fail("field_not_editable", path)
        if (!metadataText(row.issueKey, 128)) invalid()
        try { cashImportIssue(row.code, row.fieldPath) } catch (_: IllegalArgumentException) { invalid() }
        for (json in listOf(row.pendingSourcePayload, row.pendingFields, row.origin)) if (!metadataJson(json)) invalid()
        if (row.pendingSourcePayload != JsonNull && row.pendingSourcePayload !is JsonObject ||
            row.pendingFields != JsonNull && row.pendingFields !is JsonObject) invalid()
        val origin = if (row.origin == JsonNull) JsonObject(emptyMap()) else row.origin as? JsonObject ?: invalid()
        if (origin.keys.any { it !in setOf("file_digest", "table_key", "position", "source_type", "source_scope_kind", "source_scope_key", "parser_version") }) invalid()
        if (row.code == "source_identity_ambiguous" && old != null && row.candidates.isEmpty())
            return@mapIndexed row.copy(pendingSourcePayload = old.pendingSourcePayload, pendingFields = old.pendingFields, origin = old.origin)
        if (row.code != "source_identity_ambiguous" && row.candidates.isNotEmpty()) invalid()
        for ((candidateIndex, candidate) in row.candidates.withIndex()) {
            val previous = (candidate.reference as? ExistingCashWriteReferenceDto)?.id?.let(parents::get)
            val next = writes[candidate.reference]?.values
            if (candidate.reference == row.transaction.reference || previous?.deletedAt != null ||
                (next?.recordOrigin ?: previous?.recordOrigin) !in setOf("import", "legacy") ||
                (next?.sourceType ?: previous?.sourceType) != value.sourceType)
                fail("source_candidate_changed", "$path.candidates[$candidateIndex]")
        }
        if (row.code == "source_update_pending") try {
            pendingLocalCashSourceIssuePatch(CashSourceIssue(0, 0, row.code, row.fieldPath, row.pendingFields, row.pendingSourcePayload, origin))
        } catch (_: CashPolicyError) { invalid() }
        row.copy(origin = origin)
    }
    for ((index, target) in changes.resolvedIssues.withIndex()) {
        val old = oldIssues.getValue((target.reference as ExistingCashWriteReferenceDto).id)
        val path = "resolved_issues[$index]"
        if (old.code == "source_identity_ambiguous" || old.issueKey.startsWith("relation:")) fail("field_not_editable", path)
        owner(ExistingCashWriteReferenceDto(old.transactionId), path)
    }
    val replaced = changes.issues.map { it.target.reference }.toSet() + changes.resolvedIssues.map { it.reference }
    val remaining = facts.cashTransactionIssues.filter { ExistingCashWriteReferenceDto(it.id) !in replaced && !it.issueKey.startsWith("relation:") }
        .map { ExistingCashWriteReferenceDto(it.transactionId) } + issues.filterNot { it.issueKey.startsWith("relation:") }.map { it.transaction.reference }
    val blocked = remaining.toSet()
    for ((index, row) in changes.transactions.withIndex()) {
        val old = (row.target.reference as? ExistingCashWriteReferenceDto)?.id?.let(parents::get)
        if (row.values.state == "posted" && old?.state != "posted" && row.target.reference in blocked)
            fail("posting_incomplete", "transactions[$index].values.state")
    }
    return normalized.copy(sourceEmptyPages = pages, issues = issues)
}

private fun metadataText(value: String, maximum: Int): Boolean {
    if (value.isEmpty() || value.pythonCodePointCount() > maximum || '\u0000' in value) return false
    return try { value.encodeToByteArray(throwOnInvalidSequence = true); true } catch (_: CharacterCodingException) { false }
}

private fun metadataJson(value: JsonElement, depth: Int = 0): Boolean {
    if (depth > 64) return false
    return when (value) {
        is JsonObject -> value.all { (key, item) -> (key.isEmpty() || metadataText(key, Int.MAX_VALUE)) && metadataJson(item, depth + 1) }
        is JsonArray -> value.all { metadataJson(it, depth + 1) }
        is JsonPrimitive -> !value.isString || value.content.isEmpty() || metadataText(value.content, Int.MAX_VALUE)
    }
}
