package com.finance.tracker.business.domain

import kotlin.time.Instant

/** Gregorian month after applying the explicitly selected zone's offset at this instant. */
fun cashLedgerMonthAtOffset(instant: Instant, offsetSeconds: Int): String {
    require(offsetSeconds in -86400..86400)
    return formatCashCandidateDay((instant.epochSeconds + offsetSeconds).floorDiv(86400L))
        .removePrefix("+").substringBeforeLast('-')
}

/** Normalize only the ledger filter's existing calendar/ISO-week date language. */
fun normalizeCashLedgerFilterDate(raw: String): String {
    fun invalid(): Nothing = throw CashProjectionDomainFailure("invalid_filter")
    if (!raw.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}|[0-9]{8}|[0-9]{4}(?:-W[0-9]{2}(?:-[0-9])?|W[0-9]{2}[0-9]?)"))) invalid()
    val instant = try { parsePythonCashTimestamp(raw, allowNaive = true) }
        catch (_: IllegalArgumentException) { invalid() }
    return formatPythonCashTimestamp(instant).substringBefore('T')
}
