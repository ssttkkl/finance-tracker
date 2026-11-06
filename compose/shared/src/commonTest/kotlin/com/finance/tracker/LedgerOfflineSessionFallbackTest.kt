package com.finance.tracker

import com.finance.tracker.business.contracts.SessionDto
import com.finance.tracker.business.contracts.UserDto
import com.finance.tracker.core.TokenStore
import com.finance.tracker.data.*
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpMethod
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class LedgerOfflineSessionFallbackTest {
    private val origin = "https://fallback.example.test"
    private val credentials = ApiCredentialSnapshot("original-token", origin)
    private val cached = SessionDto(UserDto("cached@example.test"))
    private val headers get() = headersOf(HttpHeaders.ContentType, "application/json")

    @Test fun actualNetworkIoAndRequestTimeoutMayReadTheOriginalBoundCache() = runTest {
        for (network in listOf(IOException("synthetic disconnected transport"),
            HttpRequestTimeoutException(HttpRequestBuilder().apply { url(origin) }))) {
            val api = api(MockEngine { request ->
                assertEquals("Bearer original-token", request.headers[HttpHeaders.Authorization])
                assertEquals("fallback.example.test", request.url.host)
                throw network
            })
            var recoveries = 0
            try {
                assertEquals(cached, api.restoreSessionWithOfflineFallback(credentials) {
                    recoveries++; cached
                })
                assertEquals(1, recoveries)
            } finally { api.close() }
        }
    }

    @Test fun aVerifiedOnlineSessionNeverReadsTheCachedIdentity() = runTest {
        val online = SessionDto(UserDto("online@example.test"))
        val api = api(MockEngine { respond(Json.encodeToString(online), headers = headers) })
        try {
            assertEquals(online, api.restoreSessionWithOfflineFallback(credentials) { error("No fallback after success") })
        } finally { api.close() }
    }

    @Test fun explicitAnonymousCredentialsCannotRequestOrRecoverAnAuthenticatedSession() = runTest {
        var requests = 0
        val api = api(MockEngine { requests++; throw IOException("Must not request") })
        try {
            assertEquals(401, assertFailsWith<ApiFailure> {
                api.restoreSessionWithOfflineFallback(ApiCredentialSnapshot(null, origin)) { error("No anonymous fallback") }
            }.status)
            assertEquals(0, requests)
        } finally { api.close() }
    }

    @Test fun explicitHttpRejectionsAndMalformedSuccessNeverFallBack() = runTest {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden,
            HttpStatusCode.ServiceUnavailable, HttpStatusCode.OK)) {
            val api = api(MockEngine { respond("{}", status, headers) })
            try {
                assertEquals(status.value, assertFailsWith<ApiFailure> {
                    api.restoreSessionWithOfflineFallback(credentials) { error("No fallback for HTTP/protocol failures") }
                }.status)
            } finally { api.close() }
        }
    }

    @Test fun transportProgramErrorsAndCancellationCannotBecomeOfflineSuccess() = runTest {
        for (failure in listOf(IllegalStateException("synthetic engine bug"), CancellationException("synthetic cancellation"))) {
            val api = api(MockEngine { throw failure })
            var recoveries = 0
            try {
                val actual = assertFails {
                    api.restoreSessionWithOfflineFallback(credentials) { recoveries++; cached }
                }
                assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === failure })
                assertEquals(0, recoveries)
            } finally { api.close() }
        }
    }

    @Test fun anObservedHttpOrProtocolFailureCannotBeHiddenByALaterDisconnectedRetry() = runTest {
        for (status in listOf(HttpStatusCode.Forbidden, HttpStatusCode.ServiceUnavailable, HttpStatusCode.OK)) {
            var requests = 0
            var recoveries = 0
            val api = api(MockEngine {
                if (++requests == 1) respond("{}", status, headers)
                else throw IOException("synthetic disconnection after an observed rejection")
            })
            try {
                assertEquals(status.value, assertFailsWith<ApiFailure> {
                    api.restoreSessionWithOfflineFallback(credentials) { recoveries++; cached }
                }.status)
                assertEquals(0, recoveries)
                assertEquals(1, requests)
            } finally { api.close() }
        }
    }

    @Test fun rejectedResponseHeadersCannotBecomeOfflineSuccessWhenItsBodyFails() = runTest {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden, HttpStatusCode.ServiceUnavailable)) {
            val bodyFailure = IOException("synthetic response body disconnected after rejection headers")
            var recoveries = 0
            val api = api(MockEngine {
                val body = ByteChannel(autoFlush = true)
                body.cancel(bodyFailure)
                respond(body, status, headers)
            })
            try {
                val actual = assertFailsWith<ApiFailure> {
                    api.restoreSessionWithOfflineFallback(credentials) { recoveries++; cached }
                }
                assertEquals(status.value, actual.status)
                assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === bodyFailure })
                assertEquals(0, recoveries)
                assertEquals(if (status == HttpStatusCode.Unauthorized) null else "original-token", api.readToken())
            } finally { api.close() }
        }
    }

    @Test fun ordinaryWriteBodyFailureKeepsItsOriginalTransportUncertainty() = runTest {
        val bodyFailure = IOException("synthetic write response lost")
        val api = api(MockEngine {
            respond(ByteChannel().apply { cancel(bodyFailure) }, HttpStatusCode.ServiceUnavailable, headers)
        })
        try {
            val actual = assertFailsWith<IOException> { api.request<SessionDto>(HttpMethod.Post, "/synthetic-write") }
            assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === bodyFailure })
        } finally { api.close() }
    }

    @Test fun timeoutUnwrappedFromBodyCancellationStillCannotHideRejectionHeaders() = runTest {
        val timeout = HttpRequestTimeoutException(HttpRequestBuilder().apply { url(origin) })
        var recoveries = 0
        val api = api(MockEngine {
            val body = ByteChannel().apply { cancel(CancellationException("synthetic timeout wrapper", timeout)) }
            respond(body, HttpStatusCode.Forbidden, headers)
        })
        try {
            val actual = assertFailsWith<ApiFailure> {
                api.restoreSessionWithOfflineFallback(credentials) { recoveries++; cached }
            }
            assertEquals(403, actual.status)
            assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === timeout })
            assertEquals(0, recoveries)
        } finally { api.close() }
    }

    @Test fun callerCancellationDuringARejectedBodyRemainsCancellation() = runTest {
        val entered = CompletableDeferred<Unit>()
        val body = ByteChannel()
        var recoveries = 0
        val api = api(MockEngine {
            entered.complete(Unit)
            respond(body, HttpStatusCode.Forbidden, headers)
        })
        try {
            val job = launch { api.restoreSessionWithOfflineFallback(credentials) { recoveries++; cached } }
            entered.await(); job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertEquals(0, recoveries)
        } finally { body.cancel(CancellationException("test complete")); api.close() }
    }

    @Test fun missingEligibleCacheKeepsTheOriginalNetworkFailure() = runTest {
        val network = IOException("synthetic disconnected transport")
        val api = api(MockEngine { throw network })
        try {
            val actual = assertFailsWith<IOException> {
                api.restoreSessionWithOfflineFallback(credentials) { null }
            }
            assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === network })
        } finally { api.close() }
    }

    @Test fun damagedCacheKeepsItsErrorAndTheNetworkDiagnosticWithoutRetryingIt() = runTest {
        val network = IOException("synthetic disconnected transport")
        val damaged = IllegalStateException("synthetic corrupt encrypted cache")
        val api = api(MockEngine { throw network })
        var recoveries = 0
        try {
            val actual = assertFailsWith<IllegalStateException> {
                api.restoreSessionWithOfflineFallback(credentials) { recoveries++; throw damaged }
            }
            val causes = generateSequence<Throwable>(actual) { it.cause }.toList()
            assertTrue(causes.any { it === damaged })
            assertTrue(causes.flatMap { it.suppressedExceptions }.any { it === network ||
                generateSequence<Throwable>(it) { it.cause }.any { cause -> cause === network } })
            assertEquals(1, recoveries)
        } finally { api.close() }
    }

    @Test fun cacheIoFailureAndCacheCancellationDoNotRestartTheFallbackLoop() = runTest {
        for (failure in listOf(IOException("synthetic local disk I/O"), CancellationException("synthetic cache cancellation"))) {
            val api = api(MockEngine { throw IOException("synthetic disconnected transport") })
            var recoveries = 0
            try {
                val actual = assertFails {
                    api.restoreSessionWithOfflineFallback(credentials) { recoveries++; throw failure }
                }
                assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === failure })
                assertEquals(1, recoveries)
            } finally { api.close() }
        }
    }

    @Test fun tokenStoreIoFailureAfterAnHttpRejectionIsNotATransportFallback() = runTest {
        val local = IOException("synthetic token store disk failure")
        val api = FinanceApiClient({ origin }, object : TokenStore {
            override suspend fun get(): String = throw local
            override suspend fun set(value: String) = error("No credential write")
            override suspend fun clear() = error("No credential clear")
        }, MockEngine { respond("{}", HttpStatusCode.Unauthorized, headers) })
        try {
            val actual = assertFailsWith<ApiFailure> {
                api.restoreSessionWithOfflineFallback(credentials) { error("Token storage is not HTTP transport") }
            }
            assertEquals("token_store_unavailable", actual.code)
            assertSame(local, actual.cause)
        } finally { api.close() }
    }

    private fun api(engine: MockEngine) = FinanceApiClient({ origin }, object : TokenStore {
        var value: String? = "original-token"
        override suspend fun get() = value
        override suspend fun set(value: String) { this.value = value }
        override suspend fun clear() { value = null }
    }, engine)
}
