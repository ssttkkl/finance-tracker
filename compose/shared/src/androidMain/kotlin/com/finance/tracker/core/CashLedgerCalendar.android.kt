package com.finance.tracker.core

import java.time.Instant
import java.time.ZoneId

internal actual fun cashLedgerPlatformOffset(epochSeconds: String, timeZoneId: String): Int =
    ZoneId.of(timeZoneId).rules.getOffset(Instant.ofEpochSecond(epochSeconds.toLong())).totalSeconds

internal actual fun cashLedgerPlatformTimeZoneExists(timeZoneId: String): Boolean =
    timeZoneId in ZoneId.getAvailableZoneIds()

internal actual fun cashLedgerPlatformStartOfDay(date: String, timeZoneId: String): kotlin.time.Instant =
    kotlin.time.Instant.fromEpochSeconds(java.time.LocalDate.parse(date).atStartOfDay(ZoneId.of(timeZoneId)).toEpochSecond())
