package com.finance.tracker.core

import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeZoneWithName

internal actual fun cashLedgerPlatformOffset(epochSeconds: String, timeZoneId: String): Int {
    val zone = requireNotNull(NSTimeZone.timeZoneWithName(timeZoneId)) { "Unknown ledger time zone" }
    return zone.secondsFromGMTForDate(NSDate.dateWithTimeIntervalSince1970(epochSeconds.toDouble())).toInt()
}
