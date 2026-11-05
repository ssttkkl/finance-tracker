package com.finance.tracker.core

import java.time.Instant
import java.time.ZoneId

internal actual fun cashLedgerPlatformOffset(epochSeconds: String, timeZoneId: String): Int =
    ZoneId.of(timeZoneId).rules.getOffset(Instant.ofEpochSecond(epochSeconds.toLong())).totalSeconds
