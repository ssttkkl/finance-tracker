@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.finance.tracker

import com.finance.tracker.business.contracts.SessionDto
import com.finance.tracker.business.contracts.UserDto
import com.finance.tracker.core.TokenStore
import com.finance.tracker.data.*
import io.ktor.client.engine.mock.MockEngine
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.*

class WebSessionTransportFailureTest {
    private val deniedOrigin = "http://127.0.0.1:9" // Browser-denied port: no backend process or external network required.
    private val token = "synthetic-local-browser-token"
    private val cached = SessionDto(UserDto("synthetic@example.test"))
    private fun store() = object : TokenStore {
        override suspend fun get() = token
        override suspend fun set(value: String) = error("No credential writes")
        override suspend fun clear() = error("No 401 or logout expected")
    }

    @Test fun nativeFetchRejectionCanSelectOfflineRecovery() = runTest {
        val api = FinanceApiClient({ deniedOrigin }, store(), allowInsecureHttp = true)
        var recoveries = 0
        try {
            assertEquals(cached, api.restoreSessionWithOfflineFallback(ApiCredentialSnapshot(token, deniedOrigin)) {
                recoveries++; cached
            })
            assertEquals(1, recoveries)
        } finally { api.close() }
    }

    @Test fun nativeFetchFailureOnAWriteStillReportsTransportUncertainty() = runTest {
        val api = FinanceApiClient({ deniedOrigin }, store(), allowInsecureHttp = true)
        try {
            val actual = assertFailsWith<IOException> { api.request<SessionDto>(HttpMethod.Post, "/synthetic-write") }
            assertNotNull(actual.cause)
        } finally { api.close() }
    }

    @Test fun anActualReadableHttpRejectionNeverSelectsOfflineRecovery() = runTest {
        val origin = browserOrigin()
        val api = FinanceApiClient({ origin }, store(), allowInsecureHttp = true)
        var recoveries = 0
        try {
            val actual = assertFailsWith<ApiFailure> {
                api.restoreSessionWithOfflineFallback(ApiCredentialSnapshot(token, origin)) { recoveries++; cached }
            }
            assertEquals(404, actual.status) // Karma has no authentication endpoint, but its HTTP response is readable.
            assertEquals(0, recoveries)
        } finally { api.close() }
    }

    @Test fun anInjectedEngineProgramErrorCannotForgeFetchProvenanceByTextOrCause() = runTest {
        val original = Error("Fail to fetch", IOException("synthetic I/O cause"))
        val api = FinanceApiClient({ deniedOrigin }, store(), MockEngine { throw original }, allowInsecureHttp = true)
        var recoveries = 0
        try {
            val actual = assertFails {
                api.restoreSessionWithOfflineFallback(ApiCredentialSnapshot(token, deniedOrigin)) { recoveries++; cached }
            }
            assertFalse(actual is IOException)
            assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === original })
            assertEquals(0, recoveries)
        } finally { api.close() }
    }

    @Test fun anotherClientsRejectedObjectCannotAuthorizeAnInjectedEngineFallback() = runTest {
        val source = FinanceApiClient({ deniedOrigin }, store(), allowInsecureHttp = true)
        val original = try { assertFails { source.request<SessionDto>(HttpMethod.Get, "/synthetic-read") } }
            finally { source.close() }
        val programFailure = Error("synthetic program failure using a previous network cause", original)
        val api = FinanceApiClient({ deniedOrigin }, store(), MockEngine { throw programFailure }, allowInsecureHttp = true)
        var recoveries = 0
        try {
            val actual = assertFails {
                api.restoreSessionWithOfflineFallback(ApiCredentialSnapshot(token, deniedOrigin)) { recoveries++; cached }
            }
            assertFalse(actual is IOException)
            assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === programFailure })
            assertEquals(0, recoveries)
        } finally { api.close() }
    }

    @Test fun anOldFetchCauseInCredentialStorageMustNotBeReclassifiedAsTransport() = runTest {
        var previous: Throwable? = null
        var failedReads = 0
        val api = FinanceApiClient({ deniedOrigin }, object : TokenStore {
            override suspend fun get(): String {
                previous?.let { failedReads++; throw it }
                return token
            }
            override suspend fun set(value: String) = error("No credential write")
            override suspend fun clear() = error("The read failure precedes clearing")
        }, allowInsecureHttp = true)
        try {
            previous = assertFails { api.request<SessionDto>(HttpMethod.Get, "/original-disconnection") }
            installUnauthorizedResponse(deniedOrigin + "/api/v1/auth/session")
            val actual = assertFailsWith<ApiFailure> { api.session(ApiCredentialSnapshot(token, deniedOrigin)) }
            assertEquals("token_store_unavailable", actual.code)
            assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === previous })
            assertEquals(1, failedReads)
        } finally { restoreFetchResponse(); api.close() }
    }
}

private fun browserOrigin(): String = js("location.origin")
private fun installUnauthorizedResponse(url: String) { js("""
    globalThis.__ftUnauthorizedFetchProbe = globalThis.fetch;
    globalThis.fetch = (input, init) => String(input) === url
        ? Promise.resolve(new Response('{}', { status: 401, headers: { 'Content-Type': 'application/json' } }))
        : globalThis.__ftUnauthorizedFetchProbe(input, init);
""") }
private fun restoreFetchResponse() { js("""
    if (globalThis.__ftUnauthorizedFetchProbe) {
        globalThis.fetch = globalThis.__ftUnauthorizedFetchProbe;
        delete globalThis.__ftUnauthorizedFetchProbe;
    }
""") }
