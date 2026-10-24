package com.finance.tracker.core

import java.time.LocalDateTime
import java.time.ZoneId

actual fun instantForLocalDateTime(value: String, timeZoneId: String): String? = runCatching {
    if (!Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}").matches(value)) return null
    val local = LocalDateTime.parse(value)
    val offsets = ZoneId.of(timeZoneId).rules.getValidOffsets(local)
    if (offsets.size != 1) null else local.toInstant(offsets.single()).toString()
}.getOrNull()
