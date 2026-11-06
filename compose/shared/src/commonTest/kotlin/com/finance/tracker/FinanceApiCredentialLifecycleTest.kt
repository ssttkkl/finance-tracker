package com.finance.tracker

import com.finance.tracker.core.TokenStore
import com.finance.tracker.data.ApiFailure
import com.finance.tracker.data.FinanceApiClient
import com.finance.tracker.data.ApiCredentialSnapshot
import com.finance.tracker.data.request
import com.finance.tracker.data.login
import com.finance.tracker.data.logout
import com.finance.tracker.data.session
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.*

class FinanceApiCredentialLifecycleTest {
    @Test fun oldUnauthorizedResponseCannotEraseALaterLogin() = runTest {
        val store = Store("old-token")
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val api = FinanceApiClient({ "https://api.example.test" }, store, MockEngine { request ->
            if (request.url.encodedPath.endsWith("/login")) respond(newLogin, headers = jsonHeaders)
            else {
                assertEquals("Bearer old-token", request.headers[HttpHeaders.Authorization])
                started.complete(Unit); release.await()
                respond("""{"error":{"code":"authentication_required"}}""", HttpStatusCode.Unauthorized, jsonHeaders)
            }
        })
        try {
            val old = async { assertFailsWith<ApiFailure> { api.session() } }
            started.await()
            api.login("new@example.test", "synthetic-password")
            assertEquals("new-token", store.value)
            release.complete(Unit)
            assertEquals(401, old.await().status)
            assertEquals("new-token", store.value)
            assertEquals(0, store.clears)
        } finally { release.complete(Unit); api.close() }
    }

    @Test fun anOldLogoutFailureCannotEraseALaterLogin() = runTest {
        val store = Store("old-token")
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val api = FinanceApiClient({ "https://api.example.test" }, store, MockEngine { request ->
            if (request.url.encodedPath.endsWith("/login")) respond(newLogin, headers = jsonHeaders)
            else {
                assertEquals("/api/v1/auth/logout", request.url.encodedPath)
                assertEquals("Bearer old-token", request.headers[HttpHeaders.Authorization])
                started.complete(Unit); release.await()
                respond("{}", HttpStatusCode.ServiceUnavailable, jsonHeaders)
            }
        })
        try {
            val old = async { assertFailsWith<ApiFailure> { api.logout() } }
            started.await()
            api.login("new@example.test", "synthetic-password")
            release.complete(Unit)
            assertEquals(503, old.await().status)
            assertEquals("new-token", store.value)
            assertEquals(0, store.clears)
        } finally { release.complete(Unit); api.close() }
    }

    @Test fun cancellationWhileReadingCredentialsPropagatesWithoutSendingARequest() = runTest {
        val cancelled = CancellationException("synthetic cancellation")
        val api = FinanceApiClient({ "https://api.example.test" }, object : TokenStore {
            override suspend fun get(): String? = throw cancelled
            override suspend fun set(value: String) = error("No write expected")
            override suspend fun clear() = error("No clear expected")
        }, MockEngine { error("No request expected") })
        try { assertSame(cancelled, assertFailsWith<CancellationException> { api.session() }) }
        finally { api.close() }
    }

    @Test fun credentialStorageFailuresKeepTheirOriginalCause() = runTest {
        val failure = IllegalStateException("synthetic storage failure")
        val api = FinanceApiClient({ "https://api.example.test" }, object : TokenStore {
            override suspend fun get(): String? = throw failure
            override suspend fun set(value: String) = error("No write expected")
            override suspend fun clear() = error("No clear expected")
        }, MockEngine { error("No request expected") })
        try {
            val actual = assertFailsWith<ApiFailure> { api.session() }
            assertEquals("token_store_unavailable", actual.code)
            assertSame(failure, actual.cause)
        } finally { api.close() }
    }

    @Test fun logoutCleanupFailureKeepsTheRemoteRejectionAsWell() = runTest {
        val failure = IllegalStateException("synthetic clear failure")
        val api = FinanceApiClient({ "https://api.example.test" }, object : TokenStore {
            override suspend fun get() = "old-token"
            override suspend fun set(value: String) = error("No write expected")
            override suspend fun clear(): Unit = throw failure
        }, MockEngine { respond("{}", HttpStatusCode.ServiceUnavailable, jsonHeaders) })
        try {
            val actual = assertFailsWith<ApiFailure> { api.logout() }
            assertSame(failure, actual.cause)
            assertTrue(actual.suppressedExceptions.any { it is ApiFailure && it.status == 503 })
        } finally { api.close() }
    }

    @Test fun cancellingLogoutStillAwaitsAsynchronousLocalCredentialCleanup() = runTest {
        var value: String? = "old-token"
        val started = CompletableDeferred<Unit>()
        val api = FinanceApiClient({ "https://api.example.test" }, object : TokenStore {
            override suspend fun get() = value
            override suspend fun set(value: String) { error("No write expected") }
            override suspend fun clear() { yield(); value = null }
        }, MockEngine { started.complete(Unit); awaitCancellation() })
        try {
            val logout = launch { api.logout() }
            started.await()
            logout.cancelAndJoin()
            assertTrue(logout.isCancelled)
            assertNull(value)
        } finally { api.close() }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun loginCannotInterleaveBetweenComparisonAndDeletion() = runTest {
        var storedToken: String? = "old-token"
        var reads = 0
        val comparing = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val api = FinanceApiClient({ "https://api.example.test" }, object : TokenStore {
            override suspend fun get(): String? {
                val captured = storedToken
                if (++reads == 2) { comparing.complete(Unit); release.await() }
                return captured
            }
            override suspend fun set(value: String) { storedToken = value }
            override suspend fun clear() { storedToken = null }
        }, MockEngine { request ->
            if (request.url.encodedPath.endsWith("/login")) respond(newLogin, headers = jsonHeaders)
            else respond("{}", HttpStatusCode.Unauthorized, jsonHeaders)
        })
        try {
            val old = async { assertFailsWith<ApiFailure> { api.session() } }
            comparing.await()
            val login = async { api.login("new@example.test", "synthetic-password") }
            runCurrent()
            assertFalse(login.isCompleted)
            release.complete(Unit)
            assertEquals(401, old.await().status)
            login.await()
            assertEquals("new-token", storedToken)
        } finally { release.complete(Unit); api.close() }
    }

    @Test fun explicitAnonymousSnapshotDoesNotUseOrClearLaterCredentials() = runTest {
        val store = Store("new-token")
        val api = FinanceApiClient({ "https://api.example.test" }, store, MockEngine { request ->
            assertNull(request.headers[HttpHeaders.Authorization])
            respond("{}", HttpStatusCode.Unauthorized, jsonHeaders)
        })
        try {
            assertEquals(401, assertFailsWith<ApiFailure> {
                api.request<Unit>(HttpMethod.Get, "/api/v1/auth/session", credentials = ApiCredentialSnapshot(null))
            }.status)
            assertEquals("new-token", store.value)
            assertEquals(0, store.clears)
        } finally { api.close() }
    }

    @Test fun logoutCancellationRemainsPrimaryWhenCleanupAlsoFails() = runTest {
        val cancelled = CancellationException("synthetic request cancellation")
        val cleanup = IllegalStateException("synthetic clear failure")
        val api = FinanceApiClient({ "https://api.example.test" }, object : TokenStore {
            override suspend fun get() = "old-token"
            override suspend fun set(value: String) = error("No write expected")
            override suspend fun clear(): Unit = throw cleanup
        }, MockEngine { throw cancelled })
        try {
            val actual = assertFailsWith<CancellationException> { api.logout() }
            assertEquals(cancelled.message, actual.message)
            assertTrue(actual.suppressedExceptions.any { it is ApiFailure && it.cause === cleanup })
        } finally { api.close() }
    }

    private class Store(var value: String?) : TokenStore {
        var clears = 0
        override suspend fun get() = value
        override suspend fun set(value: String) { this.value = value }
        override suspend fun clear() { clears++; value = null }
    }

    private val jsonHeaders get() = headersOf(HttpHeaders.ContentType, "application/json")
    private val newLogin get() = """{"access_token":"new-token","user":{"email":"new@example.test"}}"""
}
