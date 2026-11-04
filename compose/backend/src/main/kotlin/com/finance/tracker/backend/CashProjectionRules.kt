package com.finance.tracker.backend

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import java.util.List.copyOf

internal const val CASH_PROJECTION_RULES_VERSION=com.finance.tracker.business.domain.CASH_PROJECTION_RULES_VERSION
internal typealias CashProjectionFailure=com.finance.tracker.business.domain.CashProjectionFailure
internal typealias CashProjectionDomainFailure=com.finance.tracker.business.domain.CashProjectionDomainFailure
internal typealias CashEconomicType=com.finance.tracker.business.domain.CashEconomicType
internal typealias CashProjectionComposition=com.finance.tracker.business.domain.CashProjectionComposition
private fun invalidProjectionFact(): Nothing=throw CashProjectionDomainFailure("projection.invalid_fact")
private fun invalidProjectionRelation(): Nothing=throw CashProjectionDomainFailure("projection.invalid_relation")
private val projectionArithmetic=MathContext(80,RoundingMode.HALF_EVEN)
private fun CashAmount.number()=arithmeticValue()
private fun projectionAmount(value: BigDecimal): CashAmount=CashAmount.parseOrNull(value.toString()) ?: invalidProjectionRelation()
internal fun BigInteger.storedCashId(): Long = try {
    longValueExact().also { if (it <= 0) invalidProjectionFact() }
} catch (_: ArithmeticException) { invalidProjectionFact() }
internal fun BigInteger.storedRelationId(): Long = try {
    longValueExact().also { if (it <= 0) invalidProjectionRelation() }
} catch (_: ArithmeticException) { invalidProjectionRelation() }
internal fun CashProjection.storedMemberIds(): List<Long> = memberIds.map { it.storedCashId() }

@ConsistentCopyVisibility
internal data class CashProjectionFact private constructor(val id: BigInteger,val accountId: Long?,val occurredAt: Instant?,val amount: CashAmount?,
    val currency: String?,val counterparty: String,val categoryId: String?,val note: String,val sourceType: String?,val recordId: String,
    val fundingRelationId: Long?,val cashGranularity: String,val state: String,val createdAt: Instant?,val needsAttention: Boolean,
    val recordType: String?=null,val recordSubtype: String?=null,val stableSourceKey: List<String> = emptyList(),
    val recordOrigin: String = "legacy") {
    init {
        if(id.signum()<=0 || accountId!=null && accountId<=0 || state !in setOf("draft","posted") || cashGranularity !in setOf("atomic","aggregate") ||
            state=="posted" && (amount==null || currency==null || occurredAt==null) ||
            state=="draft" && (occurredAt==null && createdAt==null || fundingRelationId!=null) ||
            currency!=null && !currency.matches(Regex("[A-Z]{3}"))) invalidProjectionFact()
        for(value in listOfNotNull(occurredAt,createdAt)) try {pythonCashTimestamp(value)} catch (_: InvalidCashValue) {invalidProjectionFact()}
    }
    fun withAttention(value:Boolean):CashProjectionFact=if(value==needsAttention) this else copy(needsAttention=value)
    fun withPublicFields(counterparty:String,categoryId:String?,note:String):CashProjectionFact=
        copy(counterparty=counterparty,categoryId=categoryId,note=note)
    companion object {
        fun create(id: Long,accountId: Long?,occurredAt: Instant?,amount: String?,currency: String?,counterparty: String,categoryId: String?,
            note: String,sourceType: String?,recordId: String,fundingRelationId: Long?=null,cashGranularity: String="atomic",state: String="posted",
            createdAt: Instant?=null,needsAttention: Boolean=false,recordType: String?=null,recordSubtype: String?=null,
            stableSourceKey: List<String> = emptyList(),recordOrigin: String = "legacy"): CashProjectionFact = create(BigInteger.valueOf(id),accountId,occurredAt,
            amount,currency,counterparty,categoryId,note,sourceType,recordId,fundingRelationId,cashGranularity,state,createdAt,needsAttention,recordType,recordSubtype,
            stableSourceKey,recordOrigin)
        fun create(id: BigInteger,accountId: Long?,occurredAt: Instant?,amount: String?,currency: String?,counterparty: String,categoryId: String?,
            note: String,sourceType: String?,recordId: String,fundingRelationId: Long?=null,cashGranularity: String="atomic",state: String="posted",
            createdAt: Instant?=null,needsAttention: Boolean=false,recordType: String?=null,recordSubtype: String?=null,
            stableSourceKey: List<String> = emptyList(),recordOrigin: String = "legacy"): CashProjectionFact {
            val parsed=amount?.let {CashAmount.parseOrNull(it) ?: invalidProjectionFact()}
            val normalized=currency?.let {buildString {it.codePoints().forEach {code ->append(pythonUpperCase[code] ?: String(Character.toChars(code)))}}}
            return CashProjectionFact(id,accountId,occurredAt,parsed,normalized,counterparty,categoryId,note,sourceType,recordId,
                fundingRelationId,cashGranularity,state,createdAt,needsAttention,recordType,recordSubtype,stableSourceKey,recordOrigin)
        }
    }
}

@ConsistentCopyVisibility
internal data class CashProjectionRelation private constructor(val id: BigInteger,val kind: String,val primaryFactId: BigInteger,val secondaryFactId: BigInteger,
    val status: String,val appliedAmount: CashAmount?,val refundAllocationKey: Pair<String,String>?) {
    init {
        if(kind !in setOf("payment_mirror","refund_offset","transfer_pair") || id.signum()<=0 || primaryFactId.signum()<=0 || secondaryFactId.signum()<=0 ||
            refundAllocationKey!=null && (kind!="refund_offset" || refundAllocationKey.first.isEmpty() || refundAllocationKey.second.isEmpty()) ||
            appliedAmount!=null && appliedAmount.number().signum()<0) invalidProjectionRelation()
    }
    companion object {
        fun create(id: Any?,kind: String,primaryFactId: Long,secondaryFactId: Long,status: String="accepted",
            appliedAmount: String?=null,refundAllocationKey: Pair<String,String>?=null)=create(id,kind,BigInteger.valueOf(primaryFactId),
            BigInteger.valueOf(secondaryFactId),status,appliedAmount,refundAllocationKey)
        fun create(id: Any?,kind: String,primaryFactId: BigInteger,secondaryFactId: BigInteger,status: String="accepted",
            appliedAmount: String?=null,refundAllocationKey: Pair<String,String>?=null)=CashProjectionRelation(strictCashInteger(id) ?: invalidProjectionRelation(),kind,primaryFactId,secondaryFactId,
            status,appliedAmount?.let {CashAmount.parseOrNull(it) ?: invalidProjectionRelation()},refundAllocationKey)
    }
}

internal data class CashProjection(val projectionId: String,val primaryRecord: CashProjectionFact,val netAmount: CashAmount?,
    val economicType: CashEconomicType?,val visible: Boolean,val hiddenReason: String?,val businessLabel: String?,val memberIds: List<BigInteger>,
    val members: List<Pair<CashProjectionFact,List<String>>>,val relations: List<CashProjectionRelation>,val compositions: List<String>,
    val fundingRelationId: Long?=null,val state: String="posted",val statisticsEligible: Boolean=true,val attentionCount: Int=0,
    val economicRootIds: List<BigInteger> = emptyList()) {
    val occurredAt: Instant? get()=primaryRecord.occurredAt
    val sortAt: Instant get()=primaryRecord.occurredAt ?: primaryRecord.createdAt ?: invalidProjectionFact()
    val confirmationStatus: String get()=if(state=="draft" || attentionCount>0) "pending" else "confirmed"
}
internal data class CashProjectionBuild(val projections: List<CashProjection>,val rulesVersion: String=CASH_PROJECTION_RULES_VERSION) {
    val memberIds: Set<BigInteger> get()=java.util.Set.copyOf(projections.flatMap {it.memberIds})
}
private typealias ProjectionComponents=com.finance.tracker.business.domain.CashProjectionComponents<BigInteger>
private data class LogicalProjectionRelation(val relation: CashProjectionRelation,val primary: BigInteger,val secondary: BigInteger)
private data class ProjectionEdge(val kind: String,val primary: BigInteger,val secondary: BigInteger)
private fun rootedProjectionTree(nodes:List<BigInteger>,edges:List<Pair<BigInteger,BigInteger>>):BigInteger=
    com.finance.tracker.business.domain.cashProjectionRoot(nodes,edges)
private fun transferBusinessLabel(facts:List<CashProjectionFact>):String=
    com.finance.tracker.business.domain.cashTransferBusinessLabel(facts.map {it.recordType}.toSet(),facts.map {it.recordSubtype})
internal fun cashSourceOrderKey(sourceType:String?,recordId:String,bindings:List<List<String>> = emptyList(),
    recordOrigin:String="legacy",manualTransferId:String?=null,amount:BigDecimal?=null):List<String> =
    com.finance.tracker.business.domain.cashSourceOrderKey(sourceType,recordId,bindings,recordOrigin,manualTransferId,amount?.signum())
private fun sourceIdentityKey(fact: CashProjectionFact): List<String> {
    if (fact.stableSourceKey.isNotEmpty()) return fact.stableSourceKey
    val source=fact.sourceType.orEmpty()
    if (fact.recordOrigin in setOf("legacy","manual") && fact.recordId.isNotEmpty() && !fact.recordId.startsWith("import-v1:"))
        return listOf(source.ifEmpty {"manual"},"legacy_source_record",fact.recordId)
    invalidProjectionFact()
}
private fun representativeKey(fact: CashProjectionFact): List<Any?> {
    val source=fact.sourceType.orEmpty().lowercase()
    val rank=if (source in setOf("alipay","wechat","weixin","支付宝","微信")) 0
        else if (source in setOf("ccb_debit","ccb_credit","icbc_debit","icbc_credit","icbc_asia","bank","debit","credit")) 1 else 2
    return listOf(rank,fact.occurredAt,sourceIdentityKey(fact))
}
internal fun validateProjectionGroup(group: List<BigInteger>,facts: Map<BigInteger,CashProjectionFact>,relations: List<CashProjectionRelation>): CashProjection {
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
        if(left.currency==right.currency && a.abs().compareTo(b.abs())!=0) invalidProjectionRelation()
    }
    val mirrorComponents=ProjectionComponents(group)
    mirrors.forEach {mirrorComponents.union(it.primaryFactId,it.secondaryFactId)}
    val canonical=mutableMapOf<BigInteger,BigInteger>()
    for(members in mirrorComponents.groups()) {
        if(members.size>1) {
            val identities=members.map {sourceIdentityKey(facts.getValue(it))}
            if(identities.toSet().size!=identities.size) invalidProjectionRelation()
        }
        val root=if(members.size==1) members.first() else members.minWithOrNull(compareBy<BigInteger> {sourceJson(representativeKey(facts.getValue(it))).toString()})
            ?: invalidProjectionRelation()
        members.forEach {canonical[it]=root}
    }
    if(group.size>1) group.forEach {sourceIdentityKey(facts.getValue(it))}
    val logical=mutableListOf<LogicalProjectionRelation>();val seen=mutableSetOf<ProjectionEdge>()
    for(original in contained) {
        if(original.kind=="payment_mirror") continue
        val left=canonical.getValue(original.primaryFactId);val right=canonical.getValue(original.secondaryFactId)
        if(left==right || !seen.add(ProjectionEdge(original.kind,left,right))) continue
        var relation=original
        if(relation.kind=="refund_offset") {
            val same=contained.filter {it.kind==original.kind && canonical[it.primaryFactId]==left && canonical[it.secondaryFactId]==right}
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
            val zero=refunds.all {facts.getValue(it.primary).amount!!.number().signum()==0 && facts.getValue(it.secondary).amount!!.number().signum()==0 && facts.getValue(it.primary).currency==facts.getValue(it.secondary).currency}
            if(root.amount!!.number().signum()>=0 && !zero) invalidProjectionRelation()
            var refunded=BigDecimal.ZERO
            for(item in refunds) {
                val expense=facts.getValue(item.primary);val refund=facts.getValue(item.secondary)
                if(!zero && (expense.amount!!.number().signum()>=0 || refund.amount!!.number().signum()<=0 || expense.currency!=refund.currency)) invalidProjectionRelation()
                val applied=item.relation.appliedAmount?.number()
                if(applied!=null && applied.compareTo(refund.amount!!.number())!=0) invalidProjectionRelation()
                refunded=refunded.add(refund.amount!!.number(),projectionArithmetic)
            }
            if(refunded>root.amount.number().negate()) invalidProjectionRelation()
            amount=projectionAmount(root.amount.number().add(refunded,projectionArithmetic));type=CashEconomicType.EXPENSE;visible=true;hidden=null
            label=if(group.any {facts.getValue(it).recordType=="transfer_reversal"}) "p2p_return" else null
        }
        else -> {amount=base.netAmount!!;type=base.economicType!!;visible=base.visible;hidden=base.hiddenReason;label=base.businessLabel}
    }
    val members=group.sortedWith(compareBy<BigInteger> {if(it==rootId) 0 else 1}.thenBy {facts.getValue(it).occurredAt}.thenBy {it})
        .map {facts.getValue(it) to copyOf(roles.getValue(it).sorted())}
    return CashProjection("cash:$rootId",root,amount,type,visible,hidden,label,copyOf(members.map {it.first.id}),copyOf(members),copyOf(contained),
        copyOf(listOf("payment_mirror","transfer_pair","refund_offset").filter {kind ->contained.any {it.kind==kind}}),root.fundingRelationId,
        attentionCount=members.count {it.first.needsAttention}, economicRootIds=group.filter {canonical[it]==rootId}.sorted())
}
private fun singleProjection(fact: CashProjectionFact): CashProjection {
    val amount=fact.amount ?: invalidProjectionFact();val sign=amount.number().signum()
    val type=when {fact.fundingRelationId!=null || sign==0 ->CashEconomicType.INTERNAL_TRANSFER;sign<0 ->CashEconomicType.EXPENSE;else ->CashEconomicType.INCOME}
    val zero=sign==0 && fact.fundingRelationId==null
    return CashProjection("cash:${fact.id}",fact,if(fact.fundingRelationId!=null || zero) CashAmount.parse("0") else amount,type,!zero,
        if(zero) "balance_adjustment" else null,if(fact.fundingRelationId!=null) "bank_security_transfer" else if(zero) "balance_adjustment" else null,
        listOf(fact.id),listOf(fact to listOf("root")),emptyList(),emptyList(),fact.fundingRelationId,attentionCount=if(fact.needsAttention) 1 else 0,
        economicRootIds=listOf(fact.id))
}
internal fun buildCashProjections(facts: List<CashProjectionFact>,relations: List<CashProjectionRelation>,
    observeGroup: ((List<BigInteger>,List<CashProjectionRelation>)->Unit)?=null): CashProjectionBuild {
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
