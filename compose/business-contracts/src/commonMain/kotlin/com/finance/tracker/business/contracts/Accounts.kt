package com.finance.tracker.business.contracts

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AccountCommandViewDto(
    @SerialName("account_id") val accountId: String,
    val name: String, val type: String, val active: Boolean, val currencies: List<String>,
    @SerialName("account_revision") val accountRevision: Long,
    val deleted: Boolean,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val capabilities: Map<String, Boolean> = emptyMap(),
    @SerialName("receipt_id") val receiptId: String? = null,
    val committed: Boolean? = null,
)

@Serializable
data class AccountReferenceDto(
    val id: Long,
    val name: String,
    val type: String,
    val active: Boolean = true,
    val currencies: List<String>? = null,
)
