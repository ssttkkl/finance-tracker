package com.finance.tracker.data

import com.finance.tracker.business.contracts.CashXlsParseResultDto
import io.ktor.http.HttpMethod

/** Decode one legacy CCB XLS remotely; normalization and matching remain local. */
suspend fun FinanceApiClient.parseCashXls(input: CashImportUploadInput): CashXlsParseResultDto {
    return parseCashXls(input, null)
}

internal suspend fun FinanceApiClient.parseCashXls(input: CashImportUploadInput, credentials: ApiCredentialSnapshot?): CashXlsParseResultDto {
    validateXlsOptions(input)
    val body = cashStatementParseBody(input, "xls", "application/vnd.ms-excel")
    return request(HttpMethod.Post, "/api/v1/cash-import/xls/parse", body,
        bodyContentType = body.contentType, clearUnauthorizedToken = false, credentials = credentials)
}

internal suspend fun FinanceApiClient.parseCashXlsText(input: CashImportUploadInput, credentials: ApiCredentialSnapshot?): String {
    validateXlsOptions(input)
    val body = cashStatementParseBody(input, "xls", "application/vnd.ms-excel")
    return request(HttpMethod.Post, "/api/v1/cash-import/xls/parse", body, bodyContentType = body.contentType,
        clearUnauthorizedToken = false, credentials = credentials, requireResponseBody = true, decodeResponse = { it })
}

private fun validateXlsOptions(input: CashImportUploadInput) {
    if (input.password != null) throw ApiFailure("import_payload_invalid", 0)
    if (input.sourceHint?.replace('-', '_')?.let { it != "ccb_debit" } == true)
        throw ApiFailure("statement_source_unsupported", 0)
}
