package com.finance.tracker.backend

internal data class CashProjectionSources(val facts:List<CashProjectionFact>,val relations:List<CashProjectionRelation>)
internal class CashValidatedProjectionSources(private val owner:CashProjectionSourceRepository,private val transaction:CashSqlTransactionStamp,val digest:String,sources:CashProjectionSources) {
    val facts:List<CashProjectionFact> = java.util.Collections.unmodifiableList(sources.facts.toList())
    val relations:List<CashProjectionRelation> = java.util.Collections.unmodifiableList(sources.relations.toList())
    fun requireFor(repository:CashProjectionSourceRepository,expectedDigest:String) {
        if(owner!==repository || !transaction.isCurrent() || digest!=expectedDigest) throw CashProjectionFailure("projection.incomplete")
    }
}

internal class CashProjectionSourceRepository(private val context:CashSqlContext) {
    private val c get()=context.connection
    private val workspace get()=context.workspace
    private val relationReads=CashRelationReadRepository(context)
    fun readValidatedSources():CashValidatedProjectionSources {
        val transaction=context.requireTransactionStamp();val digest=sourceDigest();val sources=readSources()
        return CashValidatedProjectionSources(this,transaction,digest,sources)
    }
    fun parentIdsForComponents(ids:Set<Long>):Set<Long> {
        val result=sortedSetOf<Long>()
        for(batch in ids.sorted().chunked(500)) c.prepareStatement("SELECT cash_transaction_id FROM cash_transaction_components WHERE workspace_id=? AND id IN (${batch.joinToString(",") {"?"}})").use {
            it.setString(1,workspace);batch.forEachIndexed {index,id ->it.setLong(index+2,id)};it.executeQuery().use {r ->while(r.next()) result+=r.getLong(1)}
        };return result
    }
    fun confirmedTransactionClosure(ids:Set<Long>,inputIsComponents:Boolean=false):Set<Long> = relationReads.confirmedClosure(if(inputIsComponents) parentIdsForComponents(ids) else ids)
    fun affectedTransactionClosure(ids:Set<Long>,dataset:String):Set<Long> {
        var frontier=ids;val members=mutableSetOf<Long>();val visited=mutableSetOf<Long>()
        while(frontier.isNotEmpty()) {
            val confirmed=confirmedTransactionClosure(frontier);members+=confirmed;val groups=mutableSetOf<Long>()
            for(batch in confirmed.sorted().chunked(500)) c.prepareStatement("SELECT projection_row_id FROM cash_projection_members WHERE workspace_id=? AND dataset_id=? AND cash_transaction_id IN (${batch.joinToString(",") {"?"}})").use {
                it.setString(1,workspace);it.setString(2,dataset);batch.forEachIndexed {index,id ->it.setLong(index+3,id)};it.executeQuery().use {r ->while(r.next()) groups+=r.getLong(1)}
            }
            val next=mutableSetOf<Long>()
            for(batch in (groups-visited).sorted().chunked(500)) c.prepareStatement("SELECT cash_transaction_id FROM cash_projection_members WHERE workspace_id=? AND dataset_id=? AND projection_row_id IN (${batch.joinToString(",") {"?"}})").use {
                it.setString(1,workspace);it.setString(2,dataset);batch.forEachIndexed {index,id ->it.setLong(index+3,id)};it.executeQuery().use {r ->while(r.next()) next+=r.getLong(1)}
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
        c.prepareStatement("SELECT id,cash_transaction_id,account_id,amount,currency,ordinal,label,source_key FROM cash_transaction_components WHERE workspace_id=? ORDER BY cash_transaction_id,ordinal,id").use {
            it.setString(1,workspace);it.executeQuery().use {r ->while(r.next()) parts.getOrPut(r.getLong("cash_transaction_id")) {mutableListOf()}.add(linkedMapOf(
                "id" to r.getLong("id"),"account_id" to r.nullableLong("account_id"),"amount" to r.cashDecimal("amount",context.sqlite),"currency" to r.getString("currency"),
                "ordinal" to r.getLong("ordinal"),"label" to r.getString("label"),"source_key" to r.getString("source_key")))}
        }
        val relations=c.prepareStatement("SELECT ${relationReads.graphColumns},rule_id FROM transaction_relations WHERE workspace_id=? AND state IN ('confirmed','draft') AND active=true ORDER BY id").use {
            it.setString(1,workspace);it.executeQuery().use {r ->buildList {while(r.next()) {val row=relationReads.row(r,rule=true);add(linkedMapOf<String,Any?>(
                "id" to row.id,"kind" to row.kind,"primary_fact_id" to row.primaryComponent,"secondary_fact_id" to row.secondaryComponent,"primary_fact_type" to "cash","secondary_fact_type" to "cash",
                "state" to row.state,"row_revision" to row.revision,"primary_transaction_id" to row.primary,"secondary_transaction_id" to row.secondary,"primary_role" to row.primaryRole,
                "secondary_role" to row.secondaryRole,"decision_group_id" to row.decisionGroup,"subtype" to row.subtype,"rule_id" to row.ruleId,"applied_amount" to row.applied))}}}
        }
        val issues=c.prepareStatement("SELECT id,transaction_id,revision FROM cash_transaction_issues WHERE workspace_id=? ORDER BY id").use {
            it.setString(1,workspace);it.executeQuery().use {r ->buildList {while(r.next()) add(listOf(r.getLong(1),r.getLong(2),r.getLong(3)))}}
        }
        val funding=c.prepareStatement("SELECT id,cash_transaction_component_id,investment_event_id,direction,status,rule_id,evidence,active_slot FROM cash_investment_funding_relations WHERE workspace_id=? AND status='accepted' AND active_slot='active' ORDER BY id").use {
            it.setString(1,workspace);it.executeQuery().use {r ->buildList {while(r.next()) add(linkedMapOf<String,Any?>("id" to r.getLong("id"),"cash_transaction_component_id" to r.getLong("cash_transaction_component_id"),
                "investment_event_id" to r.getLong("investment_event_id"),"direction" to r.getString("direction"),"status" to r.getString("status"),"rule_id" to r.getString("rule_id"),"evidence" to r.cashJson("evidence"),"active_slot" to r.getString("active_slot")))}}
        }
        val payload=linkedMapOf<String,Any?>("facts" to facts.map {row ->linkedMapOf<String,Any?>("id" to row.id,"state" to row.state,"row_revision" to row.rowRevision,"created_at" to row.createdAt,
            "record_type" to row.recordType,"record_subtype" to row.recordSubtype,"account_id" to row.accountId,"source_type" to row.sourceType,"record_id" to row.recordId,"source_payload" to row.payload,
            "occurred_at" to row.occurredAt,"amount" to row.amount,"currency" to row.currency,"counterparty" to row.counterparty,"note" to row.note,"category_id" to row.categoryId,"cash_granularity" to row.cashGranularity,
            "components" to parts[row.id].orEmpty())},"relations" to relations,"issues" to issues,"funding_relations" to funding)
        return tokenDigest(canonicalCashJson(payload))
    }
}
