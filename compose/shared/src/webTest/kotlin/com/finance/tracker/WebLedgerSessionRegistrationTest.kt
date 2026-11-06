@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.finance.tracker

import com.finance.tracker.business.domain.sha256Hex
import com.finance.tracker.data.*
import com.finance.tracker.domain.LedgerCacheScope
import com.finance.tracker.core.TokenStore
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlin.random.Random
import kotlin.test.*

class WebLedgerSessionRegistrationTest {
    @Test fun verifiedPublicationSurvivesEncryptedCloseAndExistingOnlyReopen() = runTest {
        val fixture = Fixture()
        val client = PublicationClient(fixture.scope)
        val ticket = fixture.ticket()
        val cache = fixture.open(ticket)
        val session = publicationSession()
        try {
            cache.replace(fixture.scope, localCashLedgerOwnerSnapshot())
            client.api.publishVerifiedLedgerSession(client.credentials, fixture.directory, ticket,
                fixture.scope, cache, session)
            assertEquals(fixture.scope, fixture.directory.current(ticket))
            cache.close()
            assertState(fixture.scope, true, true, false).await()
            val reopened = createWebLedgerFactCache(fixture.scope, existingOnly = true,
                registration = LedgerSessionRegistration(fixture.scope, fixture.directory, ticket))
            try {
                assertEquals(session, reopened.readOfflineSession(fixture.scope, client.value))
                client.api.writeToken("new-login")
                assertNull(reopened.readOfflineSession(fixture.scope, client.value))
                assertFailsWith<CancellationException> {
                    client.api.publishVerifiedLedgerSession(client.credentials, fixture.directory, ticket,
                        fixture.scope, reopened, session)
                }
                assertEquals(localCashLedgerOwnerSnapshot(), reopened.read(fixture.scope))
            } finally { reopened.clear(fixture.scope) }
            assertNull(fixture.directory.current(ticket))
        } finally {
            withContext(NonCancellable) {
                try { cache.close() } finally { client.api.close(); fixture.clean() }
            }
        }
    }

    @Test fun publicationCancellationWaitsForTheRealMetadataCasAndCredentialLease() = runTest {
        val fixture = Fixture()
        val client = PublicationClient(fixture.scope)
        val ticket = fixture.ticket()
        val cache = fixture.open(ticket)
        val entered = CompletableDeferred<Unit>()
        var publishing: kotlinx.coroutines.Job? = null
        var replacement: Deferred<Unit>? = null
        try {
            cache.replace(fixture.scope, localCashLedgerOwnerSnapshot())
            fixture.beforeCas = { entered.complete(Unit) }
            holdMetadataLock().await()
            publishing = launch {
                client.api.publishVerifiedLedgerSession(client.credentials, fixture.directory, ticket,
                    fixture.scope, cache, publicationSession())
            }
            entered.await()
            replacement = async { client.api.writeToken("new-login") }
            publishing.cancel()
            browserTurn().await()
            assertFalse(publishing.isCompleted)
            assertFalse(replacement.isCompleted)
            assertState(fixture.scope, true, true, true).await()
            assertNull(fixture.directory.current(ticket))
            releaseMetadataLock().await()
            publishing.join(); replacement.await()
            assertTrue(publishing.isCancelled)
            assertEquals(fixture.scope, fixture.directory.current(ticket))
            assertEquals("new-login", client.value)
            assertNull(cache.readOfflineSession(fixture.scope, client.value))
            assertEquals(publicationSession(), cache.readOfflineSession(fixture.scope, client.credentials.token!!))
        } finally {
            withContext(NonCancellable) {
                releaseMetadataLock().await()
                publishing?.cancelAndJoin(); replacement?.cancelAndJoin()
                fixture.beforeCas = null
                try { cache.clear(fixture.scope) } finally { client.api.close(); fixture.clean() }
            }
        }
    }

    private class PublicationClient(scope: LedgerCacheScope) : TokenStore {
        var value = "synthetic-publication-token"
        val credentials = ApiCredentialSnapshot(value, scope.apiOrigin)
        val api = FinanceApiClient({ scope.apiOrigin }, this, MockEngine { error("No HTTP during local publication") })
        override suspend fun get() = value
        override suspend fun set(value: String) { this.value = value }
        override suspend fun clear() = error("No logout during local publication")
    }

    private fun publicationSession() = SessionDto(UserDto("synthetic@example.test"), "workspace",
        listOf(WorkspaceDto("workspace", "Synthetic", Role.EDITOR)))

    @Test fun foreignAndSupersededRegistrationsCannotCreateMaterials() = runTest {
        val fixture = Fixture()
        val ticket = fixture.ticket()
        val foreign = fixture.scope.copy(workspaceId = "foreign")
        try {
            assertFailsWith<IllegalArgumentException> {
                createWebLedgerFactCache(foreign, registration = LedgerSessionRegistration(fixture.scope, fixture.directory, ticket))
            }
            assertState(foreign, false, false, false).await()
            fixture.directory.begin(fixture.scope.apiOrigin)
            assertFailsWith<CancellationException> { fixture.open(ticket) }
            assertState(fixture.scope, false, false, false).await()
            assertTrue(fixture.directory.scopes(fixture.scope.apiOrigin).isEmpty())
        } finally { fixture.clean() }
    }

    @Test fun registrationCommitsUnderTheLeaseBeforeMaterialsExistAndNormalClosePreservesIt() = runTest {
        val fixture = Fixture()
        var observed = false
        fixture.beforeCas = {
            assertState(fixture.scope, false, false, true).await()
            observed = true
        }
        try {
            val cache = fixture.open()
            assertTrue(observed)
            assertEquals(listOf(fixture.scope), fixture.directory.scopes(fixture.scope.apiOrigin))
            assertState(fixture.scope, true, true, true).await()
            cache.close()
            assertState(fixture.scope, true, true, false).await()
            assertEquals(listOf(fixture.scope), fixture.directory.scopes(fixture.scope.apiOrigin))
        } finally { fixture.clean() }
    }

    @Test fun failedRegistrationCreatesNoMaterialsAndReleasesTheUnopenedLease() = runTest {
        val fixture = Fixture()
        val failure = IllegalStateException("synthetic registration failure")
        fixture.beforeCas = { assertState(fixture.scope, false, false, true).await(); throw failure }
        try {
            assertSame(failure, assertFailsWith<IllegalStateException> { fixture.open() })
            assertState(fixture.scope, false, false, false).await()
            assertTrue(fixture.directory.scopes(fixture.scope.apiOrigin).isEmpty())
        } finally { fixture.beforeCas = null; fixture.clean() }
    }

    @Test fun failedForgetKeepsTheErasedScopeLockedUntilTheSameOwnerRetries() = runTest {
        val fixture = Fixture()
        try {
            val cache = fixture.open()
            val failure = IllegalStateException("synthetic forget failure")
            fixture.beforeCas = { throw failure }
            assertSame(failure, assertFailsWith<IllegalStateException> { cache.clear(fixture.scope) })
            assertState(fixture.scope, false, false, true).await()
            assertEquals(listOf(fixture.scope), fixture.directory.scopes(fixture.scope.apiOrigin))
            assertFails { fixture.open() }
            fixture.beforeCas = null
            cache.clear(fixture.scope)
            assertState(fixture.scope, false, false, false).await()
            assertTrue(fixture.directory.scopes(fixture.scope.apiOrigin).isEmpty())
            val fresh = fixture.open()
            val facts = localCashLedgerOwnerSnapshot()
            try {
                fresh.replace(fixture.scope, facts)
                cache.clear(fixture.scope)
                assertEquals(facts, fresh.read(fixture.scope))
            } finally { fresh.clear(fixture.scope) }
        } finally { fixture.beforeCas = null; fixture.clean() }
    }

    @Test fun cancellationWaitsForTheActualRegistrationCasBeforeReleasingTheLease() = runTest {
        val fixture = Fixture()
        val entered = CompletableDeferred<Unit>()
        val ticket = fixture.ticket()
        fixture.beforeCas = { entered.complete(Unit) }
        holdMetadataLock().await()
        val opening = launch { fixture.open(ticket) }
        try {
            entered.await()
            opening.cancel()
            browserTurn().await()
            assertFalse(opening.isCompleted)
            assertState(fixture.scope, false, false, true).await()
            assertTrue(fixture.directory.scopes(fixture.scope.apiOrigin).isEmpty())
            releaseMetadataLock().await()
            opening.join()
            assertTrue(opening.isCancelled)
            assertState(fixture.scope, true, true, false).await()
            assertEquals(listOf(fixture.scope), fixture.directory.scopes(fixture.scope.apiOrigin))
        } finally { releaseMetadataLock().await(); opening.cancelAndJoin(); fixture.beforeCas = null; fixture.clean() }
    }

    @Test fun cancellationWaitsForActualForgetAndOldRetryCannotEraseANewCache() = runTest {
        val fixture = Fixture()
        val cache = fixture.open()
        val entered = CompletableDeferred<Unit>()
        fixture.beforeCas = { entered.complete(Unit) }
        holdMetadataLock().await()
        val clearing = launch { cache.clear(fixture.scope) }
        try {
            entered.await()
            clearing.cancel()
            browserTurn().await()
            assertFalse(clearing.isCompleted)
            assertState(fixture.scope, false, false, true).await()
            releaseMetadataLock().await()
            clearing.join()
            assertTrue(clearing.isCancelled)
            fixture.beforeCas = null
            assertTrue(fixture.directory.scopes(fixture.scope.apiOrigin).isEmpty())
            val fresh = fixture.open()
            val facts = localCashLedgerOwnerSnapshot()
            try {
                fresh.replace(fixture.scope, facts)
                cache.clear(fixture.scope)
                assertEquals(facts, fresh.read(fixture.scope))
            } finally { fresh.clear(fixture.scope) }
        } finally { releaseMetadataLock().await(); clearing.cancelAndJoin(); fixture.beforeCas = null; fixture.clean() }
    }

    @Test fun failedFinalCloseAcknowledgementRetriesOnlyReleaseAfterErasureHasCompleted() = runTest {
        val fixture = Fixture()
        installFinalCloseProbe().await()
        try {
            val cache = fixture.open()
            armFinalCloseFailure().await()
            assertFails { cache.clear(fixture.scope) }
            assertState(fixture.scope, false, false, false).await()
            assertTrue(fixture.directory.scopes(fixture.scope.apiOrigin).isEmpty())
            val fresh = fixture.open()
            val facts = localCashLedgerOwnerSnapshot()
            try {
                fresh.replace(fixture.scope, facts)
                cache.clear(fixture.scope)
                assertEquals(facts, fresh.read(fixture.scope))
            } finally { fresh.clear(fixture.scope) }
        } finally { restoreFinalCloseProbe().await(); fixture.clean() }
    }

    private class Fixture {
        val scope = LedgerCacheScope("https://registration-${Random.nextLong().toString().removePrefix("-")}.example.test",
            ledgerOfflinePrincipal("synthetic@example.test"), "workspace")
        var beforeCas: (suspend () -> Unit)? = null
        private val actual = createPlatformLedgerSessionLocatorStorage()
        val directory = LedgerSessionDirectory(object : LedgerSessionLocatorStorage {
            override suspend fun read(key: String) = actual.read(key)
            override suspend fun compareAndSet(key: String, expected: String?, replacement: String): Boolean {
                beforeCas?.invoke()
                return actual.compareAndSet(key, expected, replacement)
            }
        })
        private var currentTicket: LedgerSessionTicket? = null
        suspend fun ticket(): LedgerSessionTicket = currentTicket ?: LedgerSessionDirectory(actual).begin(scope.apiOrigin).also { currentTicket = it }
        suspend fun open(ticket: LedgerSessionTicket? = null): LedgerStorage = createWebLedgerFactCache(scope,
            registration = LedgerSessionRegistration(scope, directory, ticket ?: ticket()))
        suspend fun clean() {
            beforeCas = null
            cleanProbe(scope.apiOrigin, scope.principal, scope.workspaceId).await()
            removeRecord("ledger-session-current-v1:" + sha256Hex(scope.apiOrigin.encodeToByteArray()))
        }
    }
}

private fun assertState(scope: LedgerCacheScope, key: Boolean, file: Boolean, locked: Boolean): Promise<JsAny?> =
    stateProbe(scope.apiOrigin, scope.principal, scope.workspaceId, key, file, locked)
private fun stateProbe(apiOrigin: String, principal: String, workspaceId: String, key: Boolean, file: Boolean, locked: Boolean): Promise<JsAny?> =
    js("import('/ledger-storage/ledger-lease-probe.mjs').then(module => module.assertState([apiOrigin, principal, workspaceId], key, file, locked))")
private fun cleanProbe(apiOrigin: String, principal: String, workspaceId: String): Promise<JsAny?> =
    js("import('/ledger-storage/connection.mjs').then(module => module.eraseWebLedgerStorage([apiOrigin, principal, workspaceId]))")
private fun holdMetadataLock(): Promise<JsAny?> = js("import('/ledger-storage/ledger-lease-probe.mjs').then(module => module.holdMetadataLock())")
private fun releaseMetadataLock(): Promise<JsAny?> = js("import('/ledger-storage/ledger-lease-probe.mjs').then(module => module.releaseMetadataLock())")
private fun browserTurn(): Promise<JsAny?> = js("new Promise(resolve => setTimeout(() => resolve(null), 30))")
private fun installFinalCloseProbe(): Promise<JsAny?> = js("import('/ledger-storage/ledger-lease-probe.mjs').then(module => module.installFinalCloseProbe())")
private fun armFinalCloseFailure(): Promise<JsAny?> = js("import('/ledger-storage/ledger-lease-probe.mjs').then(module => module.armFinalCloseFailure())")
private fun restoreFinalCloseProbe(): Promise<JsAny?> = js("import('/ledger-storage/ledger-lease-probe.mjs').then(module => module.restoreFinalCloseProbe())")
private fun removeRecord(key: String) { js("localStorage.removeItem(key)") }
