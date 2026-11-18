package com.finance.tracker.business.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CashSourceComponentDto(val id: String, val amount: String? = null, val currency: String? = null,
    val account: AccountReferenceDto? = null, @SerialName("account_name") val accountName: String? = null, val label: String = "")
@Serializable
data class CashSourceMemberDto(val id: String, @SerialName("row_revision") val rowRevision: Long, @SerialName("occurred_at") val occurredAt: String? = null,
    val amount: String? = null, val currency: String? = null, val counterparty: String = "", val note: String = "",
    @SerialName("counterparty_account") val counterpartyAccount: String? = null,
    val account: AccountReferenceDto? = null, @SerialName("account_name") val accountName: String? = null,
    val components: List<CashSourceComponentDto> = emptyList())
@Serializable
data class CashSourceEvidenceDto(@SerialName("projection_version") val projectionVersion: Long,
    val projection: CashProjectionDto, val members: List<CashSourceMemberDto>)
