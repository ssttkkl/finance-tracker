package com.finance.tracker.backend

import java.math.BigDecimal

internal typealias RelationEdge=com.finance.tracker.business.domain.RelationEdge
internal typealias MatchContext=com.finance.tracker.business.domain.MatchContext<BigDecimal>

/** 原生精确值适配；发现编排由共享规则执行。 */
internal object CashRelationPipeline {
    private val rules=com.finance.tracker.business.domain.CashRelationPipelineRules(CashRelationCore.sharedRules,
        CashRelationGeometry.sharedRules,CashMirrorMatch.sharedRules,CashTransferMatch.sharedRules,
        CashRefundDiamond.sharedRules,CashRefundMatch.sharedRules,
        {facts ->CashFactCandidateIndex(facts,CashRelationCore::sourceGroup).sharedIndex},
        {value:BigDecimal ->value.abs()},{a,b ->a.add(b)},{a,b ->a.subtract(b)},
        CashRelationGeometry::asDecimal,BigDecimal.ZERO)
    val sharedRules get()=rules
    fun needsRelationDiscovery(facts:List<RelationFact>,seedIds:Collection<String>?=null)=
        rules.needsRelationDiscovery(facts,seedIds)
    fun matchCanonicalPaymentMirrors(facts:List<RelationFact>,aliasesByTail:Map<String,List<String>> = emptyMap(),
        accountIdentifiers:Map<String,List<String>> = emptyMap(),occupiedFactIds:Set<String> = emptySet(),
        blockedPairs:Set<Pair<String,String>> = emptySet())=
        rules.matchCanonicalPaymentMirrors(facts,aliasesByTail,accountIdentifiers,occupiedFactIds,blockedPairs)
    fun demoteOverlappingPhaseARefunds(facts:List<RelationFact>,phaseA:List<RelationProposal>,
        acceptedRelations:List<Map<String,Any?>>,mirrorPairs:List<Pair<String,String>>)=
        rules.demoteOverlappingPhaseARefunds(facts,phaseA,acceptedRelations,mirrorPairs)
    fun shareRefundRemainingAcrossMirrorEvents(facts:List<RelationFact>,components:Map<String,Set<String>>,
        remainingByExpense:Map<String,BigDecimal>)=rules.shareRefundRemainingAcrossMirrorEvents(facts,components,remainingByExpense)
    fun bankRefundSeedIds(facts:List<RelationFact>,blocked:Set<String>)=rules.bankRefundSeedIds(facts,blocked)
    fun run(facts:List<RelationFact>,ctx:MatchContext=MatchContext(),seedIds:Collection<String>?=null,
        index:CashFactCandidateIndex?=null,aliasesByTail:Map<String,List<String>> = emptyMap(),
        accountIdentifiers:Map<String,List<String>> = emptyMap(),transferBlockedIds:Set<String> = emptySet(),
        refundBlockedIds:Set<String> = emptySet(),merchantRefundSeedIds:Collection<String>?=null,
        requireUniqueMirrors:Boolean=false)=rules.run(facts,ctx,seedIds,index?.sharedIndex,aliasesByTail,
            accountIdentifiers,transferBlockedIds,refundBlockedIds,merchantRefundSeedIds,requireUniqueMirrors)
}
