@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.finance.tracker.data

import com.finance.tracker.business.contracts.CashWriteSetRequestDto
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.domain.CashImportPlanError
import com.finance.tracker.business.domain.LocalCashImportReview
import com.finance.tracker.core.awaitResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

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
    val client = workerCall { loadCashComputeWorkerClient().await() }
    currentCoroutineContext().ensureActive()
    requireOpen()
    val response = workerCall { client.startPreview(payload).awaitResult().toString() }
    requireOpen()
    val result = Json.parseToJsonElement(response).jsonObject
    result["code"]?.let {
        throw CashImportPlanError(
            it.jsonPrimitive.content,
            result["commitState"]?.jsonPrimitive?.content,
            result["diagnostic"]?.let { diagnostic -> Json.decodeFromJsonElement(diagnostic) },
        )
    }
    val previewContext = result.getValue("reviewContext")
    return LocalCashImportCalculation(
        result.getValue("plan").jsonObject,
        LocalCashImportReview.decode(result.getValue("review").jsonPrimitive.content),
        Json.decodeFromJsonElement<CashWriteSetRequestDto>(result.getValue("writeSet")),
        LocalCashImportReview.restorePreviewReview(previewContext),
    )
}

internal suspend fun <T> workerCall(action: suspend () -> T): T = try {
    action()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    throw CashImportPlanError("cash_import_worker_failed")
}
