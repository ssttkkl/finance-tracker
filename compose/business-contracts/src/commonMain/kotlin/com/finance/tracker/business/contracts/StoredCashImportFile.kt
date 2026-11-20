package com.finance.tracker.business.contracts

import kotlinx.serialization.Serializable

@Serializable
data class StoredCashImportFile(
    val version: Int,
    val clientFileRef: String,
    val filename: String,
    val mediaType: String?,
    val lastModifiedAtMs: Long?,
    val sourceHint: String?,
    val size: Long,
    val digest: String,
    val bytes: String?,
    val document: NormalizedCashDocumentDto?,
    val status: String = "parsed",
    val errorCode: String? = null,
)
