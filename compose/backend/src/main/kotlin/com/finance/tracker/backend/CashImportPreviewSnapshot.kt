package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.CASH_SOURCE_BINDINGS
import com.finance.tracker.backend.persistence.generated.tables.references.CASH_TRANSACTIONS
import java.math.BigInteger
import java.time.Instant
import kotlinx.serialization.json.JsonElement

internal typealias CashImportPreviewSnapshot = com.finance.tracker.business.domain.CashImportPreviewSnapshot<BigInteger, Instant>

/** 在预览的同一只读上下文中构建预计财务事实；真实写入由确认事务另行处理。 */
internal class CashImportPreviewSnapshotBuilder(private val context: CashSqlContext, private val now: () -> Instant = context.now) {
    private val sql get() = context.sql
    private val workspace get() = context.workspace
    private fun copy(value: Map<*, *>): MutableMap<String, Any?> {
        @Suppress("UNCHECKED_CAST") val result = CashRecordPolicy.copyValue(value) as MutableMap<String, Any?>
        return result
    }
    private fun json(value: JsonElement?): Any? = cashSourceValue(value)
    private fun amount(value: Any?): String? = when (value) {
        null -> null
        is CashAmount -> value.toString()
        is CashStoredDecimal -> value.text
        else -> value.toString()
    }
    internal fun scopes(ids: Set<Long>): Map<Pair<String, String>, String> {
        val table = CASH_SOURCE_BINDINGS
        val grouped = linkedMapOf<Pair<String, String>, MutableMap<String, MutableSet<String>>>()
        for (batch in ids.sorted().chunked(400)) {
            sql.select(table.TRANSACTION_ID, table.SOURCE_TYPE, table.SOURCE_SCOPE_KIND, table.SOURCE_SCOPE_KEY)
                .from(table).where(table.WORKSPACE_ID.eq(workspace)).and(table.TRANSACTION_ID.`in`(batch)).fetch().forEach { row ->
                    val key = row[table.TRANSACTION_ID]!!.toString() to row[table.SOURCE_TYPE]!!
                    grouped.getOrPut(key) { linkedMapOf() }.getOrPut(row[table.SOURCE_SCOPE_KIND]!!) { linkedSetOf() }
                        .add(row[table.SOURCE_SCOPE_KEY]!!)
                }
        }
        return grouped.mapValues { (key, kinds) ->
            val account = kinds["account"].orEmpty()
            val file = kinds["file_local"].orEmpty()
            when {
                account.size == 1 -> account.first()
                account.isNotEmpty() -> "ambiguous:${key.first}"
                file.size == 1 -> "file:${file.first()}"
                else -> "ambiguous:${key.first}"
            }
        }
    }

    private fun currentValues(ids: Set<Long>, scopes: Map<Pair<String, String>, String>): MutableMap<String, MutableMap<String, Any?>> {
        val table = CASH_TRANSACTIONS
        val result = linkedMapOf<String, MutableMap<String, Any?>>()
        for (batch in ids.sorted().chunked(400)) {
            sql.selectFrom(table).where(table.WORKSPACE_ID.eq(workspace)).and(table.ID.`in`(batch)).fetch().forEach { row ->
                val id = row[table.ID]!!.toString()
                val source = row[table.SOURCE_TYPE]
                result[id] = linkedMapOf(
                    "counterparty" to row[table.COUNTERPARTY]!!,
                    "counterparty_account" to row[table.COUNTERPARTY_ACCOUNT]!!,
                    "counterparty_account_attrs" to json(row[table.COUNTERPARTY_ACCOUNT_ATTRS]),
                    "note" to row[table.NOTE]!!,
                    "category_id" to row[table.CATEGORY_ID],
                    "record_type" to row[table.RECORD_TYPE],
                    "record_subtype" to row[table.RECORD_SUBTYPE],
                    "source_payload" to json(row[table.SOURCE_PAYLOAD]),
                    "amount" to row[table.AMOUNT]?.let { CashRecordPolicy.normalize("amount", it.text) },
                    "currency" to row[table.CURRENCY],
                    "occurred_at" to row[table.OCCURRED_AT],
                    "account_id" to row[table.ACCOUNT_ID]?.toString(),
                    "components" to mutableListOf<MutableMap<String, Any?>>(),
                    "cash_granularity" to row[table.CASH_GRANULARITY]!!,
                    "source_type" to source,
                    "record_id" to row[table.RECORD_ID]!!,
                    "state" to row[table.STATE]!!,
                    "created_at" to row[table.CREATED_AT]!!,
                    "record_origin" to row[table.RECORD_ORIGIN]!!,
                    "manual_transfer_id" to row[table.MANUAL_TRANSFER_ID],
                    "source_explanation" to extractCashSourceExplanation(json(row[table.RELATION_METADATA])),
                    "source_scope_key" to scopes[id to source].let { it ?: "unbound:$id" },
                    "matching_record_id" to "occurrence:$id",
                )
            }
        }
        return result
    }

    private fun fact(id: String, value: Map<String, Any?>, attention: Boolean): CashProjectionFact = CashProjectionFact.create(
        BigInteger(id), (value["account_id"] as? String)?.toLong(), value["occurred_at"] as? Instant,
        amount(value["amount"]), value["currency"] as? String, value["counterparty"] as? String ?: "",
        value["category_id"] as? String, value["note"] as? String ?: "", value["source_type"] as? String,
        value["record_id"] as? String ?: "", cashGranularity = value["cash_granularity"] as String,
        state = value["state"] as String, createdAt = value["created_at"] as Instant, needsAttention = attention,
        recordType = value["record_type"] as? String, recordSubtype = value["record_subtype"] as? String,
        stableSourceKey = (value["stable_source_key"] as? List<*>)?.map { it.toString() }.orEmpty(),
        recordOrigin = value["record_origin"]?.toString() ?: "legacy",
    )

    fun build(prepared: PreparedCashImportPreview, rows: List<Map<String, Any?>>): CashImportPreviewSnapshot {
        context.requireActive()
        val graph = prepared.context
        val values = currentValues(graph.transactionIds, scopes(graph.transactionIds))
        for ((id, key) in graph.sourceOrderKeys) values[id.toString()]?.put("stable_source_key", key)
        val current = values.keys.map(String::toLong).toSet()
        for (part in graph.componentVersions) {
            val row = values[part.transactionId.toString()] ?: continue
            @Suppress("UNCHECKED_CAST") val components = row["components"] as MutableList<MutableMap<String, Any?>>
            components += linkedMapOf("component_id" to part.id.toString(), "account_id" to part.accountId?.toString(),
                "amount" to part.amount?.let { CashRecordPolicy.normalize("amount", it) }, "currency" to part.currency,
                "ordinal" to part.ordinal)
        }
        val facts = graph.facts.associateByTo(linkedMapOf()) { it.id.toString() }
        val components = graph.components.associateByTo(linkedMapOf()) { it.componentId as String }
        val edges = graph.relations.associateByTo(linkedMapOf()) { strictCashInteger(it.id)!! }
        val funding = graph.fundingVersions.associateByTo(linkedMapOf()) { it.id }
        val operations = prepared.operations.mapTo(mutableListOf(), ::copy)
        val snapshot = CashImportPreviewSnapshot(graph, values, facts, components, edges, funding, linkedMapOf(),
            linkedMapOf(), linkedSetOf(), linkedSetOf(), operations, rows.mapTo(mutableListOf(), ::copy),
            projectionValues = cashProjectionValues, integer = ::strictCashInteger, graphBuilder = ::buildCashRelationGraph,
            projectionBuilder = { facts, relations -> buildCashProjections(facts, relations) })
        for ((id, value) in values) if (value["state"] == "draft" && id.toLong() in graph.activeTransactionIds)
            snapshot.facts[id] = fact(id, value, true)
        com.finance.tracker.business.domain.CashImportPreviewOverlayRules(
            CashRecordPolicy, now, { kind, values -> cashImportVirtual(kind, *values.toTypedArray()) },
            ::fact, ::cashFundingRoleCompatible).apply(snapshot)
        val (members, entries) = context.entries.forTransactions(current)
        com.finance.tracker.business.domain.applyCashImportPreviewEntryPublic(snapshot, members, entries, ::json)
        return snapshot
    }
}
