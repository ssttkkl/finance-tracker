package com.finance.tracker.backend

import java.math.BigDecimal
import java.math.BigInteger
import java.security.MessageDigest
import java.time.Instant

internal data class RelationPlan(val facts: List<RelationFact>, val proposals: List<RelationProposal>,
    val contextDigest: String, val externalContextDigest: String = "")

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
    fun isHumanDecision(relation: Map<String, Any?>): Boolean {
        if (relation["created_by"] != "system") return true
        val decided = (relation["decided_by"] ?: "").toString()
        return decided.isNotEmpty() && decided != "system"
    }
    fun stableFactRef(factId: String?, factsById: Map<String, RelationFact> = emptyMap()): String {
        val key = factId.orEmpty()
        val fact = factsById[key]
        if (fact != null && fact.recordId.isNotEmpty()) return CashRelationCore.stableFactReference(fact)
        return if (key.startsWith("preview:")) key.removePrefix("preview:") else key
    }
    fun canonicalOccurredAt(value: Any?): String {
        val text = value?.toString().orEmpty()
        if (text.isEmpty()) return text
        return runCatching { ParsedImportCodec.timeText(CashRelationGeometry.parseDt(value)) }.getOrElse { text }
    }
    fun proposalKey(proposal: RelationProposal, facts: List<RelationFact> = emptyList()): String {
        val byId = facts.associateBy { it.id }
        val payload = linkedMapOf<String, Any?>(
            "kind" to proposal.kind, "subtype" to proposal.subtype,
            "primary" to stableFactRef(proposal.primaryFactId, byId),
            "secondary" to stableFactRef(proposal.secondaryFactId, byId),
            "anchor" to stableFactRef(proposal.effectiveAnchorFactId, byId),
            "rule_id" to proposal.ruleId,
            "candidates" to proposal.evidence.candidateFactIds.map { stableFactRef(it, byId) }.sortedWith(cashRelationStringOrder))
        val encoded = sourceJson(payload).toString()
        return "proposal:" + MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(32)
    }
    fun factDetailRow(fact: RelationFact): Map<String, Any?> = linkedMapOf(
        "id" to fact.id, "record_id" to fact.recordId, "amount" to fact.amount, "currency" to fact.currency,
        "occurred_at" to fact.occurredAt, "counterparty" to fact.counterparty,
        "counterparty_account" to fact.counterpartyAccount, "counterparty_account_attrs" to fact.counterpartyAccountAttrs,
        "payment_method" to fact.paymentMethod, "note" to fact.note, "record_type" to fact.recordType,
        "record_subtype" to fact.recordSubtype, "account_id" to fact.accountId, "account_name" to fact.accountName,
        "account_type" to fact.accountType, "bill_source" to fact.billSource, "source_type" to fact.billSource,
        "source" to fact.source, "fact_type" to fact.factType, "raw_payload" to fact.rawPayload,
        "relation_metadata" to fact.relationMetadata, "parent_id" to fact.parentId)
    fun initialRemaining(facts: List<RelationFact>, relations: List<Map<String, Any?>>): MutableMap<String, BigDecimal> {
        val remaining = linkedMapOf<String, BigDecimal>()
        for (fact in facts) if (fact.signedAmount.signum() < 0) remaining[fact.id] = fact.signedAmount.abs()
        val byId = facts.associateBy { it.id }
        for (relation in relations) {
            if (relation["kind"] != "refund_offset" || relation["status"] != "accepted") continue
            val primary = relation["primary_fact_id"]?.toString().orEmpty()
            val secondary = relation["secondary_fact_id"]?.toString().orEmpty()
            val refund = byId[secondary] ?: continue
            if (primary !in remaining) continue
            val applied = relation["applied_amount"]
            val appliedAmount = if (applied == null || applied.toString().isEmpty()) refund.signedAmount.abs()
            else runCatching { BigDecimal(applied.toString()).abs() }.getOrElse { refund.signedAmount.abs() }
            remaining[primary] = remaining.getValue(primary) - appliedAmount
        }
        return remaining
    }
    fun cacheJsonValue(value: Any?): Any? = when (value) {
        null, is String, is Boolean, is Int, is Long, is BigInteger -> value
        is BigDecimal -> value.toPlainString()
        is Double -> value
        is Float -> value
        is Instant -> canonicalOccurredAt(value)
        is java.time.OffsetDateTime -> canonicalOccurredAt(value)
        is Map<*, *> -> value.entries.associate { it.key.toString() to cacheJsonValue(it.value) }
        is Set<*> -> value.map(::cacheJsonValue).sortedWith(Comparator { left, right ->
            cashRelationStringOrder.compare(left.toString(), right.toString()) })
        is List<*> -> value.map(::cacheJsonValue)
        else -> value.toString()
    }
    fun serialize(plan: RelationPlan): Map<String, Any?> {
        if (plan.contextDigest.isEmpty() || plan.externalContextDigest.isEmpty())
            throw IllegalArgumentException("import_relation_reconfirmation_required")
        val byId = plan.facts.associateBy { it.id }
        fun ref(value: String?): String = stableFactRef(value.orEmpty(), byId)
        val proposals = plan.proposals.sortedWith(Comparator { left, right ->
            cashRelationStringOrder.compare(proposalKey(left, plan.facts), proposalKey(right, plan.facts))
        }).map { proposal ->
            val evidence = proposal.evidence
            linkedMapOf<String, Any?>(
                "proposal_key" to proposalKey(proposal, plan.facts), "kind" to proposal.kind,
                "subtype" to proposal.subtype, "status" to proposal.status, "rule_id" to proposal.ruleId,
                "confidence" to proposal.confidence, "primary_ref" to ref(proposal.primaryFactId),
                "secondary_ref" to ref(proposal.secondaryFactId), "anchor_ref" to ref(proposal.effectiveAnchorFactId),
                "primary_fact_type" to proposal.primaryFactType, "secondary_fact_type" to proposal.secondaryFactType,
                "created_by" to proposal.createdBy, "open_leg" to proposal.isOpenLeg,
                "evidence" to linkedMapOf<String, Any?>(
                    "amount_delta" to evidence.amountDelta, "time_delta_seconds" to evidence.timeDeltaSeconds,
                    "same_currency" to evidence.sameCurrency, "counterparty_similarity" to evidence.counterpartySimilarity,
                    "source_pair" to evidence.sourcePair.toList(), "rule_id" to evidence.ruleId,
                    "candidate_count" to evidence.candidateCount, "signals" to evidence.signals,
                    "open_leg" to evidence.openLeg, "anchor_role" to evidence.anchorRole,
                    "candidate_refs" to evidence.candidateFactIds.map(::ref),
                    "extras" to cacheJsonValue(evidence.extras)))
        }
        return linkedMapOf("version" to 1, "plan_digest" to plan.contextDigest,
            "external_context_digest" to plan.externalContextDigest, "proposals" to proposals)
    }
}
