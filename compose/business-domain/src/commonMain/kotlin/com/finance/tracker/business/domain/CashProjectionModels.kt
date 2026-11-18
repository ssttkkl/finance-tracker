package com.finance.tracker.business.domain

/** Platform value interpretation; business shape and amount checks stay in the shared factories. */
class CashProjectionValues<I:Comparable<I>,T:Comparable<T>>(internal val positiveId:(I)->Boolean,
    internal val validTime:(T)->Boolean,internal val normalizeCurrency:(String)->String)
private fun invalidProjectionFact():Nothing=throw CashProjectionDomainFailure("projection.invalid_fact")
private fun invalidProjectionRelation():Nothing=throw CashProjectionDomainFailure("projection.invalid_relation")

@ConsistentCopyVisibility
data class CashProjectionFact<I:Comparable<I>,T:Comparable<T>> private constructor(val id: I,val accountId: Long?,val occurredAt: T?,val amount: CashAmount?,
    val currency: String?,val counterparty: String,val categoryId: String?,val note: String,val sourceType: String?,val recordId: String,
    val fundingRelationId: Long?,val cashGranularity: String,val state: String,val createdAt: T?,val needsAttention: Boolean,
    val recordType: String?=null,val recordSubtype: String?=null,val stableSourceKey: List<String> = emptyList(),
    val recordOrigin: String = "legacy") {
    fun withAttention(value:Boolean):CashProjectionFact<I,T> =if(value==needsAttention) this else copy(needsAttention=value)
    fun withPublicFields(counterparty:String,categoryId:String?,note:String):CashProjectionFact<I,T> =
        copy(counterparty=counterparty,categoryId=categoryId,note=note)
    companion object {
        fun <I:Comparable<I>,T:Comparable<T>> create(values:CashProjectionValues<I,T>,
            id:I,accountId:Long?,occurredAt:T?,amount:String?,currency:String?,counterparty:String,categoryId:String?,
            note:String,sourceType:String?,recordId:String,fundingRelationId:Long?=null,cashGranularity:String="atomic",state:String="posted",
            createdAt:T?=null,needsAttention:Boolean=false,recordType:String?=null,recordSubtype:String?=null,
            stableSourceKey:List<String> = emptyList(),recordOrigin:String="legacy"):CashProjectionFact<I,T> {
            val parsed=amount?.let {CashAmount.parseOrNull(it) ?: invalidProjectionFact()}
            val normalized=currency?.let(values.normalizeCurrency)
            if(!values.positiveId(id) || accountId!=null && accountId<=0 || state !in setOf("draft","posted") || cashGranularity !in setOf("atomic","aggregate") ||
                state=="posted" && (parsed==null || normalized==null || occurredAt==null) ||
                state=="draft" && (occurredAt==null && createdAt==null || fundingRelationId!=null) ||
                normalized!=null && !normalized.matches(Regex("[A-Z]{3}"))) invalidProjectionFact()
            for(value in listOfNotNull(occurredAt,createdAt)) if(!values.validTime(value)) invalidProjectionFact()
            return CashProjectionFact(id,accountId,occurredAt,parsed,normalized,counterparty,categoryId,note,sourceType,recordId,
                fundingRelationId,cashGranularity,state,createdAt,needsAttention,recordType,recordSubtype,stableSourceKey,recordOrigin)
        }
    }
}

@ConsistentCopyVisibility
data class CashProjectionRelation<I:Comparable<I>> private constructor(val id:I,val kind:String,val primaryFactId:I,val secondaryFactId:I,
    val status:String,val appliedAmount:CashAmount?,val refundAllocationKey:Pair<String,String>?) {
    companion object {
        fun <I:Comparable<I>,T:Comparable<T>> create(values:CashProjectionValues<I,T>,id:I,kind:String,primaryFactId:I,secondaryFactId:I,
            status:String="accepted",appliedAmount:String?=null,refundAllocationKey:Pair<String,String>?=null):CashProjectionRelation<I> {
            val parsed=appliedAmount?.let {CashAmount.parseOrNull(it) ?: invalidProjectionRelation()}
            if(kind !in setOf("payment_mirror","refund_offset","transfer_pair") || !values.positiveId(id) ||
                !values.positiveId(primaryFactId) || !values.positiveId(secondaryFactId) ||
                refundAllocationKey!=null && (kind!="refund_offset" || refundAllocationKey.first.isEmpty() || refundAllocationKey.second.isEmpty()) ||
                parsed!=null && LedgerArithmeticValue.fromCashAmount(parsed).signum<0) invalidProjectionRelation()
            return CashProjectionRelation(id,kind,primaryFactId,secondaryFactId,status,parsed,refundAllocationKey)
        }
    }
}

data class CashProjection<I:Comparable<I>,T:Comparable<T>>(val projectionId: String,val primaryRecord: CashProjectionFact<I,T>,val netAmount: CashAmount?,
    val economicType: CashEconomicType?,val visible: Boolean,val hiddenReason: String?,val businessLabel: String?,val memberIds: List<I>,
    val members: List<Pair<CashProjectionFact<I,T>,List<String>>>,val relations: List<CashProjectionRelation<I>>,val compositions: List<String>,
    val fundingRelationId: Long?=null,val state: String="posted",val statisticsEligible: Boolean=true,val attentionCount: Int=0,
    val economicRootIds: List<I> = emptyList()) {
    val occurredAt: T? get()=primaryRecord.occurredAt
    val sortAt: T get()=primaryRecord.occurredAt ?: primaryRecord.createdAt ?: invalidProjectionFact()
    val confirmationStatus: String get()=if(state=="draft" || attentionCount>0) "pending" else "confirmed"
}
data class CashProjectionBuild<I:Comparable<I>,T:Comparable<T>>(val projections: List<CashProjection<I,T>>,val rulesVersion: String=CASH_PROJECTION_RULES_VERSION) {
    val memberIds: Set<I> get()=RelationSet(projections.flatMap {it.memberIds})
}
