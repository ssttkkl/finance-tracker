package com.finance.tracker.data

import com.finance.tracker.business.contracts.CashWriteSetRequestDto
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val writeSetJson = Json { encodeDefaults = true; explicitNulls = true }
private const val WRITE_SET_RECEIPTS = "/api/v1/cash-mutations/receipts"

internal suspend fun FinanceApiClient.submitCashImportWriteSet(workspaceId: String, input: CashWriteSetRequestDto,
    idempotencyKey: String, credentials: ApiCredentialSnapshot): CashMutationReceiptDto {
    val path = scopedWriteSetPath(workspaceId, "/api/v1/cash-import/write-set")
    val headers = cashKeyHeaders(idempotencyKey)
    // Freeze caller-owned JSON containers before the first suspension; exact serializers preserve numeric tokens.
    val body = TextContent(writeSetJson.encodeToString(input), ContentType.Application.Json)
    return writeSetReceiptRequest(HttpMethod.Post, path, credentials, body, headers)
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
    body: Any = NoRequestBody, headers: Map<String, String> = emptyMap()): CashMutationReceiptDto {
    val receipt: CashMutationReceiptDto = request(method, path, body, headers, clearUnauthorizedToken = false,
        credentials = credentials, preserveHttpRejectionOnBodyFailure = true, requireResponseBody = true)
    val sequence = receipt.ledgerSeq
    if (!receipt.committed || receipt.operationKind != "cash_write_set" || receipt.planId != null ||
        receipt.writeSetResult == null || sequence == null || receipt.receiptId.isBlank()) invalidWriteSetReceipt()
    try { checkedReceiptLedgerSequence(sequence) }
    catch (failure: IllegalStateException) { invalidWriteSetReceipt(failure) }
    return receipt
}
