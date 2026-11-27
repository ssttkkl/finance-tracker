package com.finance.tracker.data

import io.ktor.http.HttpMethod

private const val RECEIPTS = "/api/v1/cash-mutations/receipts"

suspend fun FinanceApiClient.readCashMutationReceipt(receiptId: String): CashMutationReceiptDto =
    request(HttpMethod.Get, receiptPath(receiptId))

suspend fun FinanceApiClient.readCashMutationReceiptByKey(key: String): CashMutationReceiptDto {
    requireReceiptLookupKey(key)
    return request(HttpMethod.Get, appendQuery("$RECEIPTS/by-key", listOf("key" to key)),
        preserveHttpRejectionOnBodyFailure = true, requireResponseBody = true)
}

suspend fun FinanceApiClient.readCashMutationResults(
    receiptId: String, kind: CashMutationResultKind, after: String = "0", limit: Int = 50,
): CashMutationResultPageDto {
    requireCashPageLimit(limit)
    if (!after.matches(Regex("0|[1-9][0-9]{0,18}")) || after.toLongOrNull() == null) invalidCashInput()
    return request(HttpMethod.Get, appendQuery(receiptPath(receiptId) + "/${kind.path}", listOf("after" to after, "limit" to limit.toString())))
}

suspend fun FinanceApiClient.resolveCashMutation(planId: String, idempotencyKey: String): CashMutationResolveDto =
    cashMaintenance { resolve(expectedWorkspace, planId, idempotencyKey) }

private fun receiptPath(id: String) = "$RECEIPTS/${encodePathSegment(id)}"
private fun requireReceiptLookupKey(key: String) {
    var index = 0
    var count = 0
    while (index < key.length) {
        val character = key[index++]
        if (character.isHighSurrogate()) {
            if (index >= key.length || !key[index++].isLowSurrogate()) invalidCashInput()
        } else if (character.isLowSurrogate()) invalidCashInput()
        if (++count > 255) invalidCashInput()
    }
    if (count == 0) invalidCashInput()
}
internal fun invalidCashInput(): Nothing = throw ApiFailure("import_input_invalid", 0)
internal fun requireCashRevision(revision: Long) { if (revision <= 0 || revision == Long.MAX_VALUE) invalidCashInput() }
internal fun requireCashPageLimit(limit: Int) { if (limit !in 1..200) invalidCashInput() }
internal fun cashPageQuery(cursor: String?, limit: Int): List<Pair<String, String?>> {
    requireCashPageLimit(limit)
    if ((cursor?.length ?: 0) > 4096) invalidCashInput()
    return listOf("cursor" to cursor, "limit" to limit.toString())
}
internal fun cashKeyHeaders(key: String): Map<String, String> {
    if (!com.finance.tracker.business.domain.isCashWriteSetKey(key)) invalidCashInput()
    return mapOf("Idempotency-Key" to key)
}
