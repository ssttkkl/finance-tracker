@file:kotlinx.serialization.UseSerializers(ExactJsonObjectSerializer::class)

package com.finance.tracker.business.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class NormalizedCashRowDto(
    val position: Int,
    val fields: JsonObject?,
    @SerialName("payment_method") val paymentMethod: String,
    @SerialName("provider_id") val providerId: String?,
    @SerialName("issue_codes") val issueCodes: List<String>,
    @SerialName("error_code") val errorCode: String?,
    val disposition: String,
    @SerialName("source_account_identifier") val sourceAccountIdentifier: String?,
    @SerialName("document_position") val documentPosition: String?,
    @SerialName("source_explanation") val sourceExplanation: JsonObject,
)

@Serializable
data class NormalizedCashDocumentDto(
    @SerialName("source_type") val sourceType: String,
    @SerialName("table_key") val tableKey: String,
    @SerialName("parser_version") val parserVersion: String,
    val rows: List<NormalizedCashRowDto>,
    @SerialName("source_account_identifier") val sourceAccountIdentifier: String?,
    @SerialName("source_account_label") val sourceAccountLabel: String?,
    val coverage: CashSourceCoverage,
    @SerialName("declared_count") val declaredCount: Long?,
    @SerialName("observed_count") val observedCount: Int,
    @SerialName("source_nickname") val sourceNickname: String?,
)

@Serializable
data class CashPdfParseResultDto(
    @SerialName("rules_version") val rulesVersion: String,
    val document: NormalizedCashDocumentDto,
)
