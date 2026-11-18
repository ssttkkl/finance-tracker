package com.finance.tracker

import com.finance.tracker.business.contracts.LedgerFactsDto
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.contracts.Role
import com.finance.tracker.business.contracts.SessionDto
import com.finance.tracker.business.contracts.UserDto
import com.finance.tracker.business.contracts.WorkspaceDto
import com.finance.tracker.core.TokenStore
import com.finance.tracker.data.*
import com.finance.tracker.domain.DomainFailure
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class LedgerCredentialSnapshotTest {
    private val headers get() = headersOf(HttpHeaders.ContentType, "application/json")
    private val original = "https://original.example.test"
    private val session = SessionDto(UserDto("synthetic@example.test"), "workspace",
        listOf(WorkspaceDto("workspace", "Synthetic workspace", Role.EDITOR)))
    private val snapshot = LedgerSnapshotDto("workspace", "7", "cash-rules-v1", LedgerFactsDto(
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "0"))

    @Test fun aScopedSourceCannotMixLaterCredentialsOrOriginsIntoItsSnapshot() = runTest {
        var origin = original
        val store = Store()
        val api = FinanceApiClient({ origin }, store, MockEngine { request ->
            assertEquals("original.example.test", request.url.host)
            assertEquals("Bearer original-token", request.headers[HttpHeaders.Authorization])
            assertEquals("workspace", request.url.parameters["workspace_id"])
            respond(if (request.url.encodedPath.endsWith("version")) """{"ledger_seq":"7"}"""
                else Json.encodeToString(snapshot), headers = headers)
        })
        try {
            val source = KtorLedgerSnapshotSource(api)
            assertEquals("7", source.version("workspace").ledgerSeq)
            store.value = "later-token"; origin = "https://later.example.test"
            assertEquals(snapshot, source.snapshot("workspace"))
            assertEquals(1, store.reads)
            assertEquals("later-token", store.value)
        } finally { api.close() }
    }

    @Test fun sessionRetriesKeepTheOriginalIdentityAndOrigin() = runTest {
        var origin = original
        val store = Store()
        var requests = 0
        val api = FinanceApiClient({ origin }, store, MockEngine { request ->
            assertEquals("original.example.test", request.url.host)
            assertEquals("Bearer original-token", request.headers[HttpHeaders.Authorization])
            requests++
            store.value = "later-token"; origin = "https://later.example.test"
            if (requests < 3) respond("{}", HttpStatusCode.ServiceUnavailable, headers)
            else respond(Json.encodeToString(session), headers = headers)
        })
        try {
            assertEquals(session, api.restoreSession(attempts = 3, retryDelayMillis = 0))
            assertEquals(3, requests)
            assertEquals("later-token", store.value)
        } finally { api.close() }
    }

    @Test fun explicitCapturedIdentityIsSharedBySessionVersionAndSnapshot() = runTest {
        var origin = original
        val store = Store()
        val paths = mutableListOf<String>()
        val api = FinanceApiClient({ origin }, store, MockEngine { request ->
            assertEquals("original.example.test", request.url.host)
            assertEquals("Bearer original-token", request.headers[HttpHeaders.Authorization])
            paths += request.url.encodedPath
            val body = when (request.url.encodedPath) {
                "/api/v1/auth/session" -> Json.encodeToString(session)
                "/api/v1/ledger/version" -> """{"ledger_seq":"7"}"""
                "/api/v1/ledger/snapshot" -> Json.encodeToString(snapshot)
                else -> error("Unexpected path")
            }
            respond(body, headers = headers)
        })
        try {
            val credentials = api.captureCredentials()
            store.value = "later-token"; origin = "https://later.example.test"
            assertEquals(session, api.restoreSession(credentials, attempts = 1))
            val source = KtorLedgerSnapshotSource(api, credentials)
            assertEquals("7", source.version("workspace").ledgerSeq)
            assertEquals(snapshot, source.snapshot("workspace"))
            assertEquals(listOf("/api/v1/auth/session", "/api/v1/ledger/version", "/api/v1/ledger/snapshot"), paths)
            assertEquals(1, store.reads)
        } finally { api.close() }
    }

    @Test fun anExplicitAnonymousSnapshotNeverAdoptsALaterLogin() = runTest {
        val store = Store().apply { value = null }
        var requests = 0
        val api = FinanceApiClient({ original }, store, MockEngine { request ->
            requests++
            assertNull(request.headers[HttpHeaders.Authorization])
            respond("{}", HttpStatusCode.Unauthorized, headers)
        })
        try {
            val credentials = api.captureCredentials()
            store.value = "later-token"
            assertEquals(401, assertFailsWith<ApiFailure> { api.restoreSession(credentials) }.status)
            assertEquals(0, requests)
            val source = KtorLedgerSnapshotSource(api, credentials)
            assertEquals(401, assertFailsWith<DomainFailure> { source.version("workspace") }.status)
            assertEquals(1, requests); assertEquals(1, store.reads)
            assertEquals("later-token", store.value); assertEquals(0, store.clears)
        } finally { api.close() }
    }

    @Test fun aRejectedScopedReadDoesNotEraseApplicationCredentials() = runTest {
        val store = Store()
        val api = FinanceApiClient({ original }, store, MockEngine {
            store.value = "later-token"
            respond("{}", HttpStatusCode.Unauthorized, headers)
        })
        try {
            assertEquals(401, assertFailsWith<DomainFailure> { KtorLedgerSnapshotSource(api).version("workspace") }.status)
            assertEquals("later-token", store.value)
            assertEquals(0, store.clears)
        } finally { api.close() }
    }

    @Test fun invalidRemoteOriginStillAllowsLogoutToClearItsLocalCredential() = runTest {
        val store = Store()
        val api = FinanceApiClient({ "invalid origin" }, store, MockEngine { error("No HTTP expected") })
        try {
            assertEquals("api_origin_invalid", assertFailsWith<ApiFailure> { api.logout() }.code)
            assertNull(store.value)
            assertEquals(1, store.clears)
        } finally { api.close() }
    }

    private class Store : TokenStore {
        var value: String? = "original-token"
        var reads = 0
        var clears = 0
        override suspend fun get(): String? { reads++; return value }
        override suspend fun set(value: String) { this.value = value }
        override suspend fun clear() { clears++; value = null }
    }
}
