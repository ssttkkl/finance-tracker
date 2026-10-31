package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import kotlinx.serialization.json.JsonObject

/** 只使实际改动的端点和候选依赖失效；调用方持有工作区事务。 */
internal fun CashSqlContext.markCashRelationCandidatesStale(ids: Set<Long>): Set<Long> {
    requireActive()
    if (ids.any { it <= 0 }) relationFailure("relation_data_invalid")
    val candidate = CASH_RELATION_CANDIDATES
    val relation = TRANSACTION_RELATIONS
    val transaction = CASH_TRANSACTIONS
    if (ids.size > 500 && !sql.fetchExists(candidate, candidate.WORKSPACE_ID.eq(workspace).and(candidate.VALIDITY.ne("stale")))) return emptySet()
    val changed = linkedSetOf<Long>()
    for (batch in ids.sorted().chunked(500)) {
        val dependent = sql.select(candidate.ID, candidate.RELATION_ID).from(candidate).join(transaction)
            .on(transaction.WORKSPACE_ID.eq(candidate.WORKSPACE_ID)).and(transaction.ID.eq(candidate.CANDIDATE_TRANSACTION_ID))
            .where(candidate.WORKSPACE_ID.eq(workspace).and(candidate.CANDIDATE_TRANSACTION_ID.`in`(batch))
                .and(candidate.VALIDITY.ne("stale")).and(candidate.EXPECTED_ROW_REVISION.ne(transaction.ROW_REVISION))).fetch()
        val endpoints = sql.select(candidate.ID, candidate.RELATION_ID).from(candidate).join(relation)
            .on(relation.WORKSPACE_ID.eq(candidate.WORKSPACE_ID)).and(relation.ID.eq(candidate.RELATION_ID))
            .where(candidate.WORKSPACE_ID.eq(workspace).and(candidate.VALIDITY.ne("stale")).and(relation.ACTIVE.isTrue)
                .and(relation.PRIMARY_TRANSACTION_ID.`in`(batch).or(relation.SECONDARY_TRANSACTION_ID.`in`(batch))
                    .or(relation.ANCHOR_TRANSACTION_ID.`in`(batch)))).fetch()
        val rows = (dependent + endpoints).associate { it.value1()!! to it.value2()!! }
        for (keys in rows.keys.sorted().chunked(500)) sql.update(candidate).set(candidate.VALIDITY, "stale")
            .where(candidate.WORKSPACE_ID.eq(workspace).and(candidate.ID.`in`(keys))).execute()
        changed += rows.values
    }
    for (batch in changed.sorted().chunked(500)) sql.update(relation).set(relation.ROW_REVISION, relation.ROW_REVISION.plus(1L))
        .where(relation.WORKSPACE_ID.eq(workspace).and(relation.ID.`in`(batch))).execute()
    return changed
}

internal data class CashRelationTypeUpdateResult(val affectedTransactionIds: Set<Long>, val removedRelationIds: Set<Long>,
    val removedFundingIds: Set<Long>, val staleRelationIds: Set<Long>, val build: CashProjectionBuild, val projectionStatus: JsonObject?)

/** 类型写入后先校验完整剩余图，再解除不兼容关系；不提交外层事务。 */
internal class CashRelationTypeUpdateService(private val context: CashSqlContext) {
    fun maintain(expected: Any?, changed: Set<Long>, updated: Set<Long> = changed, publish: Boolean = true): CashRelationTypeUpdateResult {
        val before = expected as? CashRelationSqlContext ?: relationFailure("relation_context_changed")
        if ((changed + updated).any { it <= 0 }) relationFailure("relation_data_invalid")
        if (!before.seedIds.containsAll(changed) || !updated.containsAll(changed) || !before.transactionIds.containsAll(updated))
            relationFailure("relation_context_changed")
        val current = CashRelationContextService(context).load(before.seedIds, before.decisionGroupIds)
        val oldRevisions = before.transactionRevisions.toMap()
        val revisions = current.transactionRevisions.toMap()
        fun financial(facts: List<CashProjectionFact>) = facts.map { listOf(it.id, it.accountId, it.amount, it.currency, it.occurredAt, it.cashGranularity) }
        if (current.transactionIds != before.transactionIds || current.activeTransactionIds != before.activeTransactionIds ||
            financial(current.facts) != financial(before.facts) || revisions.any { (id, revision) -> id !in updated && revision != oldRevisions[id] } ||
            updated.any { (revisions[it] ?: 0L) < oldRevisions.getValue(it) } ||
            current.copy(transactionRevisions = before.transactionRevisions).token() != before.token()) relationFailure("relation_context_changed")
        val endpoints = current.components.associateBy { it.componentId }
        val removed = linkedSetOf<Long>()
        for (edge in current.relations) {
            if (edge.active != true || edge.state != "confirmed" || listOfNotNull(edge.primaryTransactionId, edge.secondaryTransactionId)
                .none { it.toString().toLong() in changed }) continue
            val primary = endpoints[edge.primaryComponentId] ?: relationFailure("relation_endpoint_incomplete")
            val secondary = endpoints[edge.secondaryComponentId] ?: relationFailure("relation_endpoint_incomplete")
            try { validateCashRelationRoles(edge.kind, edge.subtype, primary, secondary, edge.primaryRole, edge.secondaryRole) }
            catch (failure: CashRelationFailure) {
                if (failure.code != "relation_endpoint_incompatible") throw failure
                removed += strictCashInteger(edge.id)!!.longValueExact()
            }
        }
        val removedFunding = linkedSetOf<Long>()
        val fundingByParent = linkedMapOf<Long, Long>()
        for (row in current.fundingVersions) {
            val endpoint = endpoints[row.componentId.toString()] ?: relationFailure("relation_endpoint_incomplete")
            val parent = endpoint.transactionId.toString().toLong()
            if (parent in changed && !cashFundingRoleCompatible(row.direction, endpoint.amount, endpoint.recordType)) removedFunding += row.id
            else fundingByParent.putIfAbsent(parent, row.id)
        }
        val facts = current.facts.map { f -> CashProjectionFact.create(f.id, f.accountId, f.occurredAt, f.amount?.toString(), f.currency,
            f.counterparty, f.categoryId, f.note, f.sourceType, f.recordId, fundingByParent[f.id.longValueExact()], f.cashGranularity,
            f.state, f.createdAt, f.needsAttention) }
        val edges = current.relations.map { if (strictCashInteger(it.id)!!.longValueExact() in removed) it.copy(active = false) else it }
        val build = buildCashRelationGraph(facts, current.components, edges,
            current.fundingVersions.filter { it.id !in removedFunding }.map { it.componentId.toString() })
        val relation = TRANSACTION_RELATIONS
        val relationRevisions = current.relationRevisions.toMap()
        for (id in removed.sorted()) {
            val count = context.sql.update(relation).set(relation.ACTIVE, false).set(relation.DECISION_SOURCE, "system")
                .set(relation.DECIDED_BY, "system").set(relation.DECIDED_AT, context.now()).set(relation.DECISION_REASON, "record_type_changed")
                .set(relation.ROW_REVISION, relation.ROW_REVISION.plus(1L)).where(relation.WORKSPACE_ID.eq(context.workspace)
                    .and(relation.ID.eq(id)).and(relation.ROW_REVISION.eq(relationRevisions.getValue(id))).and(relation.ACTIVE.isTrue).and(relation.STATE.eq("confirmed"))).execute()
            if (count != 1) relationFailure("relation_context_changed")
        }
        val funding = CASH_INVESTMENT_FUNDING_RELATIONS
        for (row in current.fundingVersions.filter { it.id in removedFunding }) {
            val count = context.sql.update(funding).set(funding.STATUS, "rejected").set(funding.ACTIVE_SLOT, "type-change-${row.id}")
                .set(funding.DECIDED_BY, "system").set(funding.DECIDED_AT, context.now()).set(funding.DECISION_REASON, "record_type_changed")
                .where(funding.WORKSPACE_ID.eq(context.workspace).and(funding.ID.eq(row.id)).and(funding.CASH_TRANSACTION_COMPONENT_ID.eq(row.componentId))
                    .and(funding.INVESTMENT_EVENT_ID.eq(row.investmentEventId)).and(funding.DIRECTION.eq(row.direction))
                    .and(funding.STATUS.eq("accepted")).and(funding.ACTIVE_SLOT.eq("active"))).execute()
            if (count != 1) relationFailure("relation_context_changed")
        }
        val stale = context.markCashRelationCandidatesStale(updated.filter { revisions[it] != oldRevisions[it] }.toSet())
        val projections = CashProjectionRepository(context)
        val state = if (publish) projections.readyStateLockOrNone() else null
        val affected = before.transactionIds + current.transactionIds
        val status = state?.let { projections.replaceActiveComponents(build, affected, it) }
        return CashRelationTypeUpdateResult(affected, removed, removedFunding, stale, build, status)
    }
}
