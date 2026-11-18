package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.CashFiltersDto

/** Normalize the existing filter protocol before constructing an executable local read scope. */
fun normalizeLocalCashLedgerQueryInput(input: CashFiltersDto, timeZoneId: String?,
    dateBounds: (String?, String?, String?) -> CashLedgerDateBounds): LocalCashLedgerQueryInput {
    fun invalid(): Nothing = throw CashProjectionDomainFailure("invalid_filter")
    fun String?.present(): String? = this?.takeIf(String::isNotEmpty)
    fun trim(value: String) = value.trim { it.code in pythonWhitespace }
    fun integer(raw: String): String {
        val digitStarts = pythonIntegerDigitStarts
        val value = buildString {
            for (point in raw.trim { it.code in pythonWhitespace && it.code !in 0x1c..0x1f }.pythonCodePoints()) {
                val zero = digitStarts.firstOrNull { point in it until it + 10 }
                if (zero != null) append(('0'.code + point - zero).toChar()) else appendPythonCodePoint(point)
            }
        }
        var previousDigit = false
        var count = 0
        val first = if (value.firstOrNull() in setOf('+', '-')) 1 else 0
        for (index in first until value.length) when (value[index]) {
            in '0'..'9' -> { previousDigit = true; count++; if (count > 4300) invalid() }
            '_' -> { if (!previousDigit) invalid(); previousDigit = false }
            else -> invalid()
        }
        if (!previousDigit) invalid()
        val digits = value.removePrefix("+").removePrefix("-").replace("_", "").trimStart('0').ifEmpty { "0" }
        if (digits == "0" || value.startsWith('-')) invalid()
        return digits
    }
    fun amount(raw: String?): Pair<String?, LedgerArithmeticValue?> {
        if (raw.isNullOrEmpty()) return null to null
        val parts = LedgerDecimal.parseRepresentationOrNull(raw) ?: invalid()
        // Bound the expanded representation before allocating any decimal padding.
        if (parts.exponent < -18 || parts.coefficient != "0" && parts.coefficient.length.toLong() + parts.exponent > 20) invalid()
        val number = if (parts.coefficient == "0" && parts.exponent >= 0) LedgerArithmeticValue.ZERO else
            LedgerArithmeticValue.fromCanonical((if (parts.negative) "-" else "") + parts.coefficient + "E" + parts.exponent)
        return (if (parts.negative && number.signum == 0) "-" else "") + number.toPlainString() to number
    }
    val bounds = dateBounds(input.dateFrom, input.dateTo, timeZoneId)
    val account = input.accountId.present()?.let(::integer)
    val (minimumText, minimum) = amount(input.amountMin)
    val (maximumText, maximum) = amount(input.amountMax)
    if (minimum != null && maximum != null && minimum > maximum) invalid()
    var economic = input.economicType.present()
    val label = input.businessLabel.present()?.let(::trim)
    if (label != null && (label.isEmpty() || label.pythonCodePointCount() > 32)) invalid()
    if (economic == "bank_security_transfer") {
        if (label != null && label != "bank_security_transfer") invalid()
        economic = "internal_transfer"
    }
    val businessLabel = if (input.economicType == "bank_security_transfer") "bank_security_transfer" else label
    if (economic !in setOf(null, "expense", "income", "internal_transfer")) invalid()
    if (businessLabel != null) {
        if (economic == null) economic = "internal_transfer" else if (economic != "internal_transfer") invalid()
    }
    val composition = input.composition.present()
    if (composition !in setOf(null, "single", "payment_mirror", "refund_offset", "combined")) invalid()
    val category = input.categoryId.present()?.let(::trim)
    if (category != null && (category.isEmpty() || category.pythonCodePointCount() > 64)) invalid()
    val uncategorized = when (input.uncategorized?.let(::pythonCashLower)) {
        null, "0", "false", "no", "n", "off", "f" -> false
        "1", "true", "yes", "y", "on", "t" -> true
        else -> throw CashProjectionDomainFailure("bool_parsing")
    }
    if (category != null && uncategorized) invalid()
    val currency = input.currency.present()
    if (currency != null && (currency.pythonCodePointCount() != 3 || !currency.pythonCodePoints().all(LedgerFilterCharacters::isLetter))) invalid()
    val state = input.state.present() ?: "all"
    val confirmation = input.confirmationStatus.present()
    if (state !in setOf("all", "posted", "draft") || confirmation !in setOf(null, "confirmed", "pending")) invalid()
    val hideZero = when (input.hideZeroAmount?.let(::pythonCashLower)) { null, "true" -> true; "false" -> false; else -> invalid() }
    val filters = linkedMapOf("date_from" to bounds.dateFrom, "date_to" to bounds.dateTo, "account_id" to account,
        "counterparty" to input.counterparty?.let(::trim)?.takeIf(String::isNotEmpty), "category_id" to category,
        "uncategorized" to if (uncategorized) "True" else "False", "currency" to currency?.let(::pythonCashUpper),
        "amount_min" to minimumText, "amount_max" to maximumText, "economic_type" to economic, "business_label" to businessLabel,
        "composition" to composition, "timezone" to bounds.timeZoneId, "state" to state, "confirmation_status" to confirmation,
        "hide_zero_amount" to if (hideZero) "True" else "False")
    return LocalCashLedgerQueryInput(filters, bounds.start, bounds.end)
}
