package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.CashMutationReceiptDto
import com.finance.tracker.business.contracts.CashMutationResultDto
import com.finance.tracker.business.contracts.CashImportArchiveSummary
import com.finance.tracker.business.contracts.StoredResult
import kotlinx.serialization.json.*

/** Raw authority and local omissions stay separate; the composed receipt is only a result view. */
class CashImportResultContent private constructor(
    val batchId: String,
    val submissionKey: String,
    val receiptBody: String,
    val input: LocalCashImportResultInput,
    private val items: List<CashMutationResultDto>? = null,
) {
    val hasServerItems: Boolean get() = items != null
    fun serverItems(): List<CashMutationResultDto>? = items
    fun baseEncoding(): String = encodeCashImportArchiveBase(batchId, submissionKey, receiptBody, input.encode())
    private val authority by lazy { cashWriteSetJson.decodeFromString<CashMutationReceiptDto>(receiptBody) }
    fun authoritativeReceipt(): CashMutationReceiptDto = authority
    fun archiveSummary(): CashImportArchiveSummary {
        val display = displayReceipt()
        return CashImportArchiveSummary(batchId, submissionKey, display.receiptId, hasServerItems, display.status, display.summary)
    }
    fun displayReceipt(actual: CashMutationReceiptDto = authoritativeReceipt()): CashMutationReceiptDto {
        val local = input.content()
        val rows = local.getValue("rows").jsonArray.map { it.jsonObject }
        val failures = local.getValue("relation_failures").jsonArray
        val summary = actual.summary.toMutableMap()
        fun add(key: String, count: Int) {
            if (count == 0) return
            val old = summary[key] ?: 0L
            check(old >= 0 && old <= Long.MAX_VALUE - count) { "cash_import_result_count_invalid" }
            summary[key] = old + count
        }
        add("duplicate", rows.count { it.text("planned_action") in setOf("duplicate", "duplicate_in_batch") })
        add("skipped", rows.count { it.text("planned_action") == "skip" })
        add("relations_failed", failures.size)
        val problem = rows.any { it.text("planned_action") == "skip" && it.optionalText("code") !in normalSkips } ||
            failures.isNotEmpty() || local.getValue("files").jsonArray.any { it.jsonObject.text("status") in setOf("failed", "password_required") }
        val succeeded = summary.any { (key, value) -> value > 0 && key in successfulCounts }
        val status = if (problem && succeeded) "partial" else if (problem) "failed" else actual.status
        return actual.copy(status = status, summary = summary)
    }
    fun rows(): JsonArray {
        val identities = checkNotNull(authoritativeReceipt().writeSetResult).transactions.map { it.id }
        return JsonArray(input.content().getValue("rows").jsonArray.map { raw ->
            val row = raw.jsonObject
            val index = row["operation_index"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.int
            if (index == null) row else JsonObject(row + ("transaction_id" to JsonPrimitive(identities[index])))
        })
    }
    fun relationFailures(): JsonArray {
        val identities = checkNotNull(authoritativeReceipt().writeSetResult).transactions.map { it.id }
        return JsonArray(input.content().getValue("relation_failures").jsonArray.map { raw ->
            val failure = raw.jsonObject
            JsonObject((failure - "parents") + ("parent_ids" to JsonArray(failure.getValue("parents").jsonArray.map { rawRef ->
                val ref = rawRef.jsonObject
                ref["transaction_id"] ?: JsonPrimitive(identities[ref.getValue("operation_index").jsonPrimitive.int])
            })))
        })
    }
    private fun validateLinks() {
        val count = checkNotNull(authoritativeReceipt().writeSetResult).transactions.size
        fun validate(index: JsonElement?) {
            if (index == null || index == JsonNull) return
            check(index.jsonPrimitive.int in 0 until count) { "cash_import_result_reference_invalid" }
        }
        val local = input.content()
        local.getValue("rows").jsonArray.forEach { validate(it.jsonObject["operation_index"]) }
        local.getValue("relation_failures").jsonArray.forEach { raw ->
            raw.jsonObject.getValue("parents").jsonArray.forEach { validate(it.jsonObject["operation_index"]) }
        }
    }
    fun encode(): String = Json.encodeToString(StoredResult(1, batchId, submissionKey, receiptBody, input.encode(), serverItems()))

    companion object {
        fun assemble(batchId: String, submissionKey: String, receipt: String, input: LocalCashImportResultInput,
            serverItems: List<CashMutationResultDto>?): CashImportResultContent {
            return CashImportResultContent(batchId, submissionKey, receipt, input, serverItems?.toList()).also {
                it.validateLinks(); serverItems?.let { items -> validateImportResultItems(it.authoritativeReceipt(), items) }
            }
        }
        fun decode(body: String): CashImportResultContent {
            val stored = Json.decodeFromString<StoredResult>(body)
            check(stored.version == 1 && stored.batchId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "cash_import_result_invalid" }
            check(isCashWriteSetKey(stored.submissionKey)) { "cash_import_submission_identity_invalid" }
            return CashImportResultContent(stored.batchId, stored.submissionKey, stored.receipt,
                LocalCashImportResultInput.decode(stored.input), stored.serverItems).also {
                requireCashWriteSetReceipt(it.authoritativeReceipt())
                it.validateLinks()
                stored.serverItems?.let { items -> validateImportResultItems(it.authoritativeReceipt(), items) }
            }
        }
        private val normalSkips = setOf("row_not_selected", "unpaid_closed", "failed_repay", "account_opening")
        private val successfulCounts = setOf("created_posted", "updated_posted", "saved_draft", "finalized_draft", "duplicate", "merged_draft",
            "relations_confirmed", "relations_deferred", "relations_ignored", "relations_duplicate")
    }
}

fun encodeCashImportArchiveBase(batchId: String, key: String, receipt: String, input: String): String =
    Json.encodeToString(StoredResult(1, batchId, key, receipt, input))

private fun JsonObject.text(key: String): String = checkNotNull(optionalText(key)) { "cash_import_result_input_invalid" }
private fun JsonObject.optionalText(key: String): String? = get(key)?.takeUnless { it == JsonNull }?.let {
    check(it is JsonPrimitive && it.isString && it.content.isNotBlank()) { "cash_import_result_input_invalid" }
    it.content
}
