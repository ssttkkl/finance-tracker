package com.finance.tracker.business.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CashCurrentMemberDto(
    @SerialName("transaction_id") val transactionId: String,
    @SerialName("row_revision") val rowRevision: Long,
    @SerialName("confirmation_status") val confirmationStatus: String,
)

@Serializable
data class CashCurrentMembersDto(
    @SerialName("projection_version") val projectionVersion: Long,
    val members: List<CashCurrentMemberDto>,
)
