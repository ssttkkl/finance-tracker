package com.finance.tracker.business.domain

/** Original BigDecimal(String) boundary, separate from persisted CashAmount precision limits. */
internal fun parseSourceAccountAmount(text: String): LedgerArithmeticValue {
    val ascii = buildString(text.length) {
        // BigDecimal reads UTF-16 chars: BMP Nd is accepted, supplementary Nd is not.
        for (char in text) {
            val start = decimalDigitStarts.firstOrNull { it <= 0xffff && char.code in it until it + 10 }
            append(if (start == null) char else ('0'.code + char.code - start).toChar())
        }
    }
    try {
        // Unlike Python Decimal normalization, this entry must not discard spaces or underscores.
        return LedgerArithmeticValue.fromCanonical(ascii)
    } catch (failure: IllegalArgumentException) {
        throw NumberFormatException(failure.message)
    } catch (failure: ArithmeticException) {
        // Native constructor rejects final scale overflow, not every exponent above Int.MAX_VALUE.
        throw NumberFormatException(failure.message)
    }
}
