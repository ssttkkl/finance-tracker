package com.finance.tracker.presentation

import androidx.compose.runtime.Composable
import com.finance.tracker.core.*
import com.finance.tracker.domain.*

private val IMPORT_ZERO_AMOUNT = Regex("^[+-]?0+(?:\\.0+)?$")

internal data class ImportPreviewMonthGroup(
    val month: String,
    val items: List<ImportPreviewItem>,
    val summary: CashMonthlySummary?,
)

internal fun importPreviewAmountLabel(item: ImportPreviewItem): String {
    val amount = item.amount
    val label = when {
        IMPORT_ZERO_AMOUNT.matches(amount.trim()) -> amount.removePrefix("+").removePrefix("-")
        amount.startsWith("-") || amount.startsWith("+") -> amount
        else -> "+$amount"
    }
    return "$label ${item.currency}"
}

internal fun importPreviewDirection(item: ImportPreviewItem): String {
    if (IMPORT_ZERO_AMOUNT.matches(item.amount.trim())) return "unknown"
    if (item.amount.startsWith("-")) return "expense"
    if (item.amount.startsWith("+") || item.amount != "0") return "income"
    return when (item.recordType) {
        "transfer_out", "withdrawal_out" -> "expense"
        "transfer_in", "withdrawal_in" -> "income"
        else -> "unknown"
    }
}

@Composable
internal fun importPreviewDateTimeLabel(instant: String): String {
    return localDateTimeDisplayLabel(instant)
}

internal fun importPreviewMonthlySummaries(items: List<ImportPreviewItem>): List<CashMonthlySummary> {
    val months = linkedMapOf<String, LinkedHashMap<String, MutableImportCurrencySummary>>()
    items.forEach { item ->
        val direction = importPreviewDirection(item)
        if (direction != "income" && direction != "expense") return@forEach
        val amount = displayDecimalAbs(item.amount) ?: return@forEach
        val month = localMonthKey(item.occurredAt)
        val currencies = months.getOrPut(month) { linkedMapOf() }
        val totals = currencies.getOrPut(item.currency) { MutableImportCurrencySummary() }
        if (direction == "income") {
            totals.income = displayDecimalAdd(totals.income, amount) ?: totals.income
        } else {
            totals.expense = displayDecimalAdd(totals.expense, amount) ?: totals.expense
        }
    }
    return months.entries
        .sortedByDescending { if (it.key == "unknown") "" else it.key }
        .map { (month, currencies) ->
            CashMonthlySummary(
                month = month,
                currencies = currencies.map { (currency, totals) ->
                    CashMonthlyCurrencySummary(currency, totals.income, totals.expense)
                },
            )
        }
}

internal fun importPreviewMonthGroups(items: List<ImportPreviewItem>): List<ImportPreviewMonthGroup> {
    val summaries = importPreviewMonthlySummaries(items).associateBy(CashMonthlySummary::month)
    val grouped = items
        .sortedByDescending { localDateTimeSortKey(it.occurredAt) }
        .groupBy { localMonthKey(it.occurredAt) }
    return grouped.entries
        .sortedByDescending { if (it.key == "unknown") "" else it.key }
        .map { (month, rows) -> ImportPreviewMonthGroup(month, rows, summaries[month]) }
}

@Composable
internal fun importPreviewMonthLabel(month: String): String {
    return localMonthLabel(month)
}

internal fun importPreviewSummaryAmount(direction: String, amount: String): String {
    return displaySignedSummaryAmount(direction, amount)
}

private data class MutableImportCurrencySummary(
    var income: String = "0",
    var expense: String = "0",
)
