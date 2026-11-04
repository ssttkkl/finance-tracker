package com.finance.tracker.backend

import java.math.BigDecimal

internal typealias ProjectionValidationContext=com.finance.tracker.business.domain.ProjectionValidationContext<BigDecimal,java.math.BigInteger,java.time.Instant>
internal data class ProjectionValidationSnapshot(val facts: List<CashProjectionFact>,
    val relations: List<CashProjectionRelation>, val componentToParent: List<Pair<String, Long>>)

/** 应用层关系提案规划与投影合法性校验；不持久化。 */
internal object CashRelationPlanning {
    private val fingerprints=com.finance.tracker.business.domain.CashRelationPlanFingerprints(CashRelationSerialization.sharedRules,
        {value:BigDecimal ->value.toPlainString()},{value ->sourceJson(value).toString()})
    private val validation=com.finance.tracker.business.domain.CashRelationValidationRules(CashRelationCore.sharedRules,
        CashRelationSerialization.sharedRules,cashProjectionValues,cashRelationGraphRules,{value:BigDecimal ->value.toPlainString()},
        CashRelationGeometry::parseDt,java.math.BigInteger::valueOf,{it.storedCashId()},
        {value ->CashRelationGeometry.asDecimal(value).toPlainString()},
        {value ->if(value is BigDecimal) value.toPlainString() else value.toString()},{value ->sourceJson(value).toString()})
    val sharedValidationRules get()=validation
    fun buildValidationContext(facts:List<RelationFact>):ProjectionValidationContext=validation.buildValidationContext(facts)
    fun localizedValidationInputs(context:ProjectionValidationContext,acceptedRelations:List<Map<String,Any?>>,companions:List<RelationProposal>,
        proposal:RelationProposal)=validation.localizedValidationInputs(context,acceptedRelations,companions,proposal)
    fun validationReason(facts:List<RelationFact>,acceptedRelations:List<Map<String,Any?>>,proposal:RelationProposal,
        companionProposals:List<RelationProposal> = emptyList(),validationContext:ProjectionValidationContext?=null):String?=
        validation.validationReason(facts,acceptedRelations,proposal,companionProposals,validationContext)
    fun filterIllegalProposals(facts:List<RelationFact>,activeRelations:List<Map<String,Any?>>,proposals:List<RelationProposal>,
        validationContext:ProjectionValidationContext?=null)=validation.filterIllegalProposals(facts,activeRelations,proposals,validationContext)

    fun relationContextDigest(facts:List<RelationFact>,relations:List<Map<String,Any?>>,proposals:List<RelationProposal>):String=
        fingerprints.relationContextDigest(facts,relations,proposals)
    fun externalContextDigest(facts:List<RelationFact>,relations:List<Map<String,Any?>>,aliasesByTail:Map<String,List<String>>,
        accountIdentifiers:Map<String,List<String>>):String=fingerprints.externalContextDigest(facts,relations,aliasesByTail,accountIdentifiers)

    private val rules=com.finance.tracker.business.domain.CashRelationPlanningRules(CashRelationCore.sharedRules,
        CashRelationSerialization.sharedRules,CashRefundHardKey.sharedRules,CashRelationPipeline.sharedRules,validation,fingerprints,
        {facts ->CashFactCandidateIndex(facts,CashRelationCore::sourceGroup).sharedIndex},CashRelationSerialization::factViewFromRow,
        {value:BigDecimal ->value.abs()},{a,b ->a.subtract(b)},{value ->BigDecimal(value.toString())},{it.signum()})
    fun enrichPlatformRefundRows(rows:List<Map<String,Any?>>)=rules.enrichPlatformRefundRows(rows)
    fun appendRelationEdge(context:MatchContext,relation:Map<String,Any?>)=rules.appendRelationEdge(context,relation)
    fun refundBlockedIds(facts:List<RelationFact>,relations:List<Map<String,Any?>>,remaining:Map<String,BigDecimal>)=
        rules.refundBlockedIds(facts,relations,remaining)
    fun planRelationProposals(facts:List<RelationFact>,detailedRows:List<Map<String,Any?>>?=null,seedIds:List<String>?=null,
        acceptedRelations:List<Map<String,Any?>> = emptyList(),aliasesByTail:Map<String,List<String>>?=null,
        accountIdentifiers:Map<String,List<String>>?=null,remainingByExpense:Map<String,BigDecimal>?=null,
        workspaceId:String="",requireUniqueMirrors:Boolean=false):RelationPlan=
        rules.planRelationProposals(facts,detailedRows,seedIds,acceptedRelations,aliasesByTail,accountIdentifiers,
            remainingByExpense,workspaceId,requireUniqueMirrors)
}
