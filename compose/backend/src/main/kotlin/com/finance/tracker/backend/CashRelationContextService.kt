package com.finance.tracker.backend

import java.math.BigInteger
import kotlinx.serialization.json.*

internal fun CashRelationSqlContext.token():String {
    val payload=listOf(transactionRevisions.map {listOf(it.first,it.second)},
        componentVersions.map {listOf(it.id,it.transactionId,it.accountId,it.amount,it.currency,it.ordinal)},
        relationRevisions.map {listOf(it.first,it.second)},relationGroups.map {listOf(it.first,it.second)},
        fundingVersions.map {listOf(it.id,it.componentId,it.investmentEventId,it.direction)},
        issueVersions.map {listOf(it.id,it.transactionId,it.revision)},entryVersions.map {listOf(it.transactionId,it.entryId,it.revision,it.digest)},
        jointMembers.map {listOf(it.id,it.decisionGroupId,it.rowRevision,it.state,it.active)})
    fun encode(value:Any?):String=when(value) {
        null ->"null"
        is String ->receiptAsciiJson(JsonPrimitive(value))
        is Long,is Int,is Boolean ->value.toString()
        is List<*> ->value.joinToString(",","[","]") {encode(it)}
        else ->error("Unsupported relation context token value")
    }
    return tokenDigest(encode(payload))
}
internal data class PreparedCashRelationGraph(val affectedTransactionIds:Set<Long>,val relations:List<CashRelationGraphEdge>,val build:CashProjectionBuild)
internal class CashRelationContextService(private val context:CashSqlContext) {
    private val repository=CashRelationReadRepository(context)
    fun load(ids:Set<Long>,decisionGroups:List<String> = emptyList())=repository.readContext(ids,decisionGroups)
    fun validate(expected:Any?,updates:Any?=emptyList<Any>(),additions:Any?=emptyList<Any>(),evidenceByRelation:Any?=null,cleanupGroupIds:Any?=emptySet<String>()):PreparedCashRelationGraph {
        val before=expected as? CashRelationSqlContext ?: relationFailure("relation_context_changed")
        val current=load(before.seedIds,before.decisionGroupIds)
        if(current.token()!=before.token()) relationFailure("relation_context_changed")
        return validateCurrent(current,updates,additions,evidenceByRelation,cleanupGroupIds,before.jointMembers)
    }
    private fun validateCurrent(current:CashRelationSqlContext,rawUpdates:Any?,rawAdditions:Any?,rawEvidence:Any?,rawCleanup:Any?,expectedMembers:List<CashJointRelationMember>):PreparedCashRelationGraph {
        fun iterable(value:Any?):List<*> = when(value) {
            is Map<*,*> ->value.keys.toList()
            is String ->value.codePoints().toArray().map {String(Character.toChars(it))}
            is Iterable<*> ->value.toList()
            is Sequence<*> ->value.toList()
            is Iterator<*> ->value.asSequence().toList()
            else ->relationFailure("relation_data_invalid")
        }
        fun id(edge:CashRelationGraphEdge):BigInteger=requireNotNull(strictCashInteger(edge.id))
        val collectedUpdates=iterable(rawUpdates);val collectedAdditions=iterable(rawAdditions)
        (collectedUpdates+collectedAdditions).forEach(::validateCashRelationGraphEdgeInput)
        val updates=collectedUpdates.map {it as CashRelationGraphEdge};val additions=collectedAdditions.map {it as CashRelationGraphEdge};val changes=updates+additions
        val previous=current.relations.associateBy(::id)
        if(changes.map(::id).toSet().size!=changes.size || updates.any {id(it) !in previous} || additions.any {id(it) in previous}) relationFailure("relation_context_changed")
        val grouped=current.relationGroups.associate {it.first.toBigInteger() to it.second}
        val cleanup=iterable(rawCleanup).toSet()
        if(cleanup.any {it !is String || it.codePointCount(0,it.length) !in 1..128 || it !in current.decisionGroupIds}) relationFailure("relation_group_incomplete")
        val targets=updates.associateBy(::id)
        fun sameEdge(a:CashRelationGraphEdge,b:CashRelationGraphEdge):Boolean {
            val left=cashRelationDecimal(a.appliedAmount);val right=cashRelationDecimal(b.appliedAmount)
            val sameAmount=if(left==null || right==null) a.appliedAmount==b.appliedAmount else left.arithmeticValue().compareTo(right.arithmeticValue())==0
            return id(a)==id(b) && a.copy(id=1L,appliedAmount=null)==b.copy(id=1L,appliedAmount=null) && sameAmount
        }
        for(group in cleanup) {
            val ids=current.jointMembers.filter {it.decisionGroupId==group}.map {BigInteger(it.id as String)}.toSet()
            if(ids.isEmpty() || !targets.keys.containsAll(ids)) relationFailure("relation_group_incomplete")
            val active=mutableListOf<CashRelationGraphEdge>()
            for(identifier in ids) {
                val old=previous.getValue(identifier);val target=targets.getValue(identifier)
                if(old.active==false) {if(!sameEdge(target,old)) relationFailure("relation_group_changed")} else active+=target
            }
            if(active.any {it.active==true && it.state=="confirmed"} || !active.all {it.active==false} && active.map {it.state to it.active}.toSet().size!=1) relationFailure("relation_group_incomplete")
        }
        val decided=updates.filter {previous.getValue(id(it)).state=="draft" && id(it) in grouped && grouped[id(it)] !in cleanup}.map {id(it).toString()}.toSet()
        if(decided.any {grouped[BigInteger(it)] !in current.decisionGroupIds}) relationFailure("relation_group_incomplete")
        validateCashJointContext(expectedMembers,current.jointMembers,decided)
        for(group in decided.map {grouped.getValue(BigInteger(it))}.toSet()) {
            val members=current.jointMembers.filter {it.decisionGroupId==group}.map {targets.getValue(BigInteger(it.id as String))}
            if(members.map {it.state to it.active}.toSet().size!=1) relationFailure("relation_group_incomplete")
        }
        val parents=current.transactionIds.map {it.toString()}.toSet();val activeParents=current.activeTransactionIds.map {it.toString()}.toSet()
        val owners=current.componentVersions.associate {it.id.toString() to it.transactionId.toString()}
        for(edge in changes) {
            for((parent,component) in listOf(edge.primaryTransactionId to edge.primaryComponentId,edge.secondaryTransactionId to edge.secondaryComponentId)) {
                if(parent!=null && (parent !in parents || edge.active==true && parent !in activeParents) || component!=null && owners[component]!=parent) relationFailure("relation_endpoint_incomplete")
            }
            if(edge.secondaryTransactionId==null && (edge.secondaryComponentId!=null || edge.secondaryRole!=null) || edge.primaryTransactionId==edge.secondaryTransactionId) relationFailure("relation_endpoint_incompatible")
        }
        val evidence=if(rawEvidence==null) emptyMap<Any?,Any?>() else rawEvidence as? Map<*,*> ?: relationFailure("relation_data_invalid")
        val changesIds=changes.map(::id).toSet()
        fun evidenceKey(value:Any?):BigInteger?=try {when(value) {
            is Boolean ->if(value) BigInteger.ONE else BigInteger.ZERO
            is java.math.BigDecimal ->value.toBigIntegerExact()
            is Double ->if(value.isFinite()) java.math.BigDecimal(value).toBigIntegerExact() else null
            is Float ->if(value.isFinite()) java.math.BigDecimal(value.toDouble()).toBigIntegerExact() else null
            is CashAmount ->value.arithmeticValue().toBigIntegerExact()
            is CashDecimalLiteral ->value.text.toBigDecimalOrNull()?.toBigIntegerExact()
            else ->strictCashInteger(value)
        }} catch(_:ArithmeticException) {null}
        val normalizedEvidence=linkedMapOf<BigInteger,Any?>()
        for((key,proof) in evidence) {
            val identifier=evidenceKey(key) ?: relationFailure("relation_data_invalid")
            if(identifier !in changesIds) relationFailure("relation_data_invalid")
            normalizedEvidence[identifier]=proof
        }
        val byComponent=current.components.associateBy {it.componentId}
        fun structure(edge:CashRelationGraphEdge)=listOf(edge.kind,edge.subtype,edge.primaryTransactionId,edge.secondaryTransactionId,edge.primaryComponentId,edge.secondaryComponentId,edge.primaryRole,edge.secondaryRole)
        for(edge in changes) {
            val old=previous[id(edge)]
            if(edge.active==true && edge.state=="confirmed" && (old==null || old.state!="confirmed" || old.active==false || structure(edge)!=structure(old))) {
                val primary=byComponent[edge.primaryComponentId] ?: relationFailure("relation_endpoint_incomplete")
                val secondary=byComponent[edge.secondaryComponentId] ?: relationFailure("relation_endpoint_incomplete")
                val proof=if(id(edge) in normalizedEvidence) normalizedEvidence[id(edge)] else CashRelationEvidence()
                val roles=discoverCashRelationRoles(edge.kind,edge.subtype,primary,secondary,proof)
                if(roles!=(edge.primaryRole to edge.secondaryRole)) relationFailure("relation_role_unproven")
            }
        }
        val final=previous.toMutableMap();changes.forEach {final[id(it)]=it};val edges=final.toSortedMap().values.toList()
        val pending=current.issueVersions.map {it.transactionId.toString()}.toMutableSet()
        edges.filter {it.active==true && it.state=="draft"}.forEach {pending.addAll(listOfNotNull(it.primaryTransactionId,it.secondaryTransactionId).filterIsInstance<String>())}
        val facts=current.facts.map {it.withAttention(it.id.toString() in pending)}
        val build=buildCashRelationGraph(facts,current.components,edges,current.fundingComponentIds)
        val resolved=build.projections.flatMap {it.relations}.associate {it.id to it.appliedAmount}
        return PreparedCashRelationGraph(current.transactionIds,edges.map {if(id(it) in resolved) it.copy(appliedAmount=resolved[id(it)]) else it},build)
    }
}
