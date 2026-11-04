package com.finance.tracker.backend

import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant

internal const val CASH_PROJECTION_RULES_VERSION=com.finance.tracker.business.domain.CASH_PROJECTION_RULES_VERSION
internal typealias CashProjectionFailure=com.finance.tracker.business.domain.CashProjectionFailure
internal typealias CashProjectionDomainFailure=com.finance.tracker.business.domain.CashProjectionDomainFailure
internal typealias CashEconomicType=com.finance.tracker.business.domain.CashEconomicType
internal typealias CashProjectionComposition=com.finance.tracker.business.domain.CashProjectionComposition
private fun invalidProjectionFact(): Nothing=throw CashProjectionDomainFailure("projection.invalid_fact")
private fun invalidProjectionRelation(): Nothing=throw CashProjectionDomainFailure("projection.invalid_relation")
internal fun BigInteger.storedCashId(): Long = try {
    longValueExact().also { if (it <= 0) invalidProjectionFact() }
} catch (_: ArithmeticException) { invalidProjectionFact() }
internal fun BigInteger.storedRelationId(): Long = try {
    longValueExact().also { if (it <= 0) invalidProjectionRelation() }
} catch (_: ArithmeticException) { invalidProjectionRelation() }
internal fun CashProjection.storedMemberIds(): List<Long> = memberIds.map { it.storedCashId() }

internal typealias CashProjectionFact=com.finance.tracker.business.domain.CashProjectionFact<BigInteger,Instant>
internal typealias CashProjectionRelation=com.finance.tracker.business.domain.CashProjectionRelation<BigInteger>
internal typealias CashProjection=com.finance.tracker.business.domain.CashProjection<BigInteger,Instant>
internal typealias CashProjectionBuild=com.finance.tracker.business.domain.CashProjectionBuild<BigInteger,Instant>
internal val cashProjectionValues=com.finance.tracker.business.domain.CashProjectionValues<BigInteger,Instant>({it.signum()>0},
    {value ->try {pythonCashTimestamp(value);true} catch(_:InvalidCashValue) {false}},
    {currency ->buildString {currency.codePoints().forEach {code ->append(pythonUpperCase[code] ?: String(Character.toChars(code)))}}})
internal fun com.finance.tracker.business.domain.CashProjectionFact.Companion.create(id:Long,accountId:Long?,occurredAt:Instant?,amount:String?,currency:String?,counterparty:String,categoryId:String?,
    note:String,sourceType:String?,recordId:String,fundingRelationId:Long?=null,cashGranularity:String="atomic",state:String="posted",
    createdAt:Instant?=null,needsAttention:Boolean=false,recordType:String?=null,recordSubtype:String?=null,
    stableSourceKey:List<String> = emptyList(),recordOrigin:String="legacy"):CashProjectionFact=create(BigInteger.valueOf(id),accountId,occurredAt,
        amount,currency,counterparty,categoryId,note,sourceType,recordId,fundingRelationId,cashGranularity,state,createdAt,needsAttention,recordType,recordSubtype,stableSourceKey,recordOrigin)
internal fun com.finance.tracker.business.domain.CashProjectionFact.Companion.create(id:BigInteger,accountId:Long?,occurredAt:Instant?,amount:String?,currency:String?,counterparty:String,categoryId:String?,
    note:String,sourceType:String?,recordId:String,fundingRelationId:Long?=null,cashGranularity:String="atomic",state:String="posted",
    createdAt:Instant?=null,needsAttention:Boolean=false,recordType:String?=null,recordSubtype:String?=null,
    stableSourceKey:List<String> = emptyList(),recordOrigin:String="legacy"):CashProjectionFact=create(cashProjectionValues,id,accountId,occurredAt,
        amount,currency,counterparty,categoryId,note,sourceType,recordId,fundingRelationId,cashGranularity,state,createdAt,needsAttention,recordType,recordSubtype,stableSourceKey,recordOrigin)
internal fun com.finance.tracker.business.domain.CashProjectionRelation.Companion.create(id:Any?,kind:String,primaryFactId:Long,secondaryFactId:Long,status:String="accepted",
    appliedAmount:String?=null,refundAllocationKey:Pair<String,String>?=null):CashProjectionRelation=
    create(id,kind,BigInteger.valueOf(primaryFactId),BigInteger.valueOf(secondaryFactId),status,appliedAmount,refundAllocationKey)
internal fun com.finance.tracker.business.domain.CashProjectionRelation.Companion.create(id:Any?,kind:String,primaryFactId:BigInteger,secondaryFactId:BigInteger,status:String="accepted",
    appliedAmount:String?=null,refundAllocationKey:Pair<String,String>?=null):CashProjectionRelation=
    create(cashProjectionValues,strictCashInteger(id) ?: invalidProjectionRelation(),kind,primaryFactId,secondaryFactId,status,appliedAmount,refundAllocationKey)

internal fun cashSourceOrderKey(sourceType:String?,recordId:String,bindings:List<List<String>> = emptyList(),
    recordOrigin:String="legacy",manualTransferId:String?=null,amount:BigDecimal?=null):List<String> =
    com.finance.tracker.business.domain.cashSourceOrderKey(sourceType,recordId,bindings,recordOrigin,manualTransferId,amount?.signum())
private val projectionRules=com.finance.tracker.business.domain.CashProjectionRules(cashProjectionValues) {it.toString()}
internal fun validateProjectionGroup(group:List<BigInteger>,facts:Map<BigInteger,CashProjectionFact>,relations:List<CashProjectionRelation>):CashProjection=
    projectionRules.validateGroup(group,facts,relations)
internal fun buildCashProjections(facts:List<CashProjectionFact>,relations:List<CashProjectionRelation>,
    observeGroup:((List<BigInteger>,List<CashProjectionRelation>)->Unit)?=null):CashProjectionBuild=
    projectionRules.build(facts,relations,observeGroup)
internal val cashRelationGraphRules=com.finance.tracker.business.domain.CashRelationGraphRules(cashProjectionValues,projectionRules,
    ::strictCashInteger) {it.toBigInteger()}
