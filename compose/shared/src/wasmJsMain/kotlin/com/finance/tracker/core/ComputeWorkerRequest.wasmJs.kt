@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.finance.tracker.core

import kotlinx.coroutines.await
import kotlin.js.JsAny
import kotlin.js.Promise

internal external interface ComputeWorkerRequest<T : JsAny> : JsAny {
    val result: Promise<T>
    fun cancel()
}

internal suspend fun <T : JsAny> ComputeWorkerRequest<T>.awaitResult(): T = try {
    result.await()
} finally {
    cancel()
}
