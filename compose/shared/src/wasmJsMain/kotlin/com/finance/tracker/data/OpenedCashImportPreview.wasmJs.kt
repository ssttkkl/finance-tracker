@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.finance.tracker.data

import com.finance.tracker.business.domain.CashImportPlanError
import com.finance.tracker.business.domain.LocalCashImportReview
import com.finance.tracker.business.domain.cashImportPageCursor
import com.finance.tracker.core.awaitResult
import kotlinx.coroutines.await
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import kotlin.js.JsAny

internal actual suspend fun openCashImportPreview(
    source: CashImportPreviewSource, input: JsonObject, previous: LocalCashImportReview?,
    identity: CashImportPreviewIdentity, requireOpen: () -> Unit,
): OpenedCashImportPreview {
    validateCashImportPreviewTimezone(identity.timezone)
    requireOpen()
    val files = browserPreviewFiles()
    for (text in source.encodedFiles()) appendBrowserPreviewFile(files, text)
    val snapshot = source.encodedSnapshot()
    val payload = buildJsonObject {
        put("workspaceId", source.workspaceId)
        put("choices", input)
        previous?.let { put("previous", it.encode()) }
        put("planId", identity.planId)
        put("batchId", identity.batchId)
        put("revision", identity.revision)
        put("timezone", identity.timezone)
    }.toString()

    val client = workerCall { loadCashComputeWorkerClient().await() }
    currentCoroutineContext().ensureActive()
    requireOpen()
    val response = workerCall { client.openCachedPreview(payload, files, snapshot).awaitResult() }
    requireOpen()
    val initial = readPreviewJson(checkNotNull(response.header) { "cash_import_worker_response_invalid" })
    check(initial["plan_id"]?.jsonPrimitive?.content == identity.planId &&
        initial["session_id"]?.jsonPrimitive?.content == identity.batchId &&
        initial["session_revision"]?.jsonPrimitive?.longOrNull == identity.revision) { "cash_import_worker_response_invalid" }
    return BrowserOpenedCashImportPreview(identity.timezone, initial,
        LocalCashImportReview.decode(checkNotNull(response.review)),
        checkNotNull(response.items), checkNotNull(response.processing), checkNotNull(response.detail),
        checkNotNull(response.context), checkNotNull(response.confirmation), client, requireOpen)
}

private fun browserPreviewFiles(): JsAny = js("[]")
private fun appendBrowserPreviewFile(files: JsAny, text: String): Unit = js("{ files.push(text); }")

private class BrowserOpenedCashImportPreview(
    private val initialTimezone: String,
    override val initialView: JsonObject,
    override val review: LocalCashImportReview,
    private val items: JsAny,
    private val processing: JsAny,
    private val detail: JsAny,
    private val context: JsAny,
    private val confirmation: JsAny,
    private val client: CashComputeWorkerClient,
    private val requireOpen: () -> Unit,
) : OpenedCashImportPreview {
    override suspend fun view(timezone: String): JsonObject {
        if (timezone == initialTimezone) return initialView
        validateCashImportPreviewTimezone(timezone)
        return read(detail, "view") { put("timezone", timezone) }
    }

    override suspend fun page(kind: String, cursor: String?, startIndex: Int?, limit: Int, status: String?, indexed: Boolean): JsonObject {
        if (kind == "items" && indexed && cursor == null && startIndex == 0 && status == "all") {
            initialPage(limit)?.let { return it }
        }
        return read(if (kind == "items") items else processing, kind) {
            cursor?.let { put("cursor", it) }
            startIndex?.let { put("start", it) }
            put("limit", limit)
            status?.let { put("status", it) }
        }
    }

    private fun initialPage(limit: Int): JsonObject? {
        val first = initialView.getValue("items").jsonArray
        val counts = initialView.getValue("item_counts").jsonObject
        val total = counts.values.sumOf { it.jsonPrimitive.int }
        if (limit !in 1..50 || first.size != minOf(50, total)) return null
        return buildJsonObject {
            put("items", JsonArray(first.take(limit)))
            put("start_index", 0)
            put("total_count", total)
            put("next_cursor", if (limit < total) JsonPrimitive(cashImportPageCursor(
                initialView.getValue("plan_id").jsonPrimitive.content, "items", "all", limit,
            )) else JsonNull)
        }
    }

    override suspend fun detail(itemId: String): JsonObject = read(detail, "detail") { put("id", itemId) }

    override suspend fun previousReview(input: JsonObject): LocalCashImportReview =
        LocalCashImportReview.decode(read(context, "review") { put("choices", input) }.getValue("review").jsonPrimitive.content)

    override suspend fun confirmation(batchId: String): LocalCashImportSubmission {
        if (!initialView.getValue("can_commit").jsonPrimitive.boolean) throw CashImportPlanError("plan_changed")
        check(initialView.getValue("session_id").jsonPrimitive.content == batchId)
        requireOpen()
        val result = try {
            workerCall { client.freezeConfirmation(confirmation).awaitResult() }
        } catch (failure: CashImportPlanError) {
            throw failure.also { it.commitState = "not_committed" }
        }
        requireOpen()
        result.code?.let { throw CashImportPlanError(it) }
        return LocalCashImportSubmission.fromWorker(batchId,
            checkNotNull(result.body) { "cash_import_worker_response_invalid" },
            checkNotNull(result.resultInput) { "cash_import_worker_response_invalid" })
    }

    private suspend fun read(section: JsAny, mode: String, arguments: JsonObjectBuilder.() -> Unit): JsonObject {
        requireOpen()
        val query = buildJsonObject { put("mode", mode); arguments() }.toString()
        val response = workerCall { client.readPreview(query, section).awaitResult() }
        requireOpen()
        return readPreviewJson(checkNotNull(response.payload) { "cash_import_worker_response_invalid" })
    }
}

private fun readPreviewJson(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject.also { obj ->
    obj["code"]?.let { code ->
        throw CashImportPlanError(code.jsonPrimitive.content, obj["commitState"]?.jsonPrimitive?.content,
            obj["diagnostic"]?.let { Json.decodeFromJsonElement(it) })
    }
}

internal external interface PreviewHandleResponse : JsAny {
    val header: String?
    val review: String?
    val items: JsAny?
    val processing: JsAny?
    val detail: JsAny?
    val context: JsAny?
    val confirmation: JsAny?
}

internal external interface PreviewSectionResponse : JsAny { val payload: String? }

internal external interface ConfirmationWorkerResponse : JsAny {
    val body: String?
    val resultInput: String?
    val code: String?
}
