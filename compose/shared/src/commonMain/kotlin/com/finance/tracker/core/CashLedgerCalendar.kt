package com.finance.tracker.core

import com.finance.tracker.business.domain.CashLedgerDateBounds
import com.finance.tracker.business.domain.CashProjectionDomainFailure
import com.finance.tracker.business.domain.formatPythonCashTimestamp
import com.finance.tracker.business.domain.normalizeCashLedgerFilterDate
import com.finance.tracker.business.domain.parsePythonCashTimestamp
import com.finance.tracker.business.domain.pythonWhitespace
import com.finance.tracker.business.domain.cashLedgerMonthAtOffset
import kotlin.time.Instant

/** Use the chosen browser zone, without consulting the device's default calendar or locale. */
internal fun localCashLedgerMonth(instant: Instant, timeZoneId: String): String =
    cashLedgerMonthAtOffset(instant, cashLedgerOffsetSeconds(instant, timeZoneId))

internal fun cashLedgerOffsetSeconds(instant: Instant, timeZoneId: String): Int = when (timeZoneId) {
    "EST" -> -18000
    "MST" -> -25200
    "HST" -> -36000
    else -> cashLedgerPlatformOffset(instant.epochSeconds.toString(), timeZoneId)
}

internal expect fun cashLedgerPlatformOffset(epochSeconds: String, timeZoneId: String): Int


internal fun localCashLedgerDateBounds(from: String?, to: String?, zone: String?): CashLedgerDateBounds {
    val first = from?.takeIf { it.isNotEmpty() }?.let(::normalizeCashLedgerFilterDate)
    val last = to?.takeIf { it.isNotEmpty() }?.let(::normalizeCashLedgerFilterDate)
    if (first != null && last != null && first > last) throw CashProjectionDomainFailure("invalid_filter")
    if (last == "9999-12-31") throw CashProjectionDomainFailure("internal")
    val name = (zone?.takeIf { it.isNotEmpty() } ?: "UTC").trim { it.code in pythonWhitespace }
    val fixedOffset = when (name) { "EST" -> -18000; "MST" -> -25200; "HST" -> -36000; else -> null }
    if (fixedOffset == null && (name !in cashLedgerTimeZoneIds || !cashLedgerPlatformTimeZoneExists(name)))
        throw CashProjectionDomainFailure("invalid_filter")
    fun startOfDay(date: String): Instant = if (fixedOffset != null)
        Instant.fromEpochSeconds(parsePythonCashTimestamp(date, allowNaive = true).epochSeconds - fixedOffset)
        else cashLedgerPlatformStartOfDay(date, name)
    // Add one day in the date's UTC representation, before resolving either local boundary.
    val afterLast = last?.let {
        formatPythonCashTimestamp(Instant.fromEpochSeconds(parsePythonCashTimestamp(it, allowNaive = true).epochSeconds + 86400))
            .substringBefore('T')
    }
    return CashLedgerDateBounds(first, last, name, first?.let(::startOfDay), afterLast?.let(::startOfDay))
}

internal expect fun cashLedgerPlatformTimeZoneExists(timeZoneId: String): Boolean
internal expect fun cashLedgerPlatformStartOfDay(date: String, timeZoneId: String): Instant
