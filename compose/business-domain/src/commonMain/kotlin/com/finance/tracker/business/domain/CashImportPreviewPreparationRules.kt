package com.finance.tracker.business.domain

data class PreparedCashImportPreview<I : Comparable<I>, T : Comparable<T>>(
    val operations: List<Map<String, Any?>>,
    val accounts: Map<String, Map<String, Any?>>,
    val candidateIds: Set<Long>,
    val retainedParentIds: Set<String>,
    val context: CashRelationReadContext<I, T>,
)

/** Prepare source references and dependency reads through the caller's existing authorization and read ports. */
class CashImportPreviewPreparationRules<I : Comparable<I>, T : Comparable<T>>(
    private val requireActive: () -> Unit,
    private val readAccounts: () -> Map<String, Map<String, Any?>>,
    private val candidateIds: (List<Map<String, Any?>>, Map<String, SourceOccurrence>) -> Set<Long>,
    private val loadContext: (Set<Long>, List<String>) -> CashRelationReadContext<I, T>,
) {
    fun prepare(
        operations: List<Map<String, Any?>>,
        sourceRows: Map<String, SourceOccurrence>,
        entryChoices: List<Map<String, Any?>> = emptyList(),
        previousPlan: Map<String, Any?>? = null,
        additionalSeedIds: Set<Long> = emptySet(),
    ): PreparedCashImportPreview<I, T> {
        requireActive()
        val prepared = operations.map { operation ->
            val ref = (operation["row_refs"] as List<*>).first() as String
            val source = sourceRows.getValue(ref)
            @Suppress("UNCHECKED_CAST") val values = operation["values"] as Map<String, Any?>
            @Suppress("UNCHECKED_CAST") val updates = operation["source_updates"] as? List<Map<String, Any?>> ?: emptyList()
            val bindings = updates.filter { it["bind"] == true }.flatMap { update ->
                sourceRows[update["row_ref"]?.toString()]?.bindingSpecs.orEmpty()
                    .map { listOf(it.sourceType, it.scopeKind, it.scopeKey, it.identityKind, it.identityKey) }
            }
            operation.toMutableMap().apply {
                this["source_scope_key"] = source.accountScopeKey ?: "file:${source.fileScopeKey}"
                this["matching_record_id"] = if (source.providerId != null) values["record_id"] else
                    "occurrence:${source.occurrenceKey}"
                this["stable_source_key"] = cashSourceOrderKey(source.sourceType, "", bindings = bindings)
            }
        }
        return prepareOperations(prepared, sourceRows, entryChoices, previousPlan, additionalSeedIds)
    }

    fun prepareOperations(
        operations: List<Map<String, Any?>>,
        sourceRows: Map<String, SourceOccurrence> = emptyMap(),
        entryChoices: List<Map<String, Any?>> = emptyList(),
        previousPlan: Map<String, Any?>? = null,
        additionalSeedIds: Set<Long> = emptySet(),
        decisionGroupIds: List<String> = emptyList(),
    ): PreparedCashImportPreview<I, T> {
        requireActive()
        @Suppress("UNCHECKED_CAST") val targets = previousPlan?.get("entry_targets") as? Map<String, Any?> ?: emptyMap()
        val retained = linkedSetOf<String>()
        for (choice in entryChoices) {
            val target = targets[choice["edit_ref"]] as? Map<*, *> ?: throw CashImportPlanError("plan_changed")
            val parents = target["parent_ids"] as? List<*> ?: throw CashImportPlanError("plan_changed")
            parents.forEach { retained += it.toString() }
        }
        val accounts = readAccounts()
        val new = operations.filter { it["planned_action"] in setOf("create_posted", "finalize_draft") }
        val candidates = candidateIds(new, sourceRows)
        val existing = operations.mapNotNull { it["transaction_id"]?.toString()?.takeIf(String::isNotEmpty)?.toLong() }.toSet()
        val relationContext = loadContext(existing + candidates + additionalSeedIds, decisionGroupIds)
        return PreparedCashImportPreview(operations, accounts, candidates, retained, relationContext)
    }
}
