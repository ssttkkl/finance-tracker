package com.finance.tracker.business.domain

import kotlin.time.Instant

private val platformIsoDate = Regex("([+-]?[0-9]{4,10})-([0-9]{2})-([0-9]{2})[Tt]([0-9]{2}):([0-9]{2})(?::([0-9]{2})(?:\\.([0-9]{0,9}))?)?(.*)")
private val platformSmartDate = Regex("([+-]?[0-9]{4,19})-([0-9]{2})-([0-9]{2})(?: ([0-9]{2}):([0-9]{2})(?::([0-9]{2}))?)?")
private val platformDateOffset = Regex("([+-])([0-9]{2})(?::([0-9]{2})(?::([0-9]{2}))?)?")
private val platformLocalMinimum = timestampCalendarDay(-999999999, 1, 1) * 86400L
private val platformLocalMaximum = timestampCalendarDay(1000000000, 1, 1) * 86400L

/** Preserve the original platform matcher ISO, Z-removal, then 19-UTF-16-unit SMART fallback order. */
internal fun parseLocalCashPlatformDate(value: Any?): Instant? {
    val text = value?.toString().orEmpty().trim { it.code != 0x85 && it.code in pythonWhitespace }.replace("/", "-")
    if (text.isEmpty()) return null
    platformIsoDate.matchEntire(text.replace("Z", "+00:00"))?.let { resolvePlatformDate(it, smart = false, offset = true)?.let { result -> return result } }
    platformIsoDate.matchEntire(text.replace("Z", ""))?.let { resolvePlatformDate(it, smart = false, offset = false)?.let { result -> return result } }
    return platformSmartDate.matchEntire(text.take(19))?.let { resolvePlatformDate(it, smart = true, offset = false) }
}

private fun resolvePlatformDate(match: MatchResult, smart: Boolean, offset: Boolean): Instant? {
    val values = match.groupValues
    val yearText = values[1]
    val year = yearText.toIntOrNull()?.takeIf { it in -999999999..999999999 } ?: return null
    val signed = yearText.startsWith('+') || yearText.startsWith('-')
    val width = yearText.length - if (signed) 1 else 0
    if (!signed && width != 4 || yearText.startsWith('+') && width <= 4 || yearText.startsWith('-') && year == 0) return null
    val month = values[2].toInt()
    var day = values[3].toInt()
    if (month !in 1..12 || day !in 1..31) return null
    val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
    val last = when (month) { 2 -> if (leap) 29 else 28; 4, 6, 9, 11 -> 30; else -> 31 }
    if (smart) day = minOf(day, last) else if (day > last) return null
    val hour = values[4].toIntOrNull() ?: 0
    val minute = values[5].toIntOrNull() ?: 0
    val second = values[6].toIntOrNull() ?: 0
    if (minute > 59 || second > 59 || hour > 23 && !(smart && hour == 24 && minute == 0 && second == 0)) return null
    val nanos = if (smart) 0 else values[7].padEnd(9, '0').toInt()
    val shift = if (!offset) {
        if (!smart && values[8].isNotEmpty()) return null
        0
    } else {
        val raw = values[8]
        if (raw == "z") 0 else {
            val parts = platformDateOffset.matchEntire(raw)?.groupValues ?: return null
            val hours = parts[2].toInt()
            val minutes = parts[3].toIntOrNull() ?: 0
            val seconds = parts[4].toIntOrNull() ?: 0
            if (hours > 18 || minutes > 59 || seconds > 59 || hours == 18 && (minutes != 0 || seconds != 0)) return null
            (hours * 3600 + minutes * 60 + seconds) * if (parts[1] == "-") -1 else 1
        }
    }
    val epoch = timestampCalendarDay(year, month, day) * 86400L + hour * 3600L + minute * 60L + second - shift
    if (epoch < platformLocalMinimum || epoch >= platformLocalMaximum) return null
    return Instant.fromEpochSeconds(epoch, nanos.toLong())
}
