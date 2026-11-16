package com.finance.tracker.data

import io.ktor.http.HttpMethod

typealias CashSourceComponentDto = com.finance.tracker.business.contracts.CashSourceComponentDto
typealias CashSourceMemberDto = com.finance.tracker.business.contracts.CashSourceMemberDto
typealias CashSourceEvidenceDto = com.finance.tracker.business.contracts.CashSourceEvidenceDto

suspend fun FinanceApiClient.readCashSourceEvidence(projectionId: String): CashSourceEvidenceDto =
    request(HttpMethod.Get, "/api/v1/evidence/cash-projections/${encodePathSegment(projectionId)}")
