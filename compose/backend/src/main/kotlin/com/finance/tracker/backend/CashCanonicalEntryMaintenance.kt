package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.CASH_TRANSACTIONS

/** Reconcile only the affected complete business groups; no materialized browsing state is involved. */
internal fun maintainCanonicalCashEntries(context: CashSqlContext, affected: Set<Long>) {
    context.requireActive()
    if (affected.isEmpty()) return
    val sources = CashProjectionSourceRepository(context)
    val members = mutableSetOf<Long>()
    var frontier = affected
    while (frontier.isNotEmpty()) {
        members += frontier
        val confirmed = sources.confirmedTransactionClosure(frontier)
        val expanded = confirmed + context.entries.completeGroupMembers(confirmed)
        frontier = expanded - members
        members += expanded
    }
    val graph = sources.readSourcesForFacts(members)
    val groups = buildCashProjections(graph.facts, graph.relations).projections
    context.entries.removeMembers(members - groups.flatMap { it.storedMemberIds() }.toSet())
    context.entries.reconcile(groups)
}

/** Count complete canonical groups with a draft or an existing attention reason. */
internal fun pendingCanonicalCashEntries(context: CashSqlContext, successful: Set<Long>): Int {
    val all = context.entries.completeGroupMembers(successful)
    if (all.isEmpty()) return 0
    val members = context.entries.forTransactions(all).first
    val selected = members.values.filter { it.transactionId in successful }.map { it.entryId }.toSet()
    val relations = CashRelationReadRepository(context)
    val pending = relations.attention(all, relations.graphRowsForTransactions(all)).toMutableSet()
    val t = CASH_TRANSACTIONS
    for (batch in all.sorted().chunked(400)) pending += context.sql.select(t.ID).from(t)
        .where(t.WORKSPACE_ID.eq(context.workspace)).and(t.ID.`in`(batch)).and(t.DELETED_AT.isNull)
        .and(t.STATE.eq("draft")).fetch { checkNotNull(it.value1()) }
    return members.values.filter { it.transactionId in pending && it.entryId in selected }.map { it.entryId }.toSet().size
}
