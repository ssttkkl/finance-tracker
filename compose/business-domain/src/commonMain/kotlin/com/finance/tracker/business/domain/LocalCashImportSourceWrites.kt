package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*

/** Compose file-source deltas from one completed local preview; relations and final entries are composed separately. */
fun buildLocalCashImportSourceWrites(source: LedgerSnapshotDto, files: List<ParsedImportFile>, sources: PreparedCashSourcePlan,
    plan: JsonObject, rowChoices: JsonArray = JsonArray(emptyList())): CashWriteSetDto =
    buildLocalCashImportSourceWrites(source, files, sources, plan, rowChoices, trustedFrozenPlan = false)

internal fun buildLocalCashImportSourceWrites(source: LedgerSnapshotDto, files: List<ParsedImportFile>, sources: PreparedCashSourcePlan,
    plan: JsonObject, rowChoices: JsonArray, trustedFrozenPlan: Boolean): CashWriteSetDto =
    LocalSourceWrites(source, files, sources, plan, rowChoices, trustedFrozenPlan).build()

private class LocalSourceWrites(source: LedgerSnapshotDto, private val files: List<ParsedImportFile>,
    private val sources: PreparedCashSourcePlan, private val plan: JsonObject, rowChoices: JsonArray,
    private val trustedFrozenPlan: Boolean) {
    private val facts = source.facts
    private val transactions = buildLocalCashImportTransactionWrites(source, plan, rowChoices)
    private val operations = plan.getValue("operations").jsonArray.map { it.jsonObject }
    private val parents = facts.cashTransactions.associateBy { it.id }
    private val records = sources.records.associateBy { it.transactionId }
    private val oldBindings = facts.cashSourceBindings.associateBy {
        SourceBindingKey(it.sourceType, it.sourceScopeKind, it.sourceScopeKey, it.identityKind, it.identityKey)
    }
    private val oldIssues = facts.cashTransactionIssues.groupBy { it.transactionId }
    private val bindings = linkedMapOf<SourceBindingKey, CashSourceBindingWriteDto>()
    private val issues = mutableListOf<CashIssueWriteDto>()
    private val resolved = mutableListOf<CashWriteVersionedReferenceDto>()
    private val empty = JsonObject(emptyMap())
    private val encodedCoverages = mutableMapOf<CashSourceCoverage, JsonElement>()

    fun build(): CashWriteSetDto {
        for ((operation, row) in operations.zip(transactions)) {
            @Suppress("UNCHECKED_CAST") val decoded = decodeLocalCashValue(operation) as Map<String, Any?>
            val unchanged = cashImportIssuesUnchanged(decoded, ::sourceTruthy)
            val record = operation.text("transaction_id")?.let(records::getValue)
            val missingBinding = operation.getValue("source_updates").jsonArray.map { it.jsonObject }.any { update ->
                update.getValue("bind") == JsonPrimitive(true) &&
                    sources.rows.rows.getValue(update.getValue("row_ref").jsonPrimitive.content).bindingSpecs.any { it.key !in record?.bindingKeys.orEmpty() }
            }
            if (operation.getValue("planned_action") == JsonPrimitive("duplicate") && unchanged && !missingBinding) continue
            bind(operation, row)
            sourceIssues(operation, row)
        }
        return CashWriteSetDto(transactions = transactions, sourceBindings = bindings.values.toList(), issues = issues,
            resolvedIssues = resolved, statementAccountMappings = mappings(), sourceEmptyPages = emptyPages())
    }

    private fun bind(operation: JsonObject, row: CashTransactionWriteDto) {
        for (raw in operation.getValue("source_updates").jsonArray) {
            val update = raw.jsonObject
            if (update.getValue("bind") != JsonPrimitive(true)) continue
            val occurrence = sources.rows.rows.getValue(update.getValue("row_ref").jsonPrimitive.content)
            val proof = occurrence.documentPage?.let { (_, page) -> occurrence.coverage.copy(
                pageFingerprints = mapOf(page to occurrence.coverage.pageFingerprints.getValue(page))) } ?: occurrence.coverage
            for (key in occurrence.bindingSpecs) {
                val previous = oldBindings[key]
                val pending = bindings[key]
                check(pending == null || pending.transaction == row.target.reference) { "local_import_binding_owner_changed" }
                val evidence = if (key.scopeKind == "account") occurrence.stableEvidenceBindingKey ?: pending?.stableEvidenceKey ?: previous?.stableEvidenceKey
                    else previous?.stableEvidenceKey
                bindings[key] = CashSourceBindingWriteDto(previous?.let { version(it.id, it.revision) }
                    ?: CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("binding:${key.key}"), null), row.target.reference,
                    key.sourceType, key.scopeKind, key.scopeKey, key.identityKind, key.identityKey, key.identityStrength,
                    evidence, occurrence.parserVersion, coverage(proof))
            }
        }
    }

    private fun sourceIssues(operation: JsonObject, row: CashTransactionWriteDto) {
        val previous = operation.text("transaction_id")?.let { oldIssues[it] }.orEmpty()
        val byReason = previous.associateBy { it.code to it.fieldPath }
        val retained = operation.getValue("retained_issue_ids").jsonArray.map { it.jsonPrimitive.content }.toMutableSet()
        for (raw in operation.getValue("issues").jsonArray) {
            val item = raw.jsonObject; val code = item.getValue("code").jsonPrimitive.content; val field = item.text("field_path")
            val old = byReason[code to field]
            if (old?.issueKey?.startsWith("relation:") == true) { retained += old.id; continue }
            val pending = if (code == "source_update_pending") operation.getValue("pending_fields").jsonObject else empty
            val candidates = if (code == "source_identity_ambiguous") {
                operation.text("candidate_set_ref")?.let { plan.getValue("source_candidate_sets").jsonObject.getValue(it).jsonArray }
                    ?: operation["candidate_ids"]?.jsonArray ?: JsonArray(emptyList())
            } else JsonArray(emptyList())
            if (old != null && (old.id in retained && code == "source_update_pending" && pending.isEmpty() ||
                    code == "source_identity_ambiguous" && candidates.isEmpty())) { retained += old.id; continue }
            val fields = if (pending.isEmpty()) empty else JsonObject((pending - "source_payload") +
                ("_source_explanation" to (operation["source_explanation"]?.takeIf { it != JsonNull } ?: empty)) +
                if ("source_payload" in pending) mapOf("_source_payload_present" to JsonPrimitive(true)) else emptyMap())
            val key = old?.issueKey ?: "import:" + localCashImportDigest(listOf(code, field))
            val owner = when (val ref = row.target.reference) {
                is ExistingCashWriteReferenceDto -> "existing:${ref.id}"
                is NewCashWriteReferenceDto -> "new:${ref.ref}"
            }
            issues += CashIssueWriteDto(old?.let { version(it.id, it.revision) } ?: CashWriteVersionedReferenceDto(
                NewCashWriteReferenceDto("issue:" + localCashImportDigest(listOf(owner, key))), null), row.target, key, code, field,
                pending["source_payload"] ?: JsonNull, fields, empty,
                candidates.map { candidate -> parents.getValue(candidate.jsonPrimitive.content).let { version(it.id, it.rowRevision) } })
            old?.let { retained += it.id }
        }
        for (old in previous) if (old.id !in retained && !old.issueKey.startsWith("relation:")) resolved += version(old.id, old.revision)
    }

    private fun mappings(): List<CashStatementMappingWriteDto> {
        val selected = linkedMapOf<List<String>, String>()
        for (operation in operations) for (raw in operation["used_account_selections"]?.jsonArray.orEmpty()) {
            val item = raw.jsonObject; val source = item.getValue("source_type").jsonPrimitive.content
            if (source == "wechat") continue
            val key = listOf(source, "funding_group_v1", item.getValue("source_account_ref").jsonPrimitive.content)
            val account = item.getValue("account_id").jsonPrimitive.content
            require(key !in selected || selected[key] == account) { "funding_mapping_conflict" }
            selected[key] = account
        }
        val old = facts.statementAccountMappings.associateBy { listOf(it.sourceType, it.identityKind, it.sourceAccountKey) }
        return selected.keys.sortedWith { a, b -> a.indices.firstNotNullOfOrNull { cashRelationStringOrder.compare(a[it], b[it]).takeIf { it != 0 } } ?: 0 }
            .map { key -> CashStatementMappingWriteDto(old[key]?.let { version(it.id, it.revision) } ?: CashWriteVersionedReferenceDto(
                NewCashWriteReferenceDto("mapping:" + localCashImportDigest(key)), null), key[0], key[1], key[2], selected.getValue(key)) }
    }

    private fun sourceEvidence(): Map<String, Any?> {
        if (!trustedFrozenPlan) {
            @Suppress("UNCHECKED_CAST")
            return decodeLocalCashValue(plan) as Map<String, Any?>
        }
        fun evidenceFields(rows: Iterable<JsonElement>, vararg keys: String) = rows.map { row ->
            keys.associateWith { key -> decodeLocalCashValue(row.jsonObject[key]) }
        }
        val evidence = linkedMapOf(
            "row_decisions" to evidenceFields(plan.getValue("row_decisions").jsonArray, "row_ref", "planned_action"),
            "operations" to evidenceFields(operations, "transaction_id", "candidate_ids"),
        )
        return if ("source_candidate_sets" in plan)
            evidence + ("source_candidate_sets" to decodeLocalCashValue(plan.getValue("source_candidate_sets")))
        else evidence
    }

    private fun emptyPages(): List<LedgerSourceEmptyPageFactDto> {
        val groups = cashImportSourceEvidenceGroups(files, sources, sourceEvidence())
        val namespaces = CashSourceRows.fileDocumentEvidence(groups.flatten()).map { it.namespace }.toSet()
        val scopes = namespaces.associateBy { namespace -> listOf(namespace.sourceType, namespace.parserVersion,
            localCashSourceDigest(listOf("source-document-v1", namespace.sourceType, namespace.parserVersion, namespace.documentKey))) }
        val known = facts.cashSourceBindings.filter { binding -> parents.getValue(binding.transactionId).deletedAt == null &&
            binding.sourceScopeKind == "file_local" && binding.identityKind == "file_occurrence" &&
            listOf(binding.sourceType, binding.strategyVersion, binding.sourceScopeKey) in scopes }.flatMap { binding ->
            val proof = decodeStoredCashSourceCoverage(binding.coverage)
            proof.pageFingerprints.keys.map { DocumentNamespace(binding.sourceType, binding.strategyVersion, proof.documentKey) to it }
        }.toMutableSet()
        for (page in facts.cashSourceEmptyPages) if (DocumentNamespace(page.sourceType, page.parserVersion, page.documentKey) in namespaces) {
            val proof = decodeStoredCashSourceCoverage(page.coverage)
            known += proof.pageFingerprints.keys.map { DocumentNamespace(page.sourceType, page.parserVersion, proof.documentKey) to it }
        }
        val result = mutableListOf<LedgerSourceEmptyPageFactDto>()
        for (file in groups.flatten()) {
            val document = file.document ?: continue
            val proof = document.coverage
            val documentKey = proof.documentKey
            if (file.status != "parsed" || file.isSubset || documentKey == null || document.observedCount != document.rows.size ||
                document.rows.any { it.documentPosition == null }) continue
            val occupied = document.rows.map { it.documentPosition!!.substringBefore(':').drop(1) }.toSet()
            val namespace = DocumentNamespace(document.sourceType, document.parserVersion, documentKey)
            for ((page, fingerprint) in proof.pageFingerprints) {
                if (page in occupied || !known.add(namespace to page)) continue
                result += LedgerSourceEmptyPageFactDto(document.sourceType, document.parserVersion, documentKey, page.toInt(),
                    coverage(proof.copy(complete = false, evidence = "", pageFingerprints = mapOf(page to fingerprint))))
            }
        }
        return result
    }

    private fun version(id: String, revision: String) = CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(id), revision)
    private fun coverage(value: CashSourceCoverage): JsonElement {
        encodedCoverages[value]?.let { return it }
        val encoded = encodeLocalCashValue(cashSourceCoverageFields(value) + ("page_total" to value.pageTotal?.let(::SourceJsonInteger)))
        if (trustedFrozenPlan && encodedCoverages.size < 64) encodedCoverages[value] = encoded
        return encoded
    }
    private fun JsonObject.text(key: String): String? = get(key)?.takeIf { it != JsonNull }?.jsonPrimitive?.content
    private fun sourceTruthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is Map<*, *> -> value.isNotEmpty()
        is Collection<*> -> value.isNotEmpty()
        is String -> value.isNotEmpty()
        is Number -> value.toDouble() != 0.0
        else -> true
    }
}
