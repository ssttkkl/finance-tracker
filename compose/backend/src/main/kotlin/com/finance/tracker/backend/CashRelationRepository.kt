package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.math.BigDecimal
import java.time.Instant
import kotlinx.serialization.json.JsonObject

/** 关系表的持久化仓库；状态映射、端点解析和业务键语义与 Python 仓库一致。 */
internal class CashRelationRepository(private val context: CashSqlContext) {
    private data class StoredRelation(val id: Long, val kind: String, val subtype: String, val primaryComponentId: Long?,
        val secondaryComponentId: Long?, val appliedAmount: CashStoredDecimal?, val ruleId: String, val createdBy: String,
        val createdAt: Instant, val decidedBy: String, val decidedAt: Instant?, val decisionReason: String,
        val anchorComponentId: Long?, val state: String, val active: Boolean, val primaryTransactionId: Long,
        val secondaryTransactionId: Long?, val anchorTransactionId: Long, val primaryRole: String?, val secondaryRole: String?,
        val rowRevision: Long)
    private data class StoredComponent(val id: Long, val transactionId: Long, val accountId: Long?, val amount: Any?,
        val currency: String?, val ordinal: Long)
    private data class EndpointValues(val kind: String, val subtype: String, val primaryComponentId: Long,
        val secondaryComponentId: Long?, val anchorComponentId: Long, val primaryTransactionId: Long,
        val secondaryTransactionId: Long?, val anchorTransactionId: Long, val primaryRole: String?, val secondaryRole: String?)

    private fun text(value: Any?): String = value?.toString().orEmpty()
    private fun CashAmount.numericAmount(): BigDecimal = BigDecimal(numericText())
    private fun intId(value: Any?): Long? {
        if (value == null || value == "") return null
        return if (value is Number) value.toLong() else text(value).toLongOrNull()
            ?: throw IllegalArgumentException("relation id must be an integer")
    }
    private fun candidateIds(value: Any?): List<Long> {
        if (value !is List<*> && value !is Set<*>) return emptyList()
        val result = linkedSetOf<Long>()
        for (item in value) {
            if (item is Boolean || item == null) continue
            val id = intId(item) ?: continue
            if (id > 0) result += id
        }
        return result.take(20)
    }
    private fun stateValues(status: String): Pair<Boolean, String?> {
        if (status !in setOf("pending_review", "accepted", "rejected", "superseded"))
            throw IllegalArgumentException("关系状态无效")
        if (status == "superseded") return false to null
        return true to when (status) {
            "pending_review" -> "draft"
            "accepted" -> "confirmed"
            else -> "ignored"
        }
    }
    private fun statusOf(state: String, active: Boolean): String = if (!active) "superseded" else when (state) {
        "draft" -> "pending_review"
        "confirmed" -> "accepted"
        else -> "rejected"
    }
    private fun relationQuery() = context.sql.select(
        TRANSACTION_RELATIONS.ID, TRANSACTION_RELATIONS.KIND, TRANSACTION_RELATIONS.SUBTYPE,
        TRANSACTION_RELATIONS.PRIMARY_COMPONENT_ID, TRANSACTION_RELATIONS.SECONDARY_COMPONENT_ID,
        TRANSACTION_RELATIONS.APPLIED_AMOUNT, TRANSACTION_RELATIONS.RULE_ID, TRANSACTION_RELATIONS.CREATED_BY,
        TRANSACTION_RELATIONS.CREATED_AT, TRANSACTION_RELATIONS.DECIDED_BY, TRANSACTION_RELATIONS.DECIDED_AT,
        TRANSACTION_RELATIONS.DECISION_REASON, TRANSACTION_RELATIONS.ANCHOR_COMPONENT_ID, TRANSACTION_RELATIONS.STATE,
        TRANSACTION_RELATIONS.ACTIVE, TRANSACTION_RELATIONS.PRIMARY_TRANSACTION_ID,
        TRANSACTION_RELATIONS.SECONDARY_TRANSACTION_ID, TRANSACTION_RELATIONS.ANCHOR_TRANSACTION_ID,
        TRANSACTION_RELATIONS.PRIMARY_ROLE, TRANSACTION_RELATIONS.SECONDARY_ROLE, TRANSACTION_RELATIONS.ROW_REVISION)
        .from(TRANSACTION_RELATIONS).where(TRANSACTION_RELATIONS.WORKSPACE_ID.eq(context.workspace))
    private fun toStored(row: org.jooq.Record): StoredRelation = StoredRelation(
        row[TRANSACTION_RELATIONS.ID]!!, row[TRANSACTION_RELATIONS.KIND]!!, row[TRANSACTION_RELATIONS.SUBTYPE]!!,
        row[TRANSACTION_RELATIONS.PRIMARY_COMPONENT_ID], row[TRANSACTION_RELATIONS.SECONDARY_COMPONENT_ID],
        row[TRANSACTION_RELATIONS.APPLIED_AMOUNT], row[TRANSACTION_RELATIONS.RULE_ID]!!,
        row[TRANSACTION_RELATIONS.CREATED_BY]!!, row[TRANSACTION_RELATIONS.CREATED_AT]!!,
        row[TRANSACTION_RELATIONS.DECIDED_BY]!!, row[TRANSACTION_RELATIONS.DECIDED_AT],
        row[TRANSACTION_RELATIONS.DECISION_REASON]!!, row[TRANSACTION_RELATIONS.ANCHOR_COMPONENT_ID],
        row[TRANSACTION_RELATIONS.STATE]!!, row[TRANSACTION_RELATIONS.ACTIVE]!!,
        row[TRANSACTION_RELATIONS.PRIMARY_TRANSACTION_ID]!!, row[TRANSACTION_RELATIONS.SECONDARY_TRANSACTION_ID],
        row[TRANSACTION_RELATIONS.ANCHOR_TRANSACTION_ID]!!, row[TRANSACTION_RELATIONS.PRIMARY_ROLE],
        row[TRANSACTION_RELATIONS.SECONDARY_ROLE], row[TRANSACTION_RELATIONS.ROW_REVISION]!!)

    private fun candidates(ids: List<Long>): Map<Long, List<Long>> {
        if (ids.isEmpty()) return emptyMap()
        val grouped = linkedMapOf<Long, MutableList<Long>>()
        context.sql.select(CASH_RELATION_CANDIDATES.RELATION_ID, CASH_RELATION_CANDIDATES.CANDIDATE_COMPONENT_ID)
            .from(CASH_RELATION_CANDIDATES).where(CASH_RELATION_CANDIDATES.WORKSPACE_ID.eq(context.workspace))
            .and(CASH_RELATION_CANDIDATES.RELATION_ID.`in`(ids))
            .and(CASH_RELATION_CANDIDATES.CANDIDATE_COMPONENT_ID.isNotNull)
            .orderBy(CASH_RELATION_CANDIDATES.CANDIDATE_COMPONENT_ID).fetch().forEach { row ->
                grouped.getOrPut(row.value1()!!) { mutableListOf() } += row.value2()!!
            }
        return grouped
    }
    private fun toDict(row: StoredRelation, candidateIds: List<Long>): Map<String, Any?> = linkedMapOf(
        "id" to row.id, "workspace_id" to context.workspace, "kind" to row.kind, "subtype" to row.subtype,
        "primary_fact_id" to row.primaryComponentId, "secondary_fact_id" to row.secondaryComponentId,
        "primary_record_id" to row.primaryTransactionId, "secondary_record_id" to row.secondaryTransactionId,
        "primary_component_id" to row.primaryComponentId, "secondary_component_id" to row.secondaryComponentId,
        "primary_fact_type" to "cash", "secondary_fact_type" to row.secondaryTransactionId?.let { "cash" },
        "anchor_fact_id" to row.anchorComponentId, "anchor_record_id" to row.anchorTransactionId,
        "anchor_component_id" to row.anchorComponentId,
        "applied_amount" to row.appliedAmount?.let { BigDecimal(fixedCashDecimal(it)) },
        "status" to statusOf(row.state, row.active), "rule_id" to row.ruleId,
        "candidate_fact_ids" to candidateIds, "created_by" to row.createdBy, "created_at" to row.createdAt,
        "decided_by" to row.decidedBy, "decided_at" to row.decidedAt, "decision_reason" to row.decisionReason,
        "superseded_by_id" to null, "active_slot" to (if (row.active) "active" else row.id.toString()))
    private fun toDict(row: StoredRelation): Map<String, Any?> =
        toDict(row, candidates(listOf(row.id))[row.id].orEmpty())

    private fun component(componentId: Long): StoredComponent? =
        context.sql.select(CASH_TRANSACTION_COMPONENTS.ID, CASH_TRANSACTION_COMPONENTS.CASH_TRANSACTION_ID,
            CASH_TRANSACTION_COMPONENTS.ACCOUNT_ID, CASH_TRANSACTION_COMPONENTS.AMOUNT,
            CASH_TRANSACTION_COMPONENTS.CURRENCY, CASH_TRANSACTION_COMPONENTS.ORDINAL)
            .from(CASH_TRANSACTION_COMPONENTS)
            .where(CASH_TRANSACTION_COMPONENTS.WORKSPACE_ID.eq(context.workspace))
            .and(CASH_TRANSACTION_COMPONENTS.ID.eq(componentId)).fetchOne()?.let {
                StoredComponent(it.value1()!!, it.value2()!!, it.value3(), it.value4(), it.value5(), it.value6()!!)
            }
    private fun components(ids: Set<Long>): Map<Long, StoredComponent> {
        if (ids.isEmpty()) return emptyMap()
        return context.sql.select(CASH_TRANSACTION_COMPONENTS.ID, CASH_TRANSACTION_COMPONENTS.CASH_TRANSACTION_ID,
            CASH_TRANSACTION_COMPONENTS.ACCOUNT_ID, CASH_TRANSACTION_COMPONENTS.AMOUNT,
            CASH_TRANSACTION_COMPONENTS.CURRENCY, CASH_TRANSACTION_COMPONENTS.ORDINAL)
            .from(CASH_TRANSACTION_COMPONENTS)
            .where(CASH_TRANSACTION_COMPONENTS.WORKSPACE_ID.eq(context.workspace))
            .and(CASH_TRANSACTION_COMPONENTS.ID.`in`(ids.sorted())).fetch().associate {
                val value = StoredComponent(it.value1()!!, it.value2()!!, it.value3(), it.value4(), it.value5(), it.value6()!!)
                value.id to value
            }
    }

    private fun resolveComponent(factId: Any?, explicitComponentId: Any?): Long {
        val explicit = intId(explicitComponentId)
        if (explicit != null) {
            val component = component(explicit) ?: throw IllegalArgumentException("关系组成项不存在")
            val fact = intId(factId)
            if (fact != null && fact != component.transactionId && fact != component.id)
                throw IllegalArgumentException("关系组成项不属于指定流水")
            return component.id
        }
        if (factId == null || factId == "") throw IllegalArgumentException("关系端点不能为空")
        val parent = intId(factId) ?: throw IllegalArgumentException("关系端点不能为空")
        val ids = context.sql.select(CASH_TRANSACTION_COMPONENTS.ID).from(CASH_TRANSACTION_COMPONENTS)
            .where(CASH_TRANSACTION_COMPONENTS.WORKSPACE_ID.eq(context.workspace))
            .and(CASH_TRANSACTION_COMPONENTS.CASH_TRANSACTION_ID.eq(parent))
            .orderBy(CASH_TRANSACTION_COMPONENTS.ORDINAL).fetch().map { it.value1()!! }
        if (ids.size != 1) throw IllegalArgumentException("组合支付关系必须指定组成项")
        return ids.single()
    }
    private fun resolveComponentEndpoint(factId: Any?, explicitComponentId: Any?): Long {
        if (explicitComponentId != null) return resolveComponent(factId, explicitComponentId)
        if (factId == null || factId == "") throw IllegalArgumentException("关系端点不能为空")
        return resolveComponent(factId, null)
    }

    private fun legacyRoles(kind: String, subtype: String, amount: BigDecimal): Pair<String?, String?> = when (kind) {
        "payment_mirror" -> (if (amount.signum() > 0) "refund" else "expense").let { it to it }
        "refund_offset" -> (if (subtype == "p2p_return") "p2p_out" to "p2p_in" else "expense" to "refund")
        else -> "outgoing" to "incoming"
    }
    private fun endpointValues(kind: String, subtype: String, primary: Long, secondary: Long?,
        anchor: Long): EndpointValues {
        val ids = setOfNotNull(primary, secondary, anchor)
        val found = components(ids)
        if (ids.any { it !in found }) throw IllegalArgumentException("关系组成项不存在")
        val roles = if (secondary != null) legacyRoles(kind, subtype,
            BigDecimal(fixedCashDecimal(found.getValue(primary).amount as CashStoredDecimal))) else null to null
        return EndpointValues(kind, subtype, primary, secondary, anchor, found.getValue(primary).transactionId,
            secondary?.let { found.getValue(it).transactionId }, found.getValue(anchor).transactionId,
            roles.first, roles.second)
    }

    private fun assertOrigin(transactionIds: List<Long>, kind: String?) {
        val ids = transactionIds.toSet()
        val rows = context.sql.select(CASH_TRANSACTIONS.ID, CASH_TRANSACTIONS.RECORD_ORIGIN,
            CASH_TRANSACTIONS.MANUAL_TRANSFER_ID).from(CASH_TRANSACTIONS)
            .where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(context.workspace))
            .and(CASH_TRANSACTIONS.ID.`in`(ids.sorted())).fetch()
        if (rows.size != ids.size) relationFailure("relation_endpoint_incomplete")
        if (rows.any { it.value2() == "manual" }) relationFailure("manual_relation_forbidden")
        val transfers = rows.filter { it.value2() == "manual_transfer" }
        if (transfers.isNotEmpty() && (kind != "transfer_pair" || rows.size != 2 || transfers.size != 2 ||
                transfers[0].value3().isNullOrEmpty() || transfers[0].value3() != transfers[1].value3()))
            relationFailure("manual_relation_forbidden")
    }

    private fun replaceCandidates(relationId: Long, values: List<Long>) {
        context.sql.deleteFrom(CASH_RELATION_CANDIDATES)
            .where(CASH_RELATION_CANDIDATES.WORKSPACE_ID.eq(context.workspace))
            .and(CASH_RELATION_CANDIDATES.RELATION_ID.eq(relationId)).execute()
        if (values.isEmpty()) return
        val anchor = context.sql.select(TRANSACTION_RELATIONS.ANCHOR_TRANSACTION_ID).from(TRANSACTION_RELATIONS)
            .where(TRANSACTION_RELATIONS.WORKSPACE_ID.eq(context.workspace))
            .and(TRANSACTION_RELATIONS.ID.eq(relationId)).fetchSingle().value1()
        context.sql.select(CASH_TRANSACTION_COMPONENTS.ID, CASH_TRANSACTION_COMPONENTS.CASH_TRANSACTION_ID,
            CASH_TRANSACTIONS.ROW_REVISION, CASH_TRANSACTIONS.STATE, CASH_TRANSACTIONS.DELETED_AT)
            .from(CASH_TRANSACTION_COMPONENTS)
            .join(CASH_TRANSACTIONS).on(CASH_TRANSACTIONS.WORKSPACE_ID.eq(CASH_TRANSACTION_COMPONENTS.WORKSPACE_ID))
            .and(CASH_TRANSACTIONS.ID.eq(CASH_TRANSACTION_COMPONENTS.CASH_TRANSACTION_ID))
            .where(CASH_TRANSACTION_COMPONENTS.WORKSPACE_ID.eq(context.workspace))
            .and(CASH_TRANSACTION_COMPONENTS.ID.`in`(values)).fetch().forEach { row ->
                if (row.value2() == anchor) return@forEach
                context.sql.insertInto(CASH_RELATION_CANDIDATES)
                    .set(CASH_RELATION_CANDIDATES.WORKSPACE_ID, context.workspace)
                    .set(CASH_RELATION_CANDIDATES.RELATION_ID, relationId)
                    .set(CASH_RELATION_CANDIDATES.CANDIDATE_TRANSACTION_ID, row.value2())
                    .set(CASH_RELATION_CANDIDATES.CANDIDATE_COMPONENT_ID, row.value1())
                    .set(CASH_RELATION_CANDIDATES.EXPECTED_ROW_REVISION, row.value3())
                    .set(CASH_RELATION_CANDIDATES.EVIDENCE, JsonObject(emptyMap()))
                    .set(CASH_RELATION_CANDIDATES.VALIDITY,
                        if (row.value4() == "posted" && row.value5() == null) "valid" else "incomplete")
                    .execute()
            }
    }

    fun listActive(kind: String? = null, status: String? = null): List<Map<String, Any?>> {
        if (status == "superseded") return emptyList()
        var query = relationQuery().and(TRANSACTION_RELATIONS.ACTIVE.isTrue)
        if (kind != null) query = query.and(TRANSACTION_RELATIONS.KIND.eq(kind))
        if (status != null) {
            val state = stateValues(status).second
            if (state != null) query = query.and(TRANSACTION_RELATIONS.STATE.eq(state))
        }
        val rows = query.orderBy(TRANSACTION_RELATIONS.CREATED_AT, TRANSACTION_RELATIONS.ID).fetch().map(::toStored)
        val grouped = candidates(rows.map { it.id })
        return rows.map { toDict(it, grouped[it.id].orEmpty()) }
    }
    fun get(relationId: Any?): Map<String, Any?>? {
        val id = intId(relationId) ?: return null
        val row = relationQuery().and(TRANSACTION_RELATIONS.ID.eq(id)).fetchOne()?.let(::toStored) ?: return null
        return toDict(row)
    }
    fun findByBusinessKey(kind: String, factA: Any?, factB: Any?, subtype: String = "",
        componentA: Any? = null, componentB: Any? = null): Map<String, Any?>? {
        val left = resolveComponentEndpoint(factA, componentA)
        val right = if ((factB == null || factB == "") && (componentB == null || componentB == "")) null
        else resolveComponentEndpoint(factB, componentB)
        var query = relationQuery().and(TRANSACTION_RELATIONS.KIND.eq(kind))
            .and(TRANSACTION_RELATIONS.SUBTYPE.eq(subtype))
            .and(TRANSACTION_RELATIONS.ACTIVE.isTrue)
        query = if (right == null) query.and(TRANSACTION_RELATIONS.PRIMARY_COMPONENT_ID.eq(left))
            .and(TRANSACTION_RELATIONS.SECONDARY_TRANSACTION_ID.isNull)
        else query.and((TRANSACTION_RELATIONS.PRIMARY_COMPONENT_ID.eq(left)
            .and(TRANSACTION_RELATIONS.SECONDARY_COMPONENT_ID.eq(right)))
            .or(TRANSACTION_RELATIONS.PRIMARY_COMPONENT_ID.eq(right)
                .and(TRANSACTION_RELATIONS.SECONDARY_COMPONENT_ID.eq(left))))
        return query.orderBy(TRANSACTION_RELATIONS.ID).limit(1).fetchOne()?.let(::toStored)?.let(::toDict)
    }
    fun listForFacts(factIds: List<Any?>, activeOnly: Boolean = true): List<Map<String, Any?>> {
        if (factIds.isEmpty()) return emptyList()
        val parents = factIds.mapNotNull(::intId).sorted()
        if (parents.isEmpty()) return emptyList()
        val componentIds = context.sql.select(CASH_TRANSACTION_COMPONENTS.ID).from(CASH_TRANSACTION_COMPONENTS)
            .where(CASH_TRANSACTION_COMPONENTS.WORKSPACE_ID.eq(context.workspace))
            .and(CASH_TRANSACTION_COMPONENTS.CASH_TRANSACTION_ID.`in`(parents))
            .orderBy(CASH_TRANSACTION_COMPONENTS.ORDINAL, CASH_TRANSACTION_COMPONENTS.ID)
            .fetch().map { it.value1()!! }
        return listForComponents(componentIds, activeOnly)
    }
    fun listForComponents(componentIds: List<Any?>, activeOnly: Boolean = true): List<Map<String, Any?>> {
        val ids = linkedSetOf<Long>()
        for (value in componentIds) intId(value)?.let { ids += it }
        if (ids.isEmpty()) return emptyList()
        var query = relationQuery().and((TRANSACTION_RELATIONS.PRIMARY_COMPONENT_ID.`in`(ids)
            .or(TRANSACTION_RELATIONS.SECONDARY_COMPONENT_ID.`in`(ids))))
        if (activeOnly) query = query.and(TRANSACTION_RELATIONS.ACTIVE.isTrue)
        val rows = query.orderBy(TRANSACTION_RELATIONS.CREATED_AT, TRANSACTION_RELATIONS.ID).fetch().map(::toStored)
        val grouped = candidates(rows.map { it.id })
        return rows.map { toDict(it, grouped[it.id].orEmpty()) }
    }

    fun add(relation: Map<String, Any?>): Long {
        context.requireActive()
        val secondaryRaw = relation["secondary_fact_id"]?.toString().orEmpty()
        val secondary = secondaryRaw.ifEmpty { null }
        val primaryComponent = resolveComponentEndpoint(relation["primary_fact_id"], relation["primary_component_id"])
        val secondaryComponent = secondary?.let { resolveComponentEndpoint(it, relation["secondary_component_id"]) }
        var applied = exactCashAmount(text(relation["applied_amount"]).ifEmpty { "0" })
        if (applied.numericAmount().signum() < 0)
            throw IllegalArgumentException("关系分摊金额不能为负数")
        val kind = text(relation["kind"])
        if (kind == "payment_mirror" && secondaryComponent != null) {
            val amounts = components(setOf(primaryComponent, secondaryComponent))
            if (amounts.size != 2) throw IllegalArgumentException("关系组成项不存在")
            if (applied == exactCashAmount("0")) {
                val min = amounts.values.map { BigDecimal(fixedCashDecimal(it.amount as CashStoredDecimal)).abs() }
                    .minOrNull()!!
                applied = exactCashAmount(min.toPlainString())
            }
        }
        if (applied != exactCashAmount("0")) {
            val endpoints = listOfNotNull(primaryComponent, secondaryComponent)
            val amounts = components(endpoints.toSet())
            if (amounts.size != endpoints.size) throw IllegalArgumentException("关系组成项不存在")
            if (amounts.values.any { applied.numericAmount() > BigDecimal(fixedCashDecimal(it.amount as CashStoredDecimal)).abs() })
                throw IllegalArgumentException("关系分摊金额超过组成项金额")
            if (kind == "refund_offset") {
                for (componentId in endpoints) {
                    val existing = context.sql.select(TRANSACTION_RELATIONS.APPLIED_AMOUNT).from(TRANSACTION_RELATIONS)
                        .where(TRANSACTION_RELATIONS.WORKSPACE_ID.eq(context.workspace))
                        .and(TRANSACTION_RELATIONS.KIND.eq("refund_offset"))
                        .and(TRANSACTION_RELATIONS.STATE.eq("confirmed"))
                        .and(TRANSACTION_RELATIONS.ACTIVE.isTrue)
                        .and(TRANSACTION_RELATIONS.PRIMARY_COMPONENT_ID.eq(componentId)
                            .or(TRANSACTION_RELATIONS.SECONDARY_COMPONENT_ID.eq(componentId)))
                        .fetch().map { row -> row.value1()?.let { BigDecimal(fixedCashDecimal(it)).abs() } ?: BigDecimal.ZERO }
                    val total = existing.fold(BigDecimal.ZERO, BigDecimal::add).add(applied.numericAmount())
                    if (total > BigDecimal(fixedCashDecimal(amounts.getValue(componentId).amount as CashStoredDecimal)).abs())
                        throw IllegalArgumentException("组成项退款分摊金额超过可用金额")
                }
            }
        }
        val status = text(relation["status"]).ifEmpty { "pending_review" }
        val (active, state) = stateValues(status)
        var anchor = text(relation["anchor_fact_id"]).ifEmpty { text(relation["primary_fact_id"]) }
        if (anchor.isEmpty()) anchor = text(relation["primary_fact_id"])
        val subtype = text(relation["subtype"])
        val candidates = if (secondary == null && status == "pending_review") candidateIds(relation["candidate_fact_ids"])
        else emptyList()
        val endpoints = endpointValues(kind, subtype, primaryComponent, secondaryComponent,
            resolveComponentEndpoint(anchor, relation["anchor_component_id"]))
        assertOrigin(listOfNotNull(endpoints.primaryTransactionId, endpoints.secondaryTransactionId,
            endpoints.anchorTransactionId), kind)
        val createdBy = text(relation["created_by"]).ifEmpty { "system" }
        val decidedBy = text(relation["decided_by"])
        val id = context.sql.insertInto(TRANSACTION_RELATIONS)
            .set(TRANSACTION_RELATIONS.WORKSPACE_ID, context.workspace)
            .set(TRANSACTION_RELATIONS.KIND, endpoints.kind)
            .set(TRANSACTION_RELATIONS.SUBTYPE, endpoints.subtype)
            .set(TRANSACTION_RELATIONS.PRIMARY_COMPONENT_ID, endpoints.primaryComponentId)
            .set(TRANSACTION_RELATIONS.SECONDARY_COMPONENT_ID, endpoints.secondaryComponentId)
            .set(TRANSACTION_RELATIONS.ANCHOR_COMPONENT_ID, endpoints.anchorComponentId)
            .set(TRANSACTION_RELATIONS.PRIMARY_TRANSACTION_ID, endpoints.primaryTransactionId)
            .set(TRANSACTION_RELATIONS.SECONDARY_TRANSACTION_ID, endpoints.secondaryTransactionId)
            .set(TRANSACTION_RELATIONS.ANCHOR_TRANSACTION_ID, endpoints.anchorTransactionId)
            .set(TRANSACTION_RELATIONS.PRIMARY_ROLE, endpoints.primaryRole)
            .set(TRANSACTION_RELATIONS.SECONDARY_ROLE, endpoints.secondaryRole)
            .set(TRANSACTION_RELATIONS.APPLIED_AMOUNT, CashStoredDecimal(applied.numericText()))
            .set(TRANSACTION_RELATIONS.RULE_ID, text(relation["rule_id"]))
            .set(TRANSACTION_RELATIONS.CREATED_BY, createdBy)
            .set(TRANSACTION_RELATIONS.CREATED_AT, context.now())
            .set(TRANSACTION_RELATIONS.DECIDED_BY, decidedBy)
            .setNull(TRANSACTION_RELATIONS.DECIDED_AT)
            .set(TRANSACTION_RELATIONS.DECISION_REASON, text(relation["decision_reason"]))
            .set(TRANSACTION_RELATIONS.STATE, state ?: "draft")
            .set(TRANSACTION_RELATIONS.ACTIVE, active)
            .set(TRANSACTION_RELATIONS.DECISION_SOURCE,
                if (listOf(decidedBy, createdBy, "system").first { it.isNotEmpty() } == "system") "rule" else "manual")
            .set(TRANSACTION_RELATIONS.EVIDENCE, JsonObject(emptyMap()))
            .set(TRANSACTION_RELATIONS.ROW_REVISION, 1L)
            .returningResult(TRANSACTION_RELATIONS.ID).fetchSingle().value1()!!
        replaceCandidates(id, candidates)
        return id
    }
    fun findOpenLeg(kind: String, anchorFactId: Any?, subtype: String = "",
        anchorComponentId: Any? = null): Map<String, Any?>? {
        val anchor = resolveComponentEndpoint(anchorFactId, anchorComponentId)
        return relationQuery().and(TRANSACTION_RELATIONS.KIND.eq(kind))
            .and(TRANSACTION_RELATIONS.SUBTYPE.eq(subtype))
            .and(TRANSACTION_RELATIONS.ANCHOR_COMPONENT_ID.eq(anchor))
            .and(TRANSACTION_RELATIONS.SECONDARY_COMPONENT_ID.isNull)
            .and(TRANSACTION_RELATIONS.ACTIVE.isTrue)
            .orderBy(TRANSACTION_RELATIONS.ID).limit(1).fetchOne()?.let(::toStored)?.let(::toDict)
    }
    fun bindOtherLeg(relationId: Any?, otherFactId: Any?, otherComponentId: Any? = null,
        primaryFactId: Any? = null, primaryComponentId: Any? = null, status: String, decidedBy: String,
        decisionReason: String = ""): Map<String, Any?> {
        val id = intId(relationId) ?: throw IllegalArgumentException("relation not found: $relationId")
        val row = relationQuery().and(TRANSACTION_RELATIONS.ID.eq(id)).fetchOne()?.let(::toStored)
            ?: throw IllegalArgumentException("relation not found: $relationId")
        if (row.secondaryComponentId != null) throw IllegalArgumentException("该关系已有对侧流水")
        val other = resolveComponentEndpoint(otherFactId, otherComponentId)
        val primary = if (primaryFactId != null) resolveComponentEndpoint(primaryFactId, primaryComponentId)
        else row.primaryComponentId!!
        val secondary = if (primaryFactId != null) row.primaryComponentId else other
        val conflict = findByBusinessKey(row.kind, primary, secondary, row.subtype, primary, secondary)
        if (conflict != null && conflict["id"] != id)
            throw IllegalArgumentException("无法绑定对侧流水：这两条账本记录已存在有效双边关系（${conflict["id"]}）")
        val endpoints = endpointValues(row.kind, row.subtype, primary, secondary, row.anchorComponentId!!)
        val (active, state) = stateValues(status)
        context.sql.update(TRANSACTION_RELATIONS)
            .set(TRANSACTION_RELATIONS.PRIMARY_COMPONENT_ID, endpoints.primaryComponentId)
            .set(TRANSACTION_RELATIONS.SECONDARY_COMPONENT_ID, endpoints.secondaryComponentId)
            .set(TRANSACTION_RELATIONS.ANCHOR_COMPONENT_ID, endpoints.anchorComponentId)
            .set(TRANSACTION_RELATIONS.PRIMARY_TRANSACTION_ID, endpoints.primaryTransactionId)
            .set(TRANSACTION_RELATIONS.SECONDARY_TRANSACTION_ID, endpoints.secondaryTransactionId)
            .set(TRANSACTION_RELATIONS.ANCHOR_TRANSACTION_ID, endpoints.anchorTransactionId)
            .set(TRANSACTION_RELATIONS.PRIMARY_ROLE, endpoints.primaryRole)
            .set(TRANSACTION_RELATIONS.SECONDARY_ROLE, endpoints.secondaryRole)
            .set(TRANSACTION_RELATIONS.STATE, state ?: row.state)
            .set(TRANSACTION_RELATIONS.ACTIVE, active)
            .set(TRANSACTION_RELATIONS.ROW_REVISION, row.rowRevision + 2)
            .set(TRANSACTION_RELATIONS.DECIDED_BY, decidedBy)
            .set(TRANSACTION_RELATIONS.DECIDED_AT, context.now())
            .set(TRANSACTION_RELATIONS.DECISION_SOURCE, if (decidedBy == "system") "rule" else "manual")
            .set(TRANSACTION_RELATIONS.DECISION_REASON, decisionReason)
            .where(TRANSACTION_RELATIONS.WORKSPACE_ID.eq(context.workspace))
            .and(TRANSACTION_RELATIONS.ID.eq(id)).execute()
        replaceCandidates(id, emptyList())
        return get(id)!!
    }
    fun updateOpenLegCandidates(relationId: Any?, values: List<Any?>): Map<String, Any?> {
        val id = intId(relationId) ?: throw IllegalArgumentException("relation not found: $relationId")
        val row = relationQuery().and(TRANSACTION_RELATIONS.ID.eq(id)).fetchOne()?.let(::toStored)
            ?: throw IllegalArgumentException("relation not found: $relationId")
        if (row.secondaryComponentId != null || row.state != "draft" || !row.active)
            throw IllegalArgumentException("只能更新待配对关系的候选")
        replaceCandidates(id, candidateIds(values))
        context.sql.update(TRANSACTION_RELATIONS).set(TRANSACTION_RELATIONS.ROW_REVISION, row.rowRevision + 2)
            .where(TRANSACTION_RELATIONS.WORKSPACE_ID.eq(context.workspace))
            .and(TRANSACTION_RELATIONS.ID.eq(id)).execute()
        return get(id)!!
    }
    fun updateStatus(relationId: Any?, status: String, decidedBy: String = "", decisionReason: String = "",
        supersededById: Any? = null): Map<String, Any?> {
        val id = intId(relationId) ?: throw IllegalArgumentException("relation not found: $relationId")
        val row = relationQuery().and(TRANSACTION_RELATIONS.ID.eq(id)).fetchOne()?.let(::toStored)
            ?: throw IllegalArgumentException("relation not found: $relationId")
        val (active, state) = stateValues(status)
        var update = context.sql.update(TRANSACTION_RELATIONS).set(TRANSACTION_RELATIONS.ACTIVE, active)
        if (state != null) update = update.set(TRANSACTION_RELATIONS.STATE, state)
        update = update.set(TRANSACTION_RELATIONS.ROW_REVISION,
            row.rowRevision + if (status != "pending_review") 2 else 1)
        if (decidedBy.isNotEmpty()) update = update.set(TRANSACTION_RELATIONS.DECIDED_BY, decidedBy)
            .set(TRANSACTION_RELATIONS.DECIDED_AT, context.now())
            .set(TRANSACTION_RELATIONS.DECISION_SOURCE, if (decidedBy == "system") "rule" else "manual")
        if (decisionReason.isNotEmpty()) update = update.set(TRANSACTION_RELATIONS.DECISION_REASON, decisionReason)
        update.where(TRANSACTION_RELATIONS.WORKSPACE_ID.eq(context.workspace))
            .and(TRANSACTION_RELATIONS.ID.eq(id)).execute()
        if (status != "pending_review") replaceCandidates(id, emptyList())
        return get(id)!!
    }
    fun updateStatusBatch(relationIds: List<Any?>, status: String, decidedBy: String = "",
        decisionReason: String = "") {
        val ids = relationIds.mapNotNull(::intId).distinct()
        if (ids.isEmpty()) return
        val (active, state) = stateValues(status)
        if (status == "superseded") throw IllegalArgumentException("批量更新不支持失效关系")
        if (status != "pending_review") context.sql.deleteFrom(CASH_RELATION_CANDIDATES)
            .where(CASH_RELATION_CANDIDATES.WORKSPACE_ID.eq(context.workspace))
            .and(CASH_RELATION_CANDIDATES.RELATION_ID.`in`(ids)).execute()
        var update = context.sql.update(TRANSACTION_RELATIONS).set(TRANSACTION_RELATIONS.ACTIVE, active)
        if (state != null) update = update.set(TRANSACTION_RELATIONS.STATE, state)
        update = update.set(TRANSACTION_RELATIONS.ROW_REVISION, TRANSACTION_RELATIONS.ROW_REVISION.add(1))
        if (decidedBy.isNotEmpty()) update = update.set(TRANSACTION_RELATIONS.DECIDED_BY, decidedBy)
            .set(TRANSACTION_RELATIONS.DECIDED_AT, context.now())
            .set(TRANSACTION_RELATIONS.DECISION_SOURCE, if (decidedBy == "system") "rule" else "manual")
        if (decisionReason.isNotEmpty()) update = update.set(TRANSACTION_RELATIONS.DECISION_REASON, decisionReason)
        update.where(TRANSACTION_RELATIONS.WORKSPACE_ID.eq(context.workspace))
            .and(TRANSACTION_RELATIONS.ID.`in`(ids)).execute()
    }
    fun updateKind(relationId: Any?, kind: String, subtype: String, actor: String,
        reason: String): Map<String, Any?> {
        val id = intId(relationId) ?: throw IllegalArgumentException("找不到这条关联记录")
        val row = relationQuery().and(TRANSACTION_RELATIONS.ID.eq(id)).fetchOne()?.let(::toStored)
            ?: throw IllegalArgumentException("找不到这条关联记录")
        val changed = row.kind != kind || row.subtype != subtype
        val endpoints = endpointValues(kind, subtype, row.primaryComponentId!!, row.secondaryComponentId,
            row.anchorComponentId!!)
        context.sql.update(TRANSACTION_RELATIONS)
            .set(TRANSACTION_RELATIONS.KIND, endpoints.kind)
            .set(TRANSACTION_RELATIONS.SUBTYPE, endpoints.subtype)
            .set(TRANSACTION_RELATIONS.PRIMARY_COMPONENT_ID, endpoints.primaryComponentId)
            .set(TRANSACTION_RELATIONS.SECONDARY_COMPONENT_ID, endpoints.secondaryComponentId)
            .set(TRANSACTION_RELATIONS.ANCHOR_COMPONENT_ID, endpoints.anchorComponentId)
            .set(TRANSACTION_RELATIONS.PRIMARY_TRANSACTION_ID, endpoints.primaryTransactionId)
            .set(TRANSACTION_RELATIONS.SECONDARY_TRANSACTION_ID, endpoints.secondaryTransactionId)
            .set(TRANSACTION_RELATIONS.ANCHOR_TRANSACTION_ID, endpoints.anchorTransactionId)
            .set(TRANSACTION_RELATIONS.PRIMARY_ROLE, endpoints.primaryRole)
            .set(TRANSACTION_RELATIONS.SECONDARY_ROLE, endpoints.secondaryRole)
            .set(TRANSACTION_RELATIONS.RULE_ID, "manual.web.v1")
            .set(TRANSACTION_RELATIONS.CREATED_BY, actor)
            .set(TRANSACTION_RELATIONS.DECIDED_BY, actor)
            .set(TRANSACTION_RELATIONS.DECISION_SOURCE, if (actor == "system") "rule" else "manual")
            .set(TRANSACTION_RELATIONS.DECIDED_AT, context.now())
            .set(TRANSACTION_RELATIONS.DECISION_REASON, reason)
            .set(TRANSACTION_RELATIONS.ROW_REVISION, row.rowRevision + 1)
            .where(TRANSACTION_RELATIONS.WORKSPACE_ID.eq(context.workspace))
            .and(TRANSACTION_RELATIONS.ID.eq(id)).execute()
        if (changed) replaceCandidates(id, emptyList())
        return get(id)!!
    }
}
