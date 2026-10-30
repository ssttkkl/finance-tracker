package com.finance.tracker.backend

import java.math.BigDecimal

/** 缓存关系计划的解码、兼容校验与人工决定匹配；只读纯层，不访问数据库。 */
internal object CashRelationPlanCache {
    private fun reconfirm(): Nothing = throw IllegalArgumentException("import_relation_reconfirmation_required")
    private fun truthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is String -> value.isNotEmpty()
        is Number -> value.toDouble() != 0.0
        is Collection<*> -> value.isNotEmpty()
        is Map<*, *> -> value.isNotEmpty()
        else -> true
    }
    private fun text(value: Any?): String = value?.toString().orEmpty()
    private fun firstTruthy(vararg values: Any?): Any? = values.firstOrNull(::truthy)
    private fun pythonInt(value: Any?): Int = when (value) {
        is Boolean -> if (value) 1 else 0
        is Number, is String -> BigDecimal(value.toString().trim()).toInt()
        else -> reconfirm()
    }
    private fun values(value: Any?): List<Any?> = when (value) {
        null -> emptyList()
        is String -> value.map { it.toString() }
        is Iterable<*> -> value.toList()
        is Map<*, *> -> value.keys.toList()
        else -> emptyList()
    }
    private fun referenced(value: Any?, facts: List<RelationFact>): String {
        fun stable(fact: RelationFact): String = CashRelationCore.stableFactReference(fact)
        val byId = facts.associateBy { it.id }
        val entries = facts.filter { stable(it).isNotEmpty() }.associate { stable(it) to it.id }
        val raw = text(value).removePrefix("preview:")
        return when {
            raw in byId -> raw
            raw in entries -> entries.getValue(raw)
            else -> facts.firstOrNull { it.recordId == raw }?.id ?: raw
        }
    }

    fun factsByCachedReference(facts: List<RelationFact>): Map<String, RelationFact> {
        val byId = facts.associateBy { it.id }
        val result = linkedMapOf<String, RelationFact>()
        for (fact in facts) {
            val reference = CashRelationSerialization.stableFactRef(fact.id, byId)
            val existing = result[reference]
            if (existing != null && existing.id != fact.id) reconfirm()
            result[reference] = fact
            result.putIfAbsent(fact.id, fact)
        }
        return result
    }

    /** 把缓存的稳定引用还原为提案；任何缺失、越界或键不符都要求重新确认。 */
    fun decodeCachedProposals(facts: List<RelationFact>, cachedPlan: Map<String, Any?>): List<RelationProposal> {
        if (cachedPlan["version"] != 1 && cachedPlan["version"] != true) reconfirm()
        val items = cachedPlan["proposals"] as? List<*> ?: reconfirm()
        val byReference = factsByCachedReference(facts)
        fun resolve(value: Any?, required: Boolean = true): String? {
            val reference = text(value)
            if (reference.isEmpty()) {
                if (required) reconfirm()
                return null
            }
            return (byReference[reference] ?: reconfirm()).id
        }
        val proposals = mutableListOf<RelationProposal>()
        val validKinds = setOf("payment_mirror", "refund_offset", "transfer_pair")
        val validStatuses = setOf("pending_review", "accepted", "rejected", "superseded")
        for (raw in items) {
            val item = raw as? Map<*, *> ?: reconfirm()
            val evidence = item["evidence"] as? Map<*, *> ?: reconfirm()
            val candidateRefs = evidence["candidate_refs"] as? List<*> ?: reconfirm()
            val sourcePair = evidence["source_pair"] as? List<*> ?: reconfirm()
            val extras = evidence["extras"] as? Map<*, *> ?: reconfirm()
            if (sourcePair.size != 2) reconfirm()
            val kind = text(item["kind"])
            val status = text(item["status"])
            if (kind !in validKinds || status !in validStatuses) reconfirm()
            val timeDelta = evidence["time_delta_seconds"]
            val normalizedTimeDelta = if (timeDelta == null) null else pythonInt(timeDelta)
            val proposal = RelationProposal(
                kind = kind,
                primaryFactId = resolve(item["primary_ref"])!!,
                secondaryFactId = resolve(item["secondary_ref"], required = false),
                primaryFactType = text(item["primary_fact_type"]).ifEmpty { "cash" },
                secondaryFactType = text(item["secondary_fact_type"]).ifEmpty { null },
                subtype = text(item["subtype"]),
                status = status,
                ruleId = text(item["rule_id"]),
                confidence = text(item["confidence"]).ifEmpty { RELATION_CONFIDENCE_WEAK },
                evidence = RelationEvidence(
                    amountDelta = text(evidence["amount_delta"]).ifEmpty { "0" },
                    timeDeltaSeconds = normalizedTimeDelta,
                    sameCurrency = truthy(evidence["same_currency"]),
                    counterpartySimilarity = text(evidence["counterparty_similarity"]),
                    sourcePair = text(sourcePair[0]) to text(sourcePair[1]),
                    ruleId = text(evidence["rule_id"]),
                    candidateCount = pythonInt(evidence["candidate_count"]),
                    signals = values(evidence["signals"]).map { text(it) },
                    openLeg = truthy(evidence["open_leg"]),
                    anchorRole = text(evidence["anchor_role"]),
                    candidateFactIds = candidateRefs.map { resolve(it)!! },
                    extras = extras.entries.associate { it.key.toString() to it.value }),
                createdBy = text(item["created_by"]).ifEmpty { "system" },
                anchorFactId = resolve(item["anchor_ref"], required = false).orEmpty(),
                openLeg = truthy(item["open_leg"]))
            val expectedKey = text(item["proposal_key"])
            if (expectedKey.isEmpty() || CashRelationSerialization.proposalKey(proposal, facts) != expectedKey)
                reconfirm()
            proposals += proposal
        }
        return proposals
    }

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

    fun resolveFactReference(value: Any?, facts: List<RelationFact>): String = referenced(value, facts)

    fun decisionMirrorEndpoints(proposal: RelationProposal, decision: Map<String, Any?>,
        facts: List<RelationFact>): Pair<String, String>? {
        if (proposal.kind != "payment_mirror" || text(decision["status"]).ifEmpty { "accepted" } != "accepted" ||
            proposal.secondaryFactId.isNullOrEmpty()) return null
        val secondary = firstTruthy(decision["secondary_fact_id"], decision["secondary_record_ref"],
            decision["secondary_record_id"], proposal.secondaryFactId)
        return proposal.primaryFactId to referenced(secondary, facts)
    }

    fun decisionMatches(proposal: RelationProposal, decision: Map<String, Any?>, facts: List<RelationFact>): Boolean {
        val proposalKey = text(decision["proposal_key"])
        if (proposalKey.isNotEmpty() && proposalKey != CashRelationSerialization.proposalKey(proposal, facts)) return false
        val byId = facts.associateBy { it.id }
        fun stable(id: String): String = CashRelationSerialization.stableFactRef(id, byId)
        fun ref(value: Any?): String {
            val raw = text(value)
            if (raw in byId) return stable(raw)
            val normalized = raw.removePrefix("preview:")
            val resolved = facts.firstOrNull {
                it.recordId == raw || CashRelationCore.stableFactReference(it) == normalized
            } ?: return normalized
            return stable(resolved.id)
        }
        val primary = firstTruthy(decision["primary_fact_id"], decision["primary_record_ref"],
            decision["primary_record_id"]) ?: return false
        if (ref(primary) != stable(proposal.primaryFactId)) return false
        val secondary = firstTruthy(decision["secondary_fact_id"], decision["secondary_record_ref"],
            decision["secondary_record_id"]) ?: return true
        val candidates = setOf(stable(proposal.secondaryFactId.orEmpty())) +
            proposal.evidence.candidateFactIds.map(::stable).toSet()
        return ref(secondary) in candidates
    }

    fun decisionPrimaryMatches(proposal: RelationProposal, decision: Map<String, Any?>, facts: List<RelationFact>): Boolean {
        val proposalKey = text(decision["proposal_key"])
        if (proposalKey.isNotEmpty() && proposalKey != CashRelationSerialization.proposalKey(proposal, facts)) return false
        val primary = firstTruthy(decision["primary_fact_id"], decision["primary_record_ref"],
            decision["primary_record_id"]) ?: return false
        val resolved = referenced(primary, facts)
        val byId = facts.associateBy { it.id }
        return CashRelationSerialization.stableFactRef(resolved, byId) ==
            CashRelationSerialization.stableFactRef(proposal.primaryFactId, byId)
    }

    fun resolveSeeds(facts: List<RelationFact>, seedFactIds: List<Any?>?): List<String> {
        if (seedFactIds.isNullOrEmpty()) return facts.map { it.id }
        val byParent = linkedMapOf<String, MutableList<String>>()
        val componentIds = mutableSetOf<String>()
        for (fact in facts) {
            componentIds += fact.id
            fact.parentId?.takeIf { it.isNotEmpty() }?.let { byParent.getOrPut(it) { mutableListOf() } += fact.id }
        }
        val resolved = mutableListOf<String>()
        for (value in seedFactIds) {
            val key = text(value)
            val components = byParent[key]
            if (components != null) resolved += components else if (key in componentIds) resolved += key else resolved += key
        }
        return resolved.distinct()
    }

    /** 展开聚合流水的组成项为逐个匹配事实；单组成项保持父级稳定引用。 */
    fun expandComponentRows(rows: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val expanded = mutableListOf<Map<String, Any?>>()
        for (row in rows) {
            val components = (row["components"] as? List<*>)?.filterIsInstance<Map<*, *>>().orEmpty()
            if (components.isEmpty()) {
                expanded += LinkedHashMap(row)
                continue
            }
            for ((ordinal, component) in components.withIndex()) {
                val item = LinkedHashMap(row)
                item["parent_id"] = row["id"]
                item["component_ordinal"] = if (components.size > 1) {
                    if (component.containsKey("ordinal")) component["ordinal"] else ordinal
                } else null
                item["id"] = component["id"]
                item["amount"] = component["amount"]
                item["currency"] = firstTruthy(component["currency"], row["currency"])
                item["account_id"] = component["account_id"]
                item["account_name"] = text(component["account_name"])
                item["account_type"] = firstTruthy(component["account_type"], row["account_type"], "cash")
                item["cash_granularity"] = firstTruthy(row["cash_granularity"], "atomic")
                expanded += item
            }
        }
        return expanded
    }

    /** 数字卡尾/账号别名索引；只接受规范化后的 4 位卡尾与 4 位以上账号。 */
    fun aliasIndexes(aliases: List<Map<String, Any?>>): Pair<Map<String, List<String>>, Map<String, List<String>>> {
        val tails = linkedMapOf<String, MutableList<String>>()
        val identifiers = linkedMapOf<String, MutableList<String>>()
        fun digits(value: Any?): String = text(value).filter { it in '0'..'9' }
        for (alias in aliases) {
            val account = text(alias["account_id"])
            when (alias["alias_type"]) {
                "card_tail" -> digits(alias["alias_value"]).takeIf { it.length == 4 }
                    ?.let { tails.getOrPut(it) { mutableListOf() } += account }
                "account_identifier" -> digits(alias["alias_value"]).takeIf { it.length > 4 }
                    ?.let { identifiers.getOrPut(it) { mutableListOf() } += account }
            }
        }
        return tails.mapValues { it.value } to identifiers.mapValues { it.value }
    }

    /** 退款剩余额度；已接受退款按退款流水全额占用（与计划构建的 applied_amount 语义不同）。 */
    fun refundRemaining(facts: List<RelationFact>, relations: List<Map<String, Any?>>): Map<String, BigDecimal> {
        val remaining = linkedMapOf<String, BigDecimal>()
        for (fact in facts) if (fact.signedAmount.signum() < 0) remaining[fact.id] = fact.signedAmount.abs()
        val byId = facts.associateBy { it.id }
        for (relation in relations) {
            if (relation["kind"] != "refund_offset" || relation["status"] != "accepted") continue
            val primary = text(relation["primary_fact_id"])
            val refundId = text(relation["secondary_fact_id"])
            if (refundId.isEmpty()) continue
            val refund = byId[refundId] ?: continue
            if (primary in remaining) remaining[primary] = remaining.getValue(primary) - refund.signedAmount.abs()
        }
        return remaining
    }
}
