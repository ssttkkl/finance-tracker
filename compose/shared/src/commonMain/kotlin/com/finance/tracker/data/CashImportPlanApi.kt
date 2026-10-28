package com.finance.tracker.data

import io.ktor.http.HttpMethod
import kotlinx.serialization.json.*

// 独立序列化器保证必填 null、清空选择及字段 patch 的 null 不被全局设置省略。
private val cashChoiceJson = Json(apiJson) { explicitNulls = true; encodeDefaults = true }
private const val IMPORT_PLANS = "/api/v1/cash-import/plans"
private const val RECEIPTS = "/api/v1/cash-mutations/receipts"

suspend fun FinanceApiClient.previewCashImport(sessionId: String, input: CashImportPreviewInput): CashImportPlanDto {
    requireCashRevision(input.expectedSessionRevision)
    if (maxOf(input.accountSelections.size, input.rowChoices.size, input.relationChoices.size) > 100_000) invalidCashInput()
    return request(HttpMethod.Post, "/api/v1/cash-import/sessions/${encodePathSegment(sessionId)}/plans", cashChoiceJson.encodeToJsonElement(input))
}

suspend fun FinanceApiClient.readCashImportPlan(planId: String): CashImportPlanDto = request(HttpMethod.Get, importPlanPath(planId))

suspend fun FinanceApiClient.readCashImportItems(
    planId: String, cursor: String? = null, limit: Int = 50, confirmationStatus: String = "all", startIndex: Int? = null,
): CashPlanItemPageDto {
    if (confirmationStatus !in setOf("all", "confirmed", "pending")) invalidCashInput()
    if (startIndex != null && (startIndex < 0 || cursor != null)) invalidCashInput()
    val page = if (startIndex == null) cashPageQuery(cursor, limit)
        else listOf("start_index" to startIndex.toString(), "limit" to limit.toString())
    return request(HttpMethod.Get, appendQuery(importPlanPath(planId) + "/items", page + ("confirmation_status" to confirmationStatus)))
}

suspend fun FinanceApiClient.readCashImportItem(planId: String, itemId: String): CashPlanItemDetailDto =
    request(HttpMethod.Get, importPlanPath(planId) + "/items/${encodePathSegment(itemId)}")

suspend fun FinanceApiClient.readCashImportProcessing(planId: String, cursor: String? = null, limit: Int = 50): CashPlanProcessingPageDto =
    request(HttpMethod.Get, appendQuery(importPlanPath(planId) + "/processing-details", cashPageQuery(cursor, limit)))

suspend fun FinanceApiClient.commitCashImport(planId: String, expectedSessionRevision: Long, idempotencyKey: String): CashMutationReceiptDto {
    requireCashRevision(expectedSessionRevision)
    return request(HttpMethod.Post, importPlanPath(planId) + "/commit", buildJsonObject { put("expected_session_revision", expectedSessionRevision) }, cashKeyHeaders(idempotencyKey))
}

suspend fun FinanceApiClient.readCashMutationReceipt(receiptId: String): CashMutationReceiptDto =
    request(HttpMethod.Get, receiptPath(receiptId))

suspend fun FinanceApiClient.readCashMutationReceiptByKey(key: String): CashMutationReceiptDto {
    requireReceiptLookupKey(key)
    return request(HttpMethod.Get, appendQuery("$RECEIPTS/by-key", listOf("key" to key)))
}

suspend fun FinanceApiClient.readCashMutationResults(
    receiptId: String, kind: CashMutationResultKind, after: String = "0", limit: Int = 50,
): CashMutationResultPageDto {
    requireCashPageLimit(limit)
    if (!after.matches(Regex("0|[1-9][0-9]{0,18}")) || after.toLongOrNull() == null) invalidCashInput()
    return request(HttpMethod.Get, appendQuery(receiptPath(receiptId) + "/${kind.path}", listOf("after" to after, "limit" to limit.toString())))
}

suspend fun FinanceApiClient.resolveCashMutation(planId: String, idempotencyKey: String): CashMutationResolveDto {
    cashKeyHeaders(idempotencyKey)
    return request(HttpMethod.Post, "/api/v1/cash-mutations/resolve", buildJsonObject { put("plan_id", planId); put("idempotency_key", idempotencyKey) })
}

private fun importPlanPath(id: String) = "$IMPORT_PLANS/${encodePathSegment(id)}"
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
    if (!key.matches(Regex("[A-Za-z0-9_:.~-]{1,255}"))) invalidCashInput()
    return mapOf("Idempotency-Key" to key)
}
