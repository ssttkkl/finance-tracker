package com.finance.tracker.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import io.ktor.http.HttpMethod

@Serializable
data class CashSourceComponentDto(val id: String, val amount: String? = null, val currency: String? = null,
    val account: AccountDto? = null, @SerialName("account_name") val accountName: String? = null, val label: String = "")
@Serializable
data class CashSourceMemberDto(val id: String, @SerialName("row_revision") val rowRevision: Long, @SerialName("occurred_at") val occurredAt: String? = null,
    val amount: String? = null, val currency: String? = null, val counterparty: String = "", val note: String = "",
    @SerialName("counterparty_account") val counterpartyAccount: String? = null,
    val account: AccountDto? = null, @SerialName("account_name") val accountName: String? = null,
    val components: List<CashSourceComponentDto> = emptyList())
@Serializable
data class CashSourceEvidenceDto(@SerialName("projection_version") val projectionVersion: Long,
    val projection: CashProjectionDto, val members: List<CashSourceMemberDto>)

suspend fun FinanceApiClient.readCashSourceEvidence(projectionId: String): CashSourceEvidenceDto =
    request(HttpMethod.Get, "/api/v1/evidence/cash-projections/${encodePathSegment(projectionId)}")
