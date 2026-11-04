package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlin.time.Instant

/** Read and deduplicate against one complete snapshot; the cache owns workspace selection and atomic publication. */
fun prepareLocalCashImportSources(files: List<ParsedImportFile>, snapshot: LedgerSnapshotDto,
    resolvedFields: Map<String, Map<Int, Map<String, Any?>>> = emptyMap()): PreparedCashSourcePlan {
    val read = LocalCashSourceRead(snapshot.facts)
    return CashImportSourcePlanningRules(read::recordsForFile, read::documents, ::resolveLocalCashSourceBatch)
        .prepare(files, resolvedFields)
}

/** Same candidate eligibility and proof joins as the current source repository, over already scoped facts. */
internal class LocalCashSourceRead(private val facts: LedgerFactsDto) {
    private val bindings = facts.cashSourceBindings.sortedBy { it.id.toLong() }
    private val byTransaction = bindings.groupBy { it.transactionId }
    private val components = facts.cashTransactionComponents.sortedWith(compareBy({ it.cashTransactionId.toLong() },
        { it.ordinal.toLong() }, { it.id.toLong() })).groupBy { it.cashTransactionId }
    private fun LedgerSourceBindingFactDto.key() = SourceBindingKey(sourceType, sourceScopeKind, sourceScopeKey, identityKind, identityKey)
    private fun LedgerSourceBindingFactDto.coverage() = decodeStoredCashSourceCoverage(coverage)

    private fun occurredAt(fields: Map<String, Any?>): Instant? = when (val value = fields["occurred_at"]) {
        is Instant -> value
        is String -> if (Regex("(?:[zZ]|[+-][0-9]{2}:?[0-9]{2})$").containsMatchIn(value))
            runCatching { parsePythonCashTimestamp(value) }.getOrNull() else null
        else -> null
    }

    fun recordsForFile(rows: Collection<SourceOccurrence>): List<SourceRecord> {
        if (rows.isEmpty()) return emptyList()
        val keys = rows.flatMap { it.bindingSpecs }.toSet()
        val evidenceKeys = rows.mapNotNull { it.stableEvidenceBindingKey }.toSet()
        val direct = bindings.filter { it.key() in keys || it.stableEvidenceKey in evidenceKeys }.onEach { it.coverage() }
            .map { it.transactionId }.distinct().sortedBy { it.toLong() }
        val types = rows.map { it.sourceType }.toSet()
        val timeSources = rows.filter { it.sourceType != "wechat" }.map { it.sourceType }.toSet()
        val times = rows.filter { it.sourceType != "wechat" }.mapNotNull { occurredAt(it.fields) }.distinct().sorted()
        if (direct.isEmpty() && times.isEmpty()) return emptyList()
        val eligible = facts.cashTransactions.filter {
            it.deletedAt == null && it.recordOrigin in setOf("import", "legacy") && it.sourceType in types
        }
        val byId = eligible.associateBy { it.id }
        val byTime = eligible.mapNotNull { tx -> tx.occurredAt?.let {
            (tx.sourceType to parsePythonCashTimestamp(it)) to tx
        } }.groupBy({ it.first }, { it.second })
        val selected = linkedMapOf<String, LedgerCashTransactionFactDto>()
        // Preserve the repository's chunk union/order, including the source × time cross product.
        var offset = 0
        while (offset < maxOf(direct.size, times.size)) {
            val candidates = direct.drop(offset).take(250).mapNotNull { byId[it] } +
                times.drop(offset).take(250).flatMap { time -> timeSources.flatMap { byTime[it to time].orEmpty() } }
            candidates.distinctBy { it.id }.sortedBy { it.id.toLong() }.forEach { selected[it.id] = it }
            offset += 250
        }
        return selected.values.map { tx ->
            val aliases = byTransaction[tx.id].orEmpty()
            aliases.forEach { it.coverage() } // The repository decodes every selected binding, even unverified aliases.
            val scopes = aliases.filter { it.sourceScopeKind == "account" }.map { it.sourceScopeKey }.toSet()
            check(scopes.size <= 1) { "source_binding_ambiguous" }
            val verified = if (scopes.isEmpty()) aliases else aliases.filter {
                it.sourceScopeKind == "account" && it.sourceScopeKey in scopes
            }
            val positions = aliases.filter { it.sourceScopeKind == "file_local" && it.identityKind == "file_occurrence" }
                .groupBy({ it.sourceScopeKey }, { it.identityKey }).mapValues { it.value.toSet() }
            val children = components[tx.id].orEmpty().map { child -> linkedMapOf<String, Any?>(
                "component_id" to child.id, "account_id" to child.accountId, "amount" to child.amount,
                "currency" to child.currency, "ordinal" to child.ordinal.toLong()) }
            val fields = linkedMapOf<String, Any?>(
                "state" to tx.state, "amount" to tx.amount, "occurred_at" to tx.occurredAt?.let(::parsePythonCashTimestamp),
                "currency" to tx.currency, "account_id" to tx.accountId, "cash_granularity" to tx.cashGranularity,
                "counterparty" to tx.counterparty, "counterparty_account" to tx.counterpartyAccount,
                "counterparty_account_attrs" to decodeLocalCashValue(tx.counterpartyAccountAttrs), "note" to tx.note,
                "category_id" to tx.categoryId, "record_type" to tx.recordType, "record_subtype" to tx.recordSubtype,
                "source_payload" to decodeLocalCashValue(tx.sourcePayload), "components" to children)
            SourceRecord(tx.id, tx.sourceType!!, scopes.firstOrNull(), fields,
                coverages = verified.map { it.coverage() }, bindingKeys = aliases.map { it.key().key }.toSet(),
                fileOccurrences = positions, providerId = null,
                providerBindingKey = aliases.firstOrNull { it.identityKind == "provider_id" }?.key()?.key,
                stableEvidenceBindingKey = aliases.mapNotNull { it.stableEvidenceKey?.takeIf(String::isNotEmpty) }.firstOrNull(),
                rowRevision = tx.rowRevision.toLong().toInt(),
                documentEvidence = verified.mapNotNull { binding -> binding.coverage().takeIf { it.documentKey != null }?.let {
                    SourceDocumentEvidence(binding.sourceType, binding.strategyVersion, it)
                } })
        }
    }

    fun documents(files: List<ParsedImportFile>, candidateEvidence: List<SourceDocumentEvidence>): List<SourceDocumentEvidence> {
        val namespaces = (CashSourceRows.fileDocumentEvidence(files) + candidateEvidence)
            .mapNotNull { it.coverage.documentKey?.let { _ -> it.namespace } }.distinct()
            .sortedWith(compareBy({ it.sourceType }, { it.parserVersion }, { it.documentKey ?: "" }))
        val alive = facts.cashTransactions.filter { it.deletedAt == null }.map { it.id }.toSet()
        val result = mutableListOf<SourceDocumentEvidence>()
        for (batch in namespaces.chunked(150)) {
            val scopes = batch.map { Triple(it.sourceType, it.parserVersion,
                localCashSourceDigest(listOf("source-document-v1", it.sourceType, it.parserVersion, it.documentKey))) }.toSet()
            bindings.filter { it.transactionId in alive && it.sourceScopeKind == "file_local" &&
                it.identityKind == "file_occurrence" && Triple(it.sourceType, it.strategyVersion, it.sourceScopeKey) in scopes }
                .forEach { result += SourceDocumentEvidence(it.sourceType, it.strategyVersion, it.coverage()) }
            val documents = batch.map { Triple(it.sourceType, it.parserVersion, it.documentKey ?: "") }.toSet()
            facts.cashSourceEmptyPages.filter { Triple(it.sourceType, it.parserVersion, it.documentKey) in documents }
                .forEach { result += SourceDocumentEvidence(it.sourceType, it.parserVersion, decodeStoredCashSourceCoverage(it.coverage)) }
        }
        return result
    }
}
