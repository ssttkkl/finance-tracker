package com.finance.tracker.business.domain

/** Mutable preview state; readers prepare its facts, while build only derives projections. */
data class CashImportPreviewSnapshot<I : Comparable<I>, T : Comparable<T>>(
    val context: CashRelationReadContext<I, T>,
    val values: MutableMap<String, MutableMap<String, Any?>>,
    val facts: MutableMap<String, CashProjectionFact<I, T>>,
    val components: MutableMap<String, CashRelationEndpoint>,
    val edges: MutableMap<I, CashRelationGraphEdge>,
    val funding: MutableMap<Long, CashRelationFundingVersion>,
    val parentOps: MutableMap<String, MutableMap<String, Any?>>,
    val entryPublic: MutableMap<String, Map<String, Any?>>,
    val removedRelations: MutableSet<I>,
    val removedFunding: MutableSet<Long>,
    val operations: MutableList<MutableMap<String, Any?>>,
    val rows: MutableList<MutableMap<String, Any?>>,
    private val projectionValues: CashProjectionValues<I, T>,
    private val integer: (Any?) -> I?,
    val graphBuilder: (Any?, Any?, Any?, Any?) -> CashProjectionBuild<I, T>,
    private val projectionBuilder: (List<CashProjectionFact<I, T>>, List<CashProjectionRelation<I>>) -> CashProjectionBuild<I, T>,
) {
    private fun withFunding(fact: CashProjectionFact<I, T>, fundingId: Long?): CashProjectionFact<I, T> = CashProjectionFact.create(
        projectionValues,
        fact.id, fact.accountId, fact.occurredAt, fact.amount?.toString(), fact.currency, fact.counterparty,
        fact.categoryId, fact.note, fact.sourceType, fact.recordId, fundingId, fact.cashGranularity,
        fact.state, fact.createdAt, fact.needsAttention, fact.recordType, fact.recordSubtype,
        fact.stableSourceKey, fact.recordOrigin,
    )

    fun build(parentIds: Set<String>? = null, additions: List<CashRelationGraphEdge> = emptyList()): CashProjectionBuild<I, T> {
        val parents = parentIds ?: facts.keys
        val selectedEdges = (if (parentIds == null) edges.values.toList() else edges.values.filter {
            it.primaryTransactionId in parents || it.secondaryTransactionId in parents
        }).associateByTo(linkedMapOf()) { integer(it.id)!! }.apply {
            additions.forEach { put(integer(it.id)!!, it) }
        }.values.toList()
        val pending = selectedEdges.filter { it.active == true && it.state == "draft" }
            .flatMap { listOfNotNull(it.primaryTransactionId?.toString(), it.secondaryTransactionId?.toString()) }.toSet()
        val fundingRows = funding.values.filter { row ->
            val part = components[row.componentId.toString()]
            part?.transactionId in parents
        }
        val fundingByParent = fundingRows.mapNotNull { row ->
            components[row.componentId.toString()]?.transactionId?.toString()?.let { it to row.id }
        }.toMap()
        val formal = parents.mapNotNull { id -> facts[id]?.takeIf { it.state == "posted" }?.let { fact ->
            withFunding(fact.withAttention(fact.needsAttention || id in pending), fundingByParent[id])
        } }
        val parts = parents.flatMap { id ->
            @Suppress("UNCHECKED_CAST") val listed = values[id]?.get("components") as? List<Map<String, Any?>>
            listed.orEmpty().mapNotNull { components[it["component_id"].toString()] }
        }
        val componentIds = parts.map { it.componentId }.toSet()
        val complete = graphBuilder(formal, parts, selectedEdges,
            fundingRows.map { it.componentId.toString() }.filter { it in componentIds })
        val drafts = projectionBuilder(parents.mapNotNull { facts[it]?.takeIf { fact -> fact.state == "draft" } }, emptyList())
        return CashProjectionBuild<I, T>(complete.projections + drafts.projections)
    }
}

