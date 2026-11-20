package com.finance.tracker.business.contracts

import kotlinx.serialization.Serializable

@Serializable
data class CashImportArchiveSummary(val batchId: String, val submissionKey: String, val receiptId: String,
    val hasServerItems: Boolean, val status: String?, val counts: Map<String, Long>)

@Serializable
data class StoredResult(val version: Int, val batchId: String, val submissionKey: String, val receipt: String, val input: String, val serverItems: List<CashMutationResultDto>? = null)

@Serializable
data class StoredCashImportSubmission(val key: String, val request: String, val receipt: String? = null,
    val baseRejected: Boolean = false, val resultInput: String? = null)
