package com.finance.tracker.data

import com.finance.tracker.business.contracts.CashWriteSetRequestDto
import io.ktor.http.HttpMethod
import kotlinx.serialization.SerializationException

internal val cashWriteSetJson = com.finance.tracker.business.domain.cashWriteSetJson
private const val WRITE_SET_RECEIPTS = "/api/v1/cash-mutations/receipts"

internal suspend fun FinanceApiClient.submitCashImportWriteSet(workspaceId: String, input: CashWriteSetRequestDto,
    idempotencyKey: String, credentials: ApiCredentialSnapshot): CashMutationReceiptDto {
    writeSetReference(workspaceId)
    return submitFrozenCashImportWriteSet(workspaceId, LocalCashImportSubmission.freeze(idempotencyKey, input), credentials)
}

internal suspend fun FinanceApiClient.submitFrozenCashImportWriteSet(workspaceId: String, input: LocalCashImportSubmission,
    credentials: ApiCredentialSnapshot, beforeSend: suspend () -> Unit = {}): CashMutationReceiptDto {
    val path = scopedWriteSetPath(workspaceId, "/api/v1/cash-import/write-set")
    val headers = cashKeyHeaders(input.idempotencyKey)
    // Freeze caller-owned JSON containers before the first suspension; exact serializers preserve numeric tokens.
    val body = FrozenJsonBody(input.requestBody)
    return writeSetReceiptRequest(HttpMethod.Post, path, credentials, body, headers, beforeSend)
}

internal suspend fun FinanceApiClient.readCashImportWriteSetReceipt(workspaceId: String, receiptId: String,
    credentials: ApiCredentialSnapshot): CashMutationReceiptDto {
    writeSetReference(receiptId)
    val path = scopedWriteSetPath(workspaceId, "$WRITE_SET_RECEIPTS/${encodePathSegment(receiptId)}")
    return writeSetReceiptRequest(HttpMethod.Get, path, credentials).also {
        if (it.receiptId != receiptId) invalidWriteSetReceipt()
    }
}

internal suspend fun FinanceApiClient.readCashImportWriteSetReceiptByKey(workspaceId: String, key: String,
    credentials: ApiCredentialSnapshot): CashMutationReceiptDto {
    cashKeyHeaders(key)
    return writeSetReceiptRequest(HttpMethod.Get,
        appendQuery(scopedWriteSetPath(workspaceId, "$WRITE_SET_RECEIPTS/by-key"), listOf("key" to key)), credentials)
}

private fun writeSetReference(value: String) {
    if (value.isBlank()) invalidCashInput()
    try { value.encodeToByteArray(throwOnInvalidSequence = true) }
    catch (_: CharacterCodingException) { invalidCashInput() }
}

private fun scopedWriteSetPath(workspaceId: String, path: String): String {
    writeSetReference(workspaceId)
    return appendQuery(path, listOf("workspace_id" to workspaceId))
}

private fun invalidWriteSetReceipt(cause: Throwable = SerializationException("cash_write_set_receipt_invalid")): Nothing =
    throw ApiFailure("request_failed", 200, cause = cause)

private suspend fun FinanceApiClient.writeSetReceiptRequest(method: HttpMethod, path: String, credentials: ApiCredentialSnapshot,
    body: Any = NoRequestBody, headers: Map<String, String> = emptyMap(), beforeSend: suspend () -> Unit = {}): CashMutationReceiptDto {
    val receipt: CashMutationReceiptDto = request(method, path, body, headers, clearUnauthorizedToken = false,
        credentials = credentials, preserveHttpRejectionOnBodyFailure = true, requireResponseBody = true, browserJson = method == HttpMethod.Post, beforeSend = beforeSend)
    validateCashWriteSetReceipt(receipt)
    return receipt
}

internal fun validateCashWriteSetReceipt(receipt: CashMutationReceiptDto) {
    try { com.finance.tracker.business.domain.requireCashWriteSetReceipt(receipt) }
    catch (failure: IllegalStateException) { invalidWriteSetReceipt(failure) }
}
