package com.finance.tracker.business.domain

/** Literal case-insensitive containment for the ledger's public counterparty/note filter. */
fun cashLedgerKeywordMatches(counterparty: String, note: String, keyword: String?): Boolean {
    val query = keyword?.trim { it.code in pythonWhitespace }?.takeIf(String::isNotEmpty) ?: return true
    val folded = pythonCashCaseFold(query)
    return folded in pythonCashCaseFold(counterparty) || folded in pythonCashCaseFold(note)
}
