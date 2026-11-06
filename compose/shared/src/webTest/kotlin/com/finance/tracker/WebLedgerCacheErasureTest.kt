@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.finance.tracker

import com.finance.tracker.business.domain.sha256Hex
import com.finance.tracker.business.contracts.SessionDto
import com.finance.tracker.business.contracts.UserDto
import com.finance.tracker.business.contracts.WorkspaceDto
import com.finance.tracker.business.contracts.Role
import com.finance.tracker.business.contracts.LedgerVersionDto
import com.finance.tracker.core.TokenStore
import com.finance.tracker.data.*
import com.finance.tracker.domain.LedgerCacheScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.await
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.random.Random
import kotlin.test.*
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class WebLedgerCacheErasureTest {
    @Test fun verifiedRestorationPrunesRealRevokedMaterialsBeforeRefreshingAndPublishing() = runTest {
        val active = Fixture(); val revoked = Fixture(active.scope.apiOrigin, active.scope.principal, "revoked")
        val other = Fixture(active.scope.apiOrigin, ledgerOfflinePrincipal("other@example.test"), "other")
        val session = SessionDto(UserDto("synthetic@example.test"), active.scope.workspaceId,
            listOf(WorkspaceDto(active.scope.workspaceId, "Synthetic", Role.EDITOR)))
        val snapshot = localCashLedgerOwnerSnapshot(); var requests = 0
        var operation: LedgerSessionRestoration? = null
        val api = FinanceApiClient({ active.scope.apiOrigin }, object : TokenStore {
            var token: String? = "synthetic-restoration-token"
            override suspend fun get() = token
            override suspend fun set(value: String) { token = value }
            override suspend fun clear() { token = null }
        }, MockEngine { request ->
            requests++
            assertEquals("Bearer synthetic-restoration-token", request.headers[HttpHeaders.Authorization])
            val body = when (request.url.encodedPath) {
                "/api/v1/auth/session" -> Json.encodeToString(session)
                "/api/v1/ledger/version" -> {
                    state(revoked.scope, false, false, false).await()
                    state(other.scope, true, true, false).await()
                    Json.encodeToString(LedgerVersionDto(snapshot.ledgerSeq))
                }
                "/api/v1/ledger/snapshot" -> Json.encodeToString(snapshot)
                else -> error("Unexpected request")
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            revoked.seed(); other.seed()
            val otherDigest = digest(other.scope).await().toString()
            val ticket = active.advanceOperation()
            val recovery = LedgerSessionRestoration(api, ApiCredentialSnapshot("synthetic-restoration-token", active.scope.apiOrigin),
                active.directory, ticket).also { operation = it }
            assertEquals(session, recovery.restore())
            assertNotNull(recovery.page()); assertEquals(3, requests)
            assertEquals(active.scope, active.directory.current(ticket))
            assertEquals(setOf(active.scope, other.scope), active.directory.scopes(ticket).toSet())
            assertEquals(otherDigest, digest(other.scope).await().toString())
            state(active.scope, true, true, true).await()
            recovery.close()
            state(active.scope, true, true, false).await()
        } finally { operation?.close(); api.close(); for (f in listOf(active, revoked, other)) f.clean() }
    }

    @Test fun revocationCleansOnlySelectedRealCachesAndKeepsOtherUsersAndAccessibleWorkspaces() = runTest {
        val first = Fixture()
        val second = Fixture(first.scope.apiOrigin, first.scope.principal, "still-accessible")
        val other = Fixture(first.scope.apiOrigin, ledgerOfflinePrincipal("other@example.test"), "other-user")
        try {
            for (f in listOf(first, second, other)) f.seed()
            val secondDigest = digest(second.scope).await().toString()
            val otherDigest = digest(other.scope).await().toString()
            val cleanup = LedgerSessionCacheCleanup(first.directory, first.advanceOperation(), first.scope.principal,
                setOf(second.scope.workspaceId))
            cleanup.clear()
            state(first.scope, false, false, false).await()
            for (f in listOf(second, other)) state(f.scope, true, true, false).await()
            assertEquals(secondDigest, digest(second.scope).await().toString())
            assertEquals(otherDigest, digest(other.scope).await().toString())
            assertEquals(setOf(second.scope, other.scope), first.directory.scopes(first.scope.apiOrigin).toSet())
            // Completion never discovers a newly recreated target, even under a new operation.
            first.advanceOperation(); first.seed()
            val recreated = digest(first.scope).await().toString()
            cleanup.clear()
            assertEquals(recreated, digest(first.scope).await().toString())
            state(first.scope, true, true, false).await()
        } finally { for (f in listOf(first, second, other)) f.clean() }
    }

    @Test fun aPartialMultiCacheCleanupRetainsTheRealLeaseAndDoesNotSelectLaterCachesOnRetry() = runTest {
        val first = Fixture(); val second = Fixture(first.scope.apiOrigin, first.scope.principal, "second")
        val later = Fixture(first.scope.apiOrigin, first.scope.principal, "later")
        try {
            first.seed(); second.seed()
            val cleanup = LedgerSessionCacheCleanup(first.directory, first.advanceOperation(), first.scope.principal, emptySet())
            var removals = 0
            first.beforeCas = { if (++removals == 2) error("synthetic second locator removal failure") }
            try { assertFailsWith<IllegalStateException> { cleanup.clear() } }
            finally { first.beforeCas = null }
            state(first.scope, false, false, false).await()
            state(second.scope, false, false, true).await()
            assertEquals(listOf(second.scope), first.directory.scopes(first.scope.apiOrigin))
            later.seed() // A new generation and new material while the original second erasure still owns its lease.
            val untouched = digest(later.scope).await().toString()
            cleanup.clear()
            state(second.scope, false, false, false).await()
            state(later.scope, true, true, false).await()
            assertEquals(untouched, digest(later.scope).await().toString())
            assertEquals(listOf(later.scope), first.directory.scopes(first.scope.apiOrigin))
        } finally { for (f in listOf(first, second, later)) f.clean() }
    }

    @Test fun supersessionAfterTheRealLeaseIsAcquiredPreservesMaterialsAndReleasesThatLease() = runTest {
        val fixture = Fixture(); val entered = CompletableDeferred<Unit>(); val proceed = CompletableDeferred<Unit>()
        var clearing: kotlinx.coroutines.Deferred<*>? = null
        try {
            fixture.seed()
            val original = digest(fixture.scope).await().toString()
            val erasure = createPlatformLedgerCacheErasure(fixture.scope, fixture.directory, fixture.lastTicket())
            fixture.beforeRead = { entered.complete(Unit); proceed.await() }
            clearing = async { assertFailsWith<CancellationException> { erasure.clear() } }
            entered.await()
            state(fixture.scope, true, true, true).await() // The actual scope lease precedes the metadata check.
            fixture.beforeRead = null; fixture.advanceOperation(); proceed.complete(Unit); clearing.await()
            assertEquals(original, digest(fixture.scope).await().toString())
            state(fixture.scope, true, true, false).await()
            assertEquals(listOf(fixture.scope), fixture.directory.scopes(fixture.scope.apiOrigin))
            val fresh = fixture.open()
            try {
                assertNotNull(fresh.read(fixture.scope))
                val freshMaterials = digest(fixture.scope).await().toString()
                assertFailsWith<CancellationException> { erasure.clear() }
                assertEquals(freshMaterials, digest(fixture.scope).await().toString())
                state(fixture.scope, true, true, true).await()
                erasure.close(); erasure.close()
                assertEquals(freshMaterials, digest(fixture.scope).await().toString())
                state(fixture.scope, true, true, true).await()
            } finally { fresh.clear(fixture.scope) }
        } finally {
            fixture.beforeRead = null; proceed.complete(Unit); clearing?.cancelAndJoin(); fixture.clean()
        }
    }

    @Test fun anApplicationErasureRejectsAForeignTicketBeforeAcquiringOrChangingMaterials() = runTest {
        val fixture = Fixture()
        try {
            fixture.seed(); val original = digest(fixture.scope).await().toString()
            assertFailsWith<IllegalArgumentException> { createPlatformLedgerCacheErasure(fixture.scope,
                fixture.directory, LedgerSessionTicket("https://foreign.example.test", 1)).clear() }
            assertEquals(original, digest(fixture.scope).await().toString())
            state(fixture.scope, true, true, false).await()
        } finally { fixture.clean() }
    }

    @Test fun invalidScopePreservesItsOriginalFileAndKeyAndDoesNotAcquireALease() = runTest {
        for (badPrincipal in listOf(true, false)) {
            val fixture = Fixture()
            val invalid = if (badPrincipal) fixture.scope.copy(principal = "synthetic-non-hash")
                else fixture.scope.copy(apiOrigin = fixture.scope.apiOrigin + "/")
            val cache = createWebLedgerFactCache(invalid)
            try { cache.replace(invalid, localCashLedgerOwnerSnapshot()) } finally { cache.close() }
            try {
                val before = digest(invalid).await().toString()
                assertFailsWith<IllegalArgumentException> { createWebLedgerCacheErasure(invalid, fixture.directory).clear() }
                assertEquals(before, digest(invalid).await().toString())
                state(invalid, true, true, false).await()
            } finally { eraseSynthetic(invalid).await(); fixture.clean() }
        }
    }

    @Test fun keylessCorruptAndOrphanedCachesCanBeErasedWithoutOpeningThem() = runTest {
        for (mode in listOf("missing-key", "corrupt", "orphan")) {
            val fixture = Fixture()
            try {
                fixture.seed()
                alter(fixture.scope, mode).await()
                val erasure = createWebLedgerCacheErasure(fixture.scope, fixture.directory)
                erasure.clear()
                state(fixture.scope, false, false, false).await()
                assertDirectoryMissing(fixture.scope).await()
                assertTrue(fixture.directory.scopes(fixture.scope.apiOrigin).isEmpty())
                erasure.clear()
                val fresh = fixture.open()
                val facts = localCashLedgerOwnerSnapshot()
                try {
                    fresh.replace(fixture.scope, facts)
                    erasure.clear()
                    assertEquals(facts, fresh.read(fixture.scope))
                } finally { fresh.clear(fixture.scope) }
            } finally { fixture.clean() }
        }
    }

    @Test fun failedForgetKeepsTheSameErasureOwnerLockedForExplicitRetry() = runTest {
        val fixture = Fixture()
        try {
            fixture.seed()
            val failure = IllegalStateException("synthetic erase-only forget failure")
            fixture.beforeCas = { throw failure }
            val erasure = createPlatformLedgerCacheErasure(fixture.scope, fixture.directory, fixture.lastTicket())
            try {
                assertSame(failure, assertFailsWith<IllegalStateException> { erasure.clear() })
                state(fixture.scope, false, false, true).await()
                assertEquals(listOf(fixture.scope), fixture.directory.scopes(fixture.scope.apiOrigin))
                assertFails { fixture.open() }
                fixture.advanceOperation() // An already selected partial erase must finish despite a later ticket.
            } finally { fixture.beforeCas = null; erasure.clear() }
            assertTrue(fixture.directory.scopes(fixture.scope.apiOrigin).isEmpty())
            state(fixture.scope, false, false, false).await()
        } finally { fixture.clean() }
    }

    @Test fun cancellingARealPendingForgetDoesNotReleaseOwnershipBeforeTheCasCompletes() = runTest {
        val fixture = Fixture()
        fixture.seed()
        val entered = CompletableDeferred<Unit>()
        fixture.beforeCas = { entered.complete(Unit) }
        holdMetadata().await()
        val erasure = createWebLedgerCacheErasure(fixture.scope, fixture.directory)
        val clearing = launch { erasure.clear() }
        try {
            entered.await()
            clearing.cancel()
            browserTurn().await()
            assertFalse(clearing.isCompleted)
            state(fixture.scope, false, false, true).await()
            releaseMetadata().await()
            clearing.join()
            assertTrue(clearing.isCancelled)
            fixture.beforeCas = null
            assertTrue(fixture.directory.scopes(fixture.scope.apiOrigin).isEmpty())
            val fresh = fixture.open()
            val facts = localCashLedgerOwnerSnapshot()
            try {
                fresh.replace(fixture.scope, facts)
                erasure.clear()
                assertEquals(facts, fresh.read(fixture.scope))
            } finally { fresh.clear(fixture.scope) }
        } finally { releaseMetadata().await(); clearing.cancelAndJoin(); fixture.beforeCas = null; fixture.clean() }
    }

    @Test fun aLostFinalAcknowledgementCannotMakeAnOldRetryEraseANewOwner() = runTest {
        val fixture = Fixture()
        fixture.seed()
        installArmedFinalCloseProbe().await()
        try {
            val erasure = createPlatformLedgerCacheErasure(fixture.scope, fixture.directory, fixture.lastTicket())
            assertFails { erasure.clear() }
            state(fixture.scope, false, false, false).await()
            assertTrue(fixture.directory.scopes(fixture.scope.apiOrigin).isEmpty())
            fixture.advanceOperation()
            val fresh = fixture.open()
            val facts = localCashLedgerOwnerSnapshot()
            try {
                fresh.replace(fixture.scope, facts)
                erasure.clear()
                assertEquals(facts, fresh.read(fixture.scope))
            } finally { fresh.clear(fixture.scope) }
        } finally { restoreFinalCloseProbe().await(); fixture.clean() }
    }

    @Test fun closeAfterALostFinalAcknowledgementOnlyReleasesAndPreservesNewMaterials() = runTest {
        val fixture = Fixture()
        fixture.seed(); installArmedFinalCloseProbe().await()
        try {
            val erasure = createPlatformLedgerCacheErasure(fixture.scope, fixture.directory, fixture.lastTicket())
            assertFails { erasure.clear() }
            state(fixture.scope, false, false, false).await()
            fixture.advanceOperation()
            val fresh = fixture.open()
            try {
                fresh.replace(fixture.scope, localCashLedgerOwnerSnapshot())
                val materials = digest(fixture.scope).await().toString()
                erasure.close(); erasure.close()
                assertEquals(materials, digest(fixture.scope).await().toString())
                state(fixture.scope, true, true, true).await()
            } finally { fresh.clear(fixture.scope) }
        } finally { restoreFinalCloseProbe().await(); fixture.clean() }
    }

    private class Fixture(
        origin: String = "https://erasure-" + Random.nextLong().toString().removePrefix("-") + ".example.test",
        principal: String = ledgerOfflinePrincipal("synthetic@example.test"), workspaceId: String = "workspace",
    ) {
        val scope = LedgerCacheScope(origin, principal, workspaceId)
        private val actual = createPlatformLedgerSessionLocatorStorage()
        var beforeCas: (suspend () -> Unit)? = null
        var beforeRead: (suspend () -> Unit)? = null
        val directory = LedgerSessionDirectory(object : LedgerSessionLocatorStorage {
            override suspend fun read(key: String): String? { beforeRead?.invoke(); return actual.read(key) }
            override suspend fun compareAndSet(key: String, expected: String?, replacement: String): Boolean {
                beforeCas?.invoke()
                return actual.compareAndSet(key, expected, replacement)
            }
        })
        private var ticket: LedgerSessionTicket? = null
        fun lastTicket(): LedgerSessionTicket = checkNotNull(ticket)
        suspend fun advanceOperation(): LedgerSessionTicket = LedgerSessionDirectory(actual).begin(scope.apiOrigin).also { ticket = it }
        suspend fun open(): LedgerStorage {
            val current = ticket ?: LedgerSessionDirectory(actual).begin(scope.apiOrigin).also { ticket = it }
            return createWebLedgerFactCache(scope, registration = LedgerSessionRegistration(scope, directory, current))
        }
        suspend fun seed() {
            val cache = open()
            try { cache.replace(scope, localCashLedgerOwnerSnapshot().copy(workspaceId = scope.workspaceId)) } finally { cache.close() }
        }
        suspend fun clean() {
            beforeCas = null
            beforeRead = null
            eraseSynthetic(scope).await()
            removeRecord("ledger-session-current-v1:" + sha256Hex(scope.apiOrigin.encodeToByteArray()))
        }
    }
}

private fun state(scope: LedgerCacheScope, key: Boolean, file: Boolean, locked: Boolean): Promise<JsAny?> =
    stateProbe(scope.apiOrigin, scope.principal, scope.workspaceId, key, file, locked)
private fun stateProbe(apiOrigin: String, principal: String, workspaceId: String, key: Boolean, file: Boolean, locked: Boolean): Promise<JsAny?> =
    js("import('/ledger-storage/ledger-lease-probe.mjs').then(module => module.assertState([apiOrigin, principal, workspaceId], key, file, locked))")
private fun digest(scope: LedgerCacheScope): Promise<JsString> = digestProbe(scope.apiOrigin, scope.principal, scope.workspaceId)
private fun digestProbe(apiOrigin: String, principal: String, workspaceId: String): Promise<JsString> =
    js("import('/ledger-storage/ledger-lease-probe.mjs').then(module => module.materialDigest([apiOrigin, principal, workspaceId]))")
private fun alter(scope: LedgerCacheScope, mode: String): Promise<JsAny?> = alterProbe(scope.apiOrigin, scope.principal, scope.workspaceId, mode)
private fun alterProbe(apiOrigin: String, principal: String, workspaceId: String, mode: String): Promise<JsAny?> =
    js("import('/ledger-storage/ledger-lease-probe.mjs').then(module => module.alterMaterials([apiOrigin, principal, workspaceId], mode))")
private fun assertDirectoryMissing(scope: LedgerCacheScope): Promise<JsAny?> = directoryProbe(scope.apiOrigin, scope.principal, scope.workspaceId)
private fun directoryProbe(apiOrigin: String, principal: String, workspaceId: String): Promise<JsAny?> =
    js("import('/ledger-storage/ledger-lease-probe.mjs').then(module => module.assertDirectoryMissing([apiOrigin, principal, workspaceId]))")
private fun eraseSynthetic(scope: LedgerCacheScope): Promise<JsAny?> = eraseProbe(scope.apiOrigin, scope.principal, scope.workspaceId)
private fun eraseProbe(apiOrigin: String, principal: String, workspaceId: String): Promise<JsAny?> =
    js("import('/ledger-storage/connection.mjs').then(module => module.eraseWebLedgerStorage([apiOrigin, principal, workspaceId]))")
private fun holdMetadata(): Promise<JsAny?> = js("import('/ledger-storage/ledger-lease-probe.mjs').then(module => module.holdMetadataLock())")
private fun releaseMetadata(): Promise<JsAny?> = js("import('/ledger-storage/ledger-lease-probe.mjs').then(module => module.releaseMetadataLock())")
private fun browserTurn(): Promise<JsAny?> = js("new Promise(resolve => setTimeout(() => resolve(null), 30))")
private fun installArmedFinalCloseProbe(): Promise<JsAny?> = js("import('/ledger-storage/ledger-lease-probe.mjs').then(module => module.installFinalCloseProbe(true))")
private fun restoreFinalCloseProbe(): Promise<JsAny?> = js("import('/ledger-storage/ledger-lease-probe.mjs').then(module => module.restoreFinalCloseProbe())")
private fun removeRecord(key: String) { js("localStorage.removeItem(key)") }
