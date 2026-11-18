package com.finance.tracker.data

import io.ktor.client.HttpClientConfig

/** Per-client provenance supplied by its actual transport, never inferred from exception text. */
internal expect class PlatformTransportFailureTracker() {
    fun configure(client: HttpClientConfig<*>)
    fun matches(failure: Throwable): Boolean
}
