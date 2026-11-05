package com.finance.tracker.business.domain

/** Raw platform workbook dates use a timezone-free 1900/1904 calendar and Java's optional seconds. */
fun cashPlatformWorkbookDateText(serial: Double, use1904: Boolean): String = workbookDateText(serial, use1904, fixedSeconds = false)

/** Raw CCB workbook dates keep fixed seconds; milliseconds are not displayed in this source. */
fun cashCcbWorkbookDateText(serial: Double, use1904: Boolean): String = workbookDateText(serial, use1904, fixedSeconds = true)

private fun workbookDateText(serial: Double, use1904: Boolean, fixedSeconds: Boolean): String {
    if (!serial.isFinite() || serial < 0.0) throw StatementDocumentFailure("statement_unreadable")
    // POI's date arithmetic starts from BigDecimal.valueOf(double), hence Java's selected decimal, not its exact binary value.
    val decimal = LedgerArithmeticValue.fromCanonical(javaBinary64Text(serial))
    val wholeDays = narrowWorkbookInteger(decimal.toPlainString()).toInt()
    val remainder = decimal.subtract(LedgerArithmeticValue.fromCanonical(wholeDays.toString()))
    val parts = requireNotNull(LedgerDecimal.parseRepresentationOrNull(remainder.toPlainString()))
    check(!parts.negative)
    // 86,400 * 10^9 nanoseconds per day = 864 * 10^11. The decimal is bounded by finite binary64.
    var carry = 0
    val product = CharArray(parts.coefficient.length)
    for (index in parts.coefficient.lastIndex downTo 0) {
        val digit = parts.coefficient[index].digitToInt() * 864 + carry
        product[index] = ('0'.code + digit % 10).toChar()
        carry = digit / 10
    }
    val coefficient = (if (carry == 0) "" else carry.toString()) + product.concatToString()
    val nanos = LedgerArithmeticValue.fromCanonical(coefficient + "e" + (parts.exponent + 11))
        .add(LedgerArithmeticValue.fromCanonical("500000"))
    val narrowedNanos = narrowWorkbookInteger(nanos.toPlainString())
    val dayNanos = 86_400_000_000_000L
    val adjustment = if (use1904) 1 else if (wholeDays < 61) 0 else -1
    val day = (if (use1904) -24_107L else -25_567L) + (wholeDays + adjustment).toLong() - 1L + narrowedNanos.floorDiv(dayNanos)
    val millis = narrowedNanos.mod(dayNanos) / 1_000_000
    val hour = (millis / 3_600_000).toString().padStart(2, '0')
    val minute = (millis / 60_000 % 60).toString().padStart(2, '0')
    val second = millis / 1000 % 60
    val fraction = millis % 1000
    val clock = hour + ":" + minute + if (fixedSeconds || second != 0L || fraction != 0L) {
        ":" + second.toString().padStart(2, '0') + if (!fixedSeconds && fraction != 0L) "." + fraction.toString().padStart(3, '0') else ""
    } else ""
    return formatCashWorkbookDay(day) + " " + clock
}

// BigDecimal.intValue/longValue truncate toward zero, then retain the low bits rather than saturating.
private fun narrowWorkbookInteger(positivePlainDecimal: String): Long {
    var result = 0L
    for (digit in positivePlainDecimal.substringBefore('.')) result = result * 10 + digit.digitToInt()
    return result
}
