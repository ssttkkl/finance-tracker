package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import kotlinx.serialization.json.*

/** 已验证操作的事务内收尾；调用方负责重验、授权、收据及提交。 */
internal class CashPlanFinalizer(private val context: CashSqlContext) {
    @Suppress("UNCHECKED_CAST") private fun map(value: Any?) = value as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") private fun maps(value: Any?) = value as List<Map<String, Any?>>
    private fun json(value: Map<String, Any?>?) = value?.let { sourceJson(it).jsonObject }
    private fun counted(value: Map<String, Any?>?) = cashCountsTowardBalance(json(value))
    private fun components(value: Map<String, Any?>) = maps(value["components"])
    private fun finances(value: Map<String, Any?>?): List<List<Any?>> = if (!counted(value)) emptyList() else components(value!!).map {
        listOf(it["account_id"], it["amount"], it["currency"])
    }
    private data class Account(val name: String, val type: String)
    private fun accounts(): Map<String, Account> = context.sql.select(ACCOUNTS.ID, ACCOUNTS.NAME, ACCOUNTS.TYPE).from(ACCOUNTS)
        .where(ACCOUNTS.WORKSPACE_ID.eq(context.workspace)).fetch().associate { it.value1().toString() to Account(it.value2()!!, it.value3()!!) }

    fun balances(changes: List<Pair<Map<String, Any?>?, Map<String, Any?>?>>) {
        context.requireActive()
        val transfers = changes.filter { (_, after) -> after?.get("record_origin") == "manual_transfer" }
            .filter { (before, after) -> !CashRecordPolicy.valuesEqual(before?.get("components"), after?.get("components")) }
        val relevant = changes.filter { (before, after) -> (after ?: before)?.get("record_origin") != "manual_transfer" && finances(before) != finances(after) }
        if (transfers.isEmpty() && relevant.isEmpty()) return
        val accounts = accounts()
        if (transfers.isNotEmpty()) {
            val snapshot = context.snapshot.load(lock = true)
            for ((before, after) in transfers) for ((value, sign) in listOf(before to -1, after to 1)) {
                if (value == null || value.isEmpty()) continue
                for (component in components(value)) {
                    val account = accounts.getValue(component["account_id"].toString())
                    val amount = CashAmount.parse(component["amount"].toString())
                    val text = amount.toString().let { if (sign == 1) it else if (it.startsWith('-')) it.drop(1) else "-$it" }
                    snapshot.value = applyCashTransferSnapshot(snapshot.value, CashTransferAccount(account.name, account.type), component["currency"] as String, CashAmount.parse(text))
                }
            }
            snapshot.value = JsonObject(snapshot.value + ("updated_at" to sourceJson(transfers.last().second!!.getValue("occurred_at"))))
            context.snapshot.save(snapshot)
        }
        if (relevant.isEmpty()) return
        val snapshot = context.snapshot.load(lock = true)
        var latest: Any? = null
        for ((before, after) in relevant) for ((value, multiplier) in listOf(before to -1, after to 1)) {
            if (!counted(value)) continue
            val parts = components(value!!).map { component ->
                val account = accounts.getValue(component["account_id"] as String)
                component + mapOf("account_name" to account.name, "account_type" to account.type)
            }
            CashBalanceService(context).applyComponents(snapshot, json(value + ("components" to parts)), multiplier)
            latest = value["occurred_at"]
        }
        snapshot.value = JsonObject(snapshot.value + ("updated_at" to sourceJson(latest)))
        context.snapshot.save(snapshot)
    }

    fun attentionCount(affected: Set<Long>): Int {
        val repository = CashRelationReadRepository(context)
        return repository.attention(affected, repository.graphRowsForTransactions(affected)).size
    }

    companion object {
        @Suppress("UNCHECKED_CAST") fun rowResults(plan: Map<String, Any?>, actual: Map<String, Map<String, Any?>>): List<Map<String, Any?>> =
            (plan["row_decisions"] as List<Map<String, Any?>>).map { row ->
                val reference = row["operation_ref"] as String?
                val item = actual[reference]?.let { if (row["planned_action"] == "duplicate_in_batch") it + ("outcome" to "duplicate_in_batch") else it.toMap() }
                    ?: if (row["planned_action"] == "skip") mapOf("outcome" to "skipped", "code" to row.getOrDefault("code", "row_unreadable"))
                    else mapOf("outcome" to if (row["planned_action"] == "duplicate_in_batch") "duplicate_in_batch" else "duplicate", "transaction_id" to row["transaction_id"])
                mapOf("result_kind" to "row", "item_ref" to row["row_ref"], "operation_ref" to reference) + item
            }
    }
}
