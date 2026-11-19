@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.finance.tracker.data

import com.finance.tracker.business.contracts.CashWriteSetRequestDto
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.domain.CashImportPlanError
import com.finance.tracker.business.domain.LocalCashImportReview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.Promise
import com.finance.tracker.core.CashLedgerWorkerClient

internal actual suspend fun executeCashImportPreview(
    files: List<LocalCashImportFile>,
    snapshot: LedgerSnapshotDto,
    input: JsonObject,
    previous: LocalCashImportReview?,
    requireOpen: () -> Unit,
): LocalCashImportCalculation {
    requireOpen()
    val payload = buildJsonObject {
        put("files", JsonArray(files.map(LocalCashImportFile::previewInput)))
        put("snapshot", Json.encodeToJsonElement(snapshot))
        put("choices", input)
        previous?.let { put("previous", it.encode()) }
    }.toString()
    val client = workerCall { loadPreviewWorkerClient().await() }
    currentCoroutineContext().ensureActive()
    requireOpen()
    val response = workerCall {
        val request = client.startPreview(payload)
        try {
            request.result.await().toString()
        } finally {
            request.cancel()
        }
    }
    requireOpen()
    val result = Json.parseToJsonElement(response).jsonObject
    result["code"]?.let {
        throw CashImportPlanError(
            it.jsonPrimitive.content,
            result["commitState"]?.jsonPrimitive?.content,
            result["diagnostic"]?.let { diagnostic -> Json.decodeFromJsonElement(diagnostic) },
        )
    }
    return LocalCashImportCalculation(
        result.getValue("plan").jsonObject,
        LocalCashImportReview.decode(result.getValue("review").jsonPrimitive.content),
        Json.decodeFromJsonElement<CashWriteSetRequestDto>(result.getValue("writeSet")),
        LocalCashImportReview.restorePreviewReview(result.getValue("reviewContext")),
    )
}

internal suspend fun <T> workerCall(action: suspend () -> T): T = try {
    action()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    throw CashImportPlanError("cash_import_worker_failed")
}

internal external interface PreviewWorkerClient : JsAny {
    fun startPreview(payload: String): PreviewWorkerRequest
    fun startFile(metadata: String, file: JsAny, limit: Int): BrowserFileWorkerRequest
    fun openCachedPreview(payload: String, files: JsAny, snapshot: String): OpeningPreviewRequest
    fun createCashLedgerClient(): CashLedgerWorkerClient
    fun readPreview(query: String, section: JsAny): PreviewSectionRequest
    fun freezeConfirmation(section: JsAny): ConfirmationWorkerRequest
    fun assembleArchive(batchId: String, key: String, receipt: String, input: String, serverItems: String?): ImportArchiveRequest
    fun inspectArchive(body: String): ImportArchiveRequest
    fun inspectSubmission(body: String): SubmissionWorkerRequest
}

internal external interface PreviewWorkerRequest : JsAny {
    val result: Promise<JsString>
    fun cancel()
}

internal fun loadPreviewWorkerClient(): Promise<PreviewWorkerClient> =
    js("import(/* webpackIgnore: true */ '/cash-import-worker/client.mjs')")
