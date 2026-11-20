package com.finance.tracker.business.contracts

import kotlinx.serialization.Serializable

@Serializable
data class StoredImportBatch(
    val version: Int,
    val files: List<String>,
    val submission: String? = null,
    val review: String? = null,
    val attempt: Int = 0,
)
