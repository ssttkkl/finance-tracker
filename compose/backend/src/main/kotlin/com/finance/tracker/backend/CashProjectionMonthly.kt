package com.finance.tracker.backend

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.serialization.json.*

internal data class CashProjectionMonthlyData(val summaries: JsonArray, val positions: JsonArray, val total: Long, val pending: Long)

internal class CashProjectionMonthlyAccumulator(private val zone: ZoneId) {
    private val months = sortedSetOf<String>(reverseOrder())
    private val totals = sortedMapOf<Pair<String, String>, Pair<BigDecimal, BigDecimal>>(compareBy({ it.first }, { it.second }))
    private val positions = mutableListOf<Triple<String, Long, Long>>()
    private var count = 0L
    private var pending = 0L

    fun add(occurred: Instant?, economic: String?, amount: BigDecimal?, currency: String?, eligible: Boolean = true, attention: Long = 0) {
        val month = occurred?.atZone(zone)?.let { "%04d-%02d".format(Locale.ROOT, it.year, it.monthValue) } ?: "unknown"
        if (positions.lastOrNull()?.first == month) positions[positions.lastIndex] = positions.last().copy(third = count + 1)
        else positions += Triple(month, count, count + 1)
        count++; if (attention > 0) pending++
        if (eligible && occurred != null) {
            months += month
            if (economic in setOf("income", "expense") && !currency.isNullOrEmpty()) {
                val key = month to currency.trim { it.code in pythonWhitespace }.uppercase(Locale.ROOT)
                val (income, expense) = totals[key] ?: (BigDecimal.ZERO to BigDecimal.ZERO)
                val value = requireNotNull(amount)
                totals[key] = if (economic == "income") income + value to expense else income to expense + value
            }
        }
    }
    fun result(): CashProjectionMonthlyData {
        fun money(value: BigDecimal) = if (value.signum() == 0) "0" else value.stripTrailingZeros().toPlainString()
        return CashProjectionMonthlyData(JsonArray(months.map { month -> buildJsonObject {
            put("month", month); put("currencies", JsonArray(totals.filterKeys { it.first == month }.map { (key, values) -> buildJsonObject {
                put("currency", key.second); put("income", money(values.first)); put("expense", money(values.second))
            } }))
        } }), JsonArray(positions.map { (month, first, end) -> buildJsonObject { put("month", month); put("start_index", first); put("end_index", end) } }), count, pending)
    }
}
