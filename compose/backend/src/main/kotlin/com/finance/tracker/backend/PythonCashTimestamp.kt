package com.finance.tracker.backend

import java.time.*
import java.time.temporal.ChronoUnit

internal fun manualCashTimestamp(value:String,rawSurrogates:Set<Int> = emptySet()):Instant {
    val text=value.trim {it.code in pythonWhitespace}
    require(text.isNotEmpty()) {"date is required"}
    val start=value.indexOfFirst {it.code !in pythonWhitespace}
    return try {pythonCashTimestamp(text,allowNaive=true,separateSurrogates=rawSurrogates.map {it-start}.toSet())}
        catch(_:InvalidCashValue) {throw IllegalArgumentException("invalid transaction date: $text")}
}

internal fun manualCashOccurrenceTimestamp(value:String,rawSurrogates:Set<Int> = emptySet()):Instant {
    val instant=manualCashTimestamp(value,rawSurrogates)
    require(com.finance.tracker.business.domain.cashOccurrenceTimeInRange(instant.epochSecond)) {
        "invalid transaction date: "+value.trim {it.code in pythonWhitespace}
    }
    return instant
}

internal fun pythonCashTimestamp(value: Any,allowNaive:Boolean=false,separateSurrogates:Set<Int> = emptySet(),
    preserveZSeparator:Boolean=false): Instant {
    if (value is String) {
        val parsed = try {
            com.finance.tracker.business.domain.parsePythonCashTimestamp(
                value, allowNaive, separateSurrogates, preserveZSeparator)
        } catch (_: IllegalArgumentException) { throw InvalidCashValue() }
        return Instant.ofEpochSecond(parsed.epochSeconds, parsed.nanosecondsOfSecond.toLong())
    }
    return try {
        val instant = when (value) {
            is Instant -> value
            is OffsetDateTime -> value.toInstant()
            is ZonedDateTime -> value.toInstant()
            else -> throw InvalidCashValue()
        }
        if (instant.atOffset(ZoneOffset.UTC).year !in 1..9999) {
            if (allowNaive) throw ArithmeticException("date value out of range")
            throw InvalidCashValue()
        }
        instant.truncatedTo(ChronoUnit.MICROS)
    } catch (_: DateTimeException) { throw InvalidCashValue() }
}
