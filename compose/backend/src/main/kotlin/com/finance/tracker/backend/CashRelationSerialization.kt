package com.finance.tracker.backend

import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant

internal typealias RelationPlan = com.finance.tracker.business.domain.RelationPlan<BigDecimal>

/** 关系计划的稳定引用、提案键、摘要和内部序列化；不暴露给客户端。 */
internal object CashRelationSerialization {
    fun factViewFromRow(row: Map<String, Any?>): RelationFact =
        com.finance.tracker.business.domain.cashRelationFactFromRow(row, { BigDecimal(it.toString()) },
            ::pythonTruthyRelation, { it.toString().toIntOrNull() })
    private fun pythonTruthyRelation(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is String -> value.isNotEmpty()
        is Number -> value.toDouble() != 0.0
        is Collection<*> -> value.isNotEmpty()
        is Map<*, *> -> value.isNotEmpty()
        else -> true
    }
    private fun nativeCanonicalOccurredAt(value: Any?): String {
        val text = value?.toString().orEmpty()
        if (text.isEmpty()) return text
        return runCatching { ParsedImportCodec.timeText(CashRelationGeometry.parseDt(value)) }.getOrElse { text }
    }
    private fun nativeCacheScalar(value:Any?):Any?=when(value) {
        null,is String,is Boolean,is Int,is Long,is BigInteger ->value
        is BigDecimal ->value.toPlainString()
        is Double ->value
        is Float ->value
        is Instant,is java.time.OffsetDateTime ->nativeCanonicalOccurredAt(value)
        else ->value.toString()
    }
    private val rules=com.finance.tracker.business.domain.CashRelationSerializationRules(CashRelationCore.sharedRules,
        {value ->BigDecimal(value.toString())},{value:BigDecimal ->value.abs()},
        {a,b ->a.subtract(b)},::nativeCanonicalOccurredAt,::nativeCacheScalar)
    val sharedRules get()=rules
    fun isHumanDecision(relation:Map<String,Any?>)=rules.isHumanDecision(relation)
    fun stableFactRef(factId:String?,factsById:Map<String,RelationFact> = emptyMap())=rules.stableFactRef(factId,factsById)
    fun proposalKey(proposal:RelationProposal,facts:List<RelationFact> = emptyList())=rules.proposalKey(proposal,facts)
    fun canonicalOccurredAt(value:Any?)=rules.canonicalOccurredAt(value)
    fun factDetailRow(fact:RelationFact)=rules.factDetailRow(fact)
    fun initialRemaining(facts:List<RelationFact>,relations:List<Map<String,Any?>>)=rules.initialRemaining(facts,relations)
    fun cacheJsonValue(value:Any?)=rules.cacheJsonValue(value)
    fun serialize(plan:RelationPlan)=rules.serialize(plan)
}
