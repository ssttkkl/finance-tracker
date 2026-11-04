package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.CashSourceCoverage

/** 已解码平台账单行的逐条归一结果。 */
data class ParsedCashRow(
    val position: Int,
    val fields: Map<String, Any?>?,
    val paymentMethod: String = "",
    val providerId: String? = null,
    val issueCodes: List<String> = emptyList(),
    val errorCode: String? = null,
    val disposition: String = "candidate",
    val sourceAccountIdentifier: String? = null,
    val documentPosition: String? = null,
    val sourceExplanation: Map<String, Any?> = emptyMap(),
)

data class ParsedCashDocument(
    val sourceType: String,
    val tableKey: String,
    val parserVersion: String,
    val rows: List<ParsedCashRow>,
    val sourceAccountIdentifier: String?,
    val sourceAccountLabel: String?,
    val coverage: CashSourceCoverage,
    val declaredCount: Number?,
    val observedCount: Number,
    val sourceNickname: String? = null,
)
