@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.finance.tracker.data

import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.encodedPath

internal val BrowserAuthDiagnostics = createClientPlugin("BrowserAuthDiagnostics") {
    onResponse { response ->
        val operation = when (response.call.request.url.encodedPath) {
            "/api/v1/auth/login" -> "login"
            "/api/v1/auth/register" -> "register"
            "/api/v1/auth/session" -> "session"
            else -> return@onResponse
        }
        recordBrowserAuthResponse(operation, response.status.value, response.headers["X-Request-ID"])
    }
}

private fun recordBrowserAuthResponse(operation: String, status: Int, requestId: String?): Unit = js("""{
    try {
        if (typeof globalThis.financeTrackerRecordAuthResponse === "function") {
            globalThis.financeTrackerRecordAuthResponse(operation, status, requestId);
        }
    } catch (_) {
        // Diagnostic failures must not change the HTTP result.
    }
}""")
