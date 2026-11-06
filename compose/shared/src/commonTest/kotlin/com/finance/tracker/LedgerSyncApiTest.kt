package com.finance.tracker

import com.finance.tracker.business.contracts.*
import com.finance.tracker.core.TokenStore
import com.finance.tracker.data.*
import com.finance.tracker.domain.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class LedgerSyncApiTest {
    private val empty = LedgerFactsDto(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "0")
    private fun api(handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        FinanceApiClient({ "https://api.example.test" }, object : TokenStore {
            override suspend fun get() = "token"
            override suspend fun set(value: String) = Unit
            override suspend fun clear() = Unit
        }, MockEngine(handler))
    @Test fun readsAlwaysCarryTheirCacheWorkspaceAndKeepExactVersions() = runTest {
        val workspace = "work space/账本?"
        val seen = mutableListOf<String>()
        val api = api { request ->
            assertEquals(workspace, request.url.parameters["workspace_id"])
            assertEquals("Bearer token", request.headers[HttpHeaders.Authorization])
            seen += request.url.encodedPath
            respond(if (request.url.encodedPath.endsWith("version")) """{"ledger_seq":"9223372036854775807"}"""
                else Json.encodeToString(LedgerSnapshotDto(workspace, "9223372036854775807", "cash-rules-v1", empty)),
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        try {
            assertEquals("9223372036854775807", api.fetchLedgerVersion(workspace).ledgerSeq)
            assertEquals(workspace, api.fetchLedgerSnapshot(workspace).workspaceId)
            assertEquals(listOf("/api/v1/ledger/version", "/api/v1/ledger/snapshot"), seen)
            assertFailsWith<IllegalArgumentException> { api.fetchLedgerVersion("") }
            assertEquals(2, seen.size)
        } finally { api.close() }
    }
    @Test fun aLateCacheUnauthorizedResponseCannotClearCredentialsFromANewerLogin() = runTest {
        var token: String? = "old-login"
        val store = object : TokenStore {
            override suspend fun get() = token
            override suspend fun set(value: String) { token = value }
            override suspend fun clear() { token = null }
        }
        val api = FinanceApiClient({"https://api.example.test"},store,MockEngine {
            assertEquals("Bearer old-login",it.headers[HttpHeaders.Authorization])
            store.set("new-login")
            respond("""{"error":{"code":"authentication_required"}}""",HttpStatusCode.Unauthorized,
                headers=headersOf(HttpHeaders.ContentType,"application/json"))
        })
        try {
            assertEquals(401,assertFailsWith<ApiFailure> {api.fetchLedgerVersion("workspace")}.status)
            assertEquals("new-login",store.get())
        } finally {api.close()}
    }
    @Test fun aRealHttpAccessRejectionClearsAndClosesTheFactCache() = runTest {
        val api = api { respond("""{"error":{"code":"access_denied"}}""", HttpStatusCode.Forbidden,
            headers = headersOf(HttpHeaders.ContentType, "application/json")) }
        var stored: LedgerSnapshotDto? = LedgerSnapshotDto("workspace", "1", "cash-rules-v1", empty)
        val cache = object : LedgerFactCache {
            override suspend fun read(scope: LedgerCacheScope) = stored
            override suspend fun replace(scope: LedgerCacheScope, snapshot: LedgerSnapshotDto) { error("forbidden read must not publish") }
            override suspend fun close() = Unit
            override suspend fun clear(scope: LedgerCacheScope) { stored = null }
        }
        val sync = LedgerSnapshotSync(LedgerCacheScope("https://api.example.test", "user", "workspace"),cache,KtorLedgerSnapshotSource(api))
        try {
            assertEquals(403,assertFailsWith<DomainFailure> { sync.refresh() }.status)
            assertNull(stored)
            assertFailsWith<kotlinx.coroutines.CancellationException> { sync.cached() }
        } finally { api.close() }
    }
    @Test fun aForeignSnapshotIsNeverReturnedToTheCacheCaller() = runTest {
        val api = api { respond(Json.encodeToString(LedgerSnapshotDto("foreign", "1", "cash-rules-v1", empty)),
            headers = headersOf(HttpHeaders.ContentType, "application/json")) }
        try { assertEquals("ledger_snapshot_workspace_mismatch",
            assertFailsWith<IllegalStateException> { api.fetchLedgerSnapshot("expected") }.message) }
        finally { api.close() }
    }
}
