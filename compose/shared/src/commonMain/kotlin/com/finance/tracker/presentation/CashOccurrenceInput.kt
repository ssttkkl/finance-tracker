package com.finance.tracker.presentation

import com.finance.tracker.business.domain.cashOccurrenceTimeInRange
import com.finance.tracker.core.instantForLocalDateTime
import kotlin.time.Instant

internal fun cashInstantForLocalDateTime(value: String, timeZoneId: String): String? =
    instantForLocalDateTime(value, timeZoneId)?.takeIf { cashOccurrenceTimeInRange(Instant.parse(it).epochSeconds) }
