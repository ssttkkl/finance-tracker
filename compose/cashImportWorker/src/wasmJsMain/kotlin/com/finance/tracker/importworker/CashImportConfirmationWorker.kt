@file:OptIn(kotlin.js.ExperimentalJsExport::class, kotlin.js.ExperimentalWasmJsInterop::class)

package com.finance.tracker.importworker

import com.finance.tracker.business.contracts.CashWriteSetRequestDto
import com.finance.tracker.business.contracts.CashMutationResultDto
import com.finance.tracker.business.domain.CashImportResultContent
import com.finance.tracker.business.domain.LocalCashImportResultInput
import com.finance.tracker.business.domain.cashWriteSetJson
import com.finance.tracker.business.domain.decodeStoredCashImportSubmission
import kotlinx.serialization.json.*
import kotlin.js.JsAny
import kotlin.js.JsExport

@JsExport
fun freezeCashImportConfirmation(material: String): JsAny {
    val confirmation = Json.parseToJsonElement(material).jsonObject
    val writeSet = Json.decodeFromJsonElement<CashWriteSetRequestDto>(confirmation.getValue("writeSet"))
    val result = LocalCashImportResultInput.freeze(confirmation.getValue("resultPlan").jsonObject)
    return confirmationResponse(cashWriteSetJson.encodeToString(writeSet), result.encode())
}

private fun confirmationResponse(body: String, resultInput: String): JsAny = js("({ body, resultInput })")

@JsExport
fun assembleCashImportResult(batchId: String, key: String, receipt: String, input: String, items: String?): JsAny =
    describeArchive(CashImportResultContent.assemble(batchId, key, receipt, LocalCashImportResultInput.decode(input),
        items?.let { Json.decodeFromString<List<CashMutationResultDto>>(it) }))

@JsExport
fun inspectCashImportResult(body: String): JsAny = describeArchive(CashImportResultContent.decode(body))

private fun describeArchive(content: CashImportResultContent): JsAny =
    archiveResponse(content.encode(), content.baseEncoding(), content.receiptBody, Json.encodeToString(content.archiveSummary()))

private fun archiveResponse(body: String, base: String, receipt: String, summary: String): JsAny =
    js("({ body, base, receipt, summary })")

@JsExport
fun inspectCashImportSubmission(body: String): JsAny = decodeStoredCashImportSubmission(body).let {
    submissionResponse(it.key, it.request, it.receipt, it.baseRejected, it.resultInput)
}

private fun submissionResponse(key: String, request: String, receipt: String?, baseRejected: Boolean, resultInput: String?): JsAny =
    js("({ key, request, receipt, baseRejected, resultInput })")
