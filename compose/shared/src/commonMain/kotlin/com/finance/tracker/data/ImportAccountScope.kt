package com.finance.tracker.data

import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.*

internal class ImportAccountScope(val workspaceId: String, val credentials: ApiCredentialSnapshot) {
    init { requireImportAccountReference(workspaceId) }
}

internal class ImportAccountApi(private val client: FinanceApiClient, private val scope: ImportAccountScope? = null) {
    suspend fun list(): List<ImportAccountListItemDto> =
        request<ImportAccountListDto>(HttpMethod.Get, "/api/v1/accounts?view=cash").items

    suspend fun read(accountId: String): ImportAccountDto = request(HttpMethod.Get, importAccountPath(accountId))

    suspend fun create(name: String, type: String, currencies: List<String>, idempotencyKey: String): ImportAccountDto =
        request(HttpMethod.Post, "/api/v1/accounts", buildJsonObject {
            put("name", name); put("type", type); put("currencies", apiJson.encodeToJsonElement(currencies))
        }, cashKeyHeaders(idempotencyKey))

    suspend fun update(accountId: String, expectedRevision: Long, patch: JsonObject, idempotencyKey: String): ImportAccountDto {
        requireCashRevision(expectedRevision)
        if (patch.keys.any { it !in setOf("name", "active", "currencies") }) invalidCashInput()
        return request(HttpMethod.Patch, importAccountPath(accountId), buildJsonObject {
            put("expected_revision", expectedRevision); put("patch", patch)
        }, cashKeyHeaders(idempotencyKey))
    }

    suspend fun delete(accountId: String, expectedRevision: Long, idempotencyKey: String): ImportAccountDto {
        requireCashRevision(expectedRevision)
        return request(HttpMethod.Delete, importAccountPath(accountId), buildJsonObject { put("expected_revision", expectedRevision) }, cashKeyHeaders(idempotencyKey))
    }

    suspend fun ensureCurrencies(requirements: List<ImportCurrencyRequirementDto>): List<ImportCurrencyRequirementResultDto> {
        if (requirements.isEmpty()) invalidCashInput()
        requirements.forEach { requireCashRevision(it.expectedRevision) }
        return request<ImportCurrencyRequirementsViewDto>(HttpMethod.Post, "/api/v1/cash-import/accounts/currency-requirements",
            buildJsonObject { put("items", apiJson.encodeToJsonElement(requirements)) }).results
    }

    private fun importAccountPath(id: String) = "/api/v1/accounts/by-id/${encodePathSegment(id)}"

    suspend fun receipt(receiptId: String): CashMutationReceiptDto {
        requireImportAccountReference(receiptId)
        val result: CashMutationReceiptDto = request(HttpMethod.Get, "/api/v1/cash-mutations/receipts/${encodePathSegment(receiptId)}")
        if (result.receiptId != receiptId) throw ApiFailure("request_failed", 200)
        return result
    }

    suspend fun receiptByKey(key: String): CashMutationReceiptDto {
        cashKeyHeaders(key)
        return request(HttpMethod.Get, appendQuery("/api/v1/cash-mutations/receipts/by-key", listOf("key" to key)))
    }

    private suspend inline fun <reified T> request(method: HttpMethod, path: String, body: Any = NoRequestBody,
        headers: Map<String, String> = emptyMap()): T {
        val scopedPath = scope?.let { appendQuery(path, listOf("workspace_id" to it.workspaceId)) } ?: path
        val frozenBody = if (scope != null && body is JsonElement) TextContent(body.toString(), ContentType.Application.Json) else body
        return client.request(method, scopedPath, frozenBody, headers, credentials = scope?.credentials,
            clearUnauthorizedToken = scope == null, preserveHttpRejectionOnBodyFailure = scope != null, requireResponseBody = scope != null)
    }
}

private fun requireImportAccountReference(value: String) {
    if (value.isBlank()) invalidCashInput()
    try { value.encodeToByteArray(throwOnInvalidSequence = true) }
    catch (_: CharacterCodingException) { invalidCashInput() }
}
