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

    fun validateCachedPlanLegality(facts:List<RelationFact>,acceptedRelations:List<Map<String,Any?>>,proposals:List<RelationProposal>,
        validationContext:ProjectionValidationContext?=null)=
        CashRelationPlanning.sharedValidationRules.validateCachedPlanLegality(facts,acceptedRelations,proposals,validationContext)
}
