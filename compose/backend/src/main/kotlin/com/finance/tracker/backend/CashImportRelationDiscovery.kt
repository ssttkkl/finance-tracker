package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.ACCOUNT_ALIASES

/** 将导入预计现金部分交给既有关系管线；提议在完整组校验前只存在于内存。 */
internal class CashImportRelationDiscovery(private val context: CashSqlContext) {
    companion object {
        fun originalExpense(value: Map<String, Any?>): Boolean {
            val metadata = value["source_explanation"] as? Map<*, *> ?: emptyMap<String, Any?>()
            val amount = value["amount"] as? CashAmount ?: return false
            return value["source_type"] == "wechat" && value["record_type"] == "refund" &&
                amount.arithmeticValue().signum() < 0 && CashPlatformRefund.wechatIsRefundOriginExpense(
                (metadata["_wechat_direction"] ?: metadata["direction"]) as? String,
                (metadata["platform_status"] ?: metadata["status"]) as? String)
        }
    }

    fun matchingFacts(snapshot: CashImportPreviewSnapshot, accounts: Map<String, Map<String, Any?>>): List<RelationFact> =
        snapshot.components.values.map { part ->
            val parent = part.transactionId as String
            val value = snapshot.values.getValue(parent)
            val account = accounts.getValue(part.accountId as String)
            @Suppress("UNCHECKED_CAST") val metadata = CashRecordPolicy.copyValue(
                value["source_explanation"] ?: emptyMap<String, Any?>()) as MutableMap<String, Any?>
            if (originalExpense(value)) metadata["offset_role"] = "expense"
            val source = value["source_type"] as? String ?: ""
            val aggregate = value["cash_granularity"] == "aggregate"
            @Suppress("UNCHECKED_CAST") val parts = value["components"] as List<Map<String, Any?>>
            @Suppress("UNCHECKED_CAST") val attrs = value["counterparty_account_attrs"] as List<String>
            RelationFact(part.componentId as String, cashRelationDecimal(part.amount)!!.arithmeticValue(),
                part.currency as String, part.accountId, accountName = account["name"] as String,
                accountType = account["type"] as String, occurredAt = value["occurred_at"],
                counterparty = value["counterparty"] as String, counterpartyAccount = value["counterparty_account"] as String,
                counterpartyAccountAttrs = attrs, paymentMethod = metadata["payment_method"] as? String ?: "",
                note = cashSourceNote(source, value["source_payload"]), recordType = part.recordType as String,
                recordSubtype = part.recordSubtype as String, billSource = source, source = source,
                recordId = (value["matching_record_id"] ?: value["record_id"]) as String,
                sourceIdentity = value["source_scope_key"] as? String ?: "", rawPayload = metadata,
                relationMetadata = metadata, parentId = parent,
                componentOrdinal = if (aggregate) parts.single { it["component_id"] == part.componentId }["ordinal"].toString().toInt() else null,
                cashGranularity = value["cash_granularity"] as String)
        }

    fun discover(snapshot: CashImportPreviewSnapshot, seedIds: Set<String>, accounts: Map<String, Map<String, Any?>>): List<RelationProposal> {
        context.requireActive()
        val facts = matchingFacts(snapshot, accounts).filter { fact ->
            val value = snapshot.values.getValue(fact.parentId!!)
            (if ("record_origin" in value) value["record_origin"] else "legacy") in setOf("import", "legacy")
        }
        val eligible = facts.map { it.id }.toSet()
        val seeds = seedIds.intersect(eligible)
        if (seeds.isEmpty() || !CashRelationPipeline.needsRelationDiscovery(facts, seeds)) return emptyList()
        val accepted = snapshot.edges.values.filter {
            it.active == true && it.state == "confirmed" && it.primaryComponentId in eligible && it.secondaryComponentId in eligible
        }.map { edge -> linkedMapOf<String, Any?>(
            "id" to edge.id.toString(), "kind" to edge.kind, "subtype" to edge.subtype,
            "primary_fact_id" to edge.primaryComponentId, "secondary_fact_id" to edge.secondaryComponentId,
            "status" to "accepted", "applied_amount" to edge.appliedAmount?.let { cashRelationDecimal(it)!!.arithmeticValue() },
        ) }
        val tails = linkedMapOf<String, MutableList<String>>()
        val identifiers = linkedMapOf<String, MutableList<String>>()
        val table = ACCOUNT_ALIASES
        context.sql.select(table.ALIAS_TYPE, table.ALIAS_VALUE, table.ACCOUNT_ID).from(table)
            .where(table.WORKSPACE_ID.eq(context.workspace)).fetch().forEach { row ->
                val index = when (row[table.ALIAS_TYPE]) {
                    "card_tail" -> tails
                    "account_identifier" -> identifiers
                    else -> null
                }
                index?.getOrPut(row[table.ALIAS_VALUE]!!) { mutableListOf() }?.add(row[table.ACCOUNT_ID]!!.toString())
            }
        val proposals = CashRelationPlanning.planRelationProposals(facts, seedIds = seeds.sortedWith(cashRelationStringOrder),
            acceptedRelations = accepted, aliasesByTail = tails, accountIdentifiers = identifiers,
            workspaceId = context.workspace, requireUniqueMirrors = true).proposals
        val unique = linkedMapOf<List<String?>, RelationProposal>()
        proposals.forEach { unique.putIfAbsent(listOf(it.kind, it.subtype, it.primaryFactId, it.secondaryFactId), it) }
        return unique.values.toList()
    }
}
