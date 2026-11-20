@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.finance.tracker.data

import com.finance.tracker.core.awaitResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.Promise

internal actual suspend fun fetchLargeBrowserJson(url: String, method: String, headers: Map<String, String>,
    body: String?, beforeSend: suspend () -> Unit, onStatus: (Int) -> Unit): LargeBrowserJson? {
    val requestBody = body?.let { text ->
        val response = importComputeWorkerCall { loadCashComputeWorkerClient().await().packJsonRequest(text).awaitResult() }
        response.checkHandler()
        checkNotNull(response.blob) { "cash_import_request_body_missing" }
    }
    beforeSend()
    currentCoroutineContext().ensureActive()
    val controller = abortController()
    try {
        val response = browserFetchJson(url, method, Json.encodeToString(headers), requestBody, controller).awaitNetwork()
        onStatus(response.status)
        return LargeBrowserJson(response.status, response.text().awaitNetwork().toString())
    } finally {
        abort(controller)
    }
}

private suspend fun <T : JsAny> Promise<T>.awaitNetwork(): T = try {
    await()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    throw FinanceTransportFailure(failure, "network_unavailable")
}

private external interface BrowserJsonResponse : JsAny {
    val status: Int
    fun text(): Promise<JsString>
}

private fun abortController(): JsAny = js("new AbortController()")
private fun abort(controller: JsAny): Unit = js("{ controller.abort(); }")
private fun browserFetchJson(url: String, method: String, headers: String, body: JsAny?, controller: JsAny): Promise<BrowserJsonResponse> =
    js("fetch(url, { method, headers: JSON.parse(headers), body, signal: controller.signal })")

internal external interface BrowserJsonRequestBody : ImportComputeResponse {
    val blob: JsAny?
}
