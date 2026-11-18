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
        return com.finance.tracker.business.domain.CashImportMonthlySummaryRules(BigDecimal.ZERO,
            { BigDecimal(it.toString()) }, { left, right -> left.add(right, precision) }, BigDecimal::toString)
            .summarize(rows) { text ->
                val instant = try { OffsetDateTime.parse(text).toInstant() }
                    catch (_: DateTimeParseException) { LocalDateTime.parse(text).toInstant(ZoneOffset.UTC) }
                DateTimeFormatter.ofPattern("uuuu-MM").format(instant.atZone(zone))
            }
    }
}
