package com.finance.tracker.business.domain

data class RelationPlan<A>(val facts:List<RelationFact<A>>,val proposals:List<RelationProposal>,
    val contextDigest:String,val externalContextDigest:String="")

/** Stable source references and proposal identities, independent of preview identifiers. */
class CashRelationReferences<A>(private val core:CashRelationCoreRules<A>) {
    fun isHumanDecision(relation:Map<String,Any?>):Boolean {
        if(relation["created_by"]!="system") return true
        val decided=(relation["decided_by"] ?: "").toString()
        return decided.isNotEmpty() && decided!="system"
    }
    fun stableFactRef(factId:String?,factsById:Map<String,RelationFact<A>> = emptyMap()):String {
        val key=factId.orEmpty(); val fact=factsById[key]
        if(fact!=null && fact.recordId.isNotEmpty()) return core.stableFactReference(fact)
        return if(key.startsWith("preview:")) key.removePrefix("preview:") else key
    }
    fun proposalKey(proposal:RelationProposal,facts:List<RelationFact<A>> = emptyList()):String {
        val byId=facts.associateBy {it.id}
        val payload=linkedMapOf<String,Any?>(
            "kind" to proposal.kind,
            "primary" to stableFactRef(proposal.primaryFactId,byId),
            "secondary" to stableFactRef(proposal.secondaryFactId,byId),
            "anchor" to stableFactRef(proposal.effectiveAnchorFactId,byId),
            "rule_id" to proposal.ruleId,
            "candidates" to proposal.evidence.candidateFactIds.map {stableFactRef(it,byId)}.sortedWith(Comparator(::pythonStringOrder)))
        val encoded=sourceJson(payload,{null},::pythonFloatText).toString()
        return "proposal:"+sha256Hex(legacyReferenceUtf8(encoded)).take(32)
    }
}

val cashRelationReferences=CashRelationReferences(cashRelationCore)

/** Preserve the existing JVM UTF-8 replacement for invalid UTF-16, without relying on platform defaults. */
internal fun legacyReferenceUtf8(value:String):ByteArray=buildString {
    var index=0
    while(index<value.length) {
        val char=value[index++]
        when {
            char.isHighSurrogate() && index<value.length && value[index].isLowSurrogate() -> {
                append(char);append(value[index++])
            }
            char.isSurrogate() ->append('?')
            else ->append(char)
        }
    }
}.encodeToByteArray()
