package com.finance.tracker.business.domain

data class CashRelationGraphEdge(val id: Any?,val kind: Any?,val primaryTransactionId: Any?,val secondaryTransactionId: Any?,
    val primaryComponentId: Any?,val secondaryComponentId: Any?,val primaryRole: Any?,val secondaryRole: Any?,
    val appliedAmount: Any?=null,val state: Any?="confirmed",val active: Any?=true)
data class CashJointRelationMember(val id: Any?,val decisionGroupId: Any?,val rowRevision: Any?,val state: Any?="draft",val active: Any?=true)

/** Exact graph validation; raw identifier interpretation belongs to the platform value boundary. */
class CashRelationGraphRules<I:Comparable<I>,T:Comparable<T>>(private val values:CashProjectionValues<I,T>,
    private val projections:CashProjectionRules<I,T>,private val integer:(Any?)->I?,private val idFromText:(String)->I) {
    private fun CashAmount.number()=LedgerArithmeticValue.fromCashAmount(this)
    private val CashRelationEndpointValues.number get()=amount.number()
    private data class CheckedCashGraphEdge<I:Comparable<I>>(val id: I,val kind: String,val primaryTransactionId: String,val secondaryTransactionId: String?,
        val primaryComponentId: String?,val secondaryComponentId: String?,val primaryRole: String?,val secondaryRole: String?,
        val appliedAmount: CashAmount?,val state: String,val active: Boolean)
    private data class CheckedJointMember<I:Comparable<I>>(val id: String,val decisionGroupId: String?,val rowRevision: I,val state: String,val active: Boolean)
    private fun rawGraphCollection(value: Any?): List<*> = when(value) {
        is Iterable<*> ->value.toList()
        is Sequence<*> ->value.toList()
        is Iterator<*> ->value.asSequence().toList()
        else ->relationFailure("relation_data_invalid")
    }
    private fun checkedGraphEdge(value: Any?): CheckedCashGraphEdge<I> {
        val raw=value as? CashRelationGraphEdge ?: relationFailure("relation_data_invalid")
        fun reference(value: Any?)=value is String && value.isNotEmpty()
        val id=integer(raw.id)?.takeIf {values.positiveId(it)}
        if(id==null || raw.kind !is String || raw.kind !in setOf("payment_mirror","refund_offset","transfer_pair") ||
            raw.state !is String || raw.state !in setOf("draft","confirmed","ignored") || raw.active !is Boolean ||
            !reference(raw.primaryTransactionId) || listOf(raw.secondaryTransactionId,raw.primaryComponentId,raw.secondaryComponentId,raw.primaryRole,raw.secondaryRole).any {it!=null && !reference(it)}) relationFailure("relation_data_invalid")
        val amount=raw.appliedAmount?.let {cashRelationDecimal(it)?.takeIf {it.number().signum>=0} ?: relationFailure("relation_amount_invalid")}
        return CheckedCashGraphEdge(id,raw.kind,raw.primaryTransactionId as String,raw.secondaryTransactionId as String?,raw.primaryComponentId as String?,
            raw.secondaryComponentId as String?,raw.primaryRole as String?,raw.secondaryRole as String?,amount,raw.state,raw.active)
    }
    fun validateEdgeInput(value: Any?) {checkedGraphEdge(value)}
    fun validateJointContext(expectedMembers: Any?,currentMembers: Any?,decidedIds: Any?) {
        fun members(value: Any?): Map<String,CheckedJointMember<I>> {
            val input=when(value) {
                is Iterable<*> ->value.toList()
                is Sequence<*> ->value.toList()
                is Iterator<*> ->value.asSequence().toList()
                else ->relationFailure("relation_group_changed")
            }
            val result=linkedMapOf<String,CheckedJointMember<I>>()
            for(raw in input) {
                val member=raw as? CashJointRelationMember ?: relationFailure("relation_group_changed")
                val revision=integer(member.rowRevision)?.takeIf {values.positiveId(it)} ?: relationFailure("relation_group_changed")
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
    private data class PreparedCashGraphEdge<I:Comparable<I>>(val edge: CheckedCashGraphEdge<I>,val primary: CashRelationEndpointValues,val secondary: CashRelationEndpointValues,val amount: LedgerArithmeticValue)
    private data class GraphBusinessKey(val kind: String,val pair: Pair<String,String>)
    fun build(facts: Any?,components: Any?,relations: Any?,fundingComponentIds: Any?=emptyList<Any>()): CashProjectionBuild<I,T> {
        val factList=rawGraphCollection(facts);val partList=rawGraphCollection(components);val edges=rawGraphCollection(relations);val fundingList=rawGraphCollection(fundingComponentIds)
        if(fundingList.any {it !is String || it.isEmpty()}) relationFailure("relation_data_invalid")
        val funding=fundingList.filterIsInstance<String>().toMutableSet()
        val parents=linkedMapOf<String,CashProjectionFact<I,T>>()
        for(raw in factList) {
            val fact=raw as? CashProjectionFact<I,T> ?: relationFailure("relation_endpoint_incomplete")
            val id=fact.id.toString()
            if(fact.state!="posted" || !values.positiveId(fact.id) || id in parents) relationFailure("relation_endpoint_incomplete")
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
            val parts=byParent[id].orEmpty();val amount=fact.amount!!.number()
            val total=parts.fold(LedgerArithmeticValue.ZERO) {sum,part ->sum.add(part.number,80)}
            if(parts.isEmpty() || parts.any {it.currency.uppercase()!=fact.currency} || total.compareTo(amount)!=0 ||
                parts.any {amount.signum<0 && it.number.signum>0 || amount.signum>0 && it.number.signum<0 || amount.signum==0 && it.number.signum!=0} ||
                parts.map {it.recordType to it.recordSubtype}.toSet().size!=1 ||
                fact.cashGranularity=="atomic" && (parts.size!=1 || parts.single().accountId!=fact.accountId.toString()) ||
                fact.cashGranularity=="aggregate" && fact.accountId!=null) relationFailure("relation_endpoint_incompatible")
        }
        if(!byComponent.keys.containsAll(funding)) relationFailure("relation_endpoint_incomplete")
        fun project(relations: List<CashProjectionRelation<I>>): CashProjectionBuild<I,T> = try {projections.build(parents.values.toList(),relations)}
            catch (_: CashProjectionFailure) { relationFailure("relation_graph_invalid") }
        if(edges.isEmpty()) return project(emptyList())
        funding.addAll(byComponent.values.filter {parents.getValue(it.transactionId).fundingRelationId!=null}.map {it.componentId})
        val componentMirrors=CashRelationGroups(byComponent.keys);val parentMirrors=CashRelationGroups(parents.keys)
        val prepared=mutableListOf<PreparedCashGraphEdge<I>>();val identifiers=mutableSetOf<I>();val businessKeys=mutableSetOf<GraphBusinessKey>();val transferOccupied=mutableSetOf<String>()
        for(raw in edges) {
            val edge=checkedGraphEdge(raw)
            if(!identifiers.add(edge.id)) relationFailure("relation_data_invalid")
            if(!edge.active || edge.state!="confirmed") continue
            val a=byComponent[edge.primaryComponentId];val b=byComponent[edge.secondaryComponentId]
            if(a==null || b==null || a.transactionId!=edge.primaryTransactionId || b.transactionId!=edge.secondaryTransactionId || a.transactionId==b.transactionId) relationFailure("relation_endpoint_incomplete")
            val rawA=rawByComponent.getValue(a.componentId);val rawB=rawByComponent.getValue(b.componentId)
            validateCashRelationRoles(edge.kind,rawA,rawB,edge.primaryRole,edge.secondaryRole)
            val pair=a.componentId to b.componentId
            val sortedPair=if(cashRelationStringOrder.compare(pair.first,pair.second)<=0) pair else pair.second to pair.first
            if(!businessKeys.add(GraphBusinessKey(edge.kind,if(edge.kind=="payment_mirror") sortedPair else pair))) relationFailure("relation_data_invalid")
            if(pair.first in funding || pair.second in funding) relationFailure("relation_endpoint_occupied")
            val amount=edge.appliedAmount?.number() ?: when(edge.kind) {
                "payment_mirror" ->minOf(a.number.abs(),b.number.abs())
                "refund_offset" ->b.number.abs()
                else ->LedgerArithmeticValue.ZERO
            }
            if(amount.signum<0 || amount>minOf(a.number.abs(),b.number.abs()) || edge.kind in setOf("payment_mirror","refund_offset") && amount.signum==0 && (a.number.signum!=0 || b.number.signum!=0)) relationFailure("relation_amount_invalid")
            when(edge.kind) {
                "payment_mirror" -> {
                    parentMirrors.join(a.transactionId,b.transactionId)
                    if(amount.compareTo(a.number.abs())!=0 || amount.compareTo(b.number.abs())!=0) relationFailure("relation_amount_invalid")
                    componentMirrors.join(pair.first,pair.second)
                }
                "refund_offset" -> {
                    if(a.number.signum==0 && b.number.signum==0 && (parents.getValue(a.transactionId).amount!!.number().signum!=0 || parents.getValue(b.transactionId).amount!!.number().signum!=0)) relationFailure("relation_endpoint_incompatible")
                    if(amount>a.number.abs()) relationFailure("relation_amount_invalid")
                }
                else -> {
                    if(amount.signum!=0) relationFailure("relation_amount_invalid")
                    if(byParent.getValue(a.transactionId).size!=1 || byParent.getValue(b.transactionId).size!=1) relationFailure("relation_aggregate_unsupported")
                    if(pair.first in transferOccupied || pair.second in transferOccupied) relationFailure("relation_endpoint_occupied")
                    transferOccupied.add(pair.first);transferOccupied.add(pair.second)
                    if(a.currency.uppercase()==b.currency.uppercase() && a.number.abs().compareTo(b.number.abs())!=0) relationFailure("relation_amount_invalid")
                }
            }
            prepared.add(PreparedCashGraphEdge(edge,a,b,amount))
        }
        val allocations=linkedMapOf<Pair<String,String>,LedgerArithmeticValue>();val targets=mutableMapOf<String,MutableSet<String>>()
        val refundParents=mutableSetOf<String>()
        for(item in prepared.filter {it.edge.kind=="refund_offset"}) {
            val key=componentMirrors.root(item.primary.componentId) to componentMirrors.root(item.secondary.componentId)
            val previous=allocations[key]
            if(previous==null) allocations[key]=item.amount
            if(previous!=null && previous.compareTo(item.amount)!=0) relationFailure("relation_amount_invalid")
            targets.getOrPut(key.second) {mutableSetOf()}.add(parentMirrors.root(item.primary.transactionId))
            refundParents.add(parentMirrors.root(item.secondary.transactionId))
        }
        if(targets.values.any {it.size>1}) relationFailure("relation_endpoint_occupied")
        val spent=mutableMapOf<String,LedgerArithmeticValue>()
        for((pair,amount) in allocations) for(id in listOf(pair.first,pair.second)) spent[id]=(spent[id] ?: LedgerArithmeticValue.ZERO).add(amount,80)
        for(part in byComponent.values) {
            val used=spent[componentMirrors.root(part.componentId)] ?: LedgerArithmeticValue.ZERO
            if(used>part.number.abs()) relationFailure("relation_refund_exceeds_remaining")
            if(parentMirrors.root(part.transactionId) in refundParents && used.compareTo(part.number.abs())!=0) relationFailure("relation_refund_incomplete")
        }
        val projectionEdges=prepared.sortedBy {it.edge.id}.map {item ->
            val a=item.primary;val b=item.secondary
            CashProjectionRelation.create(values,item.edge.id,item.edge.kind,idFromText(a.transactionId),idFromText(b.transactionId),
                appliedAmount=item.amount.toString(),refundAllocationKey=if(item.edge.kind=="refund_offset") componentMirrors.root(a.componentId) to componentMirrors.root(b.componentId) else null)
        }
        return project(projectionEdges)
    }
}
