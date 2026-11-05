package com.finance.tracker.business.domain

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

data class CashEntrySource(val values: JsonObject, val manual: Set<String>)

data class CashEntryReconciliation<I>(
    val entriesByProjection: Map<String, CashEntry<I>>,
    val newEntries: List<CashEntry<I>>,
    val newMembers: List<CashEntryMember<I>>,
)

/** Reconciles the selected members in place; persistence and transaction rollback belong to the caller. */
class CashEntryReconciliationRules<I : Comparable<I>, K : Comparable<K>>(
    private val storedId: (I) -> K,
    private val exactId: (I) -> K,
    private val nextEntryId: () -> String,
    private val onNewMember: (CashEntryMember<K>) -> Unit,
) {
    private fun sorted(values: Collection<String>) = values.distinct().sortedWith(::pythonStringOrder)
    private fun <T : Comparable<T>> anchor(projection: CashProjection<I, T>): K =
        if (projection.memberIds.size == 1) storedId(projection.memberIds.single()) else projection.memberIds.minOf(storedId)

    fun <T : Comparable<T>> reconcile(
        projections: List<CashProjection<I, T>>,
        members: Map<K, CashEntryMember<K>>,
        entries: Map<String, CashEntry<K>>,
        sources: Map<K, CashEntrySource>,
    ): CashEntryReconciliation<K> {
        val ids = projections.flatMap { it.memberIds.map(storedId) }.toSet()
        val oldMembers = members.values.groupBy { it.entryId }.mapValues { (_, values) -> values.map { it.transactionId }.toSet() }
        val anchors = entries.values.map { it.identityTransactionId }.toSet()
        val used = mutableSetOf<String>()
        val result = linkedMapOf<String, CashEntry<K>>()
        val ordered = projections.sortedWith(compareBy<CashProjection<I, T>> {
            if (it.memberIds.any { id -> exactId(id) in anchors }) 0 else 1
        }.thenComparator { a, b -> pythonStringOrder(a.projectionId, b.projectionId) })
        val fresh = mutableListOf<CashEntry<K>>()
        val freshMembers = mutableListOf<CashEntryMember<K>>()
        for (projection in ordered) {
            val group = projection.memberIds.map(storedId).toSet()
            val root = storedId(projection.primaryRecord.id)
            val oldIds = group.mapNotNull { members[it]?.entryId }.toSet()
            val economicRoots = projection.economicRootIds.map(exactId).toSet()
            var candidate = oldIds.mapNotNull { entries[it] }.filter {
                it.id !in used && (it.identityTransactionId in group || it.identityTransactionId !in ids)
            }.sortedWith(compareBy<CashEntry<K>> { if (it.identityTransactionId in economicRoots) 0 else 1 }.thenBy { it.id }).firstOrNull()
            val human = linkedMapOf<String, JsonElement>()
            for (id in oldIds) {
                val old = entries.getValue(id)
                val split = oldMembers.getValue(id).any { it !in group }
                if (split && old.splitInherit == false) {
                    for (memberId in group intersect oldMembers.getValue(id)) if (memberId == root) {
                        val member = members.getValue(memberId)
                        member.standaloneManualFields.forEach { human[it] = member.standaloneValues.getValue(it) }
                    }
                    continue
                }
                for (field in old.manualFields) {
                    val value = old.values.getValue(field)
                    if (field in human && human[field] != value) throw CashPolicyError("common_field_conflict", listOf(field))
                    human[field] = value
                }
            }
            if (candidate == null) {
                candidate = CashEntry(nextEntryId(), root, anchor(projection), 1, sources.getValue(root).values, emptyList(), null)
                fresh += candidate
            } else {
                if (candidate.rootTransactionId != root || oldMembers[candidate.id] != group) candidate.revision = incrementCashEntryRevision(candidate.revision)
                if (candidate.rootTransactionId != root) candidate.rootTransactionId = root
                if (candidate.identityTransactionId !in group) candidate.identityTransactionId = anchor(projection)
            }
            for (memberId in group) {
                val source = sources.getValue(memberId)
                var member = members[memberId]
                if (member == null) {
                    member = CashEntryMember(memberId, candidate.id, source.values, sorted(source.manual))
                    onNewMember(member)
                    freshMembers += member
                    if (memberId == root) member.standaloneManualFields.filter { it !in human }.forEach { human[it] = member.standaloneValues.getValue(it) }
                } else {
                    member.standaloneValues = JsonObject(member.standaloneValues + source.values.filterKeys { it !in member.standaloneManualFields })
                    member.entryId = candidate.id
                }
                if (oldIds.any { entries.getValue(it).splitInherit == true && oldMembers.getValue(it).any { id -> id !in group } }) {
                    member.standaloneValues = JsonObject(member.standaloneValues + human)
                    member.standaloneManualFields = sorted(member.standaloneManualFields + human.keys)
                }
            }
            val values = JsonObject(sources.getValue(root).values + human)
            if (candidate.id in entries && candidate.values != values) candidate.revision = incrementCashEntryRevision(candidate.revision)
            candidate.values = values
            candidate.manualFields = sorted(human.keys)
            used += candidate.id
            result[projection.projectionId] = candidate
        }
        return CashEntryReconciliation(result, fresh, freshMembers)
    }
}
