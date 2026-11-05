package com.finance.tracker.backend

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

internal object CashImportMonthlySummaries {
    private val precision = MathContext(80, RoundingMode.HALF_EVEN)

    fun zone(value: Any?): ZoneId {
        if (value !is String || value.codePointCount(0, value.length) > 128)
            throw CashImportPlanError("plan_input_invalid")
        return when (value) {
            "EST" -> ZoneOffset.ofHours(-5)
            "MST" -> ZoneOffset.ofHours(-7)
            "HST" -> ZoneOffset.ofHours(-10)
            else -> if (value in ZoneId.getAvailableZoneIds()) ZoneId.of(value)
                else throw CashImportPlanError("plan_input_invalid")
        }
    }

    fun summarize(rows: List<*>, timezoneName: Any? = "UTC"): Map<String, Any?> {
        val zone = zone(timezoneName)
        val statuses = listOf("all", "pending", "confirmed")
        val months = statuses.associateWith { sortedSetOf<String>(reverseOrder()) }
        val totals = statuses.associateWith { mutableMapOf<Pair<String, String>, Pair<BigDecimal, BigDecimal>>() }
        for (raw in rows) {
            val row = raw as Map<*, *>
            val text = row["occurred_at"] as String?
            if (text.isNullOrEmpty()) continue
            val instant = try { OffsetDateTime.parse(text).toInstant() }
                catch (_: DateTimeParseException) { LocalDateTime.parse(text).toInstant(ZoneOffset.UTC) }
            val month = DateTimeFormatter.ofPattern("uuuu-MM").format(instant.atZone(zone))
            val state = row["confirmation_status"] as String
            check(state in setOf("pending", "confirmed"))
            for (status in listOf("all", state)) {
                months.getValue(status).add(month)
                if (row["statistics_eligible"] != true || row["economic_type"] !in setOf("income", "expense")) continue
                val currency = row["currency"] as String?
                val amount = row["amount"] ?: continue
                if (currency.isNullOrEmpty()) continue
                val money = BigDecimal(amount.toString())
                val key = month to currency
                val (income, expense) = totals.getValue(status)[key] ?: (BigDecimal.ZERO to BigDecimal.ZERO)
                totals.getValue(status)[key] = if (row["economic_type"] == "income")
                    income.add(money, precision) to expense else income to expense.add(money, precision)
            }
        }
        return statuses.associateWith { status -> months.getValue(status).map { month ->
            mapOf("month" to month, "currencies" to totals.getValue(status).keys.filter { it.first == month }
                .sortedBy { it.second }.map { key ->
                    val (income, expense) = totals.getValue(status).getValue(key)
                    mapOf("currency" to key.second, "income" to income.toString(), "expense" to expense.toString())
                })
        } }
    }
}
