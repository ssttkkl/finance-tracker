package com.finance.tracker.business.domain

/** Match preview components using source evidence and confirmed edges; aliases are read only when discovery is needed. */
class CashImportRelationDiscoveryRules<A>(
    private val requireActive: () -> Unit,
    private val copyValue: (Any?) -> Any?,
    private val originalExpense: (Map<String, Any?>) -> Boolean,
    private val amount: (Any?) -> A,
    private val ordinal: (Any?) -> Int,
    private val needsDiscovery: (List<RelationFact<A>>, Set<String>) -> Boolean,
    private val plan: (List<RelationFact<A>>, List<String>, List<Map<String, Any?>>, Map<String, List<String>>,
        Map<String, List<String>>, String) -> List<RelationProposal>,
) {
    fun <I : Comparable<I>, T : Comparable<T>> matchingFacts(snapshot: CashImportPreviewSnapshot<I, T>, accounts: Map<String, Map<String, Any?>>): List<RelationFact<A>> =
        snapshot.components.values.map { part ->
            val parent = part.transactionId as String
            val value = snapshot.values.getValue(parent)
            val accountId = part.accountId as String
            val account = accounts.getValue(accountId)
            @Suppress("UNCHECKED_CAST") val metadata = copyValue(
                value["source_explanation"] ?: emptyMap<String, Any?>()) as MutableMap<String, Any?>
            if (originalExpense(value)) metadata["offset_role"] = "expense"
            val source = value["source_type"] as? String ?: ""
            val aggregate = value["cash_granularity"] == "aggregate"
            @Suppress("UNCHECKED_CAST") val parts = value["components"] as List<Map<String, Any?>>
            @Suppress("UNCHECKED_CAST") val attrs = value["counterparty_account_attrs"] as List<String>
            RelationFact(part.componentId as String, amount(part.amount),
                part.currency as String, accountId, accountName = account["name"] as String,
                accountType = account["type"] as String, occurredAt = value["occurred_at"],
                counterparty = value["counterparty"] as String, counterpartyAccount = value["counterparty_account"] as String,
                counterpartyAccountAttrs = attrs, paymentMethod = metadata["payment_method"] as? String ?: "",
                note = cashSourceNote(source, value["source_payload"]), recordType = part.recordType as String,
                recordSubtype = part.recordSubtype as String, billSource = source, source = source,
                recordId = (value["matching_record_id"] ?: value["record_id"]) as String,
                sourceIdentity = value["source_scope_key"] as? String ?: "", rawPayload = metadata,
                relationMetadata = metadata, parentId = parent,
                componentOrdinal = if (aggregate) parts.single { it["component_id"] == part.componentId }["ordinal"].let(ordinal) else null,
                cashGranularity = value["cash_granularity"] as String,
                stableSourceKey = (value["stable_source_key"] as? List<*>)?.map { it.toString() }.orEmpty(),
                recordOrigin = (value["record_origin"] ?: "legacy").toString())
        }

    fun <I : Comparable<I>, T : Comparable<T>> discover(snapshot: CashImportPreviewSnapshot<I, T>, seedIds: Set<String>,
        accounts: Map<String, Map<String, Any?>>, workspace: String, readAliases: () -> List<Map<String, Any?>>): List<RelationProposal> {
        requireActive()
        val facts = matchingFacts(snapshot, accounts).filter { fact ->
            val value = snapshot.values.getValue(fact.parentId!!)
            (if ("record_origin" in value) value["record_origin"] else "legacy") in setOf("import", "legacy")
        }
        val eligible = facts.map { it.id }.toSet()
        val seeds = seedIds.intersect(eligible)
        if (seeds.isEmpty() || !needsDiscovery(facts, seeds)) return emptyList()
        val accepted = snapshot.edges.values.filter {
            it.active == true && it.state == "confirmed" && it.primaryComponentId in eligible && it.secondaryComponentId in eligible
        }.map { edge -> linkedMapOf<String, Any?>(
            "id" to edge.id.toString(), "kind" to edge.kind,
            "primary_fact_id" to edge.primaryComponentId, "secondary_fact_id" to edge.secondaryComponentId,
            "status" to "accepted", "applied_amount" to edge.appliedAmount?.let { amount(it) },
        ) }
        val tails = linkedMapOf<String, MutableList<String>>()
        val identifiers = linkedMapOf<String, MutableList<String>>()
        for (row in readAliases()) {
            val index = when (row["alias_type"]) {
                "card_tail" -> tails
                "account_identifier" -> identifiers
                else -> null
            }
            index?.getOrPut(row["alias_value"] as String) { mutableListOf() }?.add(row["account_id"] as String)
        }
        val proposals = plan(facts, seeds.sortedWith(Comparator(::pythonStringOrder)), accepted, tails, identifiers, workspace)
        val unique = linkedMapOf<List<String?>, RelationProposal>()
        for (proposal in proposals) {
            val key = listOf(proposal.kind, proposal.primaryFactId, proposal.secondaryFactId)
            if (key !in unique) unique[key] = proposal
        }
        return unique.values.toList()
    }
}
