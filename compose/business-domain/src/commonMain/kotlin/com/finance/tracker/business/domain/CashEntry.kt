package com.finance.tracker.business.domain

import kotlinx.serialization.json.JsonObject

data class CashEntry(val id: String, var rootTransactionId: Long, var identityTransactionId: Long, var revision: Long,
    var values: JsonObject, var manualFields: List<String>, var splitInherit: Boolean?)

data class CashEntryMember(val transactionId: Long, var entryId: String, var standaloneValues: JsonObject,
    var standaloneManualFields: List<String>)
