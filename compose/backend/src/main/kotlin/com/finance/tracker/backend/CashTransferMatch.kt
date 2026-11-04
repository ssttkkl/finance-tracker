package com.finance.tracker.backend

import java.math.BigDecimal

internal const val RELATION_RULE_TRANSFER_PAIR_STRONG_V1 = com.finance.tracker.business.domain.RELATION_RULE_TRANSFER_PAIR_STRONG_V1
internal const val RELATION_RULE_PERSONAL_FX_EXCHANGE_V1 = com.finance.tracker.business.domain.RELATION_RULE_PERSONAL_FX_EXCHANGE_V1
internal const val RELATION_RULE_INTERNAL_ACCOUNT_TRANSFER_V1 = com.finance.tracker.business.domain.RELATION_RULE_INTERNAL_ACCOUNT_TRANSFER_V1
internal const val RELATION_RULE_CROSS_BORDER_REMITTANCE_V1 = com.finance.tracker.business.domain.RELATION_RULE_CROSS_BORDER_REMITTANCE_V1

internal object CashTransferMatch {
    private val rules=com.finance.tracker.business.domain.CashTransferMatchRules<BigDecimal>(CashRelationCore.sharedRules,
        {a,b ->a.abs().compareTo(b.abs())==0}, {a,b ->a.abs().subtract(b.abs()).toPlainString()},
        CashRelationGeometry::timeDeltaSeconds,{CashFactCandidateIndex(it).sharedIndex})
    fun validateCounterpartyAccount(value:Any?,attrs:List<Any?>)=rules.validateCounterpartyAccount(value,attrs)
    fun accountTargets(value:String?,attrs:List<Any?>,accountIdentifiers:Map<String,List<String>>?,cardTails:Map<String,List<String>>?)=
        rules.accountTargets(value,attrs,accountIdentifiers,cardTails)
    fun evaluateTransferPair(seed:RelationFact,candidates:List<RelationFact>,accountIdentifiers:Map<String,List<String>>?=null,
        cardTails:Map<String,List<String>>?=null)=rules.evaluateTransferPair(seed,candidates,accountIdentifiers,cardTails)
    fun matchPersonalFxExchange(seed:RelationFact,candidates:List<RelationFact>)=rules.matchPersonalFxExchange(seed,candidates)
    fun matchNormalizedSubtypeTransfers(facts:List<RelationFact>,seedIds:Collection<String>?=null,index:CashFactCandidateIndex?=null,
        accountIdentifiers:Map<String,List<String>>?=null,cardTails:Map<String,List<String>>?=null)=
        rules.matchNormalizedSubtypeTransfers(facts,seedIds,index?.sharedIndex,accountIdentifiers,cardTails)
    fun matchTransferPairsPhaseC(facts:List<RelationFact>,seedIds:Collection<String>?=null,index:CashFactCandidateIndex?=null,
        accountIdentifiers:Map<String,List<String>>?=null,cardTails:Map<String,List<String>>?=null)=
        rules.matchTransferPairsPhaseC(facts,seedIds,index?.sharedIndex,accountIdentifiers,cardTails)
}
