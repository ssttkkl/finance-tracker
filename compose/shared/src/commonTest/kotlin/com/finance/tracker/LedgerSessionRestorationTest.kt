package com.finance.tracker

import com.finance.tracker.business.contracts.SessionDto
import com.finance.tracker.business.contracts.UserDto
import com.finance.tracker.business.contracts.WorkspaceDto
import com.finance.tracker.business.contracts.Role
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.contracts.LedgerVersionDto
import com.finance.tracker.core.TokenStore
import com.finance.tracker.data.*
import com.finance.tracker.domain.LedgerCacheScope
import com.finance.tracker.domain.DomainFailure
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class LedgerSessionRestorationTest {
    private val origin = "https://restoration.example.test"
    private val session = SessionDto(UserDto("synthetic@example.test"), "workspace",
        listOf(WorkspaceDto("workspace", "Synthetic", Role.EDITOR)))
    private val scope = LedgerCacheScope(origin, ledgerOfflinePrincipal(session.user.email), "workspace")
    private val headers = headersOf(HttpHeaders.ContentType, "application/json")

    @Test fun aVerifiedSessionWithNoAccessibleWorkspaceRemovesItsHistoricalCacheReferences() = runTest {
        val f = fixture(disconnected = false)
        val other = scope.copy(principal = ledgerOfflinePrincipal("other@example.test"))
        f.directory.register(f.ticket, other)
        f.online = session.copy(activeWorkspaceId = null, workspaces = emptyList())
        try {
            assertEquals(f.online, f.operation.restore())
            assertEquals(listOf(other), f.directory.scopes(origin))
            assertTrue(f.modes.isEmpty())
            assertEquals(1, f.requests)
        } finally { f.operation.close(); f.api.close() }
    }

    @Test fun verifiedRevocationsFinishBeforeOpeningOrPublishingTheAccessibleLedger() = runTest {
        val f = fixture(disconnected = false)
        val revoked = scope.copy(workspaceId = "revoked")
        val other = revoked.copy(principal = ledgerOfflinePrincipal("other@example.test"))
        f.directory.register(f.ticket, revoked); f.directory.register(f.ticket, other)
        f.beforeOpenReturn = {
            assertEquals(listOf(revoked), f.erased)
            assertEquals(listOf(scope, other), f.directory.scopes(origin))
        }
        try {
            assertEquals(session, f.operation.restore())
            assertEquals(listOf(revoked), f.createdErasures)
            assertEquals(scope, f.directory.current(f.ticket))
            assertNotNull(f.operation.page())
        } finally { f.operation.close(); f.api.close() }
    }

    @Test fun historicalOfflineIdentityNeverAuthorizesRevocationCleanup() = runTest {
        val f = fixture(disconnected = true)
        val historical = scope.copy(workspaceId = "not-in-old-session")
        f.directory.register(f.ticket, historical)
        try {
            assertEquals(session, f.operation.restore())
            assertTrue(f.createdErasures.isEmpty())
            assertEquals(listOf(scope, historical), f.directory.scopes(origin))
            assertNotNull(f.operation.page())
        } finally { f.operation.close(); f.api.close() }
    }

    @Test fun malformedVerifiedWorkspaceCannotTriggerAnyDestructiveCleanup() = runTest {
        val f = fixture(disconnected = false)
        f.online = session.copy(workspaces = emptyList())
        try {
            assertFailsWith<IllegalStateException> { f.operation.restore() }
            assertTrue(f.createdErasures.isEmpty())
            assertEquals(listOf(scope), f.directory.scopes(origin))
            assertTrue(f.modes.isEmpty())
        } finally { f.operation.close(); f.api.close() }
    }

    @Test fun failedRevocationCleanupPreventsOpeningAndRetainsItsObjectForCloseRetry() = runTest {
        val f = fixture(disconnected = false)
        val revoked = scope.copy(workspaceId = "revoked")
        f.directory.register(f.ticket, revoked)
        val failure = IllegalStateException("synthetic partial cleanup")
        f.beforeErase = { throw failure }
        try {
            val actual = assertFailsWith<IllegalStateException> { f.operation.restore() }
            assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === failure })
            assertNull(f.operation.page()); assertTrue(f.modes.isEmpty())
            assertEquals(1, f.requests)
            assertFailsWith<IllegalStateException> { f.operation.close() }
            assertEquals(listOf(revoked), f.createdErasures)
            assertTrue(f.erased.isEmpty())
            f.beforeErase = null; f.operation.close()
            assertEquals(listOf(revoked), f.createdErasures)
            assertEquals(listOf(revoked), f.erased)
            assertEquals(0, f.storage.closes)
        } finally { f.beforeErase = null; f.operation.close(); f.api.close() }
    }

    @Test fun closeFencesPublicationWhileWaitingForSelectedRevocationCleanup() = runTest {
        val f = fixture(disconnected = false)
        val revoked = scope.copy(workspaceId = "revoked")
        val unstarted = scope.copy(workspaceId = "unstarted")
        f.directory.register(f.ticket, revoked)
        f.directory.register(f.ticket, unstarted)
        val entered = CompletableDeferred<Unit>(); val proceed = CompletableDeferred<Unit>()
        f.beforeErase = { entered.complete(Unit); proceed.await() }
        val restoring = async { assertFailsWith<CancellationException> { f.operation.restore() } }
        var closing: Deferred<Unit>? = null
        try {
            entered.await()
            closing = async { f.operation.close() }
            yield(); assertFalse(closing.isCompleted)
            proceed.complete(Unit); restoring.await(); closing.await()
            assertEquals(listOf(revoked), f.erased)
            assertEquals(listOf(revoked), f.createdErasures)
            assertEquals(listOf(scope, unstarted), f.directory.scopes(origin))
            assertTrue(f.modes.isEmpty()); assertEquals(1, f.requests)
        } finally { proceed.complete(Unit); restoring.cancelAndJoin(); closing?.cancelAndJoin(); f.operation.close(); f.api.close() }
    }

    @Test fun closeCanFinishAnErasureRejectedByALaterOperationWithoutRevivingTheCleanup() = runTest {
        val f = fixture(disconnected = false)
        val revoked = scope.copy(workspaceId = "revoked")
        f.directory.register(f.ticket, revoked)
        f.beforeEraseAcquire = { f.directory.begin(origin) }
        try {
            assertFailsWith<CancellationException> { f.operation.restore() }
            f.operation.close(); f.operation.close()
            assertTrue(f.erased.isEmpty())
            assertEquals(listOf(revoked), f.createdErasures)
            assertEquals(listOf(scope, revoked), f.directory.scopes(origin))
        } finally { f.operation.close(); f.api.close() }
    }

    @Test fun closeFinishesOnlyTheStartedPartialScopeAfterSupersession() = runTest {
        val f = fixture(disconnected = false)
        val first = scope.copy(workspaceId = "first-revoked"); val second = scope.copy(workspaceId = "second-revoked")
        f.directory.register(f.ticket, first); f.directory.register(f.ticket, second)
        f.beforeErase = { error("partial first erasure") }
        try {
            assertFailsWith<IllegalStateException> { f.operation.restore() }
            f.directory.begin(origin); f.beforeErase = null
            f.operation.close(); f.operation.close()
            assertEquals(listOf(first), f.createdErasures)
            assertEquals(listOf(first), f.erased)
            assertEquals(listOf(scope, second), f.directory.scopes(origin))
        } finally { f.beforeErase = null; f.operation.close(); f.api.close() }
    }

    @Test fun everyVerifiedWorkspaceIsValidatedBeforeRemovingAnyHistoricalMaterial() = runTest {
        val surrogate = "invalid" + charArrayOf(0xD800.toChar()).concatToString()
        for (invalid in listOf("", "  ", surrogate)) for (active in listOf(true, false)) {
            val f = fixture(disconnected = false)
            f.online = session.copy(activeWorkspaceId = if (active) invalid else null,
                workspaces = listOf(WorkspaceDto(invalid, "Synthetic", Role.EDITOR)))
            // Transport text must escape the lone surrogate; raw UTF-8 would replace it before JSON parsing.
            f.sessionBody = Json.encodeToString(f.online).replace(charArrayOf(0xD800.toChar()).concatToString(), "\\ud800")
            try {
                assertFails { f.operation.restore() }
                assertTrue(f.createdErasures.isEmpty(), "Invalid workspace codepoints ${invalid.map { it.code }}, active=$active")
                assertEquals(listOf(scope), f.directory.scopes(origin))
            } finally { f.operation.close(); f.api.close() }
        }
    }

    @Test fun disconnectedRestorationLocatesTheBoundIdentityAndBrowsesWithoutMoreHttp() = runTest {
        val f = fixture(disconnected = true)
        try {
            assertEquals(session, f.operation.restore())
            assertEquals(listOf(true), f.modes)
            assertEquals(17, assertNotNull(f.operation.options()).recordTypes.size)
            assertEquals(0L, assertNotNull(f.operation.categories()).revision)
            assertEquals(listOf("Cash"), assertNotNull(f.operation.accounts()).map { it.name })
            assertNotNull(f.operation.page())
            assertEquals(1, f.requests)
            f.operation.close()
            assertEquals(1, f.storage.closes)
            assertNotNull(f.storage.facts)
        } finally { f.operation.close(); f.api.close() }
    }

    @Test fun onlineRestorationRefreshesFactsBeforePublishingTheVerifiedIdentity() = runTest {
        val f = fixture(disconnected = false, published = false)
        f.storage.facts = null; f.storage.saved = null
        try {
            assertEquals(session, f.operation.restore())
            assertEquals(listOf(false), f.modes)
            assertEquals(3, f.requests)
            assertEquals(f.snapshot, f.storage.facts)
            assertEquals(session, f.storage.saved)
            assertEquals(scope, f.directory.current(f.ticket))
            assertNotNull(f.operation.page())
            assertEquals(3, f.requests)
        } finally { f.operation.close(); f.api.close() }
    }

    @Test fun anHttpRejectionCannotOpenOrPublishCachedIdentity() = runTest {
        val f = fixture(disconnected = false)
        f.status = HttpStatusCode.Forbidden
        try {
            assertEquals(403, assertFailsWith<ApiFailure> { f.operation.restore() }.status)
            assertTrue(f.modes.isEmpty())
            assertNull(f.operation.page())
            assertNull(f.operation.categories()); assertNull(f.operation.options()); assertNull(f.operation.accounts())
            assertEquals(1, f.requests)
        } finally { f.operation.close(); f.api.close() }
    }

    @Test fun missingCurrentReferenceCannotSelectAnUnrelatedCachedUser() = runTest {
        val f = fixture(disconnected = true, published = false)
        try {
            assertFailsWith<IOException> { f.operation.restore() }
            assertTrue(f.modes.isEmpty())
            assertEquals(1, f.requests)
        } finally { f.operation.close(); f.api.close() }
    }

    @Test fun noBoundIdentityKeepsTheOriginalNetworkFailureAndTheOpenedOwner() = runTest {
        val f = fixture(disconnected = true)
        f.storage.saved = null
        try {
            assertFailsWith<IOException> { f.operation.restore() }
            assertEquals(0, f.storage.closes)
            assertNull(f.operation.page())
            f.operation.close()
            assertEquals(1, f.storage.closes)
        } finally { f.operation.close(); f.api.close() }
    }

    @Test fun anInvalidVerifiedWorkspaceIsRejectedBeforeOpeningMaterials() = runTest {
        val f = fixture(disconnected = false)
        f.online = session.copy(workspaces = emptyList())
        try {
            assertFailsWith<IllegalStateException> { f.operation.restore() }
            assertTrue(f.modes.isEmpty())
            assertEquals(1, f.requests)
        } finally { f.operation.close(); f.api.close() }
    }

    @Test fun aVerifiedSessionWithoutAnActiveWorkspaceDoesNotCreateALedger() = runTest {
        val f = fixture(disconnected = false)
        f.online = session.copy(activeWorkspaceId = null)
        try {
            assertEquals(f.online, f.operation.restore())
            assertTrue(f.modes.isEmpty())
            assertNull(f.operation.page())
            assertEquals(1, f.requests)
        } finally { f.operation.close(); f.api.close() }
    }

    @Test fun closeDoesNotWaitForAuthenticationAndLateSuccessCannotOpenOrPublish() = runTest {
        val f = fixture(disconnected = false)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.beforeSession = { entered.complete(Unit); release.await() }
        try {
            val restoring = async { assertFailsWith<CancellationException> { f.operation.restore() } }
            entered.await()
            f.operation.close()
            assertTrue(f.modes.isEmpty())
            release.complete(Unit); restoring.await()
            assertTrue(f.modes.isEmpty())
            assertFailsWith<CancellationException> { f.operation.page() }
        } finally { release.complete(Unit); f.operation.close(); f.api.close() }
    }

    @Test fun aLaterLoginOrDirectoryOperationFencesAuthenticationAndPages() = runTest {
        for (changeToken in listOf(false, true)) {
            val f = fixture(disconnected = true)
            try {
                assertEquals(session, f.operation.restore())
                if (changeToken) f.api.writeToken("later-token") else f.directory.begin(origin)
                assertFailsWith<CancellationException> { f.operation.page() }
            } finally { f.operation.close(); f.api.close() }
        }
    }

    @Test fun cancelledOpenAndFailedCloseKeepTheSameActualOwnerUntilCloseSucceeds() = runTest {
        val f = fixture(disconnected = true)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.beforeOpenReturn = { entered.complete(Unit); release.await() }
        try {
            val restoring = launch { f.operation.restore() }
            entered.await(); restoring.cancel(); release.complete(Unit); restoring.join()
            assertTrue(restoring.isCancelled)
            f.storage.failClose = true
            assertFailsWith<IllegalStateException> { f.operation.close() }
            assertEquals(1, f.storage.closes)
            f.storage.failClose = false
            f.operation.close()
            assertEquals(2, f.storage.closes)
            assertEquals(listOf(true), f.modes)
        } finally { release.complete(Unit); f.storage.failClose = false; f.operation.close(); f.api.close() }
    }

    @Test fun anOperationAuthenticatesOnlyOnceAndCannotBrowseBeforeCompletion() = runTest {
        val f = fixture(disconnected = true)
        try {
            assertNull(f.operation.page())
            assertEquals(session, f.operation.restore())
            assertFailsWith<IllegalStateException> { f.operation.restore() }
            assertEquals(1, f.requests)
        } finally { f.operation.close(); f.api.close() }
    }

    @Test fun aFactDownloadIoFailureAfterVerifiedAuthenticationCannotReadOldIdentity() = runTest {
        val f = fixture(disconnected = false)
        f.storage.facts = null
        f.beforeSnapshot = { throw IOException("synthetic snapshot transport failure") }
        try {
            val actual = assertFailsWith<DomainFailure> { f.operation.restore() }
            assertEquals("network_unavailable", actual.code)
            assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it is IOException })
            assertEquals(0, f.storage.identityReads)
            assertEquals(listOf(false), f.modes)
            assertNull(f.operation.page())
            assertEquals(3, f.requests)
        } finally { f.operation.close(); f.api.close() }
    }

    @Test fun credentialOrGenerationChangesDuringAuthenticationRejectTheLateResponse() = runTest {
        for (changeToken in listOf(false, true)) {
            val f = fixture(disconnected = false)
            f.beforeSession = { if (changeToken) f.api.writeToken("later-token") else f.directory.begin(origin) }
            try {
                assertFailsWith<CancellationException> { f.operation.restore() }
                assertTrue(f.modes.isEmpty())
                assertEquals(1, f.requests)
            } finally { f.operation.close(); f.api.close() }
        }
    }

    @Test fun aSessionWithoutWorkspaceCannotBypassTheLateCloseOrCredentialFence() = runTest {
        for (closeOperation in listOf(false, true)) {
            val f = fixture(disconnected = false)
            f.online = session.copy(activeWorkspaceId = null)
            f.beforeSession = { if (closeOperation) f.operation.close() else f.api.writeToken("later-token") }
            try {
                assertFailsWith<CancellationException> { f.operation.restore() }
                assertTrue(f.modes.isEmpty())
                assertEquals(1, f.requests)
            } finally { f.operation.close(); f.api.close() }
        }
    }

    private suspend fun fixture(disconnected: Boolean, published: Boolean = true): Fixture {
        val f = Fixture(disconnected)
        val old = f.directory.begin(origin)
        if (published) { f.directory.register(old, scope); f.directory.publish(old, scope) }
        f.ticket = f.directory.begin(origin)
        f.operation = LedgerSessionRestoration(f.api, ApiCredentialSnapshot("original-token", origin),
            f.directory, f.ticket, createErasure = { selected, directory, ticket ->
                f.createdErasures += selected
                LedgerCacheErasureOwner(acquire = { f.beforeEraseAcquire?.invoke(); Any() },
                    beforeErasure = ledgerCacheErasureCheck(selected, directory, ticket), erase = {
                        f.beforeErase?.invoke(selected)
                        directory.forget(selected); f.erased += selected
                    }, release = {})
            }) { selected, existingOnly, registration ->
            assertEquals(scope, selected)
            f.modes += existingOnly
            registration.beforeOpen()
            f.beforeOpenReturn?.invoke()
            f.storage
        }
        return f
    }

    private inner class Fixture(val disconnected: Boolean) {
        val snapshot = localCashLedgerOwnerSnapshot()
        val storage = Storage(snapshot, session)
        var requests = 0
        var status = HttpStatusCode.OK
        var online = session
        var sessionBody: String? = null
        var beforeSession: (suspend () -> Unit)? = null
        var beforeSnapshot: (suspend () -> Unit)? = null
        var beforeOpenReturn: (suspend () -> Unit)? = null
        var beforeErase: (suspend (LedgerCacheScope) -> Unit)? = null
        var beforeEraseAcquire: (suspend () -> Unit)? = null
        val createdErasures = mutableListOf<LedgerCacheScope>()
        val erased = mutableListOf<LedgerCacheScope>()
        val modes = mutableListOf<Boolean>()
        val directory = LedgerSessionDirectory(object : LedgerSessionLocatorStorage {
            var value: String? = null
            override suspend fun read(key: String) = value
            override suspend fun compareAndSet(key: String, expected: String?, replacement: String): Boolean {
                if (value != expected) return false
                value = replacement; return true
            }
        })
        val api = FinanceApiClient({ origin }, object : TokenStore {
            var value: String? = "original-token"
            override suspend fun get() = value
            override suspend fun set(value: String) { this.value = value }
            override suspend fun clear() { value = null }
        }, MockEngine { request ->
            requests++
            assertEquals("Bearer original-token", request.headers[HttpHeaders.Authorization])
            when (request.url.encodedPath) {
                "/api/v1/auth/session" -> {
                    beforeSession?.invoke()
                    if (disconnected) throw IOException("synthetic disconnected session transport")
                    respond(sessionBody ?: Json.encodeToString(online), status, headers)
                }
                "/api/v1/ledger/version" -> respond(Json.encodeToString(LedgerVersionDto(snapshot.ledgerSeq)), headers = headers)
                "/api/v1/ledger/snapshot" -> { beforeSnapshot?.invoke(); respond(Json.encodeToString(snapshot), headers = headers) }
                else -> error("Unexpected request: ${request.url.encodedPath}")
            }
        })
        lateinit var ticket: LedgerSessionTicket
        lateinit var operation: LedgerSessionRestoration
    }

    private class Storage(var facts: LedgerSnapshotDto?, var saved: SessionDto?) : LedgerStorage {
        var closes = 0
        var identityReads = 0
        var failClose = false
        override suspend fun read(scope: LedgerCacheScope) = facts
        override suspend fun replace(scope: LedgerCacheScope, snapshot: LedgerSnapshotDto) { facts = snapshot }
        override suspend fun readOfflineSession(scope: LedgerCacheScope, token: String): SessionDto? {
            identityReads++
            return saved.takeIf { facts != null && token == "original-token" }
        }
        override suspend fun saveOfflineSession(scope: LedgerCacheScope, token: String, session: SessionDto) { check(facts != null); saved = session }
        override suspend fun close() { closes++; check(!failClose) { "synthetic unconfirmed close" } }
        override suspend fun clear(scope: LedgerCacheScope) = error("No implicit erasure")
    }
}
