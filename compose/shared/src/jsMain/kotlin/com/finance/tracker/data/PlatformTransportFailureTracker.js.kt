@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.finance.tracker.data

import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.js.JsClientEngineConfig
import kotlin.js.JsAny
import kotlin.js.unsafeCast

internal actual class PlatformTransportFailureTracker actual constructor() {
    private val rejected = BrowserFetchRejections()
    actual fun configure(client: HttpClientConfig<*>) {
        client.engine {
            val config = this as JsClientEngineConfig
            val original = config.fetch
            config.fetch = { input, init -> rejected.track(original(input, init)) }
        }
    }
    actual fun matches(failure: Throwable): Boolean = rejected.containsDirectCause(failure.unsafeCast<JsAny>())
}
