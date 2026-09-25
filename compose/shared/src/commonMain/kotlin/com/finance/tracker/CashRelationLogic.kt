package com.finance.tracker

internal data class CashRelationDateRange(val from: String, val to: String)

internal fun cashRelationDateRange(localDate: String): CashRelationDateRange? {
    parseIsoDate(localDate) ?: return null
    return CashRelationDateRange(shiftIsoDate(localDate, -3), shiftIsoDate(localDate, 3))
}

internal fun shiftIsoDate(value: String, offsetDays: Int): String {
    val parsed = requireNotNull(parseIsoDate(value)) { "Expected an ISO calendar date." }
    var year = parsed.first
    var month = parsed.second
    var day = parsed.third
    repeat(kotlin.math.abs(offsetDays)) {
        if (offsetDays < 0) {
            if (day > 1) {
                day--
            } else {
                if (month > 1) month-- else { year--; month = 12 }
                day = daysInMonth(year, month)
            }
        } else {
            if (day < daysInMonth(year, month)) {
                day++
            } else {
                if (month < 12) month++ else { year++; month = 1 }
                day = 1
            }
        }
    }
    return year.toString().padStart(4, '0') + "-" + month.toString().padStart(2, '0') + "-" + day.toString().padStart(2, '0')
}

internal fun isValidIsoDate(value: String): Boolean = parseIsoDate(value) != null

internal fun isValidCashRelationDateFilter(from: String, to: String): Boolean =
    (from.isEmpty() || isValidIsoDate(from)) &&
        (to.isEmpty() || isValidIsoDate(to)) &&
        (from.isEmpty() || to.isEmpty() || from <= to)


internal fun categoryCanMove(items: List<CashCategoryDto>, categoryId: String, direction: String): Boolean {
    val item = items.firstOrNull { it.id == categoryId } ?: return false
    val siblings = items.filter { it.parentId == item.parentId }
    val index = siblings.indexOfFirst { it.id == categoryId }
    return when (direction) {
        "before" -> index > 0
        "after" -> index >= 0 && index < siblings.lastIndex
        else -> false
    }
}

private fun parseIsoDate(value: String): Triple<Int, Int, Int>? {
    if (value.length != 10 || value[4] != '-' || value[7] != '-') return null
    val year = value.substring(0, 4).toIntOrNull() ?: return null
    val month = value.substring(5, 7).toIntOrNull() ?: return null
    val day = value.substring(8, 10).toIntOrNull() ?: return null
    if (year !in 1..9999 || month !in 1..12 || day !in 1..daysInMonth(year, month)) return null
    return Triple(year, month, day)
}

private fun daysInMonth(year: Int, month: Int): Int = when (month) {
    2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}
