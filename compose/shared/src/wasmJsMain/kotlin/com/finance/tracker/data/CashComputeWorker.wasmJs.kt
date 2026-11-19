@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.finance.tracker.data

import com.finance.tracker.core.CashLedgerWorkerClient
import com.finance.tracker.core.ComputeWorkerRequest
import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.Promise

internal external interface CashComputeWorkerClient : JsAny {
    fun startPreview(payload: String): ComputeWorkerRequest<JsString>
    fun startFile(metadata: String, file: JsAny, limit: Int): ComputeWorkerRequest<BrowserFileWorkerResult>
    fun openCachedPreview(payload: String, files: JsAny, snapshot: String): ComputeWorkerRequest<PreviewHandleResponse>
    fun createCashLedgerClient(): CashLedgerWorkerClient
    fun readPreview(query: String, section: JsAny): ComputeWorkerRequest<PreviewSectionResponse>
    fun freezeConfirmation(section: JsAny): ComputeWorkerRequest<ConfirmationWorkerResponse>
    fun assembleArchive(batchId: String, key: String, receipt: String, input: String, serverItems: String?): ComputeWorkerRequest<ImportArchiveResponse>
    fun assembleArchivePages(batchId: String, key: String, receipt: String, input: String, pages: JsAny): ComputeWorkerRequest<ImportArchiveResponse>
    fun inspectArchive(body: String): ComputeWorkerRequest<ImportArchiveResponse>
    fun inspectBatch(body: String): ComputeWorkerRequest<StoredBatchResponse>
    fun encodeBatch(files: JsAny, review: String?, submission: String?, attempt: Int): ComputeWorkerRequest<EncodedImportResponse>
    fun encodeSubmission(key: String, body: String, receipt: String?, rejected: Boolean, resultInput: String?): ComputeWorkerRequest<EncodedImportResponse>
    fun rebaseImport(workspace: String, base: String, review: String, files: JsAny, snapshot: String): ComputeWorkerRequest<ImportRebaseResponse>
    fun packJsonRequest(body: String): ComputeWorkerRequest<BrowserJsonRequestBody>
    fun investmentDisplay(portfolio: String, account: String?, options: String): ComputeWorkerRequest<EncodedImportResponse>
    fun encodeFiles(files: JsAny): ComputeWorkerRequest<EncodedImportResponse>
    fun summarizeFiles(files: JsAny): ComputeWorkerRequest<EncodedImportResponse>
    fun inspectFiles(files: JsAny): ComputeWorkerRequest<EncodedImportResponse>
    fun readArchivePage(body: String, kind: String, offset: Int, limit: Int): ComputeWorkerRequest<EncodedImportResponse>
    fun captureRemoteFile(metadata: String, format: String, data: JsAny, text: String): ComputeWorkerRequest<RemoteCashCaptureResponse>
    fun prepareImportAccounts(batch: String, workspace: String, files: JsAny, snapshot: String, review: String?): ComputeWorkerRequest<EncodedImportResponse>
    fun inspectSnapshot(workspace: String, text: String): ComputeWorkerRequest<PreparedSnapshotResponse>
    fun prepareSnapshot(workspace: String, text: String): ComputeWorkerRequest<PreparedSnapshotResponse>
    fun inspectSubmission(body: String): ComputeWorkerRequest<SubmissionWorkerResponse>
}

internal fun loadCashComputeWorkerClient(): Promise<CashComputeWorkerClient> =
    js("import(/* webpackIgnore: true */ '/cash-import-worker/client.mjs')")

internal fun computeWorkerStringArray(values: List<String>): JsAny = emptyStringArray().also { array ->
    values.forEach { appendComputeString(array, it) }
}

private fun emptyStringArray(): JsAny = js("[]")
private fun appendComputeString(array: JsAny, value: String): Unit = js("{ array.push(value); }")
