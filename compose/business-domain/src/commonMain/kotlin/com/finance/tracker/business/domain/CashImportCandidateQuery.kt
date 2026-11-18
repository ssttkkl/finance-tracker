package com.finance.tracker.business.domain

import kotlinx.serialization.json.JsonArray
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** Read conditions only. Workspace, persisted eligibility and key lookup remain the reader's responsibility. */
data class CashImportCandidateQuery<T>(val recordTypes: Set<String>, val windows: List<Pair<T, T>>,
    val expenseOnly: Boolean, val hardKeys: List<CashImportCandidateHardKeys>)

data class CashImportCandidateHardKeys(val source: String, val scope: String?, val accounts: List<Long>,
    val transactionKeys: Set<String>, val merchantKeys: Set<String>) {
    val expandedTransactionKeys: Set<String> get() = buildSet {
        addAll(transactionKeys)
        if (source == "alipay") for (value in transactionKeys) value.forEachIndexed { index, char ->
            if (index > 0 && char in "_*") add(value.substring(0, index))
        }
    }
    val allKeys: Set<String> get() = expandedTransactionKeys + merchantKeys
}

/** Native and local previews use identical window merging, request grouping and source-key precedence. */
class CashImportCandidateQueryRules<T : Comparable<T>>(private val timestamp: (Any?) -> T,
    private val shiftDays: (T, Int) -> T, private val negative: (Any?) -> Boolean) {
    @Suppress("UNCHECKED_CAST")
    private fun values(operation: Map<String, Any?>) = operation["values"] as Map<String, Any?>

    fun prepare(operations: List<Map<String, Any?>>, sourceRows: Map<String, SourceOccurrence> = emptyMap()): CashImportCandidateQuery<T> {
        if (operations.isEmpty()) return CashImportCandidateQuery(emptySet(), emptyList(), false, emptyList())
        val types = cashRelationCandidateTypes(operations.map { values(it)["record_type"] as String }.toSet())
        val windows = operations.map { operation -> timestamp(values(operation)["occurred_at"]).let {
            shiftDays(it, -61) to shiftDays(it, 61)
        } }.sortedBy { it.first }
        val merged = mutableListOf<Pair<T, T>>()
        for ((start, end) in windows) {
            if (merged.isNotEmpty() && start <= merged.last().second) {
                val last = merged.removeAt(merged.lastIndex)
                merged += last.first to maxOf(last.second, end)
            } else merged += start to end
        }
        val expenseOnly = operations.all { values(it)["record_type"] == "consumption" && negative(values(it)["amount"]) }
        return CashImportCandidateQuery(types, merged, expenseOnly, hardKeys(operations, sourceRows))
    }

    private data class Request(val source: String, val scope: String?, val accounts: List<Long>)
    private class Keys(val transaction: MutableSet<String> = linkedSetOf(), val merchant: MutableSet<String> = linkedSetOf())

    private fun hardKeys(operations: List<Map<String, Any?>>, sourceRows: Map<String, SourceOccurrence>): List<CashImportCandidateHardKeys> {
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
        return requests.map { (request, keys) -> CashImportCandidateHardKeys(request.source, request.scope,
            request.accounts, keys.transaction.toSet(), keys.merchant.toSet()) }
    }
}

/** Build a query from normalized local operations, retaining exact account IDs and timestamp arithmetic. */
fun prepareLocalCashImportCandidateQuery(operations: JsonArray,
    sourceRows: Map<String, SourceOccurrence> = emptyMap()): CashImportCandidateQuery<Instant> {
    @Suppress("UNCHECKED_CAST") val input = decodeLocalCashValue(operations) as List<Map<String, Any?>>
    return prepareLocalCashImportCandidateQuery(input, sourceRows)
}

/** Preserve computed operation types when preparing a query inside the local pipeline. */
fun prepareLocalCashImportCandidateQuery(operations: List<Map<String, Any?>>,
    sourceRows: Map<String, SourceOccurrence> = emptyMap()): CashImportCandidateQuery<Instant> {
    return CashImportCandidateQueryRules(
        timestamp = { LocalCashRecordValues.normalize("occurred_at", it) as Instant },
        shiftDays = { instant: Instant, days: Int -> instant + days.days },
        negative = { (LocalCashRecordValues.normalize("amount", it) as CashAmount).numericText().startsWith('-') },
    ).prepare(operations, sourceRows)
}
