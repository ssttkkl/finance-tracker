package com.finance.tracker.backend

import java.math.BigDecimal
import java.time.Instant

internal typealias RelationFact = com.finance.tracker.business.domain.RelationFact<BigDecimal>
internal object CashRelationCore {
    private val rules=com.finance.tracker.business.domain.CashRelationCoreRules<BigDecimal>({it.signum()},::stableTimestamp)
    val PAYMENT_PLATFORM_SOURCES get()=rules.PAYMENT_PLATFORM_SOURCES
    val BANK_CHANNEL_SOURCES get()=rules.BANK_CHANNEL_SOURCES
    fun stableTimestamp(value: Any?): String {
        val parsed: Instant? = when (value) {
            is Instant -> value
            is java.time.OffsetDateTime -> value.toInstant()
            is java.time.ZonedDateTime -> value.toInstant()
            null, "" -> null
            else -> runCatching { pythonCashTimestamp(value.toString().trim().replace("Z", "+00:00"), allowNaive = true) }
                .getOrNull()
        }
        return if (parsed != null) ParsedImportCodec.timeText(parsed)
        else value?.toString().orEmpty().trim().replace("Z", "+00:00")
    }

    fun isRefundIn(fact:RelationFact)=rules.isRefundIn(fact)
    fun isOriginalRefundExpense(fact:RelationFact)=rules.isOriginalRefundExpense(fact)
    fun isRefundExpenseCandidate(fact:RelationFact)=rules.isRefundExpenseCandidate(fact)
    fun isPaymentMirrorExpense(fact:RelationFact)=rules.isPaymentMirrorExpense(fact)
    fun isPaymentMirrorRefund(fact:RelationFact)=rules.isPaymentMirrorRefund(fact)
    fun isFxOutRecord(fact:RelationFact)=rules.isFxOutRecord(fact)
    fun isFxInRecord(fact:RelationFact)=rules.isFxInRecord(fact)
    fun isTransferOutRecord(fact:RelationFact)=rules.isTransferOutRecord(fact)
    fun isTransferInRecord(fact:RelationFact)=rules.isTransferInRecord(fact)
    fun compatiblePlatformScope(left:RelationFact,right:RelationFact)=rules.compatiblePlatformScope(left,right)
    fun stableFactReference(fact:RelationFact)=rules.stableFactReference(fact)
    fun stableFactOrderKey(fact:RelationFact)=rules.stableFactOrderKey(fact)
    fun relationOrderKey(fact:RelationFact)=rules.relationOrderKey(fact)
    fun orderedFactPair(factA:Any?,factB:Any?=null)=rules.orderedFactPair(factA,factB)
    fun relationBusinessKey(workspaceId:String,kind:String,factA:String,factB:String?)=rules.relationBusinessKey(workspaceId,kind,factA,factB)
    fun openLegBusinessKey(workspaceId:String,kind:String,anchorFactId:String)=rules.openLegBusinessKey(workspaceId,kind,anchorFactId)
    fun isOpenLegRelation(row:Map<String,Any?>?)=rules.isOpenLegRelation(row)
    fun topKCandidateIds(candidateIds:Collection<Any?>,k:Int=20,key:Comparator<String> = Comparator(::pythonStringOrder))=
        rules.topKCandidateIds(candidateIds,k,key)
    fun sourceGroup(fact:RelationFact)=rules.sourceGroup(fact)
}
internal fun cashRelationTextBlob(vararg parts:Any?):String=com.finance.tracker.business.domain.cashRelationTextBlob(*parts)
