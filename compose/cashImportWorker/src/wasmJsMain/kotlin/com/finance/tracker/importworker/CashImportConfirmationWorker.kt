@file:OptIn(kotlin.js.ExperimentalJsExport::class, kotlin.js.ExperimentalWasmJsInterop::class)

package com.finance.tracker.importworker

import com.finance.tracker.business.contracts.CashWriteSetRequestDto
import com.finance.tracker.business.contracts.CashMutationResultDto
import com.finance.tracker.business.contracts.CashMutationResultPageDto
import com.finance.tracker.business.domain.CashImportResultContent
import com.finance.tracker.business.domain.LocalCashImportResultInput
import com.finance.tracker.business.domain.cashWriteSetJson
import com.finance.tracker.business.domain.inspectStoredCashImportSubmission
import com.finance.tracker.business.contracts.StoredCashImportSubmission
import com.finance.tracker.business.contracts.StoredImportBatch
import com.finance.tracker.business.domain.decodeStoredImportBatch
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.domain.CASH_RULES_VERSION
import com.finance.tracker.business.domain.CashImportPlanError
import com.finance.tracker.business.domain.LocalCashImportReview
import com.finance.tracker.business.domain.calculateLocalCashImportBusinessPlan
import com.finance.tracker.business.domain.recalculate
import com.finance.tracker.business.domain.prepareLocalCashImportView
import com.finance.tracker.business.domain.page
import kotlin.time.Clock
import kotlinx.serialization.json.*
import kotlin.js.JsAny
import kotlin.js.JsExport

@JsExport
fun freezeCashImportConfirmation(material: String): JsAny {
    val confirmation = Json.parseToJsonElement(material).jsonObject
    val writeSet = Json.decodeFromJsonElement<CashWriteSetRequestDto>(confirmation.getValue("writeSet"))
    val result = LocalCashImportResultInput.freeze(confirmation.getValue("resultPlan").jsonObject)
    return confirmationResponse(cashWriteSetJson.encodeToString(writeSet), result.encode(), writeSet.baseLedgerSeq)
}

private fun confirmationResponse(body: String, resultInput: String, base: String): JsAny = js("({ body, resultInput, base })")

@JsExport
fun assembleCashImportResult(batchId: String, key: String, receipt: String, input: String, items: String?): JsAny =
    describeArchive(CashImportResultContent.assemble(batchId, key, receipt, LocalCashImportResultInput.decode(input),
        items?.let { Json.decodeFromString<List<CashMutationResultDto>>(it) }))

@JsExport
fun assembleCashImportResultPages(batchId: String, key: String, receipt: String, input: String, pages: JsAny): JsAny {
    val items = buildList {
        repeat(stringArraySize(pages)) { index ->
            addAll(apiResponseJson.decodeFromString<CashMutationResultPageDto>(stringArrayValue(pages, index)).items)
        }
    }
    return describeArchive(CashImportResultContent.assemble(batchId, key, receipt, LocalCashImportResultInput.decode(input), items))
}

@JsExport
fun inspectCashImportResult(body: String): JsAny = describeArchive(CashImportResultContent.decode(body))

private fun describeArchive(content: CashImportResultContent): JsAny =
    archiveResponse(content.encode(), content.receiptBody, content.input.encode(), Json.encodeToString(content.archiveSummary()))

private fun archiveResponse(body: String, receipt: String, input: String, summary: String): JsAny =
    js("({ body, receipt, input, summary })")

@JsExport
fun inspectCashImportSubmission(body: String): JsAny = inspectStoredCashImportSubmission(body).let { (stored, base) ->
    submissionResponse(stored.key, stored.request, stored.receipt, stored.baseRejected, stored.resultInput, base, stored.transport)
}

private fun submissionResponse(key: String, request: String, receipt: String?, baseRejected: Boolean,
    resultInput: String?, base: String, transport: String?): JsAny =
    js("({ key, request, receipt, baseRejected, resultInput, base, transport })")

@JsExport
fun encodeCashImportSubmission(key: String, request: String, receipt: String?, rejected: Boolean, resultInput: String?,
    transport: String?): String =
    cashWriteSetJson.encodeToString(StoredCashImportSubmission(key, request, receipt, rejected, resultInput, transport))

@JsExport
fun inspectCashImportBatch(body: String): JsAny = decodeStoredImportBatch(body).let { batch ->
    val files = makeEncodedFiles()
    batch.files.forEachIndexed { index, text -> putEncodedFile(files, index, text) }
    batchResponse(files, batch.review, batch.submission, batch.attempt)
}

@JsExport
fun encodeCashImportBatch(files: JsAny, review: String?, submission: String?, attempt: Int): String =
    Json.encodeToString(StoredImportBatch(1, List(stringArraySize(files)) { stringArrayValue(files, it) }, submission, review, attempt))

private fun makeEncodedFiles(): JsAny = js("[]")
private fun putEncodedFile(files: JsAny, index: Int, text: String): Unit = js("{ files[index] = text; }")
private fun stringArraySize(files: JsAny): Int = js("files.length")
private fun stringArrayValue(files: JsAny, index: Int): String = js("files[index]")
private fun batchResponse(files: JsAny, review: String?, submission: String?, attempt: Int): JsAny =
    js("({ files, review, submission, attempt })")

@JsExport
fun rebaseCashImport(workspace: String, base: String, review: String, files: JsAny, snapshotText: String): JsAny = try {
    val snapshot = Json.decodeFromString<LedgerSnapshotDto>(snapshotText)
    check(snapshot.workspaceId == workspace) { "ledger_snapshot_workspace_mismatch" }
    check(snapshot.ledgerSeq == base) { "cash_import_snapshot_changed" }
    val parsed = readCapturedCashImportFiles(files)
    val result = LocalCashImportReview.decode(review).recalculate(snapshot) { source, choices, previous, ids ->
        calculateLocalCashImportBusinessPlan(parsed, source, choices, previous, ids, {}, Clock.System::now)
    }
    if (!result.conclusionUnchanged) throw CashImportPlanError("plan_changed")
    val request = CashWriteSetRequestDto(snapshot.ledgerSeq, CASH_RULES_VERSION, result.changes)
    rebaseResponse(cashWriteSetJson.encodeToString(request), null)
} catch (failure: CashImportPlanError) {
    rebaseResponse(null, previewPlanError(failure).toString())
}

private fun rebaseResponse(request: String?, rejection: String?): JsAny = js("({ request, rejection })")

private val apiResponseJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

@JsExport
fun prepareCashImportSnapshot(workspace: String, apiBody: String): JsAny {
    val snapshot = apiResponseJson.decodeFromString<LedgerSnapshotDto>(apiBody)
    check(snapshot.workspaceId == workspace) { "ledger_snapshot_workspace_mismatch" }
    check(snapshot.ledgerSeq.toLongOrNull()?.let { it >= 0 } == true) { "ledger_version_invalid" }
    return snapshotResponse(Json.encodeToString(snapshot), snapshot.workspaceId, snapshot.ledgerSeq)
}

private fun snapshotResponse(body: String, workspaceId: String, version: String): JsAny =
    js("({ body, workspaceId, version })")

@JsExport
fun prepareCashImportAccounts(batch: String, workspace: String, files: JsAny, snapshotText: String, review: String?): String {
    val snapshot = Json.decodeFromString<LedgerSnapshotDto>(snapshotText)
    check(snapshot.workspaceId == workspace) { "ledger_snapshot_workspace_mismatch" }
    return Json.encodeToString(prepareLocalCashImportView(batch, readCapturedCashImportFiles(files),
        snapshot, review?.let(LocalCashImportReview::decode)))
}

@JsExport
fun inspectCapturedCashImportFiles(files: JsAny): String =
    Json.encodeToString(List(stringArraySize(files)) { index ->
        com.finance.tracker.business.domain.decodeStoredCashImportFile(stringArrayValue(files, index)).clientFileRef
    })

@JsExport
fun cashImportArchivePage(body: String, kind: String, offset: Int, limit: Int): JsAny {
    val archive = CashImportResultContent.decode(body)
    return try { archivePageResponse(Json.encodeToString(archive.page(kind, offset, limit)), null) }
    catch (failure: IllegalArgumentException) {
        if (failure.message != "import_payload_invalid") throw failure
        archivePageResponse(null, "import_payload_invalid")
    }
}

private fun archivePageResponse(body: String?, rejection: String?): JsAny = js("({ body, rejection })")

@JsExport
fun encodeInitialCashImportFiles(files: JsAny): String =
    Json.encodeToString(List(stringArraySize(files)) { stringArrayValue(files, it) })

@JsExport
fun summarizeCapturedCashImportFiles(files: JsAny): String =
    Json.encodeToString(com.finance.tracker.business.domain.buildLocalCashImportContextFiles(readCapturedCashImportFiles(files)))
