package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.JsonElement
import org.jooq.Condition
import org.jooq.Field
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

/** 预览关系发现之前的只读父流水候选召回。调用方仅传新建正式或草稿转正式操作；最终关系仍由完整图规则决定。 */
internal class CashImportCandidateRepository(private val context: CashSqlContext) {
    private val sql get() = context.sql
    private val workspace get() = context.workspace
    private val window = Duration.ofDays(61)

    fun candidateIds(operations: List<Map<String, Any?>>, sourceRows: Map<String, SourceOccurrence> = emptyMap()): Set<Long> {
        context.requireActive()
        if (operations.isEmpty()) return emptySet()
        val types = cashRelationCandidateTypes(operations.map { values(it)["record_type"] as String }.toSet())
        val windows = operations.map { operation ->
            val instant = CashRecordPolicy.normalize("occurred_at", values(operation)["occurred_at"]) as Instant
            instant.minus(window) to instant.plus(window)
        }.sortedBy { it.first }
        val merged = mutableListOf<Pair<Instant, Instant>>()
        for ((start, end) in windows) {
            if (merged.isNotEmpty() && start <= merged.last().second) {
                val last = merged.removeAt(merged.lastIndex)
                merged += last.first to maxOf(last.second, end)
            } else merged += start to end
        }
        val expenseOnly = operations.all {
            values(it)["record_type"] == "consumption" &&
                (CashRecordPolicy.normalize("amount", values(it)["amount"]) as CashAmount).numericText().startsWith('-')
        }
        val result = linkedSetOf<Long>()
        val tx = CASH_TRANSACTIONS
        for (batch in merged.chunked(100)) {
            if (types.isEmpty()) break
            val periods = batch.map { (start, end) -> tx.OCCURRED_AT.between(start, end) }
                .reduce(Condition::or)
            sql.select(tx.ID, tx.SOURCE_TYPE).from(tx).where(tx.WORKSPACE_ID.eq(workspace))
                .and(tx.RECORD_ORIGIN.`in`("import", "legacy")).and(tx.STATE.eq("posted"))
                .and(tx.DELETED_AT.isNull).and(tx.RECORD_TYPE.`in`(types)).and(periods).fetch().forEach { row ->
                    val source = row[tx.SOURCE_TYPE]
                    if (!expenseOnly || source.isNullOrBlank() ||
                        CashRelationCore.sourceGroup(RelationFact("candidate", BigDecimal.ZERO, "", "",
                            billSource = source, source = source)) != "other") result += row[tx.ID]!!
                }
        }
        result += hardKeyIds(operations, sourceRows)
        return result
    }

    @Suppress("UNCHECKED_CAST")
    private fun values(operation: Map<String, Any?>) = operation["values"] as Map<String, Any?>

    private data class Request(val source: String, val scope: String?, val accounts: List<Long>)
    private data class Keys(val transaction: MutableSet<String> = linkedSetOf(),
        val merchant: MutableSet<String> = linkedSetOf())

    private fun hardKeyIds(operations: List<Map<String, Any?>>, sourceRows: Map<String, SourceOccurrence>): Set<Long> {
        val requests = linkedMapOf<Request, Keys>()
        for (operation in operations) {
            val fields = values(operation)
            val source = fields["source_type"] as? String ?: continue
            if (source !in setOf("alipay", "wechat")) continue
            val rowRef = (operation["row_refs"] as? List<*>)?.firstOrNull() as? String
            val occurrence = rowRef?.let(sourceRows::get)
            val scope = if (occurrence != null) occurrence.accountScopeKey else operation["source_scope_key"] as? String
            val components = fields["components"] as? List<*> ?: emptyList<Any?>()
            val accounts = components.map { (it as Map<*, *>)["account_id"].toString().toLong() }.distinct().sorted()
            val metadata = (operation["source_explanation"] ?: operation["matching_source_explanation"]) as? Map<*, *>
                ?: emptyMap<Any?, Any?>()
            val keys = requests.getOrPut(Request(source, scope, accounts), ::Keys)
            fun add(key: String, fallback: String?, output: MutableSet<String>) {
                val value = (if (key in metadata) metadata[key] else fallback) as? String
                value?.trim()?.takeIf(String::isNotEmpty)?.let(output::add)
            }
            add("txn_id", occurrence?.providerId, keys.transaction)
            add("merchant_order_id", null, keys.merchant)
        }
        val result = linkedSetOf<Long>()
        for ((request, keys) in requests) {
            val transactionKeys = keys.transaction.toMutableSet()
            if (request.source == "alipay") for (value in keys.transaction) {
                value.forEachIndexed { index, char ->
                    if (index > 0 && char in "_*") transactionKeys += value.substring(0, index)
                }
            }
            if (request.scope != null) for (batch in transactionKeys.map(::providerIdentityKey).chunked(400)) {
                if (batch.isEmpty()) continue
                val binding = CASH_SOURCE_BINDINGS
                val tx = CASH_TRANSACTIONS
                sql.select(binding.TRANSACTION_ID).from(binding).join(tx)
                    .on(tx.WORKSPACE_ID.eq(binding.WORKSPACE_ID).and(tx.ID.eq(binding.TRANSACTION_ID)))
                    .where(binding.WORKSPACE_ID.eq(workspace).and(binding.SOURCE_TYPE.eq(request.source))
                        .and(binding.SOURCE_SCOPE_KIND.eq("account")).and(binding.SOURCE_SCOPE_KEY.eq(request.scope))
                        .and(binding.IDENTITY_KIND.eq("provider_id")).and(binding.IDENTITY_KEY.`in`(batch)))
                    .and(tx.RECORD_ORIGIN.`in`("import", "legacy")).and(tx.STATE.eq("posted"))
                    .and(tx.DELETED_AT.isNull).fetch(binding.TRANSACTION_ID).filterNotNullTo(result)
            }
            val allKeys = transactionKeys + keys.merchant
            if (request.accounts.isEmpty() || allKeys.isEmpty()) continue
            val tx = CASH_TRANSACTIONS
            val part = CASH_TRANSACTION_COMPONENTS
            val accountParents = DSL.select(part.CASH_TRANSACTION_ID).from(part)
                .where(part.WORKSPACE_ID.eq(workspace).and(part.ACCOUNT_ID.`in`(request.accounts)))
            val scopeCondition = scopeCondition(tx.ID, request)
            val transaction = sourceKey(tx.RELATION_METADATA, tx.SOURCE_PAYLOAD, "txn_id",
                if (request.source == "alipay") listOf("交易号") else listOf("交易单号"))
            val merchant = sourceKey(tx.RELATION_METADATA, tx.SOURCE_PAYLOAD, "merchant_order_id",
                listOf("商家订单号", "商户单号"))
            for (batch in allKeys.chunked(100)) {
                val terms = mutableListOf<Condition>(transaction.`in`(batch), merchant.`in`(batch))
                if (request.source == "alipay") for (value in batch) if (value in keys.transaction) {
                    terms += transaction.startsWith(value + "_")
                    terms += transaction.startsWith(value + "*")
                }
                sql.select(tx.ID).from(tx).where(tx.WORKSPACE_ID.eq(workspace).and(tx.SOURCE_TYPE.eq(request.source))
                    .and(tx.RECORD_ORIGIN.`in`("import", "legacy")).and(tx.ID.`in`(accountParents))
                    .and(tx.STATE.eq("posted")).and(tx.DELETED_AT.isNull).and(scopeCondition)
                    .and(terms.reduce(Condition::or))).fetch(tx.ID).filterNotNullTo(result)
            }
        }
        return result
    }

    private fun scopeCondition(transactionId: Field<Long?>, request: Request): Condition {
        val scope = request.scope ?: return DSL.noCondition()
        val binding = CASH_SOURCE_BINDINGS.`as`("candidate_scope")
        val same = binding.WORKSPACE_ID.eq(workspace).and(binding.TRANSACTION_ID.eq(transactionId))
            .and(binding.SOURCE_SCOPE_KIND.eq("account"))
        val conflicting = DSL.exists(DSL.selectOne().from(binding).where(same.and(binding.SOURCE_SCOPE_KEY.ne(scope))))
        var condition = conflicting.not()
        if (request.source == "wechat" && scope != CashSourceScope.WECHAT_DEFAULT_SCOPE)
            condition = condition.and(DSL.exists(DSL.selectOne().from(binding)
                .where(same.and(binding.SOURCE_SCOPE_KEY.eq(scope)))))
        return condition
    }

    private fun jsonText(field: Field<JsonElement?>, key: String): Field<String> = if (context.sqlite)
        DSL.function("json_extract", SQLDataType.VARCHAR, field.coerce(SQLDataType.JSON), DSL.inline("$.${key}"))
    else DSL.jsonbGetAttributeAsText(field.cast(SQLDataType.JSONB), DSL.inline(key))

    private fun sourceKey(metadata: Field<JsonElement?>, payload: Field<JsonElement?>, native: String,
        sourceKeys: List<String>): Field<String> {
        val fields = listOf(jsonText(metadata, native), jsonText(payload, native)) + sourceKeys.map { jsonText(payload, it) }
        return DSL.function("coalesce", SQLDataType.VARCHAR, *fields.toTypedArray())
    }
}
