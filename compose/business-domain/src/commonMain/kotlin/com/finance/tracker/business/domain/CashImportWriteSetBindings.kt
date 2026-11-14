package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*

/** Final binding invariants only; caller owns trusted facts, identity replay and the atomic command. */
fun prepareCashImportWriteSetBindings(changes: CashWriteSetDto, facts: LedgerFactsDto): CashWriteSetDto {
    try { validateCashImportWriteSetScope(changes, facts) }
    catch (failure: CashWriteSetReferenceFailure) { throw CashPolicyError(failure.code, listOf(failure.fieldPath)) }
    fun fail(code: String, path: String): Nothing = throw CashPolicyError(code, listOf(path))
    fun text(value: String, maximum: Int): Boolean {
        if (value.isEmpty() || value.pythonCodePointCount() > maximum || '\u0000' in value) return false
        return try { value.encodeToByteArray(throwOnInvalidSequence = true); true } catch (_: CharacterCodingException) { false }
    }
    val oldBindings = facts.cashSourceBindings.associateBy { it.id }
    val parents = facts.cashTransactions.associateBy { it.id }
    val writes = changes.transactions.associateBy { it.target.reference }
    fun oldParent(ref: CashWriteReferenceDto) = (ref as? ExistingCashWriteReferenceDto)?.id?.let(parents::get)
    fun live(ref: CashWriteReferenceDto) = oldParent(ref)?.deletedAt == null && (ref in writes || oldParent(ref) != null)
    val normalized = changes.copy(sourceBindings = changes.sourceBindings.mapIndexed { index, row ->
        val path = "source_bindings[$index]"
        if (row.sourceType !in setOf("alipay", "wechat", "icbc_credit", "icbc_debit", "icbc_asia", "ccb_debit") ||
            row.sourceScopeKind !in setOf("account", "file_local") || row.identityKind !in setOf("provider_id", "file_occurrence") ||
            row.sourceScopeKind == "file_local" && row.identityKind != "file_occurrence" ||
            row.sourceScopeKind == "account" && row.sourceType in setOf("alipay", "wechat") &&
                row.sourceScopeKey != CashSourceScope.sourceTransactionScopeKey(row.sourceType) ||
            !text(row.sourceScopeKey, 256) || !text(row.identityKey, 64) || !text(row.strategyVersion, 64) ||
            row.stableEvidenceKey?.let { !text(it, 64) } == true || row.sourceScopeKind == "file_local" && row.stableEvidenceKey != null ||
            row.identityStrength != SourceBindingKey(row.sourceType, row.sourceScopeKind, row.sourceScopeKey, row.identityKind, row.identityKey).identityStrength)
            fail("invalid_source_binding", path)
        val parent = oldParent(row.transaction)
        val pending = writes[row.transaction]?.values
        if (!live(row.transaction)) fail("source_target_unavailable", "$path.transaction")
        val source = if (pending != null) pending.sourceType else parent?.sourceType
        val origin = if (pending != null) pending.recordOrigin else parent?.recordOrigin
        if (source != row.sourceType || origin !in setOf("import", "legacy")) fail("source_target_incompatible", "$path.transaction")
        val old = (row.target.reference as? ExistingCashWriteReferenceDto)?.id?.let(oldBindings::get)
        if (old != null && (old.sourceType != row.sourceType || old.sourceScopeKind != row.sourceScopeKind ||
                old.sourceScopeKey != row.sourceScopeKey || old.identityKind != row.identityKind || old.identityKey != row.identityKey ||
                !old.stableEvidenceKey.isNullOrEmpty() && old.stableEvidenceKey != row.stableEvidenceKey)) fail("source_identity_conflict", path)
        val result = row.copy(coverage = normalizeBindingCoverage(row.coverage) { fail("invalid_source_coverage", "$path.coverage") })
        if (old != null && parents[old.transactionId]?.let { it.deletedAt == null && it.recordOrigin in setOf("import", "legacy") } == true &&
            !retainsStoredDocumentAlias(old, result)) fail("source_document_conflict", "$path.coverage")
        result
    })
    val replaced = normalized.sourceBindings.map { it.target.reference }.toSet()
    val previous = facts.cashSourceBindings.map { row -> CashSourceBindingWriteDto(
        CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(row.id), row.revision), ExistingCashWriteReferenceDto(row.transactionId),
        row.sourceType, row.sourceScopeKind, row.sourceScopeKey, row.identityKind, row.identityKey, row.identityStrength, row.stableEvidenceKey, row.strategyVersion, row.coverage) }
    val all = previous.filter { it.target.reference !in replaced } + normalized.sourceBindings
    val owners = all.filter { live(it.transaction) && !it.stableEvidenceKey.isNullOrEmpty() }.groupBy { it.stableEvidenceKey }
    val groups = all.groupBy { it.transaction }
    fun coverage(row: CashSourceBindingWriteDto): CashSourceCoverage = try { decodeStoredCashSourceCoverage(row.coverage) }
        catch (failure: IllegalArgumentException) { throw IllegalStateException("write_set.invalid_persisted_coverage", failure) }
    for (owner in normalized.sourceBindings.map { it.transaction }.distinct()) {
        val bindings = groups.getValue(owner)
        val account = bindings.filter { it.sourceScopeKind == "account" }
        if (bindings.any { it.sourceType != bindings.first().sourceType } || account.map { it.sourceScopeKey }.distinct().size > 1 ||
            account.filter { it.identityKind == "provider_id" }.map { it.identityKey }.distinct().size > 1 ||
            bindings.mapNotNull { it.stableEvidenceKey?.takeIf(String::isNotEmpty) }.distinct().size > 1)
            fail("source_identity_conflict", "source_bindings")
        val positions = linkedMapOf<String, MutableSet<String>>()
        for (binding in bindings) {
            val document = coverage(binding).documentKey ?: continue
            if (binding.sourceScopeKind == "file_local" && binding.sourceScopeKey ==
                localSourceStringDigest(listOf("source-document-v1", binding.sourceType, binding.strategyVersion, document)))
                positions.getOrPut(binding.sourceScopeKey) { linkedSetOf() } += binding.identityKey
        }
        if (positions.values.any { it.size > 1 }) fail("source_identity_conflict", "source_bindings")
    }
    for (row in normalized.sourceBindings) if (row.stableEvidenceKey != null &&
        owners[row.stableEvidenceKey].orEmpty().any { it.transaction != row.transaction }) fail("active_source_binding", "source_bindings")
    val namespaces = normalized.sourceBindings.mapNotNull { row -> coverage(row).documentKey?.let { DocumentNamespace(row.sourceType, row.strategyVersion, it) } }.toSet()
    if (namespaces.isEmpty()) return normalized
    val evidence = (previous + normalized.sourceBindings).filter { live(it.transaction) }.mapNotNull { row ->
        val document = (row.coverage as? JsonObject)?.get("document_key") as? JsonPrimitive
        val namespace = DocumentNamespace(row.sourceType, row.strategyVersion, document?.takeIf { it.isString }?.content)
        if (namespace in namespaces) SourceDocumentEvidence(row.sourceType, row.strategyVersion, coverage(row)) else null
    } + facts.cashSourceEmptyPages.filter { DocumentNamespace(it.sourceType, it.parserVersion, it.documentKey) in namespaces }
        .map { SourceDocumentEvidence(it.sourceType, it.parserVersion, decodeStoredCashSourceCoverage(it.coverage)) }
    val declarations = linkedMapOf<DocumentNamespace, Map<String, Any?>>()
    for (item in evidence) {
        val value = cashSourceDeclaration(item.coverage)
        if (declarations.getOrPut(item.namespace) { value } != value) fail("source_document_conflict", "source_bindings")
    }
    if (localCashSourceResolver.conflictingSourceDocumentPages(emptyList(), evidence = evidence).isNotEmpty())
        fail("source_document_conflict", "source_bindings")
    return normalized
}

private fun normalizeBindingCoverage(value: JsonElement, invalid: () -> Nothing): JsonElement {
    val item = value as? JsonObject ?: invalid()
    for (key in listOf("start", "end", "evidence", "strategy_version", "time_precision", "document_key"))
        item[key]?.takeIf { it != JsonNull }?.let { if (it !is JsonPrimitive || !it.isString) invalid() }
    for (key in listOf("complete", "start_inclusive", "end_inclusive"))
        item[key]?.takeIf { it != JsonNull }?.let { if (it !is JsonPrimitive || it.isString || it.booleanOrNull == null) invalid() }
    val coverage = try { decodeStoredCashSourceCoverage(item) } catch (_: IllegalArgumentException) { invalid() }
    val fields = cashSourceCoverageFields(coverage) + ("page_total" to coverage.pageTotal?.let(::SourceJsonInteger))
    return sourceJson(fields, { null }, ::pythonFloatText)
}

private fun retainsStoredDocumentAlias(old: LedgerSourceBindingFactDto, row: CashSourceBindingWriteDto): Boolean {
    if (old.sourceScopeKind != "file_local" || old.identityKind != "file_occurrence") return true
    val previous = try { decodeStoredCashSourceCoverage(old.coverage) }
        catch (failure: IllegalArgumentException) { throw IllegalStateException("write_set.invalid_persisted_coverage", failure) }
    val document = previous.documentKey ?: return true
    val scope = localSourceStringDigest(listOf("source-document-v1", old.sourceType, old.strategyVersion, document))
    val alias = old.sourceScopeKey == scope || previous.pageFingerprints.keys.any {
        old.sourceScopeKey == localSourceStringDigest(listOf("source-document-page-v1", scope, it)) }
    if (!alias) return true
    val next = decodeStoredCashSourceCoverage(row.coverage)
    return row.strategyVersion == old.strategyVersion && cashSourceDeclaration(next) == cashSourceDeclaration(previous) &&
        previous.pageFingerprints.all { (page, fingerprint) -> next.pageFingerprints[page] == fingerprint }
}
