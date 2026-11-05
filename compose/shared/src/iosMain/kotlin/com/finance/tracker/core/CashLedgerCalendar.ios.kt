package com.finance.tracker.core

import kotlinx.datetime.IllegalTimeZoneException
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.time.Instant
import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeZoneWithName

internal actual fun cashLedgerPlatformOffset(epochSeconds: String, timeZoneId: String): Int {
    val zone = requireNotNull(NSTimeZone.timeZoneWithName(timeZoneId)) { "Unknown ledger time zone" }
    return zone.secondsFromGMTForDate(NSDate.dateWithTimeIntervalSince1970(epochSeconds.toDouble())).toInt()
}

internal actual fun cashLedgerPlatformTimeZoneExists(timeZoneId: String): Boolean =
    if (timeZoneId.isEmpty() || timeZoneId[0] == '+' || timeZoneId[0] == '-') false
    else try { TimeZone.of(timeZoneId); true } catch (_: IllegalTimeZoneException) { false }

internal actual fun cashLedgerPlatformStartOfDay(date: String, timeZoneId: String): Instant =
    LocalDate.parse(date).atStartOfDayIn(TimeZone.of(timeZoneId))
