package com.finance.tracker.backend

import java.math.BigDecimal

internal const val RELATION_RULE_PAYMENT_MIRROR_STRONG_V1 = com.finance.tracker.business.domain.RELATION_RULE_PAYMENT_MIRROR_STRONG_V1
internal const val RELATION_RULE_PAYMENT_MIRROR_SHORT_WINDOW_TEXT_V1 = com.finance.tracker.business.domain.RELATION_RULE_PAYMENT_MIRROR_SHORT_WINDOW_TEXT_V1
internal const val RELATION_RULE_PAYMENT_MIRROR_WEAK_V1 = com.finance.tracker.business.domain.RELATION_RULE_PAYMENT_MIRROR_WEAK_V1
internal const val RELATION_RULE_PAYMENT_MIRROR_BANK_DATE_ONLY_V1 = com.finance.tracker.business.domain.RELATION_RULE_PAYMENT_MIRROR_BANK_DATE_ONLY_V1
internal const val RELATION_RULE_PAYMENT_MIRROR_SAME_ACCOUNT_BIZ_DAY_V1 = com.finance.tracker.business.domain.RELATION_RULE_PAYMENT_MIRROR_SAME_ACCOUNT_BIZ_DAY_V1
internal const val RELATION_RULE_PAYMENT_MIRROR_REFUND_DUAL_SOURCE_V1 = com.finance.tracker.business.domain.RELATION_RULE_PAYMENT_MIRROR_REFUND_DUAL_SOURCE_V1

internal object CashMirrorMatch {
    private val rules=com.finance.tracker.business.domain.CashMirrorMatchRules<BigDecimal>(CashRelationCore.sharedRules,
        CashRelationGeometry.sharedRules,{it.stripTrailingZeros().toPlainString()}, {a,b ->a.compareTo(b)==0},
        {a,b ->a.subtract(b).abs().toPlainString()}, {CashRelationGeometry.parseDt(it).epochSecond},
        {a,b ->CashRelationGeometry.mainStyleCrossVerify(a,b)})
    val sharedRules get()=rules
    fun evaluate(seed:RelationFact,candidates:List<RelationFact>,aliasesByTail:Map<String,List<String>> = emptyMap(),
        accountIdentifiers:Map<String,List<String>> = emptyMap(),requireUniqueMirrors:Boolean=false)=
        rules.evaluate(seed,candidates,aliasesByTail,accountIdentifiers,requireUniqueMirrors)
    fun matchGreedy(facts:List<RelationFact>,aliasesByTail:Map<String,List<String>> = emptyMap(),
        accountIdentifiers:Map<String,List<String>> = emptyMap(),seedIds:Collection<String>?=null,
        index:CashFactCandidateIndex?=null,occupiedFactIds:Set<String> = emptySet(),
        blockedPairs:Set<Pair<String,String>> = emptySet(),requireUniqueMirrors:Boolean=false)=
        rules.matchGreedy(facts,aliasesByTail,accountIdentifiers,seedIds,index?.sharedIndex,occupiedFactIds,blockedPairs,requireUniqueMirrors)
}
