package com.finance.tracker.core

import com.finance.tracker.client.CashLedgerCalendar
import kotlin.time.Instant

internal val localCashLedgerCalendar = CashLedgerCalendar(
    ::cashLedgerPlatformTimeZoneExists,
    ::cashLedgerPlatformOffset,
    ::cashLedgerPlatformStartOfDay,
)

internal fun localCashLedgerMonth(instant: Instant, timeZoneId: String): String =
    localCashLedgerCalendar.monthAt(instant, timeZoneId)

internal fun cashLedgerOffsetSeconds(instant: Instant, timeZoneId: String): Int =
    localCashLedgerCalendar.offsetSeconds(instant, timeZoneId)

internal fun localCashLedgerDateBounds(from: String?, to: String?, zone: String?) =
    localCashLedgerCalendar.bounds(from, to, zone)

internal expect fun cashLedgerPlatformOffset(epochSeconds: String, timeZoneId: String): Int
internal expect fun cashLedgerPlatformTimeZoneExists(timeZoneId: String): Boolean
internal expect fun cashLedgerPlatformStartOfDay(date: String, timeZoneId: String): Instant
