package com.finance.tracker.backend

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode

internal data class CashRelationGraphEdge(val id: Any?,val kind: Any?,val primaryTransactionId: Any?,val secondaryTransactionId: Any?,
    val primaryComponentId: Any?,val secondaryComponentId: Any?,val primaryRole: Any?,val secondaryRole: Any?,val subtype: Any?="",
    val appliedAmount: Any?=null,val state: Any?="confirmed",val active: Any?=true)
internal data class CashJointRelationMember(val id: Any?,val decisionGroupId: Any?,val rowRevision: Any?,val state: Any?="draft",val active: Any?=true)
private data class CheckedCashGraphEdge(val id: BigInteger,val kind: String,val primaryTransactionId: String,val secondaryTransactionId: String?,
    val primaryComponentId: String?,val secondaryComponentId: String?,val primaryRole: String?,val secondaryRole: String?,val subtype: String,
    val appliedAmount: CashAmount?,val state: String,val active: Boolean)
private data class CheckedJointMember(val id: String,val decisionGroupId: String?,val rowRevision: BigInteger,val state: String,val active: Boolean)
internal fun strictCashInteger(value: Any?): BigInteger?=when(value) {
    is BigInteger ->value
    is Byte,is Short,is Int,is Long ->BigInteger.valueOf((value as Number).toLong())
    else ->null
}
internal fun CashAmount.arithmeticValue(): BigDecimal=if(representation.coefficient=="0" && representation.exponent>Int.MAX_VALUE) BigDecimal.ZERO else BigDecimal(toString())
private val cashGraphArithmetic=MathContext(80,RoundingMode.HALF_EVEN)
private fun rawGraphCollection(value: Any?): List<*> = when(value) {
    is Iterable<*> ->value.toList()
    is Sequence<*> ->value.toList()
    is Iterator<*> ->value.asSequence().toList()
    else ->relationFailure("relation_data_invalid")
}
private fun checkedGraphEdge(value: Any?): CheckedCashGraphEdge {
    val raw=value as? CashRelationGraphEdge ?: relationFailure("relation_data_invalid")
    fun reference(value: Any?)=value is String && value.isNotEmpty()
    val id=strictCashInteger(raw.id)?.takeIf {it.signum()>0}
    if(id==null || raw.kind !is String || raw.kind !in setOf("payment_mirror","refund_offset","transfer_pair") ||
        raw.state !is String || raw.state !in setOf("draft","confirmed","ignored") || raw.active !is Boolean || raw.subtype !is String ||
        !reference(raw.primaryTransactionId) || listOf(raw.secondaryTransactionId,raw.primaryComponentId,raw.secondaryComponentId,raw.primaryRole,raw.secondaryRole).any {it!=null && !reference(it)}) relationFailure("relation_data_invalid")
    val amount=raw.appliedAmount?.let {cashRelationDecimal(it)?.takeIf {it.arithmeticValue().signum()>=0} ?: relationFailure("relation_amount_invalid")}
    return CheckedCashGraphEdge(id,raw.kind,raw.primaryTransactionId as String,raw.secondaryTransactionId as String?,raw.primaryComponentId as String?,
        raw.secondaryComponentId as String?,raw.primaryRole as String?,raw.secondaryRole as String?,raw.subtype,amount,raw.state,raw.active)
}
internal fun validateCashRelationGraphEdgeInput(value: Any?) {checkedGraphEdge(value)}
internal fun validateCashJointContext(expectedMembers: Any?,currentMembers: Any?,decidedIds: Any?) {
    fun members(value: Any?): Map<String,CheckedJointMember> {
        val input=when(value) {
            is Iterable<*> ->value.toList()
            is Sequence<*> ->value.toList()
            is Iterator<*> ->value.asSequence().toList()
            else ->relationFailure("relation_group_changed")
        }
        val result=linkedMapOf<String,CheckedJointMember>()
        for(raw in input) {
            val member=raw as? CashJointRelationMember ?: relationFailure("relation_group_changed")
            val revision=strictCashInteger(member.rowRevision)?.takeIf {it.signum()>0} ?: relationFailure("relation_group_changed")
            if(member.id !is String || member.id.isEmpty() || member.id in result || member.decisionGroupId!=null && (member.decisionGroupId !is String || member.decisionGroupId.isEmpty()) ||
                member.active !is Boolean || member.state !is String || member.state !in setOf("draft","confirmed","ignored")) relationFailure("relation_group_changed")
            result[member.id]=CheckedJointMember(member.id,member.decisionGroupId,revision,member.state,member.active)
        }
        return result
    }
    val expected=members(expectedMembers);val current=members(currentMembers)
    if(decidedIds !is Collection<*> || decidedIds.any {it !is String || it.isEmpty()}) relationFailure("relation_group_changed")
    val decided=decidedIds.filterIsInstance<String>().toSet()
    if(!expected.keys.containsAll(decided)) relationFailure("relation_group_changed")
    for(group in decided.mapNotNull {expected.getValue(it).decisionGroupId}.toSet()) {
        val before=expected.filterValues {it.decisionGroupId==group};val after=current.filterValues {it.decisionGroupId==group}
        if(before!=after || after.values.any {!it.active || it.state!="draft"}) relationFailure("relation_group_changed")
        if(!decided.containsAll(before.keys)) relationFailure("relation_group_incomplete")
    }
    for(id in decided) if(current[id]!=expected[id] || !current.getValue(id).active || current.getValue(id).state!="draft") relationFailure("relation_group_changed")
}
private data class PreparedCashGraphEdge(val edge: CheckedCashGraphEdge,val primary: CashRelationEndpointValues,val secondary: CashRelationEndpointValues,val amount: BigDecimal)
private data class GraphBusinessKey(val kind: String,val subtype: String,val pair: Pair<String,String>)
internal fun buildCashRelationGraph(facts: Any?,components: Any?,relations: Any?,fundingComponentIds: Any?=emptyList<Any>()): CashProjectionBuild {
    val factList=rawGraphCollection(facts);val partList=rawGraphCollection(components);val edges=rawGraphCollection(relations);val fundingList=rawGraphCollection(fundingComponentIds)
    if(fundingList.any {it !is String || it.isEmpty()}) relationFailure("relation_data_invalid")
    val funding=fundingList.filterIsInstance<String>().toMutableSet()
    val parents=linkedMapOf<String,CashProjectionFact>()
    for(raw in factList) {
        val fact=raw as? CashProjectionFact ?: relationFailure("relation_endpoint_incomplete")
        val id=fact.id.toString()
        if(fact.state!="posted" || fact.id<=0 || id in parents) relationFailure("relation_endpoint_incomplete")
        parents[id]=fact
    }
    val rawByComponent=linkedMapOf<String,Any?>()
    val byComponent=linkedMapOf<String,CashRelationEndpointValues>();val byParent=mutableMapOf<String,MutableList<CashRelationEndpointValues>>()
    for(raw in partList) {
        val part=validateCashRelationEndpoint(raw)
        if(part.componentId in byComponent || part.transactionId !in parents) relationFailure("relation_endpoint_incomplete")
        rawByComponent[part.componentId]=raw;byComponent[part.componentId]=part;byParent.getOrPut(part.transactionId) {mutableListOf()}.add(part)
    }
    for((id,fact) in parents) {
        val parts=byParent[id].orEmpty();val amount=fact.amount!!.arithmeticValue()
        val total=parts.fold(BigDecimal.ZERO) {sum,part ->sum.add(part.number,cashGraphArithmetic)}
        if(parts.isEmpty() || parts.any {it.currency.uppercase()!=fact.currency} || total.compareTo(amount)!=0 ||
            parts.any {amount.signum()<0 && it.number.signum()>0 || amount.signum()>0 && it.number.signum()<0 || amount.signum()==0 && it.number.signum()!=0} ||
            parts.map {it.recordType to it.recordSubtype}.toSet().size!=1 ||
            fact.cashGranularity=="atomic" && (parts.size!=1 || parts.single().accountId!=fact.accountId.toString()) ||
            fact.cashGranularity=="aggregate" && fact.accountId!=null) relationFailure("relation_endpoint_incompatible")
    }
    if(!byComponent.keys.containsAll(funding)) relationFailure("relation_endpoint_incomplete")
    fun project(relations: List<CashProjectionRelation>): CashProjectionBuild = try {buildCashProjections(parents.values.toList(),relations)}
        catch (_: CashProjectionFailure) {relationFailure("relation_graph_invalid")}
    if(edges.isEmpty()) return project(emptyList())
    funding.addAll(byComponent.values.filter {parents.getValue(it.transactionId).fundingRelationId!=null}.map {it.componentId})
    val componentMirrors=CashRelationGroups(byComponent.keys);val parentMirrors=CashRelationGroups(parents.keys)
    val prepared=mutableListOf<PreparedCashGraphEdge>();val identifiers=mutableSetOf<BigInteger>();val businessKeys=mutableSetOf<GraphBusinessKey>();val transferOccupied=mutableSetOf<String>()
    for(raw in edges) {
        val edge=checkedGraphEdge(raw)
        if(!identifiers.add(edge.id)) relationFailure("relation_data_invalid")
        if(!edge.active || edge.state!="confirmed") continue
        val a=byComponent[edge.primaryComponentId];val b=byComponent[edge.secondaryComponentId]
        if(a==null || b==null || a.transactionId!=edge.primaryTransactionId || b.transactionId!=edge.secondaryTransactionId || a.transactionId==b.transactionId) relationFailure("relation_endpoint_incomplete")
        val rawA=rawByComponent.getValue(a.componentId);val rawB=rawByComponent.getValue(b.componentId)
        validateCashRelationRoles(edge.kind,edge.subtype,rawA,rawB,edge.primaryRole,edge.secondaryRole)
        val pair=a.componentId to b.componentId
        val sortedPair=if(cashRelationStringOrder.compare(pair.first,pair.second)<=0) pair else pair.second to pair.first
        if(!businessKeys.add(GraphBusinessKey(edge.kind,edge.subtype,if(edge.kind=="payment_mirror") sortedPair else pair))) relationFailure("relation_data_invalid")
        if(pair.first in funding || pair.second in funding) relationFailure("relation_endpoint_occupied")
        val amount=edge.appliedAmount?.arithmeticValue() ?: when(edge.kind) {
            "payment_mirror" ->minOf(a.number.abs(),b.number.abs())
            "refund_offset" ->b.number.abs()
            else ->BigDecimal.ZERO
        }
        if(amount.signum()<0 || amount>minOf(a.number.abs(),b.number.abs()) || edge.kind in setOf("payment_mirror","refund_offset") && amount.signum()==0 && (a.number.signum()!=0 || b.number.signum()!=0)) relationFailure("relation_amount_invalid")
        when(edge.kind) {
            "payment_mirror" -> {
                parentMirrors.join(a.transactionId,b.transactionId)
                if(amount.compareTo(a.number.abs())==0 && amount.compareTo(b.number.abs())==0) componentMirrors.join(pair.first,pair.second)
            }
            "refund_offset" -> {
                if(a.number.signum()==0 && b.number.signum()==0 && (parents.getValue(a.transactionId).amount!!.arithmeticValue().signum()!=0 || parents.getValue(b.transactionId).amount!!.arithmeticValue().signum()!=0)) relationFailure("relation_endpoint_incompatible")
                if(amount>a.number.abs()) relationFailure("relation_amount_invalid")
            }
            else -> {
                if(pair.first in transferOccupied || pair.second in transferOccupied) relationFailure("relation_endpoint_occupied")
                transferOccupied.add(pair.first);transferOccupied.add(pair.second)
                if(a.currency.uppercase()==b.currency.uppercase() && a.number.abs().compareTo(b.number.abs())!=0) relationFailure("relation_amount_invalid")
            }
        }
        prepared.add(PreparedCashGraphEdge(edge,a,b,amount))
    }
    val allocations=linkedMapOf<Pair<String,String>,BigDecimal>();val targets=mutableMapOf<String,MutableSet<String>>()
    for(item in prepared.filter {it.edge.kind=="refund_offset"}) {
        val key=componentMirrors.root(item.primary.componentId) to componentMirrors.root(item.secondary.componentId)
        val previous=allocations.putIfAbsent(key,item.amount)
        if(previous!=null && previous.compareTo(item.amount)!=0) relationFailure("relation_amount_invalid")
        targets.getOrPut(key.second) {mutableSetOf()}.add(parentMirrors.root(item.primary.transactionId))
    }
    if(targets.values.any {it.size>1}) relationFailure("relation_endpoint_occupied")
    val spent=mutableMapOf<String,BigDecimal>()
    for((pair,amount) in allocations) for(id in listOf(pair.first,pair.second)) spent[id]=(spent[id] ?: BigDecimal.ZERO).add(amount,cashGraphArithmetic)
    for(part in byComponent.values) if((spent[componentMirrors.root(part.componentId)] ?: BigDecimal.ZERO)>part.number.abs()) relationFailure("relation_refund_exceeds_remaining")
    val projectionEdges=prepared.sortedBy {it.edge.id}.map {item ->
        val a=item.primary;val b=item.secondary
        CashProjectionRelation.create(item.edge.id,item.edge.kind,a.transactionId.toLong(),b.transactionId.toLong(),subtype=item.edge.subtype,
            appliedAmount=item.amount.toString(),refundAllocationKey=if(item.edge.kind=="refund_offset") componentMirrors.root(a.componentId) to componentMirrors.root(b.componentId) else null)
    }
    return project(projectionEdges)
}
