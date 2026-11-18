package com.finance.tracker.business.domain

import kotlin.time.Instant

private val explicitStatementOffset = Regex("(?:Z|[+-][0-9]{2}(?::?[0-9]{2})?)$")
private val basicStatementDate = Regex("[0-9]{8}")
private val basicStatementDateTime = Regex("([0-9]{4})([0-9]{2})([0-9]{2})T([0-9]{2})([0-9]{2})([0-9]{2})([^\\n\\r\\u0085\\u2028\\u2029]*)")
private val compactStatementOffset = Regex("([+-][0-9]{2})([0-9]{2})$")
private val hourOnlyStatementOffset = Regex("([+-][0-9]{2})$")

/** Existing source-zone normalization, including historical gaps and earlier-offset overlaps. */
fun normalizeStatementTimestamp(value: String, source: String): String {
    var text = value.trim { it.code in pythonWhitespace }.replace(',', '.')
    if (basicStatementDate.matches(text)) text = "${text.take(4)}-${text.substring(4, 6)}-${text.substring(6, 8)}"
    else basicStatementDateTime.matchEntire(text)?.let {
        text = "${it.groupValues[1]}-${it.groupValues[2]}-${it.groupValues[3]}T" +
            "${it.groupValues[4]}:${it.groupValues[5]}:${it.groupValues[6]}${it.groupValues[7]}"
    }
    text = compactStatementOffset.replace(text) { "${it.groupValues[1]}:${it.groupValues[2]}" }
    text = hourOnlyStatementOffset.replace(text) { "${it.groupValues[1]}:00" }
    if (text.length > 10 && text[10] == ' ') text = text.substring(0, 10) + "T" + text.substring(11)
    fun localToUtc(parsed: Instant): Instant = Instant.fromEpochSeconds(
        parsed.epochSeconds - StatementSourceZoneData.offsetSeconds(source, parsed.epochSeconds),
        parsed.nanosecondsOfSecond.toLong(),
    )
    val instant = try {
        try {
            val parsed = parsePythonCashTimestamp(text)
            if (explicitStatementOffset.containsMatchIn(text) || text.endsWith("Z")) parsed else localToUtc(parsed)
        } catch (_: IllegalArgumentException) {
            localToUtc(parsePythonCashTimestamp(text, allowNaive = true))
        }
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("invalid timestamp")
    }
    // A year-1 local timestamp may legitimately become year 0 UTC, as in the original atZone path.
    val date = formatCashCandidateDay(instant.epochSeconds.floorDiv(86400L))
    val second = instant.epochSeconds.mod(86400L)
    val micro = instant.nanosecondsOfSecond / 1000
    return date + "T" + (second / 3600).toString().padStart(2, '0') + ":" +
        (second / 60 % 60).toString().padStart(2, '0') + ":" + (second % 60).toString().padStart(2, '0') +
        (if (micro == 0) "" else "." + micro.toString().padStart(6, '0')) + "+00:00"
}
