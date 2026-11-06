package com.finance.tracker

import com.finance.tracker.business.contracts.AuthResponseDto
import com.finance.tracker.business.contracts.SessionDto
import com.finance.tracker.business.contracts.UserDto
import com.finance.tracker.core.TokenStore
import com.finance.tracker.data.*
import com.finance.tracker.domain.LedgerCacheScope
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class AuthenticatedApiSessionTest {
    private val origin = "https://original.example.test"
    private val headers get() = headersOf(HttpHeaders.ContentType, "application/json")
    private val response = AuthResponseDto("response-token", UserDto("first@example.test"))

    @Test fun loginKeepsItsResponseIdentityAndTokenAfterAnotherLogin() = runTest {
        val store = Store()
        var requests = 0
        val api = FinanceApiClient({ origin }, store, MockEngine {
            respond(Json.encodeToString(if (++requests == 1) response
                else AuthResponseDto("later-token", UserDto("later@example.test"))), headers = headers)
        })
        try {
            val first = api.loginWithCredentials("first@example.test", "synthetic")
            val later = api.loginWithCredentials("later@example.test", "synthetic")
            assertEquals(response.session(), first.session)
            assertEquals("response-token", first.credentials.token)
            assertEquals(origin, first.credentials.apiOrigin)
            assertEquals("later@example.test", later.session.user.email)
            assertEquals("later-token", later.credentials.token)
            assertEquals("later-token", store.value)
            assertEquals(2, store.reads) // One read per original request, none to guess the returned token.
        } finally { api.close() }
    }

    @Test fun registrationReturnsTheSameCredentialPairAsItsOwnResponse() = runTest {
        val store = Store()
        val api = FinanceApiClient({ origin }, store, MockEngine { request ->
            assertEquals("/api/v1/auth/register", request.url.encodedPath)
            respond(Json.encodeToString(response), headers = headers)
        })
        try {
            val verified = api.registerWithCredentials("first@example.test", "synthetic")
            api.writeToken("later-token")
            assertEquals(response.session(), verified.session)
            assertEquals("response-token", verified.credentials.token)
            assertEquals(origin, verified.credentials.apiOrigin)
            assertEquals(1, store.reads)
        } finally { api.close() }
    }

    @Test fun responsePairKeepsTheRequestOriginAndBindsSubsequentSessionReads() = runTest {
        var configuredOrigin = origin
        var requests = 0
        val store = Store()
        val api = FinanceApiClient({ configuredOrigin }, store, MockEngine { request ->
            assertEquals("original.example.test", request.url.host)
            if (++requests == 1) {
                assertEquals("Bearer original-token", request.headers[HttpHeaders.Authorization])
                configuredOrigin = "https://later.example.test"
                respond(Json.encodeToString(response), headers = headers)
            } else {
                assertEquals("Bearer response-token", request.headers[HttpHeaders.Authorization])
                assertEquals("/api/v1/auth/session", request.url.encodedPath)
                respond(Json.encodeToString(response.session()), headers = headers)
            }
        })
        try {
            val verified = api.loginWithCredentials("first@example.test", "synthetic")
            api.writeToken("later-token")
            assertEquals(response.session(), api.session(verified.credentials))
            assertEquals(origin, verified.credentials.apiOrigin)
            assertEquals("later-token", store.value)
            assertEquals(1, store.reads)
        } finally { api.close() }
    }

    @Test fun laterLoginCannotBindAnEarlierResponseToItsLedgerRecord() = runTest {
        val store = Store()
        val api = FinanceApiClient({ origin }, store, MockEngine {
            respond(Json.encodeToString(response), headers = headers)
        })
        try {
            val verified = api.loginWithCredentials("first@example.test", "synthetic")
            api.writeToken("later-token")
            val directory = LedgerSessionDirectory(object : LedgerSessionLocatorStorage {
                var value: String? = null
                override suspend fun read(key: String) = value
                override suspend fun compareAndSet(key: String, expected: String?, replacement: String): Boolean {
                    if (value != expected) return false
                    value = replacement
                    return true
                }
            })
            val ticket = directory.begin(origin)
            val scope = LedgerCacheScope(origin, ledgerOfflinePrincipal(response.user.email), "workspace")
            directory.register(ticket, scope)
            val storage = object : LedgerStorage {
                override suspend fun read(scope: LedgerCacheScope): LedgerSnapshotDto? = error("Must reject before storage")
                override suspend fun replace(scope: LedgerCacheScope, snapshot: LedgerSnapshotDto) = error("No facts write")
                override suspend fun clear(scope: LedgerCacheScope) = error("No implicit cleanup")
                override suspend fun close() = error("No implicit close")
                override suspend fun readOfflineSession(scope: LedgerCacheScope, token: String): SessionDto? = error("Must reject before storage")
                override suspend fun saveOfflineSession(scope: LedgerCacheScope, token: String, session: SessionDto) = error("Must reject before storage")
            }
            assertFailsWith<CancellationException> {
                api.publishVerifiedLedgerSession(verified.credentials, directory, ticket, scope, storage, verified.session)
            }
            assertNull(directory.current(ticket))
            assertEquals("later-token", store.value)
        } finally { api.close() }
    }

    @Test fun rejectedAndMalformedResponsesCannotProduceACredentialPairOrWriteAToken() = runTest {
        for (status in listOf(HttpStatusCode.ServiceUnavailable, HttpStatusCode.OK)) {
            val store = Store()
            val api = FinanceApiClient({ origin }, store, MockEngine { respond("{}", status, headers) })
            try {
                assertEquals(status.value, assertFailsWith<ApiFailure> {
                    api.loginWithCredentials("first@example.test", "synthetic")
                }.status)
                assertEquals("original-token", store.value)
                assertEquals(0, store.writes)
            } finally { api.close() }
        }
    }

    @Test fun credentialPersistenceFailurePreservesItsCauseAndDoesNotReturnASession() = runTest {
        val failure = IllegalStateException("synthetic credential write failure")
        val store = Store().apply { writeFailure = failure }
        val api = FinanceApiClient({ origin }, store, MockEngine { respond(Json.encodeToString(response), headers = headers) })
        try {
            val actual = assertFailsWith<ApiFailure> { api.registerWithCredentials("first@example.test", "synthetic") }
            assertEquals("token_store_unavailable", actual.code)
            assertSame(failure, actual.cause)
            assertEquals("original-token", store.value)
        } finally { api.close() }
    }

    private class Store : TokenStore {
        var value = "original-token"
        var reads = 0
        var writes = 0
        var writeFailure: Throwable? = null
        override suspend fun get(): String { reads++; return value }
        override suspend fun set(value: String) { writes++; writeFailure?.let { throw it }; this.value = value }
        override suspend fun clear() = error("No credential clearing expected")
    }
}
