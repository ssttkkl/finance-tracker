package com.finance.tracker.business.domain

import kotlin.time.Instant

/** Gregorian month after applying the explicitly selected zone's offset at this instant. */
fun cashLedgerMonthAtOffset(instant: Instant, offsetSeconds: Int): String {
    require(offsetSeconds in -86400..86400)
    return formatCashCandidateDay((instant.epochSeconds + offsetSeconds).floorDiv(86400L))
        .removePrefix("+").substringBeforeLast('-')
}
