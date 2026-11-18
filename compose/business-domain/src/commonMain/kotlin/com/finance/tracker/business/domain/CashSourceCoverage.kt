package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.CashSourceCoverage
import kotlin.time.Instant

fun CashSourceCoverage.comparable(other: CashSourceCoverage, value: Any?): Boolean =
    contains(value) && other.contains(value) && filters == other.filters &&
        timePrecision == other.timePrecision && strategyVersion == other.strategyVersion

fun CashSourceCoverage.contains(value: Any?): Boolean {
    val rangeStart = start
    val rangeEnd = end
    if (!complete || evidence.isEmpty() || rangeStart == null || rangeEnd == null || value == null) return false
    if (!hasSourceOffset(rangeStart) || !hasSourceOffset(rangeEnd) || !hasSourceOffset(value)) return false
    return try {
        val startTime = parsePythonCashTimestamp(rangeStart)
        val endTime = parsePythonCashTimestamp(rangeEnd)
        val whenTime = when (value) {
            is String -> parsePythonCashTimestamp(value)
            is Instant -> {
                require(value.epochSeconds >= -62135596800L && value.epochSeconds < 253402300800L)
                Instant.fromEpochSeconds(value.epochSeconds, value.nanosecondsOfSecond / 1000 * 1000L)
            }
            else -> return false
        }
        startTime < endTime &&
            (if (startInclusive) startTime <= whenTime else startTime < whenTime) &&
            (if (endInclusive) whenTime <= endTime else whenTime < endTime)
    } catch (_: IllegalArgumentException) {
        false
    }
}

private fun hasSourceOffset(value: Any?): Boolean = when (value) {
    is Instant -> true
    is String -> Regex("[T ].*?(?:Z|[+-][0-9]{2}(?::?[0-9]{2})?(?::?[0-9]{2})?)$").containsMatchIn(value)
    else -> false
}
