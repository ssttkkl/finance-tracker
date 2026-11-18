package com.finance.tracker.business.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CashPlanDisplayDto(
    @SerialName("occurred_at") val occurredAt: String?,
    val amount: String?, val currency: String?, val counterparty: String?, val note: String?,
    val accounts: List<String>,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("counterparty_account") val counterpartyAccount: String? = null,
    @SerialName("counterparty_account_attrs") val counterpartyAccountAttrs: List<String>? = null,
    @SerialName("record_type") val recordType: String? = null,
    @SerialName("record_subtype") val recordSubtype: String? = null,
)
