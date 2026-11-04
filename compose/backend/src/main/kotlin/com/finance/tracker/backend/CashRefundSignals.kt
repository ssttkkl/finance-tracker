package com.finance.tracker.backend

internal const val RELATION_CONFIDENCE_STRONG=com.finance.tracker.business.domain.RELATION_CONFIDENCE_STRONG
internal const val RELATION_CONFIDENCE_WEAK=com.finance.tracker.business.domain.RELATION_CONFIDENCE_WEAK
internal const val RELATION_RULE_REFUND_DIAMOND_V1=com.finance.tracker.business.domain.RELATION_RULE_REFUND_DIAMOND_V1
internal typealias RelationEvidence=com.finance.tracker.business.domain.RelationEvidence
internal typealias RelationProposal=com.finance.tracker.business.domain.RelationProposal

internal object CashRefundSignals {
    fun isPlatformImportRefundSource(fact:RelationFact)=com.finance.tracker.business.domain.CashRefundSignals.isPlatformImportRefundSource(fact)
    fun hasRefundSignal(text:String?)=com.finance.tracker.business.domain.CashRefundSignals.hasRefundSignal(text)
    fun hasRefundSignalForFact(fact:RelationFact)=com.finance.tracker.business.domain.CashRefundSignals.hasRefundSignalForFact(fact,CashRelationCore.sharedRules)
    fun isP2pTransferFamily(text:String?)=com.finance.tracker.business.domain.CashRefundSignals.isP2pTransferFamily(text)
    fun isP2pStyleRefund(text:String?)=com.finance.tracker.business.domain.CashRefundSignals.isP2pStyleRefund(text)
    fun p2pSubtype(text:String?)=com.finance.tracker.business.domain.CashRefundSignals.p2pSubtype(text)
    fun isRefundExcludedLeg(text:String?)=com.finance.tracker.business.domain.CashRefundSignals.isRefundExcludedLeg(text)
    fun stripRefundDescriptionPrefix(description:String?)=com.finance.tracker.business.domain.CashRefundSignals.stripRefundDescriptionPrefix(description)
    fun refundTitleExactMatch(refund:RelationFact,expense:RelationFact)=com.finance.tracker.business.domain.CashRefundSignals.refundTitleExactMatch(refund,expense)
}
internal object CashRefundDiamond {
    private val rules=com.finance.tracker.business.domain.CashRefundDiamondRules(CashRelationCore.sharedRules,
        {expense,refund ->expense.abs()+java.math.BigDecimal("0.0001")>=refund.abs()},
        {it.abs().toPlainString()},CashRelationGeometry::timeDeltaSeconds)
    val sharedRules get()=rules
    fun match(facts:List<RelationFact>,acceptedMirrors:List<Pair<String,String>> = emptyList(),
        acceptedPlatformRefunds:List<Pair<String,String>> = emptyList(),openOrPendingBankRefundIds:List<String>?=null)=
        rules.match(facts,acceptedMirrors,acceptedPlatformRefunds,openOrPendingBankRefundIds)
}
