package com.finance.tracker

import com.finance.tracker.business.contracts.AuthResponseDto
import com.finance.tracker.business.contracts.UserDto
import com.finance.tracker.core.TokenStore
import com.finance.tracker.data.*
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class ScopedAuthenticatedApiSessionTest {
    private val origin = "https://scoped-auth.example.test"
    private val headers = headersOf(HttpHeaders.ContentType, "application/json")
    private val response = AuthResponseDto("response-token", UserDto("synthetic@example.test"))

    @Test fun aLateLoginOrRegistrationCannotOverwriteALaterLoginResponse() = runTest {
        for (register in listOf(false, true)) {
            val store = Store(); val directory = directory()
            val entered = CompletableDeferred<Unit>(); val proceed = CompletableDeferred<Unit>(); var requests = 0
            val api = FinanceApiClient({ origin }, store, MockEngine {
                val number = ++requests
                if (number == 1) { entered.complete(Unit); proceed.await() }
                respond(Json.encodeToString(if (number == 1) response
                    else response.copy(accessToken = "later-token", user = UserDto("later@example.test"))), headers = headers)
            })
            val old = directory.begin(origin)
            val pending = async { assertFailsWith<CancellationException> {
                if (register) api.registerWithCredentials("synthetic@example.test", "synthetic", directory, old)
                else api.loginWithCredentials("synthetic@example.test", "synthetic", directory, old)
            } }
            try {
                entered.await()
                val current = directory.begin(origin)
                val later = api.loginWithCredentials("synthetic@example.test", "synthetic", directory, current)
                assertEquals("later-token", later.credentials.token)
                assertEquals("later@example.test", later.session.user.email)
                proceed.complete(Unit); pending.await()
                assertEquals("later-token", store.value); assertEquals(1, store.writes)
            } finally { proceed.complete(Unit); pending.cancelAndJoin(); api.close() }
        }
    }

    @Test fun staleAndForeignOperationsCannotSendAnAuthenticationRequest() = runTest {
        val store = Store(); var requests = 0; val directory = directory()
        val api = FinanceApiClient({ origin }, store, MockEngine { requests++; error("must not request") })
        val old = directory.begin(origin); directory.begin(origin)
        try {
            assertFailsWith<CancellationException> { api.loginWithCredentials("synthetic@example.test", "synthetic", directory, old) }
            val foreign = directory.begin("https://foreign.example.test")
            assertFailsWith<IllegalArgumentException> { api.registerWithCredentials("synthetic@example.test", "synthetic", directory, foreign) }
            assertEquals(0, requests); assertEquals(0, store.writes)
        } finally { api.close() }
    }

    @Test fun aCredentialChangeWithoutDirectoryPublicationAlsoRejectsTheOldResponse() = runTest {
        val store = Store(); val directory = directory(); val ticket = directory.begin(origin)
        lateinit var api: FinanceApiClient
        api = FinanceApiClient({ origin }, store, MockEngine {
            api.writeToken("later-token")
            respond(Json.encodeToString(response), headers = headers)
        })
        try {
            assertFailsWith<CancellationException> { api.loginWithCredentials("synthetic@example.test", "synthetic", directory, ticket) }
            assertEquals("later-token", store.value); assertEquals(1, store.writes)
        } finally { api.close() }
    }

    @Test fun theCurrentOperationReturnsItsResponsePairAndPreservesRequestSemantics() = runTest {
        for (register in listOf(false, true)) for (initialToken in listOf(null, "", "original-token")) {
            val store = Store().apply { value = initialToken }; val directory = directory(); val ticket = directory.begin(origin)
            val api = FinanceApiClient({ origin }, store, MockEngine { request ->
                assertEquals(if (register) "/api/v1/auth/register" else "/api/v1/auth/login", request.url.encodedPath)
                assertEquals(initialToken?.takeIf { it.isNotEmpty() }?.let { "Bearer $it" }, request.headers[HttpHeaders.Authorization])
                respond(Json.encodeToString(response), headers = headers)
            })
            try {
                val result = if (register) api.registerWithCredentials("synthetic@example.test", "synthetic", directory, ticket)
                    else api.loginWithCredentials("synthetic@example.test", "synthetic", directory, ticket)
                assertEquals(response.session(), result.session)
                assertEquals(response.accessToken, result.credentials.token); assertEquals(origin, result.credentials.apiOrigin)
                assertEquals(response.accessToken, store.value); assertEquals(1, store.writes)
            } finally { api.close() }
        }
    }

    @Test fun aFailedCredentialRecheckDoesNotWriteOrReturnTheAuthenticatedSession() = runTest {
        val store = Store(); val directory = directory(); val ticket = directory.begin(origin)
        val cause = IllegalStateException("synthetic credential read failed")
        val api = FinanceApiClient({ origin }, store, MockEngine {
            store.readFailure = cause
            respond(Json.encodeToString(response), headers = headers)
        })
        try {
            val failure = assertFailsWith<ApiFailure> { api.loginWithCredentials("synthetic@example.test", "synthetic", directory, ticket) }
            assertEquals("token_store_unavailable", failure.code); assertSame(cause, failure.cause)
            assertEquals("original-token", store.value); assertEquals(0, store.writes)
        } finally { api.close() }
    }

    private fun directory() = LedgerSessionDirectory(object : LedgerSessionLocatorStorage {
        val values = mutableMapOf<String, String>()
        override suspend fun read(key: String) = values[key]
        override suspend fun compareAndSet(key: String, expected: String?, replacement: String): Boolean {
            if (values[key] != expected) return false
            values[key] = replacement; return true
        }
    })

    private class Store : TokenStore {
        var value: String? = "original-token"; var writes = 0; var readFailure: Throwable? = null
        override suspend fun get(): String? { readFailure?.let { throw it }; return value }
        override suspend fun set(value: String) { writes++; this.value = value }
        override suspend fun clear() { value = null }
    }
}
