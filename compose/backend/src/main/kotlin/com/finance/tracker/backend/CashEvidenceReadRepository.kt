package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.records.*
import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.sql.Connection
import kotlinx.serialization.json.*

internal class CashEvidenceReadRepository(connection: Connection, private val workspace: String, sqlite: Boolean) {
    private val sql = connection.jooq(sqlite)
    private data class Member(val record: CashTransactionsRecord, val account: AccountsRecord?, val roles: JsonElement)
    private data class Endpoint(val part: CashTransactionComponentsRecord, val parent: CashTransactionsRecord, val account: AccountsRecord?)

    fun read(projection: CashProjectionsRecord, version: Long, base: JsonObject, transfer: () -> JsonElement): JsonObject {
        val published = sql.selectFrom(CASH_PROJECTION_RELATIONS)
            .where(CASH_PROJECTION_RELATIONS.WORKSPACE_ID.eq(workspace))
            .and(CASH_PROJECTION_RELATIONS.DATASET_ID.eq(projection.datasetId))
            .and(CASH_PROJECTION_RELATIONS.PROJECTION_ROW_ID.eq(projection.id))
            .orderBy(CASH_PROJECTION_RELATIONS.ORDINAL).fetch()
        val m = CASH_PROJECTION_MEMBERS.`as`("member")
        val f = CASH_TRANSACTIONS.`as`("fact")
        val a = ACCOUNTS.`as`("account")
        val part = CASH_TRANSACTION_COMPONENTS.`as`("part")
        val pa = ACCOUNTS.`as`("part_account")
        val members = linkedMapOf<Long, Member>()
        val components = linkedMapOf<Long, MutableList<JsonObject>>()
        sql.select(*m.fields(), *f.fields(), *a.fields(), *part.fields(), *pa.fields()).from(m)
            .join(f).on(f.WORKSPACE_ID.eq(m.WORKSPACE_ID)).and(f.ID.eq(m.CASH_TRANSACTION_ID))
            .leftJoin(a).on(a.WORKSPACE_ID.eq(f.WORKSPACE_ID)).and(a.ID.eq(f.ACCOUNT_ID))
            .leftJoin(part).on(part.WORKSPACE_ID.eq(f.WORKSPACE_ID)).and(part.CASH_TRANSACTION_ID.eq(f.ID))
            .leftJoin(pa).on(pa.WORKSPACE_ID.eq(part.WORKSPACE_ID)).and(pa.ID.eq(part.ACCOUNT_ID))
            .where(m.WORKSPACE_ID.eq(workspace)).and(m.DATASET_ID.eq(projection.datasetId)).and(m.PROJECTION_ROW_ID.eq(projection.id))
            .orderBy(m.ORDINAL, part.ORDINAL, part.ID).fetch().forEach { row ->
                val fact = row.into(f)
                members.putIfAbsent(fact.id!!, Member(fact, row.into(a).takeIf { it.id != null }, row[m.ROLES_JSON]!!))
                if (row[part.ID] != null) components.getOrPut(fact.id!!) { mutableListOf() }
                    .add(component(row.into(part), row.into(pa).takeIf { it.id != null }))
            }
        val root = members[projection.rootCashTransactionId] ?: throw CashEvidenceNotFound()
        val r = TRANSACTION_RELATIONS
        val inactiveCondition = r.STATE.ne("confirmed").or(r.ACTIVE.isFalse)
            .and(r.PRIMARY_TRANSACTION_ID.`in`(members.keys).or(r.SECONDARY_TRANSACTION_ID.`in`(members.keys)))
        val allRelations = sql.selectFrom(r).where(r.WORKSPACE_ID.eq(workspace))
            .and(r.ID.`in`(published.map { it.transactionRelationId!! }).or(inactiveCondition)).fetch()
        val byId = allRelations.associateBy { it.id!! }
        val inactive = allRelations.filter { (it.state != "confirmed" || it.active == false) &&
            (it.primaryTransactionId in members || it.secondaryTransactionId in members) }
            .sortedWith(compareBy<TransactionRelationsRecord> { relationStatus(it) }.thenBy { it.id })
        val ids = allRelations.flatMap { listOfNotNull(it.primaryComponentId, it.secondaryComponentId) }.distinct().sorted()
        val endpoints = linkedMapOf<Long, Endpoint>()
        for (batch in ids.chunked(500)) {
            sql.select(*part.fields(), *f.fields(), *a.fields()).from(part)
                .join(f).on(f.WORKSPACE_ID.eq(part.WORKSPACE_ID)).and(f.ID.eq(part.CASH_TRANSACTION_ID))
                .leftJoin(a).on(a.WORKSPACE_ID.eq(part.WORKSPACE_ID)).and(a.ID.eq(part.ACCOUNT_ID))
                .where(part.WORKSPACE_ID.eq(workspace)).and(part.ID.`in`(batch)).fetch().forEach { row ->
                    endpoints[row[part.ID]!!] = Endpoint(row.into(part), row.into(f), row.into(a).takeIf { it.id != null })
                }
        }
        val categories = if (members.values.any { !it.record.categoryId.isNullOrEmpty() } || endpoints.values.any { !it.parent.categoryId.isNullOrEmpty() })
            sql.selectFrom(CASH_CATEGORIES).where(CASH_CATEGORIES.WORKSPACE_ID.eq(workspace)).fetch().associateBy { it.id!! }
            else emptyMap()
        fun full(member: Member): JsonObject = JsonObject(record(member.record, member.account, categories) + mapOf(
            "cash_granularity" to JsonPrimitive(member.record.cashGranularity!!),
            "components" to JsonArray(components[member.record.id].orEmpty()),
        ))
        fun endpoint(id: Long?): JsonElement = endpoints[id]?.let { endpoint ->
            val cash = endpoint.part
            JsonObject(record(endpoint.parent, endpoint.account, categories) + mapOf(
                "component_id" to JsonPrimitive(cash.id.toString()),
                "component" to buildJsonObject {
                    put("id", cash.id.toString()); put("amount", amount(cash.amount)); put("currency", text(cash.currency))
                    put("account_id", cash.accountId?.let(::JsonPrimitive) ?: JsonNull)
                    put("label", text(cash.label)); put("ordinal", cash.ordinal!!)
                },
                "amount" to amount(cash.amount), "currency" to text(cash.currency),
                "account_type" to text(endpoint.account?.type),
            ))
        } ?: JsonNull
        fun relationDetails(relation: TransactionRelationsRecord?, published: Boolean = false): Map<String, JsonElement> = buildJsonObject {
            val primary = relation?.primaryComponentId?.toString() ?: if (published && relation != null) "None" else null
            val secondary = relation?.secondaryComponentId?.toString()
            put("primary_fact_id", text(primary)); put("primary_component_id", text(primary))
            put("secondary_fact_id", text(secondary)); put("secondary_component_id", text(secondary))
            put("applied_amount", if (relation == null) JsonPrimitive("0") else amount(relation.appliedAmount))
            put("primary_record", endpoint(relation?.primaryComponentId)); put("secondary_record", endpoint(relation?.secondaryComponentId))
        }
        val projectionAccount = projection.accountId?.let { id ->
            sql.selectFrom(ACCOUNTS).where(ACCOUNTS.WORKSPACE_ID.eq(workspace)).and(ACCOUNTS.ID.eq(id)).fetchOne()
        }
        val kinds = published.map { it.kind!! }.distinct().sortedWith(cashRelationStringOrder)
        val summary = published.groupingBy { it.kind!! to it.subtype!! }.eachCount().entries.sortedWith { left, right ->
            cashRelationStringOrder.compare(left.key.first, right.key.first).takeIf { it != 0 }
                ?: cashRelationStringOrder.compare(left.key.second, right.key.second)
        }.map { (kind, count) -> buildJsonObject { put("kind", kind.first); put("subtype", kind.second); put("count", count) } }
        val projectionValue = JsonObject(base + mapOf(
            "account" to account(projectionAccount), "category" to category(categories, projection.categoryId),
            "source_types" to JsonArray(members.values.mapNotNull { it.record.sourceType?.takeIf(String::isNotEmpty) }.distinct().map(::JsonPrimitive)),
            "composition" to JsonArray(kinds.map(::JsonPrimitive)), "accepted_relation_summary" to JsonArray(summary),
            "transfer" to if ("transfer_pair" in kinds || projection.transferSubtype == "bank_security_transfer") transfer() else JsonNull,
        ))
        return buildJsonObject {
            put("projection_version", version); put("projection", projectionValue); put("root_record", full(root))
            put("members", JsonArray(members.values.map { JsonObject(full(it) + ("roles" to roles(it.roles))) }))
            put("accepted_relations", JsonArray(published.map { relation ->
                val accepted = byId[relation.transactionRelationId]
                JsonObject(relationDetails(accepted, published = true) + buildJsonObject {
                    put("id", relation.transactionRelationId.toString()); put("kind", relation.kind!!); put("subtype", relation.subtype!!)
                    put("rule_id", accepted?.ruleId ?: "")
                })
            }))
            put("inactive_relation_hints", JsonArray(inactive.map { relation ->
                JsonObject(relationDetails(relation) + buildJsonObject {
                    put("id", relation.id.toString()); put("kind", relation.kind!!); put("subtype", relation.subtype!!)
                    put("status", relationStatus(relation))
                })
            }))
            put("refund_timeline", JsonArray(members.values.filter {
                if (it.roles is JsonPrimitive && it.roles.isString) "refund" in it.roles.content
                else JsonPrimitive("refund") in roles(it.roles)
            }.map { member ->
                val cash = member.record
                buildJsonObject {
                    put("record_id", text(cash.recordId)); put("occurred_at", manualInstantText(requireNotNull(cash.occurredAt)))
                    put("amount", amount(cash.amount)); put("currency", text(cash.currency)); put("source_type", text(cash.sourceType))
                }
            }))
            put("funding_relation", funding(projection.fundingRelationId))
        }
    }

    private fun relationStatus(relation: TransactionRelationsRecord): String =
        if (relation.active == false) "superseded" else when (relation.state) {
            "draft" -> "pending_review"
            "confirmed" -> "accepted"
            "ignored" -> "rejected"
            else -> error("invalid relation state")
        }

    private fun roles(value: JsonElement): JsonArray = when (value) {
        is JsonArray -> value
        is JsonObject -> JsonArray(value.keys.map(::JsonPrimitive))
        is JsonPrimitive -> if (value.isString) JsonArray(value.content.codePoints().toArray().map { JsonPrimitive(String(Character.toChars(it))) })
            else error("invalid member roles")
    }

    private fun record(row: CashTransactionsRecord, account: AccountsRecord?, categories: Map<String, CashCategoriesRecord>) = buildJsonObject {
        put("id", row.id.toString()); put("occurred_at", row.occurredAt?.let { JsonPrimitive(manualInstantText(it)) } ?: JsonNull)
        put("account", account(account)); put("account_name", account?.name ?: "")
        put("account_id", account?.id?.let(::JsonPrimitive) ?: JsonNull); put("account_type", account?.type ?: "cash")
        put("counterparty", text(row.counterparty)); put("category", category(categories, row.categoryId)); put("note", text(row.note))
        put("amount", amount(row.amount)); put("currency", text(row.currency)); put("source_type", text(row.sourceType))
        put("counterparty_account", row.counterpartyAccount ?: ""); put("record_type", text(row.recordType)); put("record_subtype", text(row.recordSubtype))
        put("state", text(row.state)); put("row_revision", row.rowRevision!!)
    }

    private fun component(row: CashTransactionComponentsRecord, account: AccountsRecord?) = buildJsonObject {
        put("id", row.id.toString()); put("cash_transaction_id", row.cashTransactionId.toString()); put("account", account(account))
        put("account_id", account?.id?.let(::JsonPrimitive) ?: JsonNull); put("account_name", account?.name ?: "")
        put("account_type", text(account?.type)); put("amount", amount(row.amount)); put("currency", text(row.currency))
        put("ordinal", row.ordinal!!); put("label", text(row.label))
    }

    private fun account(row: AccountsRecord?): JsonElement = row?.let { buildJsonObject {
        put("id", it.id!!); put("name", it.name!!); put("type", it.type!!); put("active", it.active!!)
    } } ?: JsonNull

    private fun category(categories: Map<String, CashCategoriesRecord>, id: String?): JsonElement {
        val selected = categories[id] ?: return JsonNull
        val path = mutableListOf<JsonObject>()
        val visited = mutableSetOf<String>()
        var current: CashCategoriesRecord? = selected
        while (current != null) {
            val row = current
            check(visited.add(row.id!!)) { "category cycle in evidence" }
            path.add(buildJsonObject { put("id", row.id!!); put("name", row.name!!) })
            current = row.parentId?.takeIf(String::isNotEmpty)?.let(categories::get)
        }
        return buildJsonObject { put("id", selected.id!!); put("name", selected.name!!); put("path", JsonArray(path.reversed())) }
    }

    private fun funding(id: Long?): JsonElement {
        if (id == null) return JsonNull
        val table = CASH_INVESTMENT_FUNDING_RELATIONS
        val row = sql.selectFrom(table).where(table.WORKSPACE_ID.eq(workspace)).and(table.ID.eq(id))
            .and(table.STATUS.eq("accepted")).and(table.ACTIVE_SLOT.eq("active")).fetchOne() ?: return JsonNull
        return buildJsonObject {
            put("id", row.id.toString()); put("investment_event_id", row.investmentEventId.toString())
            put("direction", row.direction!!); put("status", row.status!!); put("rule_id", row.ruleId!!)
            put("evidence", safeFundingEvidence(row.evidence))
        }
    }

    private fun safeFundingEvidence(value: JsonElement?): JsonObject {
        val payload = value as? JsonObject ?: return JsonObject(emptyMap())
        return buildJsonObject {
            for ((key, range) in listOf("business_day_window" to 0L..7L, "candidate_count" to 1L..100_000L)) {
                val number = payload[key] as? JsonPrimitive
                if (number != null && !number.isString && number.longOrNull in range) put(key, number)
            }
            val rawKind = payload["cash_record_type"]
            check(rawKind == null || rawKind is JsonPrimitive) { "invalid funding evidence record type" }
            val kind = rawKind as? JsonPrimitive
            if (kind?.isString == true && kind.content in setOf("investment_in", "investment_out", "transfer_in", "transfer_out")) put("cash_record_type", kind)
            val keys = payload["match_keys"]
            if (keys == JsonArray(listOf("amount", "currency", "direction", "business_day").map(::JsonPrimitive)) ||
                keys == JsonArray(listOf("institution_name", "direction", "business_day").map(::JsonPrimitive))) put("match_keys", keys)
        }
    }

    private fun amount(value: CashStoredDecimal?): JsonElement {
        val number = value?.text?.toBigDecimal() ?: return JsonNull
        return JsonPrimitive(if (number.signum() == 0) "0" else number.stripTrailingZeros().toPlainString())
    }

    private fun text(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
}
