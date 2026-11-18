package com.finance.tracker.business.domain

/** Aggregate every frozen review row in its original order, before pagination. */
class CashImportMonthlySummaryRules<A>(
    private val zero: A,
    private val amount: (Any) -> A,
    private val add: (A, A) -> A,
    private val text: (A) -> String,
) {
    fun summarize(rows: List<*>, monthAt: (String) -> String): Map<String, Any?> {
        val statuses = listOf("all", "pending", "confirmed")
        val months = statuses.associateWith { mutableSetOf<String>() }
        val totals = statuses.associateWith { mutableMapOf<Pair<String, String>, Pair<A, A>>() }
        for (raw in rows) {
            val row = raw as Map<*, *>
            val occurred = row["occurred_at"] as String?
            if (occurred.isNullOrEmpty()) continue
            val month = monthAt(occurred)
            val state = row["confirmation_status"] as String
            check(state in setOf("pending", "confirmed"))
            for (status in listOf("all", state)) {
                months.getValue(status).add(month)
                if (row["statistics_eligible"] != true || row["economic_type"] !in setOf("income", "expense")) continue
                val currency = row["currency"] as String?
                val value = row["amount"] ?: continue
                if (currency.isNullOrEmpty()) continue
                val money = amount(value)
                val key = month to currency
                val (income, expense) = totals.getValue(status)[key] ?: (zero to zero)
                totals.getValue(status)[key] = if (row["economic_type"] == "income") add(income, money) to expense
                    else income to add(expense, money)
            }
        }
        return statuses.associateWith { status -> months.getValue(status).sortedDescending().map { month ->
            mapOf("month" to month, "currencies" to totals.getValue(status).keys.filter { it.first == month }
                .sortedBy { it.second }.map { key ->
                    val (income, expense) = totals.getValue(status).getValue(key)
                    mapOf("currency" to key.second, "income" to text(income), "expense" to text(expense))
                })
        } }
    }
}

/** Preserve the import summary's 80-digit accumulation and decimal scale. */
fun summarizeLocalCashImportMonths(rows: List<*>, monthAt: (String) -> String): Map<String, Any?> =
    CashImportMonthlySummaryRules(LedgerArithmeticValue.ZERO,
        { LedgerArithmeticValue.fromCanonical(if (it is kotlinx.serialization.json.JsonPrimitive) it.content else it.toString()) },
        { left, right -> left.add(right, 80) }, LedgerArithmeticValue::toString).summarize(rows, monthAt)
