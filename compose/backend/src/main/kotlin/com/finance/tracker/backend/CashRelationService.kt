package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.math.BigDecimal
import kotlinx.serialization.json.*

/** 关系规划/上下文的应用服务；持有工作区 SQL 上下文，按 Python 行形状暴露事实。 */
internal class CashRelationService(private val context: CashSqlContext) {
    private val relations = CashRelationRepository(context)
    private fun text(value: Any?): String = value?.toString().orEmpty()
    private fun jsonValue(value: JsonElement?): Any? = when (value) {
        null, is JsonNull -> null
        is JsonObject -> value.entries.associate { it.key to jsonValue(it.value) }
        is JsonArray -> value.map(::jsonValue)
        is JsonPrimitive -> when {
            value.isString -> value.content
            value.booleanOrNull != null -> value.boolean
            value.longOrNull != null -> value.long
            else -> value.double
        }
    }
    private fun jsonMap(value: JsonElement?): Map<String, Any?> =
        (jsonValue(value) as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }.orEmpty()

    private val payloadAliases = mapOf(
        "平台状态" to listOf("交易状态", "当前状态"), "status" to listOf("交易状态", "当前状态"),
        "txn_id" to listOf("交易订单号", "交易单号"), "merchant_order_id" to listOf("商家订单号", "商户单号"),
        "txn_type" to listOf("交易分类", "交易类型"), "payment_method" to listOf("收/付款方式", "支付方式"),
        "direction" to listOf("收/支"))

    /** 按 Python `list_detailed` 形状返回流水行（含组成项与账户类型）。 */
    fun detailCashRows(includeDeleted: Boolean = false): List<Map<String, Any?>> {
        context.requireActive()
        val tx = CASH_TRANSACTIONS
        val account = ACCOUNTS
        val fields = arrayOf(tx.ID, tx.STATE, tx.RECORD_ORIGIN, tx.MANUAL_TRANSFER_ID, tx.ROW_REVISION,
            tx.RECORD_ID, tx.SOURCE_TYPE, tx.SOURCE_PAYLOAD, tx.RELATION_METADATA, tx.SOURCE_FINGERPRINT,
            tx.MANUAL_OVERRIDES, tx.OCCURRED_AT, tx.AMOUNT, tx.CURRENCY, tx.COUNTERPARTY,
            tx.COUNTERPARTY_ACCOUNT, tx.COUNTERPARTY_ACCOUNT_ATTRS, tx.NOTE, tx.CATEGORY_ID, tx.RECORD_TYPE,
            tx.RECORD_SUBTYPE, tx.ACCOUNT_ID, tx.CASH_GRANULARITY, tx.DELETED_AT, tx.DELETED_BY,
            tx.DELETE_REASON, account.ID, account.NAME, account.TYPE)
        val query = context.sql.select(*fields)
            .from(tx).leftJoin(account).on(account.WORKSPACE_ID.eq(tx.WORKSPACE_ID), account.ID.eq(tx.ACCOUNT_ID))
            .where(tx.WORKSPACE_ID.eq(context.workspace))
        val rows = (if (includeDeleted) query else query.and(tx.DELETED_AT.isNull))
            .orderBy(tx.OCCURRED_AT, tx.ID).fetch()
        val ids = rows.map { it[tx.ID]!! }
        val components = componentsByParent(ids)
        return rows.map { row ->
            val parentId = row[tx.ID]!!
            val parts = components[parentId].orEmpty()
            val singleAccount = parts.singleOrNull()?.let { part ->
                val id = part["account_id"] as? Long
                if (id != null) accounts(part["account_id"] as? Long) else null
            }
            val effective = if (row[tx.ACCOUNT_ID] != null)
                Triple(row[account.ID]!!, row[account.NAME]!!, row[account.TYPE]!!) else singleAccount
            val state = row[tx.STATE]!!
            val payload = jsonMap(row[tx.SOURCE_PAYLOAD])
            val item = linkedMapOf<String, Any?>(
                "id" to parentId, "state" to state, "record_origin" to row[tx.RECORD_ORIGIN]!!,
                "manual_transfer_id" to row[tx.MANUAL_TRANSFER_ID], "row_revision" to row[tx.ROW_REVISION]!!,
                "record_id" to row[tx.RECORD_ID]!!, "source_type" to (row[tx.SOURCE_TYPE] ?: ""),
                "source_payload" to payload, "relation_metadata" to jsonMap(row[tx.RELATION_METADATA]),
                "source_fingerprint" to row[tx.SOURCE_FINGERPRINT],
                "manual_overrides" to jsonMap(row[tx.MANUAL_OVERRIDES]),
                "source" to (row[tx.SOURCE_TYPE] ?: ""), "bill_source" to (row[tx.SOURCE_TYPE] ?: ""),
                "occurred_at" to row[tx.OCCURRED_AT]?.let { ParsedImportCodec.timeText(it) },
                "amount" to row[tx.AMOUNT]?.let(::fixedCashDecimal), "currency" to row[tx.CURRENCY],
                "counterparty" to row[tx.COUNTERPARTY], "counterparty_account" to row[tx.COUNTERPARTY_ACCOUNT],
                "counterparty_account_attrs" to ((jsonValue(row[tx.COUNTERPARTY_ACCOUNT_ATTRS]) as? List<*>)
                    ?.map { it?.toString() } ?: emptyList<String>()),
                "note" to row[tx.NOTE], "category_id" to row[tx.CATEGORY_ID],
                "record_type" to row[tx.RECORD_TYPE], "record_subtype" to row[tx.RECORD_SUBTYPE],
                "account_name" to (effective?.second ?: if (state == "draft") null else ""),
                "account_id" to row[tx.ACCOUNT_ID],
                "account_type" to (effective?.third ?: if (state == "draft") null else ""),
                "cash_granularity" to row[tx.CASH_GRANULARITY]!!, "components" to parts,
                "deleted_at" to row[tx.DELETED_AT]?.let { ParsedImportCodec.timeText(it) },
                "deleted_by" to (row[tx.DELETED_BY] ?: ""), "delete_reason" to (row[tx.DELETE_REASON] ?: ""),
                "deleted" to (row[tx.DELETED_AT] != null),
                "_record_type" to (effective?.third ?: ""))
            item["raw_payload"] = payload.ifEmpty { emptyMap<String, Any?>() }
            for (key in listOf("platform_status", "status", "txn_id", "merchant_order_id", "txn_type",
                "payment_method", "direction", "type")) {
                val payloadValue = payload[key]
                if (!pythonRelationTruthy(item[key]) && payloadValue != null && payloadValue.toString().isNotEmpty())
                    item[key] = payloadValue
            }
            for ((key, aliases) in payloadAliases) {
                if (pythonRelationTruthy(item[key])) continue
                for (alias in aliases) {
                    val value = payload[alias]
                    if (value != null && value.toString().isNotEmpty()) {
                        item[key] = value
                        break
                    }
                }
            }
            if (!pythonRelationTruthy(item["txn_id"]) && pythonRelationTruthy(item["record_id"]))
                item["txn_id"] = item["record_id"]
            item.putIfAbsent("source", item["source_type"])
            item.putIfAbsent("bill_source", item["source_type"])
            item
        }
    }

    private val accountCache = mutableMapOf<Long, Triple<Long, String, String>>()
    private fun accounts(id: Long?): Triple<Long, String, String>? {
        if (id == null) return null
        return accountCache.getOrPut(id) {
            val row = context.sql.select(ACCOUNTS.ID, ACCOUNTS.NAME, ACCOUNTS.TYPE).from(ACCOUNTS)
                .where(ACCOUNTS.WORKSPACE_ID.eq(context.workspace)).and(ACCOUNTS.ID.eq(id)).fetchOne()
                ?: return null
            Triple(row.value1()!!, row.value2()!!, row.value3()!!)
        }
    }
    private fun componentsByParent(parentIds: List<Long>): Map<Long, List<Map<String, Any?>>> {
        if (parentIds.isEmpty()) return emptyMap()
        val result = linkedMapOf<Long, MutableList<Map<String, Any?>>>()
        for (batch in parentIds.sorted().chunked(500)) {
            val part = CASH_TRANSACTION_COMPONENTS
            val account = ACCOUNTS.`as`("component_account")
            context.sql.select(part.ID, part.CASH_TRANSACTION_ID, part.ACCOUNT_ID, part.AMOUNT, part.CURRENCY,
                part.ORDINAL, part.LABEL, part.SOURCE_KEY, part.METADATA_JSON, account.NAME, account.TYPE)
                .from(part).leftJoin(account).on(account.WORKSPACE_ID.eq(part.WORKSPACE_ID),
                    account.ID.eq(part.ACCOUNT_ID))
                .where(part.WORKSPACE_ID.eq(context.workspace)).and(part.CASH_TRANSACTION_ID.`in`(batch))
                .orderBy(part.ORDINAL, part.ID).fetch().forEach { row ->
                    result.getOrPut(row[part.CASH_TRANSACTION_ID]!!) { mutableListOf() } += linkedMapOf(
                        "id" to row[part.ID], "cash_transaction_id" to row[part.CASH_TRANSACTION_ID],
                        "account_id" to row[part.ACCOUNT_ID], "account_name" to row[account.NAME],
                        "account_type" to row[account.TYPE], "amount" to row[part.AMOUNT]?.let(::fixedCashDecimal),
                        "currency" to row[part.CURRENCY], "ordinal" to row[part.ORDINAL],
                        "label" to row[part.LABEL], "source_key" to row[part.SOURCE_KEY],
                        "metadata" to jsonMap(row[part.METADATA_JSON]))
                }
        }
        return result
    }

    fun activeCashFacts(includeDeleted: Boolean = false): List<RelationFact> =
        CashRelationPlanCache.expandComponentRows(detailCashRows(includeDeleted = includeDeleted))
            .filter { includeDeleted || !pythonRelationTruthy(it["deleted_at"]) && !pythonRelationTruthy(it["deleted"]) }
            .map(CashRelationSerialization::factViewFromRow)

    fun aliasIndexes(): Pair<Map<String, List<String>>, Map<String, List<String>>> {
        val aliases = context.sql.selectFrom(ACCOUNT_ALIASES).where(
            ACCOUNT_ALIASES.WORKSPACE_ID.eq(context.workspace))
            .orderBy(ACCOUNT_ALIASES.CREATED_AT, ACCOUNT_ALIASES.ID).fetch().map { row ->
                linkedMapOf<String, Any?>("id" to row.id, "alias_type" to row.aliasType,
                    "alias_value" to row.aliasValue, "account_id" to row.accountId,
                    "created_at" to row.createdAt, "updated_at" to row.updatedAt)
            }
        return CashRelationPlanCache.aliasIndexes(aliases)
    }

    fun externalContextDigest(): String {
        val (tails, identifiers) = aliasIndexes()
        return CashRelationPlanning.externalContextDigest(activeCashFacts(), relations.listActive(), tails, identifiers)
    }

    fun plan(previewRows: List<Map<String, Any?>> = emptyList(), seedIds: List<String>? = null): RelationPlan {
        val existingRows = CashRelationPlanCache.expandComponentRows(detailCashRows())
        val existingFacts = activeCashFacts()
        val virtualFacts = mutableListOf<RelationFact>()
        val virtualRows = mutableListOf<Map<String, Any?>>()
        for (row in previewRows) {
            val item = LinkedHashMap(row)
            item.putIfAbsent("source_type", text(item["bill_source"]))
            item.putIfAbsent("bill_source", text(item["source_type"]))
            var componentRows = CashRelationPlanCache.expandComponentRows(listOf(item))
            if (componentRows.isEmpty()) componentRows = listOf(item)
            for ((ordinal, sourceRow) in componentRows.withIndex()) {
                val componentRow = LinkedHashMap(sourceRow)
                if (!pythonRelationTruthy(componentRow["id"]))
                    componentRow["id"] = "preview:${text(item["record_id"]).ifEmpty { virtualRows.size.toString() }}:$ordinal"
                virtualRows += componentRow
                virtualFacts += CashRelationSerialization.factViewFromRow(componentRow)
            }
        }
        val facts = existingFacts + virtualFacts
        val accepted = relations.listActive()
        val (tails, identifiers) = aliasIndexes()
        val built = CashRelationPlanning.planRelationProposals(facts,
            detailedRows = existingRows + virtualRows, seedIds = seedIds, acceptedRelations = accepted,
            aliasesByTail = tails, accountIdentifiers = identifiers, workspaceId = context.workspace)
        return built.copy(externalContextDigest = CashRelationPlanning.externalContextDigest(
            existingFacts, accepted, tails, identifiers))
    }

    /** 仅比较摘要的缓存计划上下文校验；不重新生成候选。 */
    fun validateCachedPlanContext(cachedPlan: Map<String, Any?>, expectedPlanDigest: String? = null) {
        fun invalid(): Nothing = throw IllegalArgumentException("import_relation_reconfirmation_required")
        if (cachedPlan["version"] != 1 && cachedPlan["version"] != true) invalid()
        val planDigest = text(cachedPlan["plan_digest"])
        val contextDigest = text(cachedPlan["external_context_digest"])
        if (planDigest.isEmpty() || contextDigest.isEmpty() ||
            (expectedPlanDigest != null && planDigest != expectedPlanDigest)) invalid()
        if (contextDigest != externalContextDigest()) invalid()
    }

    fun refundRemaining(facts: List<RelationFact>): Map<String, BigDecimal> =
        CashRelationPlanCache.refundRemaining(facts,
            relations.listActive(kind = "refund_offset", status = "accepted"))

    fun listPending(kind: String? = null): List<Map<String, Any?>> =
        relations.listActive(kind = kind, status = "pending_review")

    /** 关系余额/损益投影；已删除事实由纯投影规则排除。 */
    fun project(): Map<String, Any?> {
        val accepted = relations.listActive().filter { it["status"] == "accepted" }
        val result = CashRelationProjection.project(activeCashFacts(includeDeleted = true), accepted)
        fun wireId(value: String): Any = value.toLongOrNull() ?: value
        fun idOrder(first: Any, second: Any): Int = when {
            first is Long && second is Long -> first.compareTo(second)
            else -> first.toString().compareTo(second.toString())
        }
        return linkedMapOf(
            "balances" to result.balances.entries.associate { "${it.key.first}|${it.key.second}" to it.value },
            "expenses" to result.expenses, "income" to result.income,
            "excluded_transfer_fact_ids" to result.excludedTransferFactIds.map(::wireId)
                .sortedWith(Comparator(::idOrder)),
            "mirror_groups" to result.mirrorGroups.map { group -> group.map(::wireId).sortedWith(Comparator(::idOrder)) },
            "net_expense_by_group" to result.netExpenseByGroup)
    }

    private fun requireActiveCash(factId: Any?): RelationFact {
        val key = text(factId)
        return activeCashFacts().firstOrNull { it.id == key }
            ?: throw IllegalArgumentException("找不到有效现金流水：$key")
    }
    private fun componentParents(ids: Set<Long>): Map<Long, Long> {
        if (ids.isEmpty()) return emptyMap()
        return context.sql.select(CASH_TRANSACTION_COMPONENTS.ID, CASH_TRANSACTION_COMPONENTS.CASH_TRANSACTION_ID)
            .from(CASH_TRANSACTION_COMPONENTS)
            .where(CASH_TRANSACTION_COMPONENTS.WORKSPACE_ID.eq(context.workspace))
            .and(CASH_TRANSACTION_COMPONENTS.ID.`in`(ids.sorted())).fetch()
            .associate { it.value1()!! to it.value2()!! }
    }
    private fun validateTransferEndpointAvailability(factIds: List<String>, relationId: Any?,
        candidates: List<Map<String, Any?>>? = null) {
        val endpoints = factIds.filter { it.isNotEmpty() }.toSet()
        val rows = candidates ?: relations.listForComponents(endpoints.toList(), activeOnly = true)
        for (relation in rows) {
            if (relation["status"] == "superseded") continue
            if (relation["kind"] != "transfer_pair") continue
            if (relation["status"] != "accepted" || text(relation["id"]) == text(relationId)) continue
            val occupied = setOf(text(relation["primary_fact_id"]), text(relation["secondary_fact_id"]))
            if (endpoints.any { it in occupied })
                throw IllegalArgumentException("指定的转账端点已被另一条已确认关系占用")
        }
    }
    private fun validateOpenLegOther(rel: Map<String, Any?>, other: RelationFact) {
        val kind = text(rel["kind"])
        if (other.deleted) throw IllegalArgumentException("指定的对侧流水已删除")
        if (kind == "refund_offset") {
            if (other.signedAmount.signum() >= 0)
                throw IllegalArgumentException("退款待配对关系的对侧必须是负金额消费流水")
            return
        }
        if (kind == "transfer_pair") {
            val anchor = requireActiveCash(rel["primary_fact_id"])
            if (text(rel["subtype"]) == "currency_exchange") {
                if (!CashRelationCore.isFxOutRecord(anchor) || !CashRelationCore.isFxInRecord(other))
                    throw IllegalArgumentException("个人购汇待配对关系必须连接购汇转出和购汇转入")
                if (anchor.currency == other.currency)
                    throw IllegalArgumentException("个人购汇待配对关系的两端币种必须不同")
                if (CashTransferMatch.matchPersonalFxExchange(anchor, listOf(other)) == null)
                    throw IllegalArgumentException("指定的对侧不在个人购汇待配对候选范围内")
            }
            return
        }
        throw IllegalArgumentException("关系类型 $kind 不支持确认待配对关系")
    }

    /** 确认前把候选关系纳入完整投影图；非法图必须失败关闭。 */
    private fun validateProjectionAcceptance(relation: Map<String, Any?>, otherFactId: Any?) {
        fun invalid(): Nothing = throw IllegalArgumentException("该关系无法形成有效收支投影")
        val rawPrimary = text(relation["primary_component_id"]).ifEmpty { text(relation["primary_fact_id"]) }
        val rawSecondary = listOf(text(otherFactId), text(relation["secondary_component_id"]),
            text(relation["secondary_fact_id"])).firstOrNull { it.isNotEmpty() } ?: "0"
        var primaryId = rawPrimary.toLongOrNull() ?: invalid()
        var secondaryId = rawSecondary.toLongOrNull() ?: invalid()
        if (secondaryId == 0L) throw IllegalArgumentException("关系缺少对侧流水，无法形成有效收支投影")
        if (CashRelationCore.isOpenLegRelation(relation) && relation["kind"] == "refund_offset") {
            val swap = primaryId
            primaryId = secondaryId
            secondaryId = swap
        }
        val parents = componentParents(setOf(primaryId, secondaryId))
        val candidatePrimary = parents[primaryId] ?: throw IllegalArgumentException("关系组成项不存在")
        val candidateSecondary = parents[secondaryId] ?: throw IllegalArgumentException("关系组成项不存在")
        try {
            val graph = CashRelationReadRepository(context).readContext(setOf(candidatePrimary, candidateSecondary))
            val previous = graph.relations.firstOrNull { text(it.id) == text(relation["id"]) } ?: invalid()
            var roles = previous.primaryRole to previous.secondaryRole
            if (previous.secondaryComponentId == null) {
                val primary = graph.components.firstOrNull { it.componentId == primaryId.toString() } ?: invalid()
                val amount = CashRelationGeometry.asDecimal((primary.amount as CashDecimalLiteral).text)
                roles = legacyCashRelationRoles(previous.kind.toString(), previous.subtype.toString(), amount)
            }
            val candidate = previous.copy(primaryTransactionId = candidatePrimary.toString(),
                secondaryTransactionId = candidateSecondary.toString(),
                primaryComponentId = primaryId.toString(), secondaryComponentId = secondaryId.toString(),
                primaryRole = roles.first, secondaryRole = roles.second, state = "confirmed")
            val edges = graph.relations.map { if (text(it.id) == text(candidate.id)) candidate else it }
            buildCashRelationGraph(graph.facts, graph.components, edges, graph.fundingComponentIds)
        } catch (_: CashProjectionFailure) {
            invalid()
        } catch (_: CashRelationFailure) {
            invalid()
        } catch (_: InvalidCashValue) {
            invalid()
        }
    }
    private fun supersedeSystemPaymentMirrorConflicts(factIds: List<String>, replacementRelationId: Any?) {
        val endpoints = factIds.filter { it.isNotEmpty() }.toSet()
        if (endpoints.isEmpty()) return
        for (relation in relations.listForFacts(endpoints.toList(), activeOnly = true)) {
            if (relation["kind"] != "payment_mirror") continue
            if (relation["status"] != "accepted") continue
            if (replacementRelationId != null && text(relation["id"]) == text(replacementRelationId)) continue
            val occupied = setOf(text(relation["primary_fact_id"]), text(relation["secondary_fact_id"]))
            if (endpoints.none { it in occupied }) continue
            if (CashRelationSerialization.isHumanDecision(relation))
                throw IllegalArgumentException("指定的支付镜像端点已被另一条人工关系占用")
            relations.updateStatus(relation["id"], status = "superseded", decidedBy = "system",
                decisionReason = "superseded_by_human_payment_mirror", supersededById = replacementRelationId)
        }
    }

    fun accept(relationId: Any?, actor: String, reason: String = "", otherFactId: Any? = null): RelationDecision {
        val relation = relations.get(relationId)
            ?: throw IllegalArgumentException("找不到关系：${text(relationId)}")
        if (relation["status"] != "pending_review")
            throw IllegalArgumentException("只能确认 pending_review 状态的关系")
        val openLeg = CashRelationCore.isOpenLegRelation(relation)
        val other: RelationFact?
        val factIds: List<String>
        if (openLeg) {
            if (otherFactId == null || text(otherFactId).isEmpty())
                throw IllegalArgumentException("确认待配对关系时，必须通过 --other 指定对侧流水")
            other = requireActiveCash(otherFactId)
            val candidates = (relation["candidate_fact_ids"] as? List<*>)?.map { text(it) }?.toSet().orEmpty()
            if (other.id !in candidates)
                throw IllegalArgumentException("指定的对侧流水不在待配对候选中")
            validateOpenLegOther(relation, other)
            factIds = listOf(text(relation["primary_fact_id"]), text(otherFactId))
        } else {
            if (text(relation["secondary_fact_id"]).isEmpty())
                throw IllegalArgumentException("待审核的双边关系缺少对侧流水，无法确认")
            other = null
            factIds = listOf(text(relation["primary_fact_id"]), text(relation["secondary_fact_id"]))
        }
        if (relation["kind"] == "payment_mirror")
            supersedeSystemPaymentMirrorConflicts(factIds, relationId)
        validateTransferEndpointAvailability(factIds, relationId)
        validateProjectionAcceptance(relation, otherFactId)
        val updated = if (openLeg) {
            val refund = relation["kind"] == "refund_offset"
            relations.bindOtherLeg(relationId, otherFactId, otherComponentId = other!!.id,
                primaryFactId = if (refund) otherFactId else null,
                primaryComponentId = if (refund) other.id else null,
                status = "accepted", decidedBy = actor, decisionReason = reason)
        } else relations.updateStatus(relationId, status = "accepted", decidedBy = actor, decisionReason = reason)
        val componentIds = factIds.mapNotNull { it.toLongOrNull() }.toSet()
        CashProjectionService.maintainIfReadyInSession(context, componentIds, knownComponentIds = componentIds)
        return RelationDecision(ok = true, count = 1, message = "关系已确认", details = updated)
    }

    fun reject(relationId: Any?, actor: String, reason: String = ""): RelationDecision {
        val relation = relations.get(relationId)
            ?: throw IllegalArgumentException("找不到关系：${text(relationId)}")
        if (relation["status"] != "pending_review")
            throw IllegalArgumentException("只能驳回 pending_review 状态的关系")
        val updated = relations.updateStatus(relationId, status = "rejected", decidedBy = actor,
            decisionReason = reason.ifEmpty { "rejected" })
        val componentIds = listOf(text(relation["primary_fact_id"]), text(relation["secondary_fact_id"]))
            .filter { it.isNotEmpty() }.mapNotNull { it.toLongOrNull() }.toSet()
        CashProjectionService.maintainIfReadyInSession(context, emptySet(), knownComponentIds = componentIds)
        return RelationDecision(ok = true, count = 1, message = "关系已驳回", details = updated)
    }

    fun supersede(relationId: Any?, replacement: Map<String, Any?>, actor: String = "system",
        reason: String = "supersede"): RelationDecision {
        val old = relations.get(relationId)
            ?: throw IllegalArgumentException("找不到关系：${text(relationId)}")
        if (old["status"] == "superseded") throw IllegalArgumentException("该关系已被新结果取代")
        relations.updateStatus(relationId, status = "superseded", decidedBy = actor, decisionReason = reason)
        val newId = relations.add(replacement)
        relations.updateStatus(relationId, status = "superseded", decidedBy = actor,
            decisionReason = reason, supersededById = newId)
        val componentIds = listOf(old["primary_fact_id"], old["secondary_fact_id"],
            replacement["primary_fact_id"], replacement["secondary_fact_id"])
            .map { text(it) }.filter { it.isNotEmpty() }.mapNotNull { it.toLongOrNull() }.toSet()
        CashProjectionService.maintainIfReadyInSession(context, componentIds, knownComponentIds = componentIds)
        return RelationDecision(ok = true, count = 1, message = "关系已被新结果取代",
            details = linkedMapOf("old_id" to text(relationId), "new_id" to newId))
    }

    fun logicalDeleteCash(factId: Any?, actor: String, reason: String): RelationDecision {
        val trimmed = reason.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("delete reason is required")
        val id = text(factId).toLongOrNull() ?: throw IllegalArgumentException("cash fact not found: $factId")
        val row = context.sql.select(CASH_TRANSACTIONS.ID, CASH_TRANSACTIONS.DELETED_AT)
            .from(CASH_TRANSACTIONS).where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(context.workspace))
            .and(CASH_TRANSACTIONS.ID.eq(id)).fetchOne()
            ?: throw IllegalArgumentException("cash fact not found: $factId")
        if (row.value2() != null) throw IllegalArgumentException("cash fact already deleted: $factId")
        val now = context.now()
        context.sql.update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.DELETED_AT, now)
            .set(CASH_TRANSACTIONS.DELETED_BY, actor).set(CASH_TRANSACTIONS.DELETE_REASON, trimmed)
            .set(CASH_TRANSACTIONS.ROW_REVISION, CASH_TRANSACTIONS.ROW_REVISION.add(1))
            .where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(context.workspace))
            .and(CASH_TRANSACTIONS.ID.eq(id)).execute()
        val related = relations.listForFacts(listOf(id), activeOnly = true)
        for (relation in related) relations.updateStatus(relation["id"], status = "superseded",
            decidedBy = actor, decisionReason = "fact deleted: $trimmed")
        val components = related.flatMap { listOf(text(it["primary_fact_id"]), text(it["secondary_fact_id"])) }
            .filter { it.isNotEmpty() }.mapNotNull { it.toLongOrNull() }.toSet()
        CashProjectionService.maintainIfReadyInSession(context, setOf(id), knownComponentIds = components)
        return RelationDecision(ok = true, count = 1, message = "现金流水已逻辑删除",
            details = linkedMapOf("fact_id" to factId, "deleted_at" to now, "deleted_by" to actor,
                "delete_reason" to trimmed, "event_id" to null))
    }

    private fun refundAmount(proposal: RelationProposal): BigDecimal =
        proposal.evidence.extras["refund_amount"]?.let { BigDecimal(it.toString()) }?.abs() ?: BigDecimal.ZERO

    /** 候选加入完整已确认连通组后，退款与内部转账不得共存。 */
    fun candidateCreatesKindConflict(proposal: RelationProposal,
        acceptedRelations: List<Map<String, Any?>>? = null): Boolean {
        if (proposal.secondaryFactId.isNullOrEmpty()) return false
        val relations = acceptedRelations ?: relations.listActive(status = "accepted")
        val adjacency = linkedMapOf<String, MutableSet<String>>()
        val kindsByEdge = mutableListOf<Triple<String, String, String>>()
        for (relation in relations) {
            val primary = text(relation["primary_fact_id"])
            val secondary = text(relation["secondary_fact_id"])
            if (primary.isEmpty() || secondary.isEmpty()) continue
            adjacency.getOrPut(primary) { linkedSetOf() } += secondary
            adjacency.getOrPut(secondary) { linkedSetOf() } += primary
            kindsByEdge += Triple(primary, secondary, text(relation["kind"]))
        }
        val primary = proposal.primaryFactId
        val secondary = proposal.secondaryFactId.orEmpty()
        adjacency.getOrPut(primary) { linkedSetOf() } += secondary
        adjacency.getOrPut(secondary) { linkedSetOf() } += primary
        kindsByEdge += Triple(primary, secondary, proposal.kind)
        val component = linkedSetOf<String>()
        val stack = mutableListOf(primary)
        while (stack.isNotEmpty()) {
            val current = stack.removeAt(stack.size - 1)
            if (!component.add(current)) continue
            stack += adjacency[current].orEmpty() - component
        }
        val kinds = kindsByEdge.filter { it.first in component && it.second in component }.map { it.third }.toSet()
        return setOf("refund_offset", "transfer_pair").all { it in kinds }
    }

    /** 系统自动结果在写入前必须通过完整投影校验。 */
    fun persistAutomaticProposal(proposal: RelationProposal, remaining: Map<String, BigDecimal>,
        facts: List<RelationFact>, acceptedRelations: List<Map<String, Any?>>? = null,
        validationProposals: List<RelationProposal> = emptyList()): Map<String, Any?>? {
        val reason = CashRelationPlanning.validationReason(facts, acceptedRelations.orEmpty(), proposal,
            validationProposals)
        if (reason != null) return null
        return persistProposal(proposal, remaining, acceptedRelations)
    }

    fun persistProposal(proposal: RelationProposal, remaining: Map<String, BigDecimal>,
        acceptedRelations: List<Map<String, Any?>>? = null): Map<String, Any?>? {
        val openLeg = proposal.isOpenLeg
        val subtype = proposal.subtype.ifEmpty { "" }
        val anchorId = proposal.effectiveAnchorFactId.ifEmpty { proposal.primaryFactId }
        fun bind(existing: Map<String, Any?>, reason: String): Map<String, Any?> {
            val other = if (proposal.kind == "refund_offset") proposal.primaryFactId
            else proposal.secondaryFactId.orEmpty()
            return relations.bindOtherLeg(existing["id"], other, otherComponentId = other,
                primaryFactId = if (proposal.kind == "refund_offset") proposal.primaryFactId else null,
                primaryComponentId = if (proposal.kind == "refund_offset") proposal.primaryFactId else null,
                status = "accepted", decidedBy = "", decisionReason = reason)
        }
        var existing = if (openLeg) {
            relations.findOpenLeg(proposal.kind, anchorId, subtype, anchorComponentId = anchorId)
                ?: relations.findByBusinessKey(proposal.kind, anchorId, null, subtype = subtype,
                    componentA = anchorId)
        } else {
            var found = relations.findByBusinessKey(proposal.kind, proposal.primaryFactId,
                proposal.secondaryFactId, subtype = subtype, componentA = proposal.primaryFactId,
                componentB = proposal.secondaryFactId)
            if (found == null && !proposal.secondaryFactId.isNullOrEmpty()) {
                val openExisting = relations.findOpenLeg(proposal.kind, anchorId, subtype,
                    anchorComponentId = anchorId)
                if (openExisting != null && openExisting["status"] == "pending_review" &&
                    openExisting["created_by"] == "system" &&
                    !CashRelationSerialization.isHumanDecision(openExisting) && proposal.status == "accepted") {
                    if (candidateCreatesKindConflict(proposal, acceptedRelations)) return openExisting
                    return bind(openExisting, "fx_rate_score_auto")
                }
            }
            found
        }
        if (existing != null) {
            if (openLeg && existing["status"] == "pending_review" && existing["created_by"] == "system" &&
                !CashRelationSerialization.isHumanDecision(existing))
                return relations.updateOpenLegCandidates(existing["id"], proposal.evidence.candidateFactIds)
            if (existing["created_by"] != "system" || CashRelationSerialization.isHumanDecision(existing)) {
                if (existing["status"] in setOf("accepted", "rejected", "pending_review")) return null
            }
            if (existing["status"] == "pending_review" && existing["created_by"] == "system" &&
                !CashRelationSerialization.isHumanDecision(existing) &&
                CashRelationCore.isOpenLegRelation(existing) && !proposal.secondaryFactId.isNullOrEmpty() &&
                proposal.status == "accepted" && !openLeg) {
                if (candidateCreatesKindConflict(proposal, acceptedRelations)) return existing
                return bind(existing, "fx_rate_score_auto")
            }
            if (existing["status"] in setOf("accepted", "rejected", "pending_review")) {
                if (existing["status"] == "pending_review" && existing["created_by"] == "system" &&
                    !CashRelationSerialization.isHumanDecision(existing) &&
                    !CashRelationCore.isOpenLegRelation(existing) && proposal.status == "accepted" &&
                    !openLeg && !proposal.secondaryFactId.isNullOrEmpty()) {
                    if (candidateCreatesKindConflict(proposal, acceptedRelations)) return existing
                    return relations.updateStatus(existing["id"], status = "accepted", decidedBy = "",
                        decisionReason = "auto_upgrade:${proposal.ruleId}")
                }
                return existing
            }
            return null
        }
        var status = proposal.status
        if (openLeg) status = "pending_review"
        if (status == "accepted" && candidateCreatesKindConflict(proposal, acceptedRelations))
            status = "pending_review"
        if (status == "accepted" && proposal.kind == "refund_offset") {
            val expenseId = proposal.primaryFactId
            val amount = refundAmount(proposal)
            if (expenseId in remaining && amount > remaining.getValue(expenseId))
                status = "pending_review"
        }
        val id = relations.add(linkedMapOf(
            "kind" to proposal.kind, "subtype" to subtype,
            "primary_fact_id" to proposal.primaryFactId,
            "secondary_fact_id" to if (openLeg) null else proposal.secondaryFactId,
            "primary_component_id" to proposal.primaryFactId,
            "secondary_component_id" to if (openLeg) null else proposal.secondaryFactId,
            "primary_fact_type" to proposal.primaryFactType,
            "secondary_fact_type" to if (openLeg) null else proposal.secondaryFactType,
            "anchor_fact_id" to anchorId, "anchor_component_id" to anchorId,
            "status" to status, "rule_id" to proposal.ruleId,
            "candidate_fact_ids" to if (openLeg) proposal.evidence.candidateFactIds else emptyList<String>(),
            "created_by" to proposal.createdBy,
            "applied_amount" to if (proposal.kind == "refund_offset" &&
                !proposal.secondaryFactId.isNullOrEmpty()) refundAmount(proposal).toPlainString() else "0"))
        return relations.get(id)
    }

    /** 整批自动替换在写入前校验；非法时旧边保持不动。 */
    fun validateAutomaticReplacementBatch(facts: List<RelationFact>, acceptedRelations: List<Map<String, Any?>>,
        replacements: List<RelationProposal>,
        validationContext: ProjectionValidationContext? = null): Boolean {
        val batch = replacements.filter { it.createdBy == "system" && it.status == "accepted" &&
            !it.secondaryFactId.isNullOrEmpty() && !it.isOpenLeg }
        val context = validationContext ?: CashRelationPlanning.buildValidationContext(facts)
        if (batch.isEmpty()) return true
        return CashRelationPlanning.validationReason(facts, acceptedRelations, batch.first(), batch,
            context) == null
    }

    /** 把系统支付镜像收敛到完整的规范匹配；人工决定保持不变。 */
    fun reconcileSystemPaymentMirrors(facts: List<RelationFact>, protectedFactIds: Set<String> = emptySet(),
        blockedPairs: Set<Pair<String, String>> = emptySet()): Set<Long> {
        val affected = linkedSetOf<Long>()
        fun mark(vararg identifiers: String?) {
            for (identifier in identifiers) identifier?.toLongOrNull()?.let { affected += it }
        }
        val allActive = relations.listActive().filter { it["status"] != "superseded" }
        val active = allActive.filter { it["status"] == "accepted" }
        val protectedIds = protectedFactIds.toMutableSet()
        for (relation in active) {
            if (relation["kind"] == "payment_mirror" && CashRelationSerialization.isHumanDecision(relation))
                listOf(relation["primary_fact_id"], relation["secondary_fact_id"])
                    .map { text(it) }.filter { it.isNotEmpty() }.forEach { protectedIds += it }
        }
        val blocked = blockedPairs.toMutableSet()
        for (relation in allActive) {
            if (relation["kind"] == "payment_mirror" && relation["status"] == "rejected" &&
                CashRelationSerialization.isHumanDecision(relation) &&
                text(relation["primary_fact_id"]).isNotEmpty() && text(relation["secondary_fact_id"]).isNotEmpty())
                blocked += text(relation["primary_fact_id"]) to text(relation["secondary_fact_id"])
        }
        val (tails, identifiers) = aliasIndexes()
        val canonical = CashRelationPipeline.matchCanonicalPaymentMirrors(facts, tails, identifiers,
            protectedIds, blocked)
        val canonicalPairs = canonical.filter { it.primaryFactId.isNotEmpty() && !it.secondaryFactId.isNullOrEmpty() }
            .map { setOf(it.primaryFactId, it.secondaryFactId!!) }.toSet()
        val automaticMirrors = allActive.filter { relation ->
            relation["kind"] == "payment_mirror" && relation["created_by"] == "system" &&
                !CashRelationSerialization.isHumanDecision(relation) &&
                relation["status"] in setOf("accepted", "pending_review")
        }
        val obsoleteIds = automaticMirrors.filter { relation ->
            text(relation["primary_fact_id"]).isNotEmpty() && text(relation["secondary_fact_id"]).isNotEmpty() &&
                setOf(text(relation["primary_fact_id"]), text(relation["secondary_fact_id"])) !in canonicalPairs
        }.map { text(it["id"]) }.toSet()
        val replacementBase = active.filter { text(it["id"]) !in obsoleteIds }
        val replacements = canonical.filter { it.status == "accepted" }
        val validationContext = CashRelationPlanning.buildValidationContext(facts)
        if (!validateAutomaticReplacementBatch(facts, replacementBase, replacements, validationContext))
            return affected
        for (relation in automaticMirrors) {
            val pair = setOf(text(relation["primary_fact_id"]), text(relation["secondary_fact_id"]))
            if (pair.all { it.isEmpty() } || pair in canonicalPairs) continue
            val human = pair.any { it in protectedIds }
            relations.updateStatus(relation["id"], status = "superseded", decidedBy = "system",
                decisionReason = if (human) "superseded_by_human_payment_mirror"
                else "superseded_by_canonical_payment_mirror")
            mark(text(relation["primary_fact_id"]), text(relation["secondary_fact_id"]))
        }
        val accepted = relations.listActive().filter { it["status"] == "accepted" }.toMutableList()
        for (proposal in canonical) {
            if (proposal.status != "accepted") continue
            val outcome = persistProposal(proposal, emptyMap(), accepted) ?: continue
            if (outcome["status"] == "accepted") {
                accepted += outcome
                mark(text(outcome["primary_fact_id"]), text(outcome["secondary_fact_id"]))
            }
        }
        return affected
    }

    /** 后续证据到达时重评估系统银行退款；返回受影响组件与被取代的退款对。 */
    fun reconcileSystemRefundOffsets(facts: List<RelationFact>,
        skipRefundIds: Set<String> = emptySet()): Pair<Set<Long>, Set<Pair<String, String>>> {
        val affected = linkedSetOf<Long>()
        fun mark(vararg identifiers: String?) {
            for (identifier in identifiers) identifier?.toLongOrNull()?.let { affected += it }
        }
        var acceptedRelations = relations.listActive().filter { it["status"] == "accepted" }.toMutableList()
        val mirrorPairs = acceptedRelations.filter { it["kind"] == "payment_mirror" }.map {
            text(it["primary_fact_id"]) to text(it["secondary_fact_id"]) }
        val componentsByFact = linkedMapOf<String, Set<String>>()
        for (component in CashRelationGeometry.buildMirrorComponents(facts.map { it.id }, mirrorPairs)) {
            val frozen = component.toSet()
            for (factId in frozen) componentsByFact[factId] = frozen
        }
        val candidateEventIds = componentsByFact.mapValues { it.value.minOrNull() ?: "" }
        var remaining = refundRemaining(facts).toMutableMap()
        val supersededPairs = linkedSetOf<Pair<String, String>>()
        val byId = facts.associateBy { it.id }
        val index = CashFactCandidateIndex(facts, CashRelationCore::sourceGroup)
        for (relation in acceptedRelations.toList()) {
            if (relation["kind"] != "refund_offset" || relation["status"] != "accepted" ||
                relation["created_by"] != "system" || CashRelationSerialization.isHumanDecision(relation)) continue
            val expenseId = text(relation["primary_fact_id"])
            val refundId = text(relation["secondary_fact_id"])
            val expense = byId[expenseId] ?: continue
            val refund = byId[refundId] ?: continue
            if (CashRelationCore.sourceGroup(refund) != "bank" || refund.signedAmount.signum() <= 0) continue
            val candidateRemaining = remaining.toMutableMap()
            candidateRemaining[expenseId] = (candidateRemaining[expenseId] ?: expense.signedAmount.abs()) +
                refund.signedAmount.abs()
            val replacement = CashRefundMatch.evaluate(refund, index.refundCandidates(refund),
                candidateRemaining, candidateEventIds) ?: continue
            if (replacement.status != "accepted" || replacement.primaryFactId == expenseId) continue
            val acceptedWithoutOld = acceptedRelations.filter { it["id"] != relation["id"] }
            if (CashRelationPlanning.validationReason(facts, acceptedWithoutOld, replacement) != null) continue
            relations.updateStatus(relation["id"], status = "superseded", decidedBy = "system",
                decisionReason = "replacing_later_refund_evidence")
            val created = persistAutomaticProposal(replacement, candidateRemaining, facts, acceptedWithoutOld)
            if (created == null || created["status"] != "accepted") {
                relations.updateStatus(relation["id"], status = "accepted",
                    decisionReason = text(relation["decision_reason"]))
                continue
            }
            relations.updateStatus(relation["id"], status = "superseded", decidedBy = "system",
                decisionReason = "superseded_by_later_refund_evidence", supersededById = created["id"])
            mark(text(relation["primary_fact_id"]), text(relation["secondary_fact_id"]),
                text(created["primary_fact_id"]), text(created["secondary_fact_id"]))
            supersededPairs += expenseId to refundId
            acceptedRelations = (acceptedWithoutOld + created).toMutableList()
        }
        remaining = refundRemaining(facts).toMutableMap()
        val activeRelations = relations.listActive().filter { it["status"] != "superseded" }
        val occupiedRefundIds = activeRelations.filter { it["kind"] == "refund_offset" &&
            text(it["secondary_fact_id"]).isNotEmpty() }.map { text(it["secondary_fact_id"]) }.toMutableSet()
        acceptedRelations = activeRelations.filter { it["status"] == "accepted" }.toMutableList()
        for (relation in activeRelations) {
            if (relation["kind"] != "refund_offset" || relation["status"] != "accepted") continue
            val refundId = text(relation["secondary_fact_id"])
            occupiedRefundIds += componentsByFact[refundId] ?: setOf(refundId)
        }
        val seeds = skipRefundIds.map { it }
        for (refund in facts.sortedWith(CashRelationCore.relationOrderKey(facts.firstOrNull()
            ?: RelationFact("", BigDecimal.ZERO, "", "")))) {
            val refundId = refund.id
            if (refundId in seeds || refundId in occupiedRefundIds ||
                CashRelationCore.sourceGroup(refund) != "bank" || refund.signedAmount.signum() <= 0) continue
            val proposal = CashRefundMatch.evaluate(refund, index.refundCandidates(refund), remaining,
                candidateEventIds) ?: continue
            if (proposal.status != "accepted") continue
            val created = persistAutomaticProposal(proposal, remaining, facts, acceptedRelations) ?: continue
            if (created["status"] != "accepted") continue
            acceptedRelations += created
            val expenseId = text(created["primary_fact_id"])
            if (expenseId.isNotEmpty())
                remaining[expenseId] = (remaining[expenseId] ?: BigDecimal.ZERO) - refund.signedAmount.abs()
            occupiedRefundIds += refundId
            mark(text(created["primary_fact_id"]), text(created["secondary_fact_id"]))
        }
        return affected to supersededPairs
    }

    /** 导入拒绝的自动提案：人工决定保持，系统结果更新为 rejected，否则新建 rejected 记录。 */
    fun persistRejectedProposal(proposal: RelationProposal): Map<String, Any?> {
        val subtype = proposal.subtype.ifEmpty { "" }
        val existing = relations.findByBusinessKey(proposal.kind, proposal.primaryFactId,
            proposal.secondaryFactId, subtype = subtype, componentA = proposal.primaryFactId,
            componentB = proposal.secondaryFactId)
        if (existing != null) {
            if (CashRelationSerialization.isHumanDecision(existing)) return existing
            return relations.updateStatus(existing["id"], status = "rejected", decidedBy = "web",
                decisionReason = "import_rejected")
        }
        val id = relations.add(linkedMapOf(
            "kind" to proposal.kind, "subtype" to subtype,
            "primary_fact_id" to proposal.primaryFactId, "secondary_fact_id" to proposal.secondaryFactId,
            "primary_component_id" to proposal.primaryFactId, "secondary_component_id" to proposal.secondaryFactId,
            "primary_fact_type" to proposal.primaryFactType, "secondary_fact_type" to proposal.secondaryFactType,
            "anchor_fact_id" to proposal.effectiveAnchorFactId.ifEmpty { proposal.primaryFactId },
            "anchor_component_id" to proposal.effectiveAnchorFactId.ifEmpty { proposal.primaryFactId },
            "status" to "rejected", "rule_id" to proposal.ruleId,
            "candidate_fact_ids" to proposal.evidence.candidateFactIds,
            "created_by" to "web", "decided_by" to "web", "decision_reason" to "import_rejected"))
        return relations.get(id)!!
    }
}

internal data class RelationDecision(val ok: Boolean, val count: Int, val message: String,
    val details: Map<String, Any?>)

internal fun pythonRelationTruthy(value: Any?): Boolean = when (value) {
    null -> false
    is Boolean -> value
    is String -> value.isNotEmpty()
    is Number -> value.toDouble() != 0.0
    is Collection<*> -> value.isNotEmpty()
    is Map<*, *> -> value.isNotEmpty()
    else -> true
}
