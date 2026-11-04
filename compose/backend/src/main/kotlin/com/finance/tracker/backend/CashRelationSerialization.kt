package com.finance.tracker.backend

import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant

internal typealias RelationPlan = com.finance.tracker.business.domain.RelationPlan<BigDecimal>

/** 关系计划的稳定引用、提案键、摘要和内部序列化；不暴露给客户端。 */
internal object CashRelationSerialization {
    fun factViewFromRow(row: Map<String, Any?>): RelationFact {
        val payload = (row["raw_payload"] as? Map<*, *>)
            ?: (row["source_payload"] as? Map<*, *>)
        @Suppress("UNCHECKED_CAST")
        fun anyMap(value: Map<*, *>?): Map<String, Any?>? = value?.entries?.associate { it.key.toString() to it.value }
        val relationMetadata = anyMap(row["relation_metadata"] as? Map<*, *>)
        fun firstTruthy(vararg keys: String): String {
            for (key in keys) row[key]?.toString()?.takeIf { it.isNotEmpty() }?.let { return it }
            return ""
        }
        val sourceType = firstTruthy("source_type", "bill_source", "source")
        val ordinalRaw = row["component_ordinal"] ?: row["ordinal"]
        val ordinal = ordinalRaw?.let { value -> val text = value.toString(); text.toIntOrNull() }
        val attrsRaw = row["counterparty_account_attrs"]
        val attrs = if (attrsRaw is List<*>) attrsRaw.map { it.toString() } else emptyList()
        var recordType = firstTruthy("record_type").ifEmpty { "other" }
        var recordSubtype = firstTruthy("record_subtype").ifEmpty { "not_applicable" }
        if (recordSubtype == "not_applicable" && recordType in setOf("transfer_in", "transfer_out", "fx_in", "fx_out",
                "repayment", "withdrawal_in", "withdrawal_out"))
            recordSubtype = defaultCashRecordSubtype(recordType)
        val deleted = pythonTruthyRelation(row["deleted"]) || pythonTruthyRelation(row["deleted_at"])
        return RelationFact(
            id = row.getValue("id").toString(),
            amount = BigDecimal(row.getValue("amount").toString()),
            currency = firstTruthy("currency").ifEmpty { "CNY" },
            accountId = row["account_id"]?.toString() ?: (row["account_name"] ?: "").toString(),
            accountName = (row["account_name"] ?: "").toString(),
            accountType = firstTruthy("account_type", "_record_type").ifEmpty { "cash" },
            occurredAt = row["occurred_at"] ?: row["date"] ?: "",
            counterparty = (row["counterparty"] ?: "").toString(),
            counterpartyAccount = (row["counterparty_account"] ?: "").toString(),
            counterpartyAccountAttrs = attrs,
            paymentMethod = (row["payment_method"] ?: "").toString(),
            note = (row["note"] ?: "").toString(),
            recordType = recordType,
            recordSubtype = recordSubtype,
            billSource = sourceType,
            source = sourceType,
            factType = firstTruthy("fact_type").ifEmpty { "cash" },
            deleted = deleted,
            sourceIdentity = (row["source_identity"] ?: "").toString(),
            recordId = (row["record_id"] ?: "").toString(),
            rawPayload = anyMap(payload),
            relationMetadata = relationMetadata,
            parentId = row["parent_id"]?.toString()?.takeIf { it.isNotEmpty() },
            componentOrdinal = ordinal,
            cashGranularity = firstTruthy("cash_granularity").ifEmpty { "atomic" },
            stableSourceKey = (row["stable_source_key"] as? List<*>)?.map { it.toString() }.orEmpty(),
            recordOrigin = firstTruthy("record_origin").ifEmpty { "legacy" },
        )
    }
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
    fun isHumanDecision(relation:Map<String,Any?>)=rules.isHumanDecision(relation)
    fun stableFactRef(factId:String?,factsById:Map<String,RelationFact> = emptyMap())=rules.stableFactRef(factId,factsById)
    fun proposalKey(proposal:RelationProposal,facts:List<RelationFact> = emptyList())=rules.proposalKey(proposal,facts)
    fun canonicalOccurredAt(value:Any?)=rules.canonicalOccurredAt(value)
    fun factDetailRow(fact:RelationFact)=rules.factDetailRow(fact)
    fun initialRemaining(facts:List<RelationFact>,relations:List<Map<String,Any?>>)=rules.initialRemaining(facts,relations)
    fun cacheJsonValue(value:Any?)=rules.cacheJsonValue(value)
    fun serialize(plan:RelationPlan)=rules.serialize(plan)
}
