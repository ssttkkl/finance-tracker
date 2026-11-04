package com.finance.tracker.business.domain

import kotlin.time.Instant

/** Parse the existing Python ISO timestamp contract, truncating fractions to microseconds. */
fun parsePythonCashTimestamp(
    value: String,
    allowNaive: Boolean = false,
    separateSurrogates: Set<Int> = emptySet(),
    preserveZSeparator: Boolean = false,
): Instant {
    val input = if (allowNaive || preserveZSeparator) value else value.replace("Z", "+00:00")
    require(input.length >= if (allowNaive) 7 else 8)
    val separator = timestampDateEnd(input)
    require(separator <= input.length && (separator < input.length || allowNaive))
    val epochDay = timestampEpochDay(input.take(separator))
    if (separator == input.length) return Instant.fromEpochSeconds(epochDay * 86400)
    val width = if (input[separator].isHighSurrogate() && separator + 1 < input.length &&
        input[separator + 1].isLowSurrogate()) 2 else 1
    require(width != 2 || separator !in separateSurrogates && separator + 1 !in separateSurrogates)
    val rest = input.substring(separator + width)
    val offsetIndex = rest.indexOfFirst { it == '+' || it == '-' || it == 'Z' }
    require(offsetIndex >= 0 || allowNaive)
    val clockText = if (offsetIndex < 0) rest else rest.take(offsetIndex)
    val (seconds, micros) = timestampClock(clockText, local = true)
    val offset = if (offsetIndex < 0) "Z" else rest.substring(offsetIndex)
    val (offsetSeconds, offsetMicros) = if ((allowNaive || preserveZSeparator) && offset == "Z") {
        0L to 0
    } else {
        timestampClock(offset.substring(1), local = false)
    }
    require(offsetSeconds < 86400)
    val direction = if (offset.startsWith('-')) -1 else 1
    val instant = Instant.fromEpochSeconds(
        epochDay * 86400 + seconds - direction * offsetSeconds,
        micros * 1000L - if (offsetSeconds == 0L) 0L else direction * offsetMicros * 1000L,
    )
    if (instant.epochSeconds < -62135596800L || instant.epochSeconds >= 253402300800L) {
        if (allowNaive) throw ArithmeticException("date value out of range")
        throw IllegalArgumentException("invalid timestamp")
    }
    return instant
}

private fun timestampDateEnd(input: String): Int = when {
    input[4] == '-' && input[5] == 'W' -> {
        if (input.length > 8 && input[8] == '-') {
            require(input.length != 9)
            if (input.length > 10 && input[10] in '0'..'9') 8 else 10
        } else 8
    }
    input[4] == '-' -> 10
    input[4] == 'W' -> {
        var end = 7
        while (end < input.length && input[end] in '0'..'9') end++
        if (end < 9) end else if (end % 2 == 0) 7 else 8
    }
    else -> 8
}

private fun timestampEpochDay(text: String): Long {
    val calendar = text.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
    val week = text.matches(Regex("[0-9]{4}(?:-W[0-9]{2}(?:-[0-9])?|W[0-9]{2}[0-9]?)"))
    val basic = text.matches(Regex("[0-9]{8}"))
    require(calendar || week || basic)
    val year = text.take(4).toInt()
    require(year in 1..9999)
    if (week) {
        val compact = text.replace("-", "")
        val weekNumber = compact.substring(5, 7).toInt()
        val day = if (compact.length == 8) compact.last().digitToInt() else 1
        val jan1 = timestampCalendarDay(year, 1, 1)
        val jan1Weekday = (jan1 + 3).mod(7)
        val weeks = if (jan1Weekday == 3 || jan1Weekday == 2 && timestampLeapYear(year)) 53 else 52
        require(weekNumber in 1..weeks && day in 1..7)
        val jan4 = jan1 + 3
        val result = jan4 - (jan4 + 3).mod(7) + (weekNumber - 1) * 7 + day - 1
        require(result >= -719162L && result < 2932897L)
        return result
    }
    val compact = text.replace("-", "")
    return timestampCalendarDay(year, compact.substring(4, 6).toInt(), compact.substring(6, 8).toInt())
}

private fun timestampCalendarDay(year: Int, month: Int, day: Int): Long {
    val months = intArrayOf(31, if (timestampLeapYear(year)) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    require(month in 1..12 && day in 1..months[month - 1])
    val previousYear = year - 1L
    var ordinal = previousYear * 365 + previousYear / 4 - previousYear / 100 + previousYear / 400
    for (index in 0 until month - 1) ordinal += months[index]
    return ordinal + day - 1 - 719162
}

private fun timestampLeapYear(year: Int): Boolean = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

private fun timestampClock(text: String, local: Boolean): Pair<Long, Int> {
    val fractionIndex = text.indexOfFirst { it == '.' || it == ',' }
    val main = if (fractionIndex < 0) text else text.take(fractionIndex)
    val fraction = if (fractionIndex < 0) "" else text.substring(fractionIndex + 1).also {
        require(it.isNotEmpty() && it.all { char -> char in '0'..'9' })
    }
    val parts = if (':' in main) main.split(':') else {
        require(main.length in setOf(2, 4, 6))
        main.chunked(2)
    }
    require(parts.size in 1..3 && parts.all { it.length == 2 && it.all { char -> char in '0'..'9' } })
    val hour = parts[0].toInt()
    val minute = parts.getOrNull(1)?.toInt() ?: 0
    val second = parts.getOrNull(2)?.toInt() ?: 0
    if (local) require(hour <= 23 && minute <= 59 && second <= 59)
    return (hour * 3600L + minute * 60L + second) to fraction.take(6).padEnd(6, '0').toInt()
}

/** UTC text used by stable source and relation keys, with fixed six-digit nonzero microseconds. */
fun formatPythonCashTimestamp(value: Instant): String {
    val day=value.epochSeconds.floorDiv(86400L)
    require(day>=timestampCalendarDay(1,1,1) && day<timestampCalendarDay(10000,1,1))
    var low=1;var high=10000
    while(high-low>1) {
        val middle=(low+high)/2
        if(timestampCalendarDay(middle,1,1)<=day) low=middle else high=middle
    }
    val year=low
    var month=1
    while(month<12 && timestampCalendarDay(year,month+1,1)<=day) month++
    val date=day-timestampCalendarDay(year,month,1)+1
    val second=value.epochSeconds.mod(86400L)
    val micro=value.nanosecondsOfSecond/1000
    return year.toString().padStart(4,'0')+"-"+month.toString().padStart(2,'0')+"-"+date.toString().padStart(2,'0')+
        "T"+(second/3600).toString().padStart(2,'0')+":"+(second/60%60).toString().padStart(2,'0')+":"+(second%60).toString().padStart(2,'0')+
        (if(micro==0) "" else "."+micro.toString().padStart(6,'0'))+"+00:00"
}
