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
    fun inspectArchive(body: String): ComputeWorkerRequest<ImportArchiveResponse>
    fun inspectSubmission(body: String): ComputeWorkerRequest<SubmissionWorkerResponse>
}

internal fun loadCashComputeWorkerClient(): Promise<CashComputeWorkerClient> =
    js("import(/* webpackIgnore: true */ '/cash-import-worker/client.mjs')")
