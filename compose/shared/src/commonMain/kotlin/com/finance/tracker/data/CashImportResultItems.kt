package com.finance.tracker.data

import com.finance.tracker.business.contracts.CashMutationReceiptDto
import com.finance.tracker.business.contracts.CashMutationResultDto
import com.finance.tracker.business.contracts.CashMutationResultPageDto
import io.ktor.http.HttpMethod
import com.finance.tracker.business.domain.importResultId
import com.finance.tracker.business.domain.invalidImportResultItems
import com.finance.tracker.business.domain.validateImportResultItems

internal suspend fun FinanceApiClient.readCashImportResultPages(workspaceId: String, receipt: CashMutationReceiptDto,
    credentials: ApiCredentialSnapshot, checkActive: suspend () -> Unit): List<String> {
    val pages = mutableListOf<String>()
    readCashImportResults(workspaceId, receipt, credentials, checkActive) { pages.add(it) }
    return pages
}

/** Permanent operation outcomes only. Current server projection references are deliberately not retained. */
internal suspend fun FinanceApiClient.readCashImportResultItems(workspaceId: String, receipt: CashMutationReceiptDto,
    credentials: ApiCredentialSnapshot, checkActive: suspend () -> Unit): List<CashMutationResultDto> =
    readCashImportResults(workspaceId, receipt, credentials, checkActive)

private suspend fun FinanceApiClient.readCashImportResults(workspaceId: String, receipt: CashMutationReceiptDto,
    credentials: ApiCredentialSnapshot, checkActive: suspend () -> Unit, onPage: ((String) -> Unit)? = null): List<CashMutationResultDto> {
    validateCashWriteSetReceipt(receipt)
    val identities = checkNotNull(receipt.writeSetResult)
    val result = mutableListOf<CashMutationResultDto>()
    for ((kind, expected) in listOf("rows" to identities.transactions.size, "relations" to identities.relations.size)) {
        if (expected == 0) continue
        var after = "0"
        var received = 0
        while (true) {
            checkActive()
            val page: CashMutationResultPageDto = boundedCashImportRequest {
                request(HttpMethod.Get,
                appendQuery("/api/v1/cash-import/write-set/receipt/${encodePathSegment(receipt.receiptId)}/$kind",
                    listOf("workspace_id" to workspaceId, "after" to after, "limit" to "200")),
                clearUnauthorizedToken = false, credentials = credentials,
                preserveHttpRejectionOnBodyFailure = true, requireResponseBody = true,
                decodeResponse = onPage?.let { capture -> { text ->
                    apiJson.decodeFromString<CashMutationResultPageDto>(text).also { capture(text) }
                } })
            }
            checkActive()
            if (page.items.size > 200 || page.items.size > expected - received) invalidImportResultItems()
            var last = importResultId(after, allowZero = true)
            for (item in page.items) {
                val next = importResultId(item.resultId)
                if (next <= last || item.resultKind != (if (kind == "rows") "row" else "relation")) invalidImportResultItems()
                last = next
            }
            if (onPage == null) result += page.items
            received += page.items.size
            val next = page.nextAfter ?: break
            if (received == expected || page.items.isEmpty() || next != page.items.last().resultId || importResultId(next) <= importResultId(after, true)) invalidImportResultItems()
            after = next
        }
        if (received != expected) invalidImportResultItems()
    }
    if (onPage == null) validateImportResultItems(receipt, result)
    return result.toList()
}
