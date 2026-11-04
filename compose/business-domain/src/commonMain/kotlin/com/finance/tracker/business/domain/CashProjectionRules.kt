package com.finance.tracker.business.domain

/** One deterministic per-row projection algorithm for native services and local clients. */
class CashProjectionRules<I:Comparable<I>,T:Comparable<T>>(private val values:CashProjectionValues<I,T>,
    private val timeText:(T)->String) {
    private fun invalidProjectionFact():Nothing=throw CashProjectionDomainFailure("projection.invalid_fact")
    private fun invalidProjectionRelation():Nothing=throw CashProjectionDomainFailure("projection.invalid_relation")
    private fun CashAmount.number()=LedgerArithmeticValue.fromCashAmount(this)
    private fun projectionAmount(value:LedgerArithmeticValue):CashAmount=CashAmount.parseOrNull(value.toString()) ?: invalidProjectionRelation()
    private data class LogicalProjectionRelation<I:Comparable<I>>(val relation:CashProjectionRelation<I>,val primary:I,val secondary:I)
    private data class ProjectionEdge<I:Comparable<I>>(val kind:String,val primary:I,val secondary:I)
    private fun rootedProjectionTree(nodes:List<I>,edges:List<Pair<I,I>>):I=
        cashProjectionRoot(nodes,edges)
    private fun transferBusinessLabel(facts:List<CashProjectionFact<I,T>>):String=
        cashTransferBusinessLabel(facts.map {it.recordType}.toSet(),facts.map {it.recordSubtype})
    private fun sourceIdentityKey(fact: CashProjectionFact<I,T>): List<String> {
        if (fact.stableSourceKey.isNotEmpty()) return fact.stableSourceKey
        val source=fact.sourceType.orEmpty()
        if (fact.recordOrigin in setOf("legacy","manual") && fact.recordId.isNotEmpty() && !fact.recordId.startsWith("import-v1:"))
            return listOf(source.ifEmpty {"manual"},"legacy_source_record",fact.recordId)
        invalidProjectionFact()
    }
    private fun representativeKey(fact: CashProjectionFact<I,T>): List<Any?> {
        val source=fact.sourceType.orEmpty().lowercase()
        val rank=if (source in setOf("alipay","wechat","weixin","支付宝","微信")) 0
            else if (source in setOf("ccb_debit","ccb_credit","icbc_debit","icbc_credit","icbc_asia","bank","debit","credit")) 1 else 2
        return listOf(SourceJsonInteger(rank),fact.occurredAt?.let(timeText),sourceIdentityKey(fact))
    }
    fun validateGroup(group: List<I>,facts: Map<I,CashProjectionFact<I,T>>,relations: List<CashProjectionRelation<I>>): CashProjection<I,T> {
        val contained=relations.filter {it.primaryFactId in group && it.secondaryFactId in group}.sortedBy {it.id}
        val mirrors=contained.filter {it.kind=="payment_mirror"}
        for(relation in mirrors) {
            val left=facts.getValue(relation.primaryFactId);val right=facts.getValue(relation.secondaryFactId)
            val a=left.amount!!.number();val b=right.amount!!.number();val covered=relation.appliedAmount?.number()
            val amountsMatch=if(left.cashGranularity=="atomic" && right.cashGranularity=="atomic") a.compareTo(b)==0
                else covered!=null && covered.signum>0 && covered<=a.abs() && covered<=b.abs() || a.compareTo(b)==0
            if(!amountsMatch || left.currency!=right.currency || (a.signum<0)!=(b.signum<0)) invalidProjectionRelation()
        }
        for(relation in contained.filter {it.kind=="transfer_pair"}) {
            val left=facts.getValue(relation.primaryFactId);val right=facts.getValue(relation.secondaryFactId)
            val a=left.amount!!.number();val b=right.amount!!.number()
            if(a.signum*b.signum>=0) invalidProjectionRelation()
            if(left.currency==right.currency && a.abs().compareTo(b.abs())!=0) invalidProjectionRelation()
        }
        val mirrorComponents=CashProjectionComponents(group)
        mirrors.forEach {mirrorComponents.union(it.primaryFactId,it.secondaryFactId)}
        val canonical=mutableMapOf<I,I>()
        for(members in mirrorComponents.groups()) {
            if(members.size>1) {
                val identities=members.map {sourceIdentityKey(facts.getValue(it))}
                if(identities.toSet().size!=identities.size) invalidProjectionRelation()
            }
            val root=if(members.size==1) members.first() else members.minWithOrNull(compareBy<I> {sourceJson(representativeKey(facts.getValue(it)),{null},::pythonFloatText).toString()})
                ?: invalidProjectionRelation()
            members.forEach {canonical[it]=root}
        }
        if(group.size>1) group.forEach {sourceIdentityKey(facts.getValue(it))}
        val logical=mutableListOf<LogicalProjectionRelation<I>>();val seen=mutableSetOf<ProjectionEdge<I>>()
        for(original in contained) {
            if(original.kind=="payment_mirror") continue
            val left=canonical.getValue(original.primaryFactId);val right=canonical.getValue(original.secondaryFactId)
            if(left==right || !seen.add(ProjectionEdge(original.kind,left,right))) continue
            var relation=original
            if(relation.kind=="refund_offset") {
                val same=contained.filter {it.kind==original.kind && canonical[it.primaryFactId]==left && canonical[it.secondaryFactId]==right}
                val explicit=same.mapNotNull {it.appliedAmount}.filter {it.number().signum>0}
                if(explicit.isNotEmpty()) {
                    val components=same.map {projectionAmount(facts.getValue(it.primaryFactId).amount!!.number().abs()) to projectionAmount(facts.getValue(it.secondaryFactId).amount!!.number().abs())}
                    val duplicate=explicit.toSet().size==1 && components.toSet().size==1
                    val total=if(same.any {it.refundAllocationKey!=null}) {
                        val allocations=linkedMapOf<Pair<String,String>,CashAmount>()
                        for(item in same) {
                            val key=item.refundAllocationKey ?: invalidProjectionRelation();val applied=item.appliedAmount ?: invalidProjectionRelation()
                            val previous=allocations[key]
                            if(previous==null) allocations[key]=applied
                            if(previous!=null && previous!=applied) invalidProjectionRelation()
                        }
                        allocations.values.fold(LedgerArithmeticValue.ZERO) {sum,value ->sum.add(value.number(),80)}
                    } else if(duplicate) explicit.first().number() else explicit.fold(LedgerArithmeticValue.ZERO) {sum,value ->sum.add(value.number(),80)}
                    relation=CashProjectionRelation.create(values,relation.id,relation.kind,relation.primaryFactId,relation.secondaryFactId,
                        relation.status,projectionAmount(total).toString())
                }
            }
            logical.add(LogicalProjectionRelation(relation,left,right))
        }
        val rootId=rootedProjectionTree(canonical.values.toSet().sorted(),logical.map {it.primary to it.secondary})
        val root=facts.getValue(rootId)
        if(root.fundingRelationId!=null && group.size!=1) invalidProjectionRelation()
        val transfers=logical.filter {it.relation.kind=="transfer_pair"};val refunds=logical.filter {it.relation.kind=="refund_offset"}
        if(transfers.isNotEmpty() && refunds.isNotEmpty()) invalidProjectionRelation()
        val roles=group.associateWith {mutableSetOf<String>()}
        roles.getValue(rootId).add("root");mirrors.forEach {roles.getValue(it.secondaryFactId).add("mirror")}
        refunds.forEach {roles.getValue(it.relation.secondaryFactId).add("refund")}
        transfers.forEach {roles.getValue(it.relation.primaryFactId).add("transfer");roles.getValue(it.relation.secondaryFactId).add("transfer")}
        val base=singleProjection(root)
        val amount: CashAmount;val type: CashEconomicType;val visible: Boolean;val hidden: String?;val label: String?
        when {
            root.fundingRelationId!=null || transfers.isNotEmpty() -> {
                label=if(root.fundingRelationId!=null) "bank_security_transfer" else transferBusinessLabel(group.map {facts.getValue(it)})
                amount=CashAmount.parse("0");type=CashEconomicType.INTERNAL_TRANSFER;visible=true;hidden=null
            }
            refunds.isNotEmpty() -> {
                val zero=refunds.all {facts.getValue(it.primary).amount!!.number().signum==0 && facts.getValue(it.secondary).amount!!.number().signum==0 && facts.getValue(it.primary).currency==facts.getValue(it.secondary).currency}
                val rootAmount=root.amount!!
                if(rootAmount.number().signum>=0 && !zero) invalidProjectionRelation()
                var refunded=LedgerArithmeticValue.ZERO
                for(item in refunds) {
                    val expense=facts.getValue(item.primary);val refund=facts.getValue(item.secondary)
                    if(!zero && (expense.amount!!.number().signum>=0 || refund.amount!!.number().signum<=0 || expense.currency!=refund.currency)) invalidProjectionRelation()
                    val applied=item.relation.appliedAmount?.number()
                    if(applied!=null && applied.compareTo(refund.amount!!.number())!=0) invalidProjectionRelation()
                    refunded=refunded.add(refund.amount!!.number(),80)
                }
                if(refunded>rootAmount.number().negate()) invalidProjectionRelation()
                amount=projectionAmount(rootAmount.number().add(refunded,80));type=CashEconomicType.EXPENSE;visible=true;hidden=null
                label=if(group.any {facts.getValue(it).recordType=="transfer_reversal"}) "p2p_return" else null
            }
            else -> {amount=base.netAmount!!;type=base.economicType!!;visible=base.visible;hidden=base.hiddenReason;label=base.businessLabel}
        }
        val members=group.sortedWith(compareBy<I> {if(it==rootId) 0 else 1}.thenBy {facts.getValue(it).occurredAt}.thenBy {it})
            .map {facts.getValue(it) to RelationList(roles.getValue(it).sorted())}
        return CashProjection("cash:$rootId",root,amount,type,visible,hidden,label,RelationList(members.map {it.first.id}),RelationList(members),RelationList(contained),
            RelationList(listOf("payment_mirror","transfer_pair","refund_offset").filter {kind ->contained.any {it.kind==kind}}),root.fundingRelationId,
            attentionCount=members.count {it.first.needsAttention}, economicRootIds=group.filter {canonical[it]==rootId}.sorted())
    }
    private fun singleProjection(fact: CashProjectionFact<I,T>): CashProjection<I,T> {
        val amount=fact.amount ?: invalidProjectionFact();val sign=amount.number().signum
        val type=when {fact.fundingRelationId!=null || sign==0 ->CashEconomicType.INTERNAL_TRANSFER;sign<0 ->CashEconomicType.EXPENSE;else ->CashEconomicType.INCOME}
        val zero=sign==0 && fact.fundingRelationId==null
        return CashProjection("cash:${fact.id}",fact,if(fact.fundingRelationId!=null || zero) CashAmount.parse("0") else amount,type,!zero,
            if(zero) "balance_adjustment" else null,if(fact.fundingRelationId!=null) "bank_security_transfer" else if(zero) "balance_adjustment" else null,
            listOf(fact.id),listOf(fact to listOf("root")),emptyList(),emptyList(),fact.fundingRelationId,attentionCount=if(fact.needsAttention) 1 else 0,
            economicRootIds=listOf(fact.id))
    }
    fun build(facts: List<CashProjectionFact<I,T>>,relations: List<CashProjectionRelation<I>>,
        observeGroup: ((List<I>,List<CashProjectionRelation<I>>)->Unit)?=null): CashProjectionBuild<I,T> {
        val factById=facts.associateBy {it.id}
        if(factById.size!=facts.size) invalidProjectionFact()
        val accepted=relations.filter {it.status=="accepted"}
        val businessKeys=mutableSetOf<Any>()
        for(relation in accepted) {
            val key:Any=if(relation.kind=="payment_mirror") listOf(relation.kind,minOf(relation.primaryFactId,relation.secondaryFactId),maxOf(relation.primaryFactId,relation.secondaryFactId))
            else listOf(relation.kind,relation.primaryFactId,relation.secondaryFactId)
            if(!businessKeys.add(key) && relation.appliedAmount==null) invalidProjectionRelation()
        }
        for(relation in accepted) if(relation.primaryFactId==relation.secondaryFactId || factById[relation.primaryFactId]?.state!="posted" || factById[relation.secondaryFactId]?.state!="posted") invalidProjectionRelation()
        val posted=facts.filter {it.state=="posted"};val projections=mutableListOf<CashProjection<I,T>>()
        if(accepted.isEmpty()) projections.addAll(posted.map(::singleProjection))
        else {
            val components=CashProjectionComponents(posted.map {it.id});accepted.forEach {components.union(it.primaryFactId,it.secondaryFactId)}
            val groups=components.groups();val groupByFact=groups.flatMapIndexed {index,group ->group.map {it to index}}.toMap()
            val byGroup=accepted.groupBy {groupByFact.getValue(it.primaryFactId)}
            for((index,group) in groups.withIndex()) {
                val selected=byGroup[index].orEmpty();observeGroup?.invoke(RelationList(group),RelationList(selected))
                projections.add(validateGroup(group,factById,selected))
            }
        }
        for(fact in facts.filter {it.state=="draft"}) projections.add(CashProjection("cash:${fact.id}",fact,fact.amount,null,true,null,null,
            listOf(fact.id),listOf(fact to listOf("root")),emptyList(),emptyList(),state="draft",statisticsEligible=false,attentionCount=1))
        val ordered=projections.sortedBy {it.projectionId}
        if(ordered.flatMap {it.memberIds}.toSet().size!=factById.size) throw CashProjectionDomainFailure("projection.incomplete")
        return CashProjectionBuild(RelationList(ordered))
    }
}
