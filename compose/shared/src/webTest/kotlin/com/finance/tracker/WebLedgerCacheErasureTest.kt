@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.finance.tracker

import com.finance.tracker.business.domain.sha256Hex
import com.finance.tracker.data.*
import com.finance.tracker.domain.LedgerCacheScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.await
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.random.Random
import kotlin.test.*

class WebLedgerCacheErasureTest {
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
            val erasure = createWebLedgerCacheErasure(fixture.scope, fixture.directory)
            try {
                assertSame(failure, assertFailsWith<IllegalStateException> { erasure.clear() })
                state(fixture.scope, false, false, true).await()
                assertEquals(listOf(fixture.scope), fixture.directory.scopes(fixture.scope.apiOrigin))
                assertFails { fixture.open() }
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
            val erasure = createWebLedgerCacheErasure(fixture.scope, fixture.directory)
            assertFails { erasure.clear() }
            state(fixture.scope, false, false, false).await()
            assertTrue(fixture.directory.scopes(fixture.scope.apiOrigin).isEmpty())
            val fresh = fixture.open()
            val facts = localCashLedgerOwnerSnapshot()
            try {
                fresh.replace(fixture.scope, facts)
                erasure.clear()
                assertEquals(facts, fresh.read(fixture.scope))
            } finally { fresh.clear(fixture.scope) }
        } finally { restoreFinalCloseProbe().await(); fixture.clean() }
    }

    private class Fixture {
        val scope = LedgerCacheScope("https://erasure-" + Random.nextLong().toString().removePrefix("-") + ".example.test",
            ledgerOfflinePrincipal("synthetic@example.test"), "workspace")
        private val actual = createPlatformLedgerSessionLocatorStorage()
        var beforeCas: (suspend () -> Unit)? = null
        val directory = LedgerSessionDirectory(object : LedgerSessionLocatorStorage {
            override suspend fun read(key: String) = actual.read(key)
            override suspend fun compareAndSet(key: String, expected: String?, replacement: String): Boolean {
                beforeCas?.invoke()
                return actual.compareAndSet(key, expected, replacement)
            }
        })
        private var ticket: LedgerSessionTicket? = null
        suspend fun open(): LedgerStorage {
            val current = ticket ?: LedgerSessionDirectory(actual).begin(scope.apiOrigin).also { ticket = it }
            return createWebLedgerFactCache(scope, registration = LedgerSessionRegistration(scope, directory, current))
        }
        suspend fun seed() {
            val cache = open()
            try { cache.replace(scope, localCashLedgerOwnerSnapshot()) } finally { cache.close() }
        }
        suspend fun clean() {
            beforeCas = null
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
