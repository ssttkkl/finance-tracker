package com.finance.tracker.core

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
