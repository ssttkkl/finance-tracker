@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.finance.tracker.data

import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.js.JsClientEngineConfig
import io.ktor.client.engine.js.JsError

internal actual class PlatformTransportFailureTracker actual constructor() {
    private val rejected = BrowserFetchRejections()
    actual fun configure(client: HttpClientConfig<*>) {
        client.engine {
            val config = this as JsClientEngineConfig
            val original = config.fetch
            config.fetch = { input, init -> rejected.track(original(input, init)) }
        }
    }
    actual fun matches(failure: Throwable): Boolean {
        val bridge = failure.cause as? JsError ?: return false
        return rejected.contains(bridge.origin)
    }
}
