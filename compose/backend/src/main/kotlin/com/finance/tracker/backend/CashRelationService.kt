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
}

internal fun pythonRelationTruthy(value: Any?): Boolean = when (value) {
    null -> false
    is Boolean -> value
    is String -> value.isNotEmpty()
    is Number -> value.toDouble() != 0.0
    is Collection<*> -> value.isNotEmpty()
    is Map<*, *> -> value.isNotEmpty()
    else -> true
}
