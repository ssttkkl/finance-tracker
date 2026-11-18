package com.finance.tracker.business.domain

/** Canonical dependency digests for relation planning; these are not the user review conclusion fingerprint. */
class CashRelationPlanFingerprints<A>(private val serialization:CashRelationSerializationRules<A>,
    private val plainText:(A)->String,private val encode:(Any?)->String) {
    fun relationContextDigest(facts: List<RelationFact<A>>, relations: List<Map<String, Any?>>,
        proposals: List<RelationProposal>): String {
        val byId = facts.associateBy { it.id }
        fun ref(value: String?): String = serialization.stableFactRef(value, byId)
        val payload = linkedMapOf<String, Any?>(
            "facts" to facts.sortedBy { ref(it.id) }.map { fact ->
                linkedMapOf("id" to ref(fact.id), "amount" to plainText(fact.signedAmount),
                    "currency" to fact.currency.uppercase(), "account_id" to (fact.accountId ?: ""),
                    "occurred_at" to serialization.canonicalOccurredAt(fact.occurredAt),
                    "record_type" to fact.recordType, "record_subtype" to fact.recordSubtype,
                    "source" to fact.billSource.ifEmpty { fact.source }, "record_id" to fact.recordId,
                    "payload" to (fact.rawPayload ?: emptyMap<String, Any?>()),
                    "relation_metadata" to (fact.relationMetadata ?: emptyMap<String, Any?>())) },
            "relations" to relations.sortedWith(Comparator { left, right ->
                val a = listOf(left["kind"].toString(), ref(left["primary_fact_id"]?.toString()), ref(left["secondary_fact_id"]?.toString()))
                val b = listOf(right["kind"].toString(), ref(right["primary_fact_id"]?.toString()), ref(right["secondary_fact_id"]?.toString()))
                var result = 0
                for (index in a.indices) { result = cashRelationStringOrder.compare(a[index], b[index]); if (result != 0) break }
                result
            }).map { relation ->
                linkedMapOf("kind" to (relation["kind"] ?: ""),
                    "primary" to ref(relation["primary_fact_id"]?.toString()), "secondary" to ref(relation["secondary_fact_id"]?.toString()),
                    "status" to (relation["status"] ?: ""), "rule_id" to (relation["rule_id"] ?: ""),
                    "decided" to (relation["decided_by"]?.toString().orEmpty().isNotEmpty()))
            },
            "proposals" to proposals.sortedBy { serialization.proposalKey(it, facts) }.map { proposal ->
                linkedMapOf("key" to serialization.proposalKey(proposal, facts), "status" to proposal.status,
                    "confidence" to proposal.confidence, "open_leg" to proposal.isOpenLeg,
                    "signals" to proposal.evidence.signals, "evidence" to proposal.evidence.extras)
            },
            "rule_version" to "relation-plan.v1")
        return sha256Hex(legacyReferenceUtf8(encode(payload)))
    }

    fun externalContextDigest(facts: List<RelationFact<A>>, relations: List<Map<String, Any?>>,
        aliasesByTail: Map<String, List<String>>, accountIdentifiers: Map<String, List<String>>): String {
        val byId = facts.associateBy { it.id }
        fun ref(value: String?): String = serialization.stableFactRef(value, byId)
        val payload = linkedMapOf<String, Any?>(
            "facts" to facts.sortedBy { ref(it.id) }.map { fact -> linkedMapOf(
                "ref" to ref(fact.id), "amount" to plainText(fact.signedAmount), "currency" to fact.currency.uppercase(),
                "account_id" to fact.accountId, "account_name" to fact.accountName, "account_type" to fact.accountType,
                "occurred_at" to serialization.canonicalOccurredAt(fact.occurredAt), "counterparty" to fact.counterparty,
                "counterparty_account" to fact.counterpartyAccount, "counterparty_account_attrs" to fact.counterpartyAccountAttrs,
                "payment_method" to fact.paymentMethod, "note" to fact.note, "record_type" to fact.recordType,
                "record_subtype" to fact.recordSubtype, "source" to fact.billSource.ifEmpty { fact.source },
                "record_id" to fact.recordId, "source_identity" to fact.sourceIdentity,
                "stable_source_key" to fact.stableSourceKey,
                "raw_payload" to serialization.cacheJsonValue(fact.rawPayload ?: emptyMap<String, Any?>()),
                "relation_metadata" to serialization.cacheJsonValue(
                    fact.relationMetadata ?: emptyMap<String, Any?>())) },
            "relations" to relations.sortedWith(Comparator { left, right ->
                val a = listOf(left["kind"].toString(), ref(left["primary_fact_id"]?.toString()),
                    ref(left["secondary_fact_id"]?.toString()), left["id"].toString())
                val b = listOf(right["kind"].toString(), ref(right["primary_fact_id"]?.toString()),
                    ref(right["secondary_fact_id"]?.toString()), right["id"].toString())
                var result = 0
                for (index in a.indices) { result = cashRelationStringOrder.compare(a[index], b[index]); if (result != 0) break }
                result
            }).map { relation -> linkedMapOf("kind" to (relation["kind"] ?: ""),
                "primary" to ref(relation["primary_fact_id"]?.toString()), "secondary" to ref(relation["secondary_fact_id"]?.toString()),
                "anchor" to ref(relation["anchor_fact_id"]?.toString()), "status" to (relation["status"] ?: ""),
                "rule_id" to (relation["rule_id"] ?: ""), "created_by" to (relation["created_by"] ?: ""),
                "decided_by" to (relation["decided_by"] ?: ""),
                "candidate_refs" to (relation["candidate_fact_ids"] as? List<*>)?.map { ref(it?.toString()) }?.sortedWith(cashRelationStringOrder).orEmpty()) },
            "aliases" to linkedMapOf(
                "card_tails" to aliasesByTail.entries.sortedBy { it.key }.map { linkedMapOf("value" to it.key,
                    "account_ids" to it.value.sortedWith(cashRelationStringOrder)) },
                "account_identifiers" to accountIdentifiers.entries.sortedBy { it.key }.map { linkedMapOf("value" to it.key,
                    "account_ids" to it.value.sortedWith(cashRelationStringOrder)) }),
            "rule_version" to "relation-plan.v1")
        return sha256Hex(legacyReferenceUtf8(encode(payload)))
    }

}

/** Preserve the existing positive 63-bit virtual identifier and collision probing. */
fun cashRelationSnapshotId(reference:String,used:MutableSet<Long>):Long {
    val digest=sha256(legacyReferenceUtf8(reference))
    var candidate=digest[0].toLong() and 0x7f
    for(index in 1 until 8) candidate=(candidate shl 8) or (digest[index].toLong() and 0xff)
    if(candidate<=0) candidate=1
    while(candidate in used) candidate=if(candidate<Long.MAX_VALUE) candidate+1 else 1
    used+=candidate
    return candidate
}
