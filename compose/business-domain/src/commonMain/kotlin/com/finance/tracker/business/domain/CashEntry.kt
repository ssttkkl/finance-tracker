package com.finance.tracker.business.domain

import kotlinx.serialization.json.JsonObject

data class CashEntry<I>(val id: String, var rootTransactionId: I, var identityTransactionId: I, var revision: Long,
    var values: JsonObject, var manualFields: List<String>, var splitInherit: Boolean?)

data class CashEntryMember<I>(val transactionId: I, var entryId: String, var standaloneValues: JsonObject,
    var standaloneManualFields: List<String>)

internal fun incrementCashEntryRevision(revision: Long): Long {
    if (revision == Long.MAX_VALUE) throw ArithmeticException("long overflow")
    return revision + 1
}
