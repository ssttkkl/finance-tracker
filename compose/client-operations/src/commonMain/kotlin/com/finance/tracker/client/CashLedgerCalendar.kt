package com.finance.tracker.client

import com.finance.tracker.business.domain.CashLedgerDateBounds
import com.finance.tracker.business.domain.CashImportPlanError
import com.finance.tracker.business.domain.CashProjectionDomainFailure
import com.finance.tracker.business.domain.formatPythonCashTimestamp
import com.finance.tracker.business.domain.normalizeCashLedgerFilterDate
import com.finance.tracker.business.domain.parsePythonCashTimestamp
import com.finance.tracker.business.domain.pythonWhitespace
import com.finance.tracker.business.domain.pythonCodePointCount
import com.finance.tracker.business.domain.cashLedgerMonthAtOffset
import kotlin.time.Instant

/** Platform time-zone data supplies offsets and local day boundaries. */
class CashLedgerCalendar(
    private val supportsZone: (String) -> Boolean,
    private val platformOffset: (String, String) -> Int,
    private val startOfDay: (String, String) -> Instant,
) {
    fun monthAt(instant: Instant, timeZoneId: String): String =
        cashLedgerMonthAtOffset(instant, offsetSeconds(instant, timeZoneId))

    fun offsetSeconds(instant: Instant, timeZoneId: String): Int =
        fixedOffset(timeZoneId) ?: platformOffset(instant.epochSeconds.toString(), timeZoneId)

    fun bounds(from: String?, to: String?, zone: String?): CashLedgerDateBounds {
        val first = from?.takeIf { it.isNotEmpty() }?.let(::normalizeCashLedgerFilterDate)
        val last = to?.takeIf { it.isNotEmpty() }?.let(::normalizeCashLedgerFilterDate)
        if (first != null && last != null && first > last) throw CashProjectionDomainFailure("invalid_filter")
        if (last == "9999-12-31") throw CashProjectionDomainFailure("internal")
        val name = (zone?.takeIf { it.isNotEmpty() } ?: "UTC").trim { it.code in pythonWhitespace }
        val fixedOffset = fixedOffset(name)
        if (fixedOffset == null && (name !in cashLedgerTimeZoneIds || !supportsZone(name)))
            throw CashProjectionDomainFailure("invalid_filter")
        fun localBoundary(date: String): Instant = if (fixedOffset != null)
            Instant.fromEpochSeconds(parsePythonCashTimestamp(date, allowNaive = true).epochSeconds - fixedOffset)
            else startOfDay(date, name)
        // Add one day in the date's UTC representation, before resolving either local boundary.
        val afterLast = last?.let {
            formatPythonCashTimestamp(Instant.fromEpochSeconds(parsePythonCashTimestamp(it, allowNaive = true).epochSeconds + 86400))
                .substringBefore('T')
        }
        return CashLedgerDateBounds(first, last, name, first?.let(::localBoundary), afterLast?.let(::localBoundary))
    }

    fun validateImportZone(zone: String) {
        if (zone.pythonCodePointCount() > 128 || fixedOffset(zone) == null && (zone !in cashLedgerTimeZoneIds || !supportsZone(zone)))
            throw CashImportPlanError("plan_input_invalid")
    }

    private fun fixedOffset(name: String): Int? = when (name) {
        "EST" -> -18000
        "MST" -> -25200
        "HST" -> -36000
        else -> null
    }
}
