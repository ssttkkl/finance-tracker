package com.finance.tracker

private val LOCAL_DATE_PATTERN = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$")
private val LOCAL_TIME_PATTERN = Regex("^\\d{2}:\\d{2}$")
private const val MILLIS_PER_DAY = 86_400_000L
private const val UNIX_EPOCH_DAYS_FROM_CIVIL_EPOCH = 719_468L
private const val DAYS_PER_400_YEAR_ERA = 146_097L

internal fun localDateTimeDisplayLabel(instant: String): String {
    val date = LOCAL_DATE_PATTERN.matchEntire(getLocalDateForInstant(instant)) ?: return "未提供"
    if (!isValidLocalDate(date)) return "未提供"
    val time = formatLocalDateTime(instant).substringAfter(' ', "")
    if (!LOCAL_TIME_PATTERN.matches(time)) return "未提供"
    return "${date.groupValues[1]}年${date.groupValues[2].toInt()}月${date.groupValues[3].toInt()}日 $time"
}

internal fun localMonthKey(instant: String): String {
    val date = LOCAL_DATE_PATTERN.matchEntire(getLocalDateForInstant(instant)) ?: return "unknown"
    if (!isValidLocalDate(date)) return "unknown"
    return "${date.groupValues[1]}-${date.groupValues[2]}"
}

internal fun localMonthLabel(month: String): String {
    if (month == "unknown") return "未提供时间"
    val parts = month.split('-')
    if (parts.size != 2) return "未提供时间"
    val year = parts[0].toIntOrNull() ?: return "未提供时间"
    val monthNumber = parts[1].toIntOrNull()?.takeIf { it in 1..12 } ?: return "未提供时间"
    return "${year}年${monthNumber}月"
}

internal fun isoDateDisplayLabel(value: String): String {
    val match = LOCAL_DATE_PATTERN.matchEntire(value) ?: return ""
    if (!isValidLocalDate(match)) return ""
    return "${match.groupValues[1]}年${match.groupValues[2].toInt()}月${match.groupValues[3].toInt()}日"
}

internal fun localDateTimeSortKey(instant: String): String {
    val date = getLocalDateForInstant(instant)
    val match = LOCAL_DATE_PATTERN.matchEntire(date) ?: return ""
    if (!isValidLocalDate(match)) return ""
    val time = formatLocalDateTime(instant).substringAfter(' ', "")
    return if (LOCAL_TIME_PATTERN.matches(time)) "$date $time" else "$date"
}

internal fun isoDateToUtcMillis(value: String): Long? {
    val match = LOCAL_DATE_PATTERN.matchEntire(value) ?: return null
    if (!isValidIsoDate(value)) return null
    val year = match.groupValues[1].toInt() - if (match.groupValues[2].toInt() <= 2) 1 else 0
    val month = match.groupValues[2].toInt()
    val day = match.groupValues[3].toInt()
    val era = year / 400
    val yearOfEra = year - era * 400
    val shiftedMonth = month + if (month > 2) -3 else 9
    val dayOfYear = (153 * shiftedMonth + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    val daysFromEpoch = era.toLong() * DAYS_PER_400_YEAR_ERA + dayOfEra - UNIX_EPOCH_DAYS_FROM_CIVIL_EPOCH
    return daysFromEpoch * MILLIS_PER_DAY
}

internal fun isoDateFromUtcMillis(value: Long): String {
    val epochDays = floorDivide(value, MILLIS_PER_DAY)
    val civilDays = epochDays + UNIX_EPOCH_DAYS_FROM_CIVIL_EPOCH
    val era = floorDivide(civilDays, DAYS_PER_400_YEAR_ERA)
    val dayOfEra = (civilDays - era * DAYS_PER_400_YEAR_ERA).toInt()
    val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36524 - dayOfEra / 146096) / 365
    var year = yearOfEra + era.toInt() * 400
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val monthPrime = (5 * dayOfYear + 2) / 153
    val day = dayOfYear - (153 * monthPrime + 2) / 5 + 1
    val month = monthPrime + if (monthPrime < 10) 3 else -9
    if (month <= 2) year++
    return year.toString().padStart(4, '0') + "-" + month.toString().padStart(2, '0') + "-" + day.toString().padStart(2, '0')
}

internal fun isValidLocalDateTime(value: String): Boolean {
    if (value.length != 16 || value[10] != 'T' || !isValidIsoDate(value.take(10))) return false
    val time = value.substring(11)
    if (!LOCAL_TIME_PATTERN.matches(time)) return false
    val hour = time.substring(0, 2).toIntOrNull() ?: return false
    val minute = time.substring(3, 5).toIntOrNull() ?: return false
    return hour in 0..23 && minute in 0..59
}

internal fun displaySignedSummaryAmount(direction: String, amount: String): String {
    val absolute = displayDecimalAbs(amount) ?: amount.removePrefix("+").removePrefix("-")
    if (displayDecimalSign(amount) == 0) return "0"
    return "${if (direction == "income") "+" else "-"}$absolute"
}

private fun isValidLocalDate(match: MatchResult): Boolean = isValidIsoDate(match.value)

private fun floorDivide(value: Long, divisor: Long): Long {
    val quotient = value / divisor
    return if (value % divisor < 0) quotient - 1 else quotient
}
