package com.finance.tracker.business.domain

/** The existing source-nickname NFC contract, independent of the platform's Unicode library. */
fun normalizeCashNfc(value: String): String {
    if (value.all { it.code < 0x80 }) return value
    val decomposed = ArrayList<Int>(value.length)
    for (code in value.pythonCodePoints()) {
        if (code in 0xac00..0xd7a3) {
            val syllable = code - 0xac00
            decomposed.add(0x1100 + syllable / 588)
            decomposed.add(0x1161 + syllable % 588 / 28)
            if (syllable % 28 != 0) decomposed.add(0x11a7 + syllable % 28)
        } else CashNfcData.appendDecomposition(code, decomposed)
    }
    val points = decomposed.toIntArray()
    val classes = IntArray(points.size) { CashNfcData.combiningClass(points[it]) }
    var start = 0
    for (index in points.indices) if (classes[index] == 0) {
        orderCanonicalMarks(points, classes, start, index)
        start = index + 1
    }
    orderCanonicalMarks(points, classes, start, points.size)
    var size = 0
    var starterIndex = -1
    var starter = -1
    var previousClass = 0
    for (index in points.indices) {
        val code = points[index]
        val combiningClass = classes[index]
        val composed = if (starterIndex >= 0 && (previousClass < combiningClass || previousClass == 0))
            composeCashNfc(starter, code) else null
        if (composed != null) {
            points[starterIndex] = composed
            starter = composed
        } else {
            if (combiningClass == 0) { starterIndex = size; starter = code }
            points[size++] = code
            previousClass = combiningClass
        }
    }
    return buildString(value.length) { for (index in 0 until size) appendPythonCodePoint(points[index]) }
}

private fun composeCashNfc(first: Int, second: Int): Int? {
    if (first in 0x1100..0x1112 && second in 0x1161..0x1175)
        return 0xac00 + (first - 0x1100) * 588 + (second - 0x1161) * 28
    if (first in 0xac00..0xd7a3 && (first - 0xac00) % 28 == 0 && second in 0x11a8..0x11c2)
        return first + second - 0x11a7
    return CashNfcData.compose(first, second)
}

private fun orderCanonicalMarks(points: IntArray, classes: IntArray, start: Int, end: Int) {
    if (end - start < 2 || (start + 1 until end).none { classes[it] < classes[it - 1] }) return
    // Bound insertion work on ordinary short runs; long hostile runs use stable linear counting sort.
    if (end - start <= 32) {
        for (index in start + 1 until end) {
            val code = points[index]
            val combiningClass = classes[index]
            var position = index
            while (position > start && classes[position - 1] > combiningClass) {
                points[position] = points[position - 1]; classes[position] = classes[position - 1]; position--
            }
            points[position] = code; classes[position] = combiningClass
        }
    } else {
        val counts = IntArray(256)
        for (index in start until end) counts[classes[index]]++
        var next = 0
        for (index in counts.indices) { val count = counts[index]; counts[index] = next; next += count }
        val sortedPoints = IntArray(end - start)
        val sortedClasses = IntArray(end - start)
        for (index in start until end) {
            val combiningClass = classes[index]
            val position = counts[combiningClass]++
            sortedPoints[position] = points[index]; sortedClasses[position] = combiningClass
        }
        sortedPoints.copyInto(points, start); sortedClasses.copyInto(classes, start)
    }
}
