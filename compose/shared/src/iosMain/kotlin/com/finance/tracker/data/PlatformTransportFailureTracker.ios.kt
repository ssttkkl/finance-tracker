package com.finance.tracker.data

import io.ktor.client.HttpClientConfig

internal actual class PlatformTransportFailureTracker actual constructor() {
    actual fun configure(client: HttpClientConfig<*>) {}
    actual fun matches(failure: Throwable): Boolean = false
}
