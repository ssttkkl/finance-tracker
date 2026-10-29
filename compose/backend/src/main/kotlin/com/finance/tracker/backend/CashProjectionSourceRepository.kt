package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*

internal data class CashProjectionSources(val facts:List<CashProjectionFact>,val relations:List<CashProjectionRelation>)
internal class CashValidatedProjectionSources(private val owner:CashProjectionSourceRepository,private val transaction:CashSqlTransactionStamp,val digest:String,sources:CashProjectionSources) {
    val facts:List<CashProjectionFact> = java.util.Collections.unmodifiableList(sources.facts.toList())
    val relations:List<CashProjectionRelation> = java.util.Collections.unmodifiableList(sources.relations.toList())
    fun requireFor(repository:CashProjectionSourceRepository,expectedDigest:String) {
        if(owner!==repository || !transaction.isCurrent() || digest!=expectedDigest) throw CashProjectionFailure("projection.incomplete")
    }
}

internal class CashProjectionSourceRepository(private val context:CashSqlContext) {
    private val sql get()=context.sql
    private val workspace get()=context.workspace
    private val relationReads=CashRelationReadRepository(context)
    fun readValidatedSources():CashValidatedProjectionSources {
        val transaction=context.requireTransactionStamp();val digest=sourceDigest();val sources=readSources()
        return CashValidatedProjectionSources(this,transaction,digest,sources)
    }
    fun parentIdsForComponents(ids:Set<Long>):Set<Long> {
        val result=sortedSetOf<Long>()
        val part = CASH_TRANSACTION_COMPONENTS
        for (batch in ids.sorted().chunked(500)) {
            sql.select(part.CASH_TRANSACTION_ID).from(part).where(part.WORKSPACE_ID.eq(workspace)).and(part.ID.`in`(batch))
                .fetch().forEach { result += it[part.CASH_TRANSACTION_ID]!! }
        };return result
    }
    fun confirmedTransactionClosure(ids:Set<Long>,inputIsComponents:Boolean=false):Set<Long> = relationReads.confirmedClosure(if(inputIsComponents) parentIdsForComponents(ids) else ids)
    fun affectedTransactionClosure(ids:Set<Long>,dataset:String):Set<Long> {
        var frontier=ids;val members=mutableSetOf<Long>();val visited=mutableSetOf<Long>()
        while(frontier.isNotEmpty()) {
            val confirmed=confirmedTransactionClosure(frontier);members+=confirmed;val groups=mutableSetOf<Long>()
            val member = CASH_PROJECTION_MEMBERS
            for (batch in confirmed.sorted().chunked(500)) {
                sql.select(member.PROJECTION_ROW_ID).from(member).where(member.WORKSPACE_ID.eq(workspace)).and(member.DATASET_ID.eq(dataset))
                    .and(member.CASH_TRANSACTION_ID.`in`(batch)).fetch().forEach { groups += it[member.PROJECTION_ROW_ID]!! }
            }
            val next = mutableSetOf<Long>()
            for (batch in (groups - visited).sorted().chunked(500)) {
                sql.select(member.CASH_TRANSACTION_ID).from(member).where(member.WORKSPACE_ID.eq(workspace)).and(member.DATASET_ID.eq(dataset))
                    .and(member.PROJECTION_ROW_ID.`in`(batch)).fetch().forEach { next += it[member.CASH_TRANSACTION_ID]!! }
            }
            visited+=groups;frontier=next-members
        };return members
    }
    fun relationContext(ids:Set<Long>,decisionGroups:List<String> = emptyList())=relationReads.readContext(ids,decisionGroups)
    fun readSources():CashProjectionSources=readSourcesForFacts(null)
    fun readSourcesForFacts(ids:Set<Long>?,inputIsComponents:Boolean=false):CashProjectionSources {
        context.requireActive();if(ids!=null && ids.isEmpty()) return CashProjectionSources(emptyList(),emptyList())
        context.entries.flush()
        val requested=if(ids!=null && inputIsComponents) parentIdsForComponents(ids) else ids
        val rows=readCashFactRows(context,requested,includeFunding=true);val identifiers=rows.map {it.id}.toSet()
        val graphRows=relationReads.graphRowsForTransactions(identifiers);val attention=relationReads.attention(identifiers,graphRows)
        val facts=rows.map {it.projection(it.id in attention)}
        val related=graphRows.filter {it.active && it.state=="confirmed"}.flatMap {listOfNotNull(it.primary,it.secondary)}.toSet()
        val relations=if(related.isEmpty()) emptyList() else {
            val graphContext=relationContext(related)
            if(!identifiers.containsAll(graphContext.transactionIds)) throw CashProjectionDomainFailure("projection.incomplete")
            try {buildCashRelationGraph(graphContext.facts,graphContext.components,graphContext.relations,graphContext.fundingComponentIds).projections.flatMap {it.relations}.sortedBy {it.id}}
            catch(_:CashRelationFailure) {throw CashProjectionDomainFailure("projection.invalid_relation")}
        }
        return CashProjectionSources(facts,relations)
    }
    fun sourceDigest():String {
        context.requireActive();context.entries.flush()
        val facts=readCashFactRows(context,includePayload=true)
        val parts=linkedMapOf<Long,MutableList<Map<String,Any?>>>()
        val part = CASH_TRANSACTION_COMPONENTS
        sql.select(part.ID, part.CASH_TRANSACTION_ID, part.ACCOUNT_ID, part.AMOUNT, part.CURRENCY, part.ORDINAL, part.LABEL, part.SOURCE_KEY)
            .from(part).where(part.WORKSPACE_ID.eq(workspace)).orderBy(part.CASH_TRANSACTION_ID, part.ORDINAL, part.ID).fetch().forEach { row ->
                parts.getOrPut(row[part.CASH_TRANSACTION_ID]!!) { mutableListOf() }.add(linkedMapOf(
                    "id" to row[part.ID]!!, "account_id" to row[part.ACCOUNT_ID], "amount" to row[part.AMOUNT], "currency" to row[part.CURRENCY],
                    "ordinal" to row[part.ORDINAL]!!, "label" to row[part.LABEL]!!, "source_key" to row[part.SOURCE_KEY]!!))
            }
        val relation = TRANSACTION_RELATIONS
        val relations = sql.select(relationReads.graphFields + relation.RULE_ID).from(relation).where(relation.WORKSPACE_ID.eq(workspace))
            .and(relation.STATE.`in`("confirmed", "draft")).and(relation.ACTIVE.isTrue).orderBy(relation.ID).fetch { record ->
                val row = relationReads.row(record, rule = true)
                linkedMapOf<String, Any?>("id" to row.id, "kind" to row.kind, "primary_fact_id" to row.primaryComponent, "secondary_fact_id" to row.secondaryComponent,
                    "primary_fact_type" to "cash", "secondary_fact_type" to "cash", "state" to row.state, "row_revision" to row.revision,
                    "primary_transaction_id" to row.primary, "secondary_transaction_id" to row.secondary, "primary_role" to row.primaryRole,
                    "secondary_role" to row.secondaryRole, "decision_group_id" to row.decisionGroup, "subtype" to row.subtype,
                    "rule_id" to row.ruleId, "applied_amount" to row.applied)
            }
        val issue = CASH_TRANSACTION_ISSUES
        val issues = sql.select(issue.ID, issue.TRANSACTION_ID, issue.REVISION).from(issue).where(issue.WORKSPACE_ID.eq(workspace)).orderBy(issue.ID)
            .fetch { listOf(it[issue.ID]!!, it[issue.TRANSACTION_ID]!!, it[issue.REVISION]!!) }
        val fund = CASH_INVESTMENT_FUNDING_RELATIONS
        val funding = sql.select(fund.ID, fund.CASH_TRANSACTION_COMPONENT_ID, fund.INVESTMENT_EVENT_ID, fund.DIRECTION, fund.STATUS, fund.RULE_ID, fund.EVIDENCE, fund.ACTIVE_SLOT)
            .from(fund).where(fund.WORKSPACE_ID.eq(workspace)).and(fund.STATUS.eq("accepted")).and(fund.ACTIVE_SLOT.eq("active")).orderBy(fund.ID).fetch { row ->
                linkedMapOf<String, Any?>("id" to row[fund.ID]!!, "cash_transaction_component_id" to row[fund.CASH_TRANSACTION_COMPONENT_ID]!!,
                    "investment_event_id" to row[fund.INVESTMENT_EVENT_ID]!!, "direction" to row[fund.DIRECTION]!!, "status" to row[fund.STATUS]!!,
                    "rule_id" to row[fund.RULE_ID]!!, "evidence" to row[fund.EVIDENCE], "active_slot" to row[fund.ACTIVE_SLOT])
            }
        val payload=linkedMapOf<String,Any?>("facts" to facts.map {row ->linkedMapOf<String,Any?>("id" to row.id,"state" to row.state,"row_revision" to row.rowRevision,"created_at" to row.createdAt,
            "record_type" to row.recordType,"record_subtype" to row.recordSubtype,"account_id" to row.accountId,"source_type" to row.sourceType,"record_id" to row.recordId,"source_payload" to row.payload,
            "occurred_at" to row.occurredAt,"amount" to row.amount,"currency" to row.currency,"counterparty" to row.counterparty,"note" to row.note,"category_id" to row.categoryId,"cash_granularity" to row.cashGranularity,
            "components" to parts[row.id].orEmpty())},"relations" to relations,"issues" to issues,"funding_relations" to funding)
        return tokenDigest(canonicalCashJson(payload))
    }
}
