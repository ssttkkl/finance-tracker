package com.finance.tracker.data

import com.finance.tracker.business.contracts.CashImportConfirmation
import com.finance.tracker.client.requireCashImportConfirmation
import kotlinx.coroutines.withTimeoutOrNull
import io.ktor.http.HttpMethod
import kotlinx.serialization.SerializationException

internal val cashWriteSetJson = com.finance.tracker.business.domain.cashWriteSetJson
private const val WRITE_SET_RECEIPTS = "/api/v1/cash-mutations/receipts"

internal suspend fun FinanceApiClient.acceptCashImportConfirmation(workspace: String, input: LocalCashImportSubmission,
    credentials: ApiCredentialSnapshot, beforeSend: suspend () -> Unit): CashImportConfirmation {
    val body = cashImportRequestBody(input, credentials)
    return cashImportConfirmationRequest(HttpMethod.Post, workspace, "", input.idempotencyKey, credentials, body, beforeSend)
}

internal suspend fun FinanceApiClient.cashImportConfirmation(workspace: String, key: String,
    credentials: ApiCredentialSnapshot, cancel: Boolean = false, beforeSend: suspend () -> Unit = {}): CashImportConfirmation =
    cashImportConfirmationRequest(if (cancel) HttpMethod.Post else HttpMethod.Get, workspace,
        if (cancel) "/cancel" else "/status", key, credentials, beforeSend = beforeSend)

private suspend fun FinanceApiClient.cashImportConfirmationRequest(method: HttpMethod, workspace: String, suffix: String,
    key: String, credentials: ApiCredentialSnapshot, body: Any = NoRequestBody,
    beforeSend: suspend () -> Unit): CashImportConfirmation {
    val path = scopedWriteSetPath(workspace, "/api/v1/cash-import/write-set$suffix")
    return boundedCashImportRequest {
        request<CashImportConfirmation>(method, path, body, cashKeyHeaders(key), credentials = credentials,
            clearUnauthorizedToken = false, preserveHttpRejectionOnBodyFailure = true, requireResponseBody = true,
            browserJson = suffix.isEmpty(), beforeSend = beforeSend).let { requireCashImportConfirmation(key, it) }
    }
}

internal suspend fun <T : Any> boundedCashImportRequest(request: suspend () -> T): T =
    withTimeoutOrNull(8_000) { request() } ?: throw FinanceTransportFailure(IllegalStateException("cash_import_request_timeout"))

private const val CASH_IMPORT_GZIP_MIN_BYTES = 16 * 1024

/** Use the persisted compact transport when present; compression is chosen only from cached server capability. */
private suspend fun FinanceApiClient.cashImportRequestBody(input: LocalCashImportSubmission,
    credentials: ApiCredentialSnapshot): FrozenJsonBody {
    val text = input.transportBody ?: input.requestBody
    val bytes = text.encodeToByteArray()
    val key = CashImportTransportCache.key(credentials.apiOrigin, credentials.token)
    val capabilities = CashImportTransportCache.cached(key)
    if (capabilities?.supportsGzipRequest != true || bytes.size < CASH_IMPORT_GZIP_MIN_BYTES) return FrozenJsonBody(text)
    val encoding = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { encodeCashImportRequest(bytes) }
    return when (encoding) {
        is CashImportRequestEncoding.Native ->
            if (encoding.gzip.size < bytes.size) FrozenJsonBody(text, gzip = encoding.gzip) else FrozenJsonBody(text)
        CashImportRequestEncoding.Browser -> FrozenJsonBody(text, compressInBrowser = true)
        CashImportRequestEncoding.Identity -> FrozenJsonBody(text)
    }
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
    return boundedCashImportRequest {
        writeSetReceiptRequest(HttpMethod.Get, scopedWriteSetPath(workspaceId, "/api/v1/cash-import/write-set/receipt"),
            credentials, headers = cashKeyHeaders(key))
    }
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
    receipt.pendingTransactionIds?.let { ids ->
        val pending = receipt.pendingItemCount
        if (pending == null || pending !in 0L..ids.size.toLong() || (pending == 0L) != ids.isEmpty() ||
            ids.toSet().size != ids.size || ids.any { id -> id.toLongOrNull()?.let { it > 0 && it.toString() == id } != true }) {
            invalidWriteSetReceipt()
        }
    }
}
