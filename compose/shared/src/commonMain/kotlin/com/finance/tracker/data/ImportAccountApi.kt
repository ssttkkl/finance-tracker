package com.finance.tracker.data

import io.ktor.http.HttpMethod
import kotlinx.serialization.json.*

suspend fun FinanceApiClient.listCashImportAccounts(): List<ImportAccountListItemDto> =
    request<ImportAccountListDto>(HttpMethod.Get, "/api/v1/accounts?view=cash").items

suspend fun FinanceApiClient.readImportAccount(accountId: String): ImportAccountDto = request(HttpMethod.Get, importAccountPath(accountId))

suspend fun FinanceApiClient.createImportAccount(name: String, type: String, currencies: List<String>, idempotencyKey: String): ImportAccountDto =
    request(HttpMethod.Post, "/api/v1/accounts", buildJsonObject {
        put("name", name); put("type", type); put("currencies", apiJson.encodeToJsonElement(currencies))
    }, cashKeyHeaders(idempotencyKey))

suspend fun FinanceApiClient.updateImportAccount(accountId: String, expectedRevision: Long, patch: JsonObject, idempotencyKey: String): ImportAccountDto {
    requireCashRevision(expectedRevision)
    if (patch.keys.any { it !in setOf("name", "active", "currencies") }) invalidCashInput()
    return request(HttpMethod.Patch, importAccountPath(accountId), buildJsonObject {
        put("expected_revision", expectedRevision); put("patch", patch)
    }, cashKeyHeaders(idempotencyKey))
}

suspend fun FinanceApiClient.deleteImportAccount(accountId: String, expectedRevision: Long, idempotencyKey: String): ImportAccountDto {
    requireCashRevision(expectedRevision)
    return request(HttpMethod.Delete, importAccountPath(accountId), buildJsonObject { put("expected_revision", expectedRevision) }, cashKeyHeaders(idempotencyKey))
}

private fun importAccountPath(id: String) = "/api/v1/accounts/by-id/${encodePathSegment(id)}"
