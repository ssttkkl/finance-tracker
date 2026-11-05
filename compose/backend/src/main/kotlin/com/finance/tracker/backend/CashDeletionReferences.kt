package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import org.jooq.Field
import org.jooq.Table
import org.jooq.Record

internal class CashDeletionReferences(private val context: CashSqlContext, val targetIds: Set<Long>) {
    private val sql get() = context.sql
    private val workspace get() = context.workspace
    private val relation = TRANSACTION_RELATIONS
    internal data class Edge(val id: Long, val primary: Long, val secondary: Long?, val anchor: Long, val group: String?, val state: String, val active: Boolean) {
        val parents get() = setOfNotNull(primary, secondary, anchor)
    }
    private fun edge(row: Record) = Edge(row[relation.ID]!!, row[relation.PRIMARY_TRANSACTION_ID]!!, row[relation.SECONDARY_TRANSACTION_ID],
        row[relation.ANCHOR_TRANSACTION_ID]!!, row[relation.DECISION_GROUP_ID], row[relation.STATE]!!, row[relation.ACTIVE]!!)
    val relations: List<Edge>
    private val candidateRelations: Set<Long>
    private val candidateIssues: Set<Long>
    val affectedIds: Set<Long>
    init {
        context.requireActive()
        val all = sortedMapOf<Long, Edge>()
        val candidates = CASH_RELATION_CANDIDATES
        for (batch in targetIds.sorted().chunked(500)) {
            val branches = listOf(relation.PRIMARY_TRANSACTION_ID, relation.SECONDARY_TRANSACTION_ID, relation.ANCHOR_TRANSACTION_ID)
                .map { sql.select(relation.ID).from(relation).where(relation.WORKSPACE_ID.eq(workspace)).and(it.`in`(batch)) }
            val ids = branches[0].union(branches[1]).union(branches[2]).union(sql.select(candidates.RELATION_ID).from(candidates)
                .where(candidates.WORKSPACE_ID.eq(workspace)).and(candidates.CANDIDATE_TRANSACTION_ID.`in`(batch)))
            sql.selectFrom(relation).where(relation.WORKSPACE_ID.eq(workspace)).and(relation.ID.`in`(ids)).fetch()
                .forEach { all[it[relation.ID]!!] = edge(it) }
        }
        val direct = all.filterValues { it.parents.any { id -> id in targetIds } }.toMutableMap()
        for (batch in direct.values.mapNotNull { it.group }.distinct().sorted().chunked(500)) {
            sql.selectFrom(relation).where(relation.WORKSPACE_ID.eq(workspace)).and(relation.DECISION_GROUP_ID.`in`(batch))
                .and(relation.STATE.ne("confirmed")).fetch().forEach { direct[it[relation.ID]!!] = edge(it) }
        }
        relations = direct.values.sortedBy { it.id }
        val retained = all.filterKeys { it !in direct }.values
        candidateRelations = retained.map { it.id }.toSet()
        val issue = CASH_TRANSACTION_ISSUES; val candidate = CASH_ISSUE_CANDIDATES
        val issues = sortedMapOf<Long, Long>()
        for (batch in targetIds.sorted().chunked(500)) {
            sql.select(issue.ID, issue.TRANSACTION_ID).from(issue).join(candidate).on(candidate.WORKSPACE_ID.eq(issue.WORKSPACE_ID))
                .and(candidate.ISSUE_ID.eq(issue.ID)).where(issue.WORKSPACE_ID.eq(workspace))
                .and(candidate.CANDIDATE_TRANSACTION_ID.`in`(batch)).fetch().forEach { row ->
                    if (row[issue.TRANSACTION_ID]!! !in targetIds) issues[row[issue.ID]!!] = row[issue.TRANSACTION_ID]!!
                }
        }
        candidateIssues = issues.keys.toSet()
        affectedIds = CashRelationReadRepository(context).confirmedClosure(targetIds + relations.flatMap { it.parents } + retained.flatMap { it.parents } + issues.values)
    }
    private fun delete(table: Table<*>, workspaceField: Field<String?>, field: Field<Long?>, ids: Set<Long>) {
        for (batch in ids.sorted().chunked(500)) sql.deleteFrom(table).where(workspaceField.eq(workspace)).and(field.`in`(batch)).execute()
    }
    fun clear() {
        context.requireActive()
        val ids = relations.map { it.id }.toSet()
        delete(CASH_PROJECTION_RELATIONS, CASH_PROJECTION_RELATIONS.WORKSPACE_ID, CASH_PROJECTION_RELATIONS.TRANSACTION_RELATION_ID, ids)
        delete(relation, relation.WORKSPACE_ID, relation.ID, ids)
        delete(CASH_RELATION_CANDIDATES, CASH_RELATION_CANDIDATES.WORKSPACE_ID, CASH_RELATION_CANDIDATES.CANDIDATE_TRANSACTION_ID, targetIds)
        for (batch in candidateRelations.sorted().chunked(500)) sql.update(relation).set(relation.ROW_REVISION, relation.ROW_REVISION.plus(1L))
            .where(relation.WORKSPACE_ID.eq(workspace)).and(relation.ID.`in`(batch)).execute()
        delete(CASH_ISSUE_CANDIDATES, CASH_ISSUE_CANDIDATES.WORKSPACE_ID, CASH_ISSUE_CANDIDATES.CANDIDATE_TRANSACTION_ID, targetIds)
        val issue = CASH_TRANSACTION_ISSUES
        for (batch in candidateIssues.sorted().chunked(500)) sql.update(issue).set(issue.REVISION, issue.REVISION.plus(1L))
            .where(issue.WORKSPACE_ID.eq(workspace)).and(issue.ID.`in`(batch)).execute()
        delete(issue, issue.WORKSPACE_ID, issue.TRANSACTION_ID, targetIds)
        delete(CASH_SOURCE_BINDINGS, CASH_SOURCE_BINDINGS.WORKSPACE_ID, CASH_SOURCE_BINDINGS.TRANSACTION_ID, targetIds)
        val funding = CASH_INVESTMENT_FUNDING_RELATIONS; val part = CASH_TRANSACTION_COMPONENTS
        for (batch in targetIds.sorted().chunked(500)) sql.deleteFrom(funding).where(funding.WORKSPACE_ID.eq(workspace))
            .and(funding.CASH_TRANSACTION_COMPONENT_ID.`in`(sql.select(part.ID).from(part).where(part.WORKSPACE_ID.eq(workspace))
                .and(part.CASH_TRANSACTION_ID.`in`(batch)))).execute()
    }
}
