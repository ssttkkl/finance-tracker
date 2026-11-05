package com.finance.tracker.business.contracts

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class RawCashRow(val position: Int, val payload: Map<String, String>?, val errorCode: String? = null,
    val documentPosition: String? = null)


@Serializable
data class CashStatementDocument(
    val sourceType: String, val tableKey: String, val parserVersion: String, val rows: List<RawCashRow>,
    val sourceAccountIdentifier: String?, val sourceAccountLabel: String?, val coverage: CashSourceCoverage,
    val declaredCount: Long?, val observedCount: Int, val sourceCurrency: String? = null,
    val sourceNickname: String? = null,
)

@Serializable
data class CashXlsParseResultDto(
    @SerialName("rules_version") val rulesVersion: String,
    val document: CashStatementDocument,
)
