package com.finance.tracker.backend

import java.math.BigDecimal

internal object CashRelationPlanCache {
    private fun reconfirm():Nothing=throw IllegalArgumentException("import_relation_reconfirmation_required")
    private fun truthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is String -> value.isNotEmpty()
        is Number -> value.toDouble() != 0.0
        is Collection<*> -> value.isNotEmpty()
        is Map<*, *> -> value.isNotEmpty()
        else -> true
    }
    private fun pythonInt(value: Any?): Int = when (value) {
        is Boolean -> if (value) 1 else 0
        is Number, is String -> BigDecimal(value.toString().trim()).toInt()
        else -> reconfirm()
    }
    private val rules=com.finance.tracker.business.domain.CashRelationPlanCacheRules(CashRelationCore.sharedRules,
        CashRelationSerialization.sharedRules,{value:BigDecimal ->value.abs()},{a,b ->a.subtract(b)},::truthy,::pythonInt)
    fun factsByCachedReference(facts:List<RelationFact>)=rules.factsByCachedReference(facts)
    fun decodeCachedProposals(facts:List<RelationFact>,cachedPlan:Map<String,Any?>)=rules.decodeCachedProposals(facts,cachedPlan)
    fun resolveFactReference(value:Any?,facts:List<RelationFact>)=rules.resolveFactReference(value,facts)
    fun decisionMirrorEndpoints(proposal:RelationProposal,decision:Map<String,Any?>,facts:List<RelationFact>)=
        rules.decisionMirrorEndpoints(proposal,decision,facts)
    fun decisionMatches(proposal:RelationProposal,decision:Map<String,Any?>,facts:List<RelationFact>)=
        rules.decisionMatches(proposal,decision,facts)
    fun decisionPrimaryMatches(proposal:RelationProposal,decision:Map<String,Any?>,facts:List<RelationFact>)=
        rules.decisionPrimaryMatches(proposal,decision,facts)
    fun resolveSeeds(facts:List<RelationFact>,seedFactIds:List<Any?>?)=rules.resolveSeeds(facts,seedFactIds)
    fun expandComponentRows(rows:List<Map<String,Any?>>)=rules.expandComponentRows(rows)
    fun aliasIndexes(aliases:List<Map<String,Any?>>)=rules.aliasIndexes(aliases)
    fun refundRemaining(facts:List<RelationFact>,relations:List<Map<String,Any?>>)=rules.refundRemaining(facts,relations)

    /** 在写入前确认缓存的自动关系图仍满足投影不变量。 */
    fun validateCachedPlanLegality(facts: List<RelationFact>, acceptedRelations: List<Map<String, Any?>>,
        proposals: List<RelationProposal>, validationContext: ProjectionValidationContext? = null) {
        val context = validationContext ?: CashRelationPlanning.buildValidationContext(facts)
        val companions = proposals.filter { it.createdBy == "system" && it.status == "accepted" &&
            !it.secondaryFactId.isNullOrEmpty() && !it.isOpenLeg }
        val batchReason = companions.firstOrNull()?.let {
            CashRelationPlanning.validationReason(facts, acceptedRelations, it, companions, context)
        }
        for (proposal in proposals) {
            if (proposal.createdBy != "system" || proposal.secondaryFactId.isNullOrEmpty() || proposal.isOpenLeg) continue
            val reason = if (proposal.status == "accepted") batchReason else {
                val localized = CashRelationPlanning.localizedValidationInputs(context, acceptedRelations,
                    companions, proposal)
                if (localized == null) CashRelationPlanning.validationReason(facts, acceptedRelations,
                    proposal, companions + proposal, context)
                else CashRelationPlanning.validationReason(facts, localized.first, proposal,
                    localized.second + proposal, localized.third)
            }
            if (reason != null) reconfirm()
        }
    }

}
