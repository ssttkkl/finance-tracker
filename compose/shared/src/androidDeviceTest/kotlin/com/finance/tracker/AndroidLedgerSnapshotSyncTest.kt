package com.finance.tracker

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.finance.tracker.business.contracts.LedgerVersionDto
import com.finance.tracker.business.domain.CashProjectionDomainFailure
import com.finance.tracker.core.LocalCashLedgerOwner
import com.finance.tracker.core.TokenStore
import com.finance.tracker.data.AndroidLedgerKeyVault
import com.finance.tracker.data.FinanceApiClient
import com.finance.tracker.data.KtorLedgerSnapshotSource
import com.finance.tracker.data.createAndroidLedgerFactCache
import com.finance.tracker.data.ledgerStorageId
import com.finance.tracker.domain.DomainFailure
import com.finance.tracker.domain.LedgerCacheScope
import com.finance.tracker.domain.LedgerFactCache
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.test.*

/** Real Keystore, SQLCipher, file leases and local pages; HTTP responses are synthetic MockEngine data. */
@RunWith(AndroidJUnit4::class)
class AndroidLedgerSnapshotSyncTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun realEncryptedFactsFeedOfflinePagesAndOnlyChangedVersionsAreDownloaded() = runTest {
        withOwner { scope, root, cache, server, owner ->
            assertNull(owner.page(timeZoneId = "UTC"))
            assertEquals(0, server.requests)
            owner.refresh()
            val before = assertNotNull(owner.page(timeZoneId = "UTC", limit = 1))
            val cursor = assertNotNull(before.nextCursor)
            assertEquals("-2", before.items.single().amount)
            assertEquals(server.snapshot, cache.read(scope))
            owner.refresh()
            assertEquals(1, server.downloads)
            assertEquals(3, server.requests)
            server.snapshot = localCashLedgerOwnerSnapshot("9007199254740994", "-20")
            owner.refresh()
            assertEquals(2, server.downloads)
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
            assertMaterials(scope, root, true)
            server.status = HttpStatusCode.OK
            server.version = server.snapshot.ledgerSeq
            server.snapshot = localCashLedgerOwnerSnapshot("9007199254740995", "-30")
            owner.refresh(committedLedgerSeq = server.snapshot.ledgerSeq)
            assertEquals(3, server.downloads)
            assertEquals("-30", owner.page(timeZoneId = "UTC", limit = 1)?.items?.single()?.amount)
            assertEquals(server.snapshot, cache.read(scope))
        }
    }

    @Test fun scopedUnauthorizedAndForbiddenResponsesEraseActualFilesAndKeys() = runTest {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            withOwner { scope, root, cache, server, owner ->
                owner.refresh()
                assertNotNull(owner.page(timeZoneId = "UTC"))
                assertMaterials(scope, root, true)
                server.status = status
                assertEquals(status.value, assertFailsWith<DomainFailure> { owner.refresh() }.status)
                assertFailsWith<CancellationException> { owner.page(timeZoneId = "UTC") }
                assertFailsWith<CancellationException> { cache.read(scope) }
                assertMaterials(scope, root, false)
                owner.close()
                val fresh = createAndroidLedgerFactCache(context, scope, root)
                try { assertNull(fresh.read(scope)) } finally { fresh.clear(scope) }
            }
        }
    }

    private suspend fun withOwner(block: suspend (LedgerCacheScope, File, LedgerFactCache, Server, LocalCashLedgerOwner) -> Unit) {
        val marker = UUID.randomUUID().toString()
        val scope = LedgerCacheScope("https://api.example.invalid", "sync-$marker", "workspace")
        val root = File(context.cacheDir, "ledger-sync-$marker")
        assertTrue(root.mkdir()); Os.chmod(root.path, 0x1C0)
        val server = Server()
        val api = server.api(scope)
        var owner: LocalCashLedgerOwner? = null
        try {
            val cache = createAndroidLedgerFactCache(context, scope, root)
            val active = LocalCashLedgerOwner(scope, cache, KtorLedgerSnapshotSource(api))
            owner = active
            block(scope, root, cache, server, active)
        } finally {
            try { owner?.close() } finally {
                api.close()
                AndroidLedgerKeyVault(context, ledgerStorageId(scope)).erase()
                root.deleteRecursively()
            }
        }
    }

    private fun assertMaterials(scope: LedgerCacheScope, root: File, expected: Boolean) {
        val key = AndroidLedgerKeyVault(context, ledgerStorageId(scope)).read()
        try { assertEquals(expected, key != null) } finally { key?.fill(0) }
        assertEquals(expected, File(root, "facts/${ledgerStorageId(scope)}").isDirectory)
    }

    private class Server {
        var snapshot = localCashLedgerOwnerSnapshot()
        var version: String? = null
        var status = HttpStatusCode.OK
        var requests = 0
        var downloads = 0
        fun api(scope: LedgerCacheScope) = FinanceApiClient({ scope.apiOrigin }, object : TokenStore {
            override suspend fun get() = "synthetic-token"
            override suspend fun set(value: String) = error("A scoped source cannot replace credentials")
            override suspend fun clear() = error("A scoped response cannot clear a different login")
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
