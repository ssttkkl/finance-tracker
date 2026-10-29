package com.finance.tracker.backend

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import java.util.List.copyOf

internal const val CASH_PROJECTION_RULES_VERSION="cash-projection-v2"
internal open class CashProjectionFailure(val code: String): RuntimeException(code)
internal class CashProjectionDomainFailure(code:String):CashProjectionFailure(code)
internal enum class CashEconomicType(val value: String) { EXPENSE("expense"), INCOME("income"), INTERNAL_TRANSFER("internal_transfer") }
internal enum class CashProjectionComposition(val value: String) { PAYMENT_MIRROR("payment_mirror"), REFUND_OFFSET("refund_offset"), TRANSFER_PAIR("transfer_pair") }
private fun invalidProjectionFact(): Nothing=throw CashProjectionDomainFailure("projection.invalid_fact")
private fun invalidProjectionRelation(): Nothing=throw CashProjectionDomainFailure("projection.invalid_relation")
private val projectionArithmetic=MathContext(80,RoundingMode.HALF_EVEN)
private fun CashAmount.number()=arithmeticValue()
private fun projectionAmount(value: BigDecimal): CashAmount=CashAmount.parseOrNull(value.toString()) ?: invalidProjectionRelation()

@ConsistentCopyVisibility
internal data class CashProjectionFact private constructor(val id: Long,val accountId: Long?,val occurredAt: Instant?,val amount: CashAmount?,
    val currency: String?,val counterparty: String,val categoryId: String?,val note: String,val sourceType: String?,val recordId: String,
    val fundingRelationId: Long?,val cashGranularity: String,val state: String,val createdAt: Instant?,val needsAttention: Boolean) {
    init {
        if(id<=0 || accountId!=null && accountId<=0 || state !in setOf("draft","posted") || cashGranularity !in setOf("atomic","aggregate") ||
            state=="posted" && (amount==null || currency==null || occurredAt==null) ||
            state=="draft" && (occurredAt==null && createdAt==null || fundingRelationId!=null) ||
            currency!=null && !currency.matches(Regex("[A-Z]{3}"))) invalidProjectionFact()
        for(value in listOfNotNull(occurredAt,createdAt)) try {pythonCashTimestamp(value)} catch (_: InvalidCashValue) {invalidProjectionFact()}
    }
    fun withAttention(value:Boolean):CashProjectionFact=if(value==needsAttention) this else copy(needsAttention=value)
    companion object {
        fun create(id: Long,accountId: Long?,occurredAt: Instant?,amount: String?,currency: String?,counterparty: String,categoryId: String?,
            note: String,sourceType: String?,recordId: String,fundingRelationId: Long?=null,cashGranularity: String="atomic",state: String="posted",
            createdAt: Instant?=null,needsAttention: Boolean=false): CashProjectionFact {
            val parsed=amount?.let {CashAmount.parseOrNull(it) ?: invalidProjectionFact()}
            val normalized=currency?.let {buildString {it.codePoints().forEach {code ->append(pythonUpperCase[code] ?: String(Character.toChars(code)))}}}
            return CashProjectionFact(id,accountId,occurredAt,parsed,normalized,counterparty,categoryId,note,sourceType,recordId,
                fundingRelationId,cashGranularity,state,createdAt,needsAttention)
        }
    }
}

@ConsistentCopyVisibility
internal data class CashProjectionRelation private constructor(val id: BigInteger,val kind: String,val primaryFactId: Long,val secondaryFactId: Long,
    val status: String,val subtype: String,val appliedAmount: CashAmount?,val refundAllocationKey: Pair<String,String>?) {
    init {
        if(kind !in setOf("payment_mirror","refund_offset","transfer_pair") || id.signum()<=0 || primaryFactId<=0 || secondaryFactId<=0 ||
            kind=="transfer_pair" && subtype !in setOf("","ordinary_transfer","cross_currency_remittance","credit_repayment","currency_exchange","bank_security_transfer","balance_adjustment") ||
            refundAllocationKey!=null && (kind!="refund_offset" || refundAllocationKey.first.isEmpty() || refundAllocationKey.second.isEmpty()) ||
            appliedAmount!=null && appliedAmount.number().signum()<0) invalidProjectionRelation()
    }
    companion object {
        fun create(id: Any?,kind: String,primaryFactId: Long,secondaryFactId: Long,status: String="accepted",subtype: String="",
            appliedAmount: String?=null,refundAllocationKey: Pair<String,String>?=null)=CashProjectionRelation(strictCashInteger(id) ?: invalidProjectionRelation(),kind,primaryFactId,secondaryFactId,
            status,subtype,appliedAmount?.let {CashAmount.parseOrNull(it) ?: invalidProjectionRelation()},refundAllocationKey)
    }
}

internal data class CashProjection(val projectionId: String,val primaryRecord: CashProjectionFact,val netAmount: CashAmount?,
    val economicType: CashEconomicType?,val visible: Boolean,val hiddenReason: String?,val transferSubtype: String?,val memberIds: List<Long>,
    val members: List<Pair<CashProjectionFact,List<String>>>,val relations: List<CashProjectionRelation>,val compositions: List<String>,
    val fundingRelationId: Long?=null,val state: String="posted",val statisticsEligible: Boolean=true,val attentionCount: Int=0) {
    val occurredAt: Instant? get()=primaryRecord.occurredAt
    val sortAt: Instant get()=primaryRecord.occurredAt ?: primaryRecord.createdAt ?: invalidProjectionFact()
    val confirmationStatus: String get()=if(state=="draft" || attentionCount>0) "pending" else "confirmed"
}
internal data class CashProjectionBuild(val projections: List<CashProjection>,val rulesVersion: String=CASH_PROJECTION_RULES_VERSION) {
    val memberIds: Set<Long> get()=java.util.Set.copyOf(projections.flatMap {it.memberIds})
}
private class ProjectionComponents(ids: Collection<Long>) {
    private val parents=ids.associateWith {it}.toMutableMap()
    fun find(id: Long): Long {
        var current=id
        while(parents.getValue(current)!=current) {
            parents[current]=parents.getValue(parents.getValue(current));current=parents.getValue(current)
        }
        return current
    }
    fun union(left: Long,right: Long) {
        val a=find(left);val b=find(right)
        if(a!=b) parents[maxOf(a,b)]=minOf(a,b)
    }
    fun groups(): List<List<Long>> = parents.keys.sorted().groupBy(::find).values.toList()
}
private data class LogicalProjectionRelation(val relation: CashProjectionRelation,val primary: Long,val secondary: Long)
private data class ProjectionEdge(val kind: String,val primary: Long,val secondary: Long,val subtype: String)
private fun rootedProjectionTree(nodes: List<Long>,edges: List<Pair<Long,Long>>): Long {
    val incoming=nodes.associateWith {0}.toMutableMap();val adjacency=nodes.associateWith {mutableListOf<Long>()}
    for((left,right) in edges) {incoming[right]=incoming.getValue(right)+1;adjacency.getValue(left).add(right)}
    val roots=nodes.filter {incoming[it]==0}
    if(roots.size!=1) invalidProjectionRelation()
    val reached=mutableSetOf<Long>();val stack=mutableListOf(roots.single())
    while(stack.isNotEmpty()) {
        val item=stack.removeAt(stack.lastIndex)
        if(!reached.add(item)) invalidProjectionRelation()
        stack.addAll(adjacency.getValue(item))
    }
    if(reached!=nodes.toSet()) invalidProjectionRelation()
    return roots.single()
}
internal fun validateProjectionGroup(group: List<Long>,facts: Map<Long,CashProjectionFact>,relations: List<CashProjectionRelation>): CashProjection {
    val contained=relations.filter {it.primaryFactId in group && it.secondaryFactId in group}.sortedBy {it.id}
    val mirrors=contained.filter {it.kind=="payment_mirror"}
    for(relation in mirrors) {
        val left=facts.getValue(relation.primaryFactId);val right=facts.getValue(relation.secondaryFactId)
        val a=left.amount!!.number();val b=right.amount!!.number();val covered=relation.appliedAmount?.number()
        val amountsMatch=if(left.cashGranularity=="atomic" && right.cashGranularity=="atomic") a.compareTo(b)==0
            else covered!=null && covered.signum()>0 && covered<=a.abs() && covered<=b.abs() || a.compareTo(b)==0
        if(!amountsMatch || left.currency!=right.currency || (a.signum()<0)!=(b.signum()<0)) invalidProjectionRelation()
    }
    for(relation in contained.filter {it.kind=="transfer_pair"}) {
        val left=facts.getValue(relation.primaryFactId);val right=facts.getValue(relation.secondaryFactId)
        val a=left.amount!!.number();val b=right.amount!!.number()
        if(a.signum()*b.signum()>=0) invalidProjectionRelation()
        if(relation.subtype=="currency_exchange") {if(left.currency==right.currency) invalidProjectionRelation()}
        else if(left.currency==right.currency && a.abs().compareTo(b.abs())!=0) invalidProjectionRelation()
    }
    val mirrorComponents=ProjectionComponents(group)
    mirrors.forEach {mirrorComponents.union(it.primaryFactId,it.secondaryFactId)}
    val canonical=mutableMapOf<Long,Long>()
    for(members in mirrorComponents.groups()) {
        val root=rootedProjectionTree(members,mirrors.filter {it.primaryFactId in members && it.secondaryFactId in members}
            .map {it.primaryFactId to it.secondaryFactId}.distinct())
        members.forEach {canonical[it]=root}
    }
    val logical=mutableListOf<LogicalProjectionRelation>();val seen=mutableSetOf<ProjectionEdge>()
    for(original in contained) {
        if(original.kind=="payment_mirror") continue
        val left=canonical.getValue(original.primaryFactId);val right=canonical.getValue(original.secondaryFactId)
        if(left==right || !seen.add(ProjectionEdge(original.kind,left,right,original.subtype))) continue
        var relation=original
        if(relation.kind=="refund_offset") {
            val same=contained.filter {it.kind==original.kind && it.subtype==original.subtype && canonical[it.primaryFactId]==left && canonical[it.secondaryFactId]==right}
            val explicit=same.mapNotNull {it.appliedAmount}.filter {it.number().signum()>0}
            if(explicit.isNotEmpty()) {
                val components=same.map {projectionAmount(facts.getValue(it.primaryFactId).amount!!.number().abs()) to projectionAmount(facts.getValue(it.secondaryFactId).amount!!.number().abs())}
                val duplicate=explicit.toSet().size==1 && components.toSet().size==1
                val total=if(same.any {it.refundAllocationKey!=null}) {
                    val allocations=linkedMapOf<Pair<String,String>,CashAmount>()
                    for(item in same) {
                        val key=item.refundAllocationKey ?: invalidProjectionRelation();val applied=item.appliedAmount ?: invalidProjectionRelation()
                        val previous=allocations.putIfAbsent(key,applied)
                        if(previous!=null && previous!=applied) invalidProjectionRelation()
                    }
                    allocations.values.fold(BigDecimal.ZERO) {sum,value ->sum.add(value.number(),projectionArithmetic)}
                } else if(duplicate) explicit.first().number() else explicit.fold(BigDecimal.ZERO) {sum,value ->sum.add(value.number(),projectionArithmetic)}
                relation=CashProjectionRelation.create(relation.id,relation.kind,relation.primaryFactId,relation.secondaryFactId,
                    relation.status,relation.subtype,projectionAmount(total).toString())
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
    val amount: CashAmount;val type: CashEconomicType;val visible: Boolean;val hidden: String?;val subtype: String?
    when {
        root.fundingRelationId!=null || transfers.isNotEmpty() -> {
            subtype=if(root.fundingRelationId!=null) "bank_security_transfer" else transfers.first().relation.subtype.ifEmpty {"ordinary_transfer"}
            if(transfers.any {it.relation.subtype !in setOf("",subtype)}) invalidProjectionRelation()
            amount=CashAmount.parse("0");type=CashEconomicType.INTERNAL_TRANSFER;visible=true;hidden=null
        }
        refunds.isNotEmpty() -> {
            val zero=refunds.all {facts.getValue(it.primary).amount!!.number().signum()==0 && facts.getValue(it.secondary).amount!!.number().signum()==0 && facts.getValue(it.primary).currency==facts.getValue(it.secondary).currency}
            if(root.amount!!.number().signum()>=0 && !zero) invalidProjectionRelation()
            var refunded=BigDecimal.ZERO
            for(item in refunds) {
                val expense=facts.getValue(item.primary);val refund=facts.getValue(item.secondary)
                if(!zero && (expense.amount!!.number().signum()>=0 || refund.amount!!.number().signum()<=0 || expense.currency!=refund.currency)) invalidProjectionRelation()
                val applied=item.relation.appliedAmount?.number()
                refunded=refunded.add(if(applied!=null && (applied.signum()>0 || item.relation.refundAllocationKey!=null)) applied else refund.amount!!.number(),projectionArithmetic)
            }
            if(refunded>root.amount.number().negate()) invalidProjectionRelation()
            amount=projectionAmount(root.amount.number().add(refunded,projectionArithmetic));type=CashEconomicType.EXPENSE;visible=true;hidden=null;subtype=null
        }
        else -> {amount=base.netAmount!!;type=base.economicType!!;visible=base.visible;hidden=base.hiddenReason;subtype=base.transferSubtype}
    }
    val members=group.sortedWith(compareBy<Long> {if(it==rootId) 0 else 1}.thenBy {facts.getValue(it).occurredAt}.thenBy {it})
        .map {facts.getValue(it) to copyOf(roles.getValue(it).sorted())}
    return CashProjection("cash:$rootId",root,amount,type,visible,hidden,subtype,copyOf(members.map {it.first.id}),copyOf(members),copyOf(contained),
        copyOf(listOf("payment_mirror","transfer_pair","refund_offset").filter {kind ->contained.any {it.kind==kind}}),root.fundingRelationId,
        attentionCount=members.count {it.first.needsAttention})
}
private fun singleProjection(fact: CashProjectionFact): CashProjection {
    val amount=fact.amount ?: invalidProjectionFact();val sign=amount.number().signum()
    val type=when {fact.fundingRelationId!=null || sign==0 ->CashEconomicType.INTERNAL_TRANSFER;sign<0 ->CashEconomicType.EXPENSE;else ->CashEconomicType.INCOME}
    val zero=sign==0 && fact.fundingRelationId==null
    return CashProjection("cash:${fact.id}",fact,if(fact.fundingRelationId!=null || zero) CashAmount.parse("0") else amount,type,!zero,
        if(zero) "balance_adjustment" else null,if(fact.fundingRelationId!=null) "bank_security_transfer" else if(zero) "balance_adjustment" else null,
        listOf(fact.id),listOf(fact to listOf("root")),emptyList(),emptyList(),fact.fundingRelationId,attentionCount=if(fact.needsAttention) 1 else 0)
}
internal fun buildCashProjections(facts: List<CashProjectionFact>,relations: List<CashProjectionRelation>,
    observeGroup: ((List<Long>,List<CashProjectionRelation>)->Unit)?=null): CashProjectionBuild {
    val factById=facts.associateBy {it.id}
    if(factById.size!=facts.size) invalidProjectionFact()
    val accepted=relations.filter {it.status=="accepted"}
    for(relation in accepted) if(relation.primaryFactId==relation.secondaryFactId || factById[relation.primaryFactId]?.state!="posted" || factById[relation.secondaryFactId]?.state!="posted") invalidProjectionRelation()
    val posted=facts.filter {it.state=="posted"};val projections=mutableListOf<CashProjection>()
    if(accepted.isEmpty()) projections.addAll(posted.map(::singleProjection))
    else {
        val components=ProjectionComponents(posted.map {it.id});accepted.forEach {components.union(it.primaryFactId,it.secondaryFactId)}
        val groups=components.groups();val groupByFact=groups.flatMapIndexed {index,group ->group.map {it to index}}.toMap()
        val byGroup=accepted.groupBy {groupByFact.getValue(it.primaryFactId)}
        for((index,group) in groups.withIndex()) {
            val selected=byGroup[index].orEmpty();observeGroup?.invoke(copyOf(group),copyOf(selected))
            projections.add(validateProjectionGroup(group,factById,selected))
        }
    }
    for(fact in facts.filter {it.state=="draft"}) projections.add(CashProjection("cash:${fact.id}",fact,fact.amount,null,true,null,null,
        listOf(fact.id),listOf(fact to listOf("root")),emptyList(),emptyList(),state="draft",statisticsEligible=false,attentionCount=1))
    val ordered=projections.sortedBy {it.projectionId}
    if(ordered.flatMap {it.memberIds}.toSet().size!=factById.size) throw CashProjectionDomainFailure("projection.incomplete")
    return CashProjectionBuild(copyOf(ordered))
}
