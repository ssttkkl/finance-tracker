package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*

internal class LocalCashEntryRead(snapshot: LedgerSnapshotDto, private val renderer: LocalCashProjectionViews) {
    private val version = snapshot.ledgerSeq.toLong()
    private val facts = snapshot.facts
    private val entries = renderer.entries.associateBy { it.projectionId }
    private val views = renderer.build().associateBy { it.projectionId }
    private val records = facts.cashTransactions.associateBy { it.id }
    private val parts = facts.cashTransactionComponents.associateBy { it.id }
    private val components = facts.cashTransactionComponents.groupBy { it.cashTransactionId }.mapValues { (_, items) ->
        items.sortedWith(compareBy<LedgerCashComponentFactDto> { it.ordinal.toLong() }.thenBy { it.id.toLong() })
    }
    private val relations = facts.transactionRelations.associateBy { it.id }
    private val inactiveByParent = buildMap<String, MutableList<LedgerCashRelationFactDto>> {
        for (relation in facts.transactionRelations) if (relation.state != "confirmed" || !relation.active) {
            for (id in listOfNotNull(relation.primaryTransactionId, relation.secondaryTransactionId).distinct())
                getOrPut(id) { mutableListOf() }.add(relation)
        }
    }
    private val funding = facts.cashInvestmentFundingRelations.associateBy { it.id }
    private val pending = facts.cashTransactionIssues.map { it.transactionId }.toSet() +
        facts.transactionRelations.filter { it.active && it.state == "draft" }
            .flatMap { listOfNotNull(it.primaryTransactionId, it.secondaryTransactionId) }
    private val json = Json { encodeDefaults = true }

    fun currentGroup(projectionId: String): CashCurrentMembersDto {
        val entry = entries[projectionId] ?: throw CashProjectionDomainFailure("projection_not_found")
        val view = views.getValue(projectionId)
        if (!view.visible && view.attentionCount == 0L) throw CashProjectionDomainFailure("projection_not_found")
        return CashCurrentMembersDto(version, entry.projection.memberIds.map { id ->
            val record = records.getValue(id.toString())
            CashCurrentMemberDto(record.id, record.rowRevision.toLong(),
                if (record.state == "draft" || record.id in pending) "pending" else "confirmed")
        })
    }

    fun evidence(projectionId: String): JsonObject {
        val entry = entries[projectionId] ?: throw CashProjectionDomainFailure("not_found")
        val projection = entry.projection
        val ids = projection.memberIds.map { it.toString() }.toSet()
        val inactive = ids.flatMap { inactiveByParent[it].orEmpty() }.distinctBy { it.id }.sortedWith { first, second ->
            pythonStringOrder(status(first), status(second)).takeIf { it != 0 } ?: first.id.toLong().compareTo(second.id.toLong())
        }
        val evidenceRelations = projection.relations.map { relations.getValue(it.id.toString()) } + inactive
        val endpointParents = evidenceRelations.flatMap { listOfNotNull(it.primaryComponentId, it.secondaryComponentId) }
            .mapNotNull { parts[it]?.cashTransactionId }
        val hasSourceCategory = (ids + endpointParents).any { !records.getValue(it).categoryId.isNullOrEmpty() }
        return buildJsonObject {
            put("projection_version", version)
            val view = views.getValue(projectionId)
            put("projection", JsonObject(json.encodeToJsonElement(view).jsonObject + buildJsonObject {
                put("account", account(view.account)); put("category", if (hasSourceCategory) category(view.category) else JsonNull)
                put("transfer", view.transfer?.let { transfer -> JsonObject(json.encodeToJsonElement(transfer).jsonObject + buildJsonObject {
                    put("from_account", account(transfer.fromAccount)); put("to_account", account(transfer.toAccount))
                }) } ?: JsonNull)
            }))
            put("root_record", full(records.getValue(entry.transactionId)))
            put("members", JsonArray(projection.members.map { (member, roles) ->
                JsonObject(full(records.getValue(member.id.toString())) + ("roles" to JsonArray(roles.map(::JsonPrimitive))))
            }))
            put("accepted_relations", JsonArray(projection.relations.map { accepted ->
                val relation = relations.getValue(accepted.id.toString())
                JsonObject(relationDetails(relation) + buildJsonObject {
                    put("id", relation.id); put("kind", accepted.kind); put("rule_id", relation.ruleId)
                })
            }))
            put("inactive_relation_hints", JsonArray(inactive.map { relation ->
                JsonObject(relationDetails(relation) + buildJsonObject {
                    put("id", relation.id); put("kind", relation.kind); put("status", status(relation))
                })
            }))
            put("refund_timeline", JsonArray(projection.members.filter { "refund" in it.second }.map { (member, _) ->
                val record = records.getValue(member.id.toString())
                buildJsonObject {
                    put("record_id", record.recordId); put("occurred_at", timestamp(requireNotNull(record.occurredAt)))
                    put("amount", amount(record.amount)); put("currency", text(record.currency)); put("source_type", text(record.sourceType))
                }
            }))
            put("funding_relation", projection.fundingRelationId?.toString()?.let(funding::get)
                ?.takeIf { it.status == "accepted" && it.activeSlot == "active" }?.let { link ->
                    buildJsonObject {
                        put("id", link.id); put("investment_event_id", link.investmentEventId)
                        put("direction", link.direction); put("status", link.status); put("rule_id", link.ruleId)
                        put("evidence", safeFundingEvidence(link.evidence))
                    }
                } ?: JsonNull)
        }
    }

    private fun full(record: LedgerCashTransactionFactDto) = JsonObject(record(record, record.accountId) + buildJsonObject {
        put("cash_granularity", record.cashGranularity)
        put("components", JsonArray(components[record.id].orEmpty().map(::component)))
    })

    private fun record(row: LedgerCashTransactionFactDto, accountId: String?): JsonObject {
        val account = renderer.account(accountId)
        return buildJsonObject {
            put("id", row.id); put("occurred_at", row.occurredAt?.let { JsonPrimitive(timestamp(it)) } ?: JsonNull)
            put("account", account(account))
            put("account_name", account?.name ?: ""); put("account_id", account?.id?.let(::JsonPrimitive) ?: JsonNull)
            put("account_type", account?.type ?: "cash"); put("counterparty", row.counterparty)
            put("category", category(renderer.category(row.categoryId)))
            put("note", row.note); put("amount", amount(row.amount)); put("currency", text(row.currency))
            put("source_type", text(row.sourceType)); put("counterparty_account", row.counterpartyAccount)
            put("record_type", text(row.recordType)); put("record_subtype", text(row.recordSubtype))
            put("state", row.state); put("row_revision", row.rowRevision.toLong())
        }
    }

    private fun component(part: LedgerCashComponentFactDto): JsonObject {
        val account = renderer.account(part.accountId)
        return buildJsonObject {
            put("id", part.id); put("cash_transaction_id", part.cashTransactionId)
            put("account", account(account))
            put("account_id", account?.id?.let(::JsonPrimitive) ?: JsonNull); put("account_name", account?.name ?: "")
            put("account_type", text(account?.type)); put("amount", amount(part.amount)); put("currency", text(part.currency))
            put("ordinal", part.ordinal.toLong()); put("label", part.label)
        }
    }

    private fun endpoint(id: String?): JsonElement {
        val part = parts[id] ?: return JsonNull
        val parent = records[part.cashTransactionId] ?: error("cash_evidence_parent_missing")
        return JsonObject(record(parent, part.accountId) + buildJsonObject {
            put("component_id", part.id)
            put("component", buildJsonObject {
                put("id", part.id); put("amount", amount(part.amount)); put("currency", text(part.currency))
                put("account_id", part.accountId?.toLong()?.let(::JsonPrimitive) ?: JsonNull)
                put("label", part.label); put("ordinal", part.ordinal.toLong())
            })
            put("amount", amount(part.amount)); put("currency", text(part.currency)); put("account_type", text(renderer.account(part.accountId)?.type))
        })
    }

    private fun relationDetails(relation: LedgerCashRelationFactDto) = buildJsonObject {
        put("primary_fact_id", text(relation.primaryComponentId)); put("primary_component_id", text(relation.primaryComponentId))
        put("secondary_fact_id", text(relation.secondaryComponentId)); put("secondary_component_id", text(relation.secondaryComponentId))
        put("applied_amount", amount(relation.appliedAmount))
        put("primary_record", endpoint(relation.primaryComponentId)); put("secondary_record", endpoint(relation.secondaryComponentId))
    }

    private fun status(relation: LedgerCashRelationFactDto) = if (!relation.active) "superseded" else when (relation.state) {
        "draft" -> "pending_review"
        "confirmed" -> "accepted"
        "ignored" -> "rejected"
        else -> error("invalid relation state")
    }

    private fun safeFundingEvidence(value: JsonElement): JsonObject {
        val payload = value as? JsonObject ?: return JsonObject(emptyMap())
        return buildJsonObject {
            for ((key, range) in listOf("business_day_window" to 0L..7L, "candidate_count" to 1L..100_000L)) {
                val number = payload[key] as? JsonPrimitive
                if (number != null && !number.isString && number.longOrNull in range) put(key, number)
            }
            val rawKind = payload["cash_record_type"]
            check(rawKind == null || rawKind is JsonPrimitive) { "invalid funding evidence record type" }
            val kind = rawKind
            if (kind?.isString == true && kind.content in setOf("investment_in", "investment_out", "transfer_in", "transfer_out")) put("cash_record_type", kind)
            val keys = payload["match_keys"]
            if (keys == JsonArray(listOf("amount", "currency", "direction", "business_day").map(::JsonPrimitive)) ||
                keys == JsonArray(listOf("institution_name", "direction", "business_day").map(::JsonPrimitive))) put("match_keys", keys)
        }
    }

    private fun timestamp(value: String) = formatPythonCashTimestamp(parsePythonCashTimestamp(value))
    private fun account(value: AccountReferenceDto?): JsonElement = value?.let { buildJsonObject {
        put("id", it.id); put("name", it.name); put("type", it.type); put("active", it.active)
    } } ?: JsonNull
    private fun category(value: CashCategoryDto?): JsonElement = value?.let { buildJsonObject {
        put("id", it.id); put("name", it.name); put("path", json.encodeToJsonElement(it.path))
    } } ?: JsonNull
    private fun amount(value: String?): JsonElement = renderer.money(value)?.let(::JsonPrimitive) ?: JsonNull
    private fun text(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
}
