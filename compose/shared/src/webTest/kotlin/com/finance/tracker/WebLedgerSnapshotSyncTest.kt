@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.finance.tracker

import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.contracts.LedgerVersionDto
import com.finance.tracker.business.domain.CashProjectionDomainFailure
import com.finance.tracker.core.LocalCashLedgerOwner
import com.finance.tracker.core.TokenStore
import com.finance.tracker.data.FinanceApiClient
import com.finance.tracker.data.KtorLedgerSnapshotSource
import com.finance.tracker.data.createWebLedgerFactCache
import com.finance.tracker.domain.DomainFailure
import com.finance.tracker.domain.LedgerCacheScope
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlinx.coroutines.await
import kotlin.random.Random
import kotlin.test.*

class WebLedgerSnapshotSyncTest {
    @Test fun realEncryptedCacheFeedsOfflinePagesAndOnlyDownloadsAChangedVersion() = runTest {
        val scope = scope()
        val cache = createWebLedgerFactCache(scope)
        val server = Server()
        val api = server.api(scope)
        val owner = LocalCashLedgerOwner(scope, cache, KtorLedgerSnapshotSource(api))
        try {
            assertNull(owner.page(timeZoneId = "UTC"))
            assertEquals(0, server.requests)
            owner.refresh()
            val original = assertNotNull(owner.page(timeZoneId = "UTC", limit = 1))
            val cursor = assertNotNull(original.nextCursor)
            assertEquals("-2", original.items.single().amount)
            assertEquals(server.snapshot, cache.read(scope))
            assertEquals(1, server.downloads)
            owner.refresh()
            assertEquals(1, server.downloads)
            assertEquals(3, server.requests) // Two version checks, one snapshot.
            server.snapshot = localCashLedgerOwnerSnapshot("9007199254740994", "-20")
            owner.refresh()
            assertEquals(2, server.downloads)
            assertEquals(server.snapshot, cache.read(scope))
            val updated = assertNotNull(owner.page(timeZoneId = "UTC", limit = 1))
            assertEquals("-20", updated.items.single().amount)
            assertEquals("read_snapshot_changed", assertFailsWith<CashProjectionDomainFailure> {
                owner.page(timeZoneId = "UTC", cursor = cursor, limit = 1)
            }.code)
            val requests = server.requests
            server.status = HttpStatusCode.ServiceUnavailable
            assertEquals(updated, owner.page(timeZoneId = "UTC", limit = 1))
            assertEquals(requests, server.requests)
            assertEquals(503, assertFailsWith<DomainFailure> { owner.refresh() }.status)
            assertEquals(updated, owner.page(timeZoneId = "UTC", limit = 1))
            assertEquals(server.snapshot, cache.read(scope))
            server.status = HttpStatusCode.OK
            // A receipt minimum forces download even when the separate version read is stale.
            server.version = server.snapshot.ledgerSeq
            server.snapshot = localCashLedgerOwnerSnapshot("9007199254740995", "-30")
            owner.refresh(committedLedgerSeq = server.snapshot.ledgerSeq)
            assertEquals(3, server.downloads)
            assertEquals("-30", owner.page(timeZoneId = "UTC", limit = 1)?.items?.single()?.amount)
            assertEquals(server.snapshot, cache.read(scope))
        } finally { try { owner.close() } finally { api.close() } }
    }

    @Test fun aScopedHttpRejectionErasesFilesAndKeysAndFencesTheOldOwner() = runTest {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            val scope = scope()
            val cache = createWebLedgerFactCache(scope)
            val server = Server()
            val api = server.api(scope)
            val owner = LocalCashLedgerOwner(scope, cache, KtorLedgerSnapshotSource(api))
            try {
                owner.refresh()
                assertNotNull(owner.page(timeZoneId = "UTC"))
                assertStorageMaterials(scope.apiOrigin, scope.principal, scope.workspaceId, expected = true).await()
                server.status = status
                assertEquals(status.value, assertFailsWith<DomainFailure> { owner.refresh() }.status)
                assertFailsWith<CancellationException> { owner.page(timeZoneId = "UTC") }
                assertFailsWith<CancellationException> { cache.read(scope) }
                assertStorageMaterials(scope.apiOrigin, scope.principal, scope.workspaceId, expected = false).await()
                owner.close()
                val fresh = createWebLedgerFactCache(scope)
                try { assertNull(fresh.read(scope)) } finally { fresh.clear(scope) }
            } finally { try { owner.close() } finally { api.close() } }
        }
    }

    private fun scope() = LedgerCacheScope("https://api.example.invalid", "sync-${Random.nextLong()}", "workspace")

    private class Server {
        var snapshot: LedgerSnapshotDto = localCashLedgerOwnerSnapshot()
        var version: String? = null
        var status = HttpStatusCode.OK
        var downloads = 0
        var requests = 0
        fun api(scope: LedgerCacheScope) = FinanceApiClient({ scope.apiOrigin }, object : TokenStore {
            override suspend fun get() = "synthetic-token"
            override suspend fun set(value: String) = error("The scoped source cannot replace application credentials")
            override suspend fun clear() = error("A scoped response cannot clear a different login's credentials")
        }, MockEngine { request ->
            requests++
            assertEquals(scope.apiOrigin, "${request.url.protocol.name}://${request.url.host}")
            assertEquals(scope.workspaceId, request.url.parameters["workspace_id"])
            assertEquals("Bearer synthetic-token", request.headers[HttpHeaders.Authorization])
            val body = if (status != HttpStatusCode.OK) """{"error":{"code":"access_denied"}}"""
            else when (request.url.encodedPath) {
                "/api/v1/ledger/version" -> Json.encodeToString(LedgerVersionDto(version ?: snapshot.ledgerSeq))
                "/api/v1/ledger/snapshot" -> { downloads++; Json.encodeToString(snapshot) }
                else -> error("Unexpected network path ${request.url.encodedPath}")
            }
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        })
    }
}

internal fun assertStorageMaterials(apiOrigin: String, principal: String, workspaceId: String,
    expected: Boolean): Promise<JsAny?> = js("""(function() {
    const scope = [apiOrigin, principal, workspaceId];
    return import('/ledger-key-vault.mjs')
        .then(vault => Promise.all([vault.readWebLedgerKey(scope), vault.webLedgerKeyId(scope)]))
        .then(([key, id]) => {
            const keyPresent = key !== null;
            if (keyPresent) key.fill(0);
            return navigator.storage.getDirectory()
                .then(root => root.getDirectoryHandle('ft-ledger-cache-v1'))
                .then(parent => parent.getDirectoryHandle(id))
                .then(() => true, failure => {
                    if (failure.name !== 'NotFoundError') throw failure;
                    return false;
                }).then(directoryPresent => {
                    if (keyPresent !== expected || directoryPresent !== expected)
                        throw new Error('Unexpected cache material presence: ' + JSON.stringify({ keyPresent, directoryPresent, expected }));
                    return null;
                });
        });
})()""")
