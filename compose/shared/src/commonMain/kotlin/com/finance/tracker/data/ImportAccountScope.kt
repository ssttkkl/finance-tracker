package com.finance.tracker.data

import com.finance.tracker.client.ClientAccountOperations
import com.finance.tracker.client.ClientAccountRequest
import com.finance.tracker.client.ClientAccountTransport
import com.finance.tracker.client.ClientOperationFailure
import io.ktor.http.HttpMethod
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.*

internal class ImportAccountScope(val workspaceId: String, val credentials: ApiCredentialSnapshot) {
    init { requireImportAccountReference(workspaceId) }
}

internal class ImportAccountApi(private val client: FinanceApiClient, private val scope: ImportAccountScope? = null) {
    suspend fun list(): List<ImportAccountListItemDto> =
        client.request<ImportAccountListDto>(HttpMethod.Get, scopedPath("/api/v1/accounts?view=cash"),
            credentials = scope?.credentials, clearUnauthorizedToken = scope == null,
            preserveHttpRejectionOnBodyFailure = scope != null, requireResponseBody = scope != null).items

    suspend fun read(accountId: String): ImportAccountDto = shared { read(accountId) }

    suspend fun create(name: String, type: String, currencies: List<String>, idempotencyKey: String): ImportAccountDto =
        shared { edit(ClientAccountOperations.create(name, type, currencies, idempotencyKey)) }

    suspend fun update(accountId: String, expectedRevision: Long, patch: JsonObject, idempotencyKey: String): ImportAccountDto =
        shared { edit(ClientAccountOperations.update(accountId, expectedRevision, patch, idempotencyKey, ::encodePathSegment)) }

    suspend fun delete(accountId: String, expectedRevision: Long, idempotencyKey: String): ImportAccountDto =
        shared { edit(ClientAccountOperations.delete(accountId, expectedRevision, idempotencyKey, ::encodePathSegment)) }

    suspend fun ensureCurrencies(requirements: List<ImportCurrencyRequirementDto>): List<ImportCurrencyRequirementResultDto> =
        shared { ensureCurrencies(ClientAccountOperations.currencyRequirements(requirements)) }

    suspend fun receipt(receiptId: String): CashMutationReceiptDto = shared { receipt(receiptId) }

    suspend fun receiptByKey(key: String): CashMutationReceiptDto = shared { receiptByKey(key) }

    private suspend fun <T> shared(call: suspend ClientAccountOperations.() -> T): T {
        val transport = object : ClientAccountTransport {
            override suspend fun <Response> send(request: ClientAccountRequest, response: DeserializationStrategy<Response>): Response {
                val credentials = scope?.credentials ?: client.captureCredentials()
                val origin = normalizeApiOrigin(credentials.apiOrigin, client.allowInsecureHttp)
                return client.exchange(HttpMethod.parse(request.method), scopedPath(request.path), origin, credentials.token,
                    request.body?.let(::FrozenJsonBody) ?: NoRequestBody,
                    request.key?.let { mapOf("Idempotency-Key" to it) }.orEmpty(),
                    clearUnauthorizedToken = scope == null, preserveHttpRejectionOnBodyFailure = scope != null) { status, text ->
                    if (status !in 200..299) client.rejectApiPayload(status, text, credentials.token, clearUnauthorizedToken = scope == null)
                    try { apiJson.decodeFromString(response, text) }
                    catch (failure: kotlinx.serialization.SerializationException) {
                        throw ApiFailure("request_failed", status, cause = failure)
                    }
                }
            }
        }
        return try { ClientAccountOperations(transport, ::encodePathSegment).call() }
        catch (failure: ClientOperationFailure) {
            throw ApiFailure(failure.code, failure.status ?: 200, commitState = failure.commitState, cause = failure)
        }
    }

    private fun scopedPath(path: String) = scope?.let { appendQuery(path, listOf("workspace_id" to it.workspaceId)) } ?: path
}

private fun requireImportAccountReference(value: String) {
    if (value.isBlank()) invalidCashInput()
    try { value.encodeToByteArray(throwOnInvalidSequence = true) }
    catch (_: CharacterCodingException) { invalidCashInput() }
}
