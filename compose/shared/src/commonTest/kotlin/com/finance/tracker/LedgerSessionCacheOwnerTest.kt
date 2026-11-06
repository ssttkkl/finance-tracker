package com.finance.tracker

import com.finance.tracker.business.contracts.CashFiltersDto
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.contracts.LedgerVersionDto
import com.finance.tracker.business.contracts.SessionDto
import com.finance.tracker.business.contracts.Role
import com.finance.tracker.business.contracts.UserDto
import com.finance.tracker.business.contracts.WorkspaceDto
import com.finance.tracker.core.TokenStore
import com.finance.tracker.data.*
import com.finance.tracker.domain.DomainFailure
import com.finance.tracker.domain.FailureCategory
import com.finance.tracker.domain.LedgerCacheScope
import com.finance.tracker.domain.LedgerSnapshotSource
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.*

class LedgerSessionCacheOwnerTest {
    private val origin = "https://owner.example.test"
    private val session = SessionDto(UserDto("synthetic@example.test"), "workspace",
        listOf(WorkspaceDto("workspace", "Synthetic", Role.EDITOR)))
    private val scope = LedgerCacheScope(origin, ledgerOfflinePrincipal(session.user.email), "workspace")

    @Test fun offlineRecoveryOnlyOpensExistingMaterialsAndCanBrowseWithoutHttp() = runTest {
        val f = fixture()
        try {
            assertEquals(session, f.owner.readOfflineSession())
            assertEquals(listOf(true), f.openModes)
            assertEquals(0, f.source.reads)
            assertEquals(17, assertNotNull(f.owner.options()).recordTypes.size)
            assertEquals(0L, assertNotNull(f.owner.categories()).revision)
            assertEquals(listOf("Cash"), assertNotNull(f.owner.accounts()).map { it.name })
            assertEquals(f.snapshot.ledgerSeq, f.owner.page(CashFiltersDto())?.projectionVersion?.toString())
            assertEquals(0, f.source.reads)
            f.owner.close()
            assertEquals(1, f.storage.closes)
            assertEquals(f.snapshot, f.storage.facts)
            assertEquals(scope, f.directory.current(f.ticket))
            assertFailsWith<CancellationException> { f.owner.readOfflineSession() }
        } finally { f.owner.close(); f.api.close() }
    }

    @Test fun onlineRefreshCommitsFactsThenPublishesTheOriginalBoundSession() = runTest {
        val f = fixture(published = false, hasFacts = false)
        try {
            f.owner.refresh(session)
            assertEquals(listOf(false), f.openModes)
            assertEquals(2, f.source.reads)
            assertEquals(f.snapshot, f.storage.facts)
            assertEquals(session, f.storage.saved)
            assertEquals("original-token", f.storage.savedToken)
            assertEquals(scope, f.directory.current(f.ticket))
            assertNotNull(f.owner.page())
        } finally { f.owner.close(); f.api.close() }
    }

    @Test fun missingRecoveryIdentityDoesNotFabricateAnAuthenticatedSession() = runTest {
        val f = fixture()
        f.storage.saved = null
        try {
            assertNull(f.owner.readOfflineSession())
            assertNull(f.owner.page())
            assertEquals(0, f.source.reads)
            assertEquals(0, f.storage.closes)
        } finally { f.owner.close(); f.api.close() }
        assertEquals(1, f.storage.closes)
    }

    @Test fun aBoundIdentityLostAfterRecoveryCannotLeaveBrowsableFacts() = runTest {
        val f = fixture()
        try {
            assertEquals(session, f.owner.readOfflineSession())
            assertNotNull(f.owner.page())
            f.storage.saved = null
            assertNull(f.owner.page())
            assertNull(f.owner.categories()); assertNull(f.owner.options()); assertNull(f.owner.accounts())
            assertEquals(f.snapshot, f.storage.facts)
            assertEquals(0, f.storage.clears)
        } finally { f.owner.close(); f.api.close() }
    }

    @Test fun staleCredentialsAndDirectoryTicketsCannotOpenOrReadACache() = runTest {
        for (changeToken in listOf(true, false)) {
            val f = fixture()
            try {
                if (changeToken) f.api.writeToken("later-token") else f.directory.begin(origin)
                assertFailsWith<CancellationException> { f.owner.readOfflineSession() }
                assertTrue(f.openModes.isEmpty())
            } finally { f.owner.close(); f.api.close() }
        }
    }

    @Test fun currentReferenceMustStillIdentifyThisScopeForOfflineRecoveryAndPages() = runTest {
        val f = fixture()
        try {
            assertEquals(session, f.owner.readOfflineSession())
            val other = scope.copy(workspaceId = "other")
            f.directory.register(f.ticket, other); f.directory.publish(f.ticket, other)
            assertFailsWith<CancellationException> { f.owner.page() }
            assertFailsWith<CancellationException> { f.owner.readOfflineSession() }
            assertEquals(0, f.source.reads)
        } finally { f.owner.close(); f.api.close() }
    }

    @Test fun tokenChangesDuringReadCannotReturnTheOldIdentityOrPage() = runTest {
        val f = fixture()
        try {
            f.storage.afterReadSession = { f.api.writeToken("later-token") }
            assertFailsWith<CancellationException> { f.owner.readOfflineSession() }
            assertFailsWith<CancellationException> { f.owner.page() }
            assertEquals(1, f.openModes.size)
        } finally { f.owner.close(); f.api.close() }
    }

    @Test fun cancellationDuringOpenKeepsTheAcquiredStorageOwnedUntilExplicitClose() = runTest {
        val f = fixture()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.beforeOpenReturn = { entered.complete(Unit); release.await() }
        try {
            val read = launch { f.owner.readOfflineSession() }
            entered.await(); read.cancel(); yield()
            assertFalse(read.isCompleted)
            assertEquals(0, f.storage.closes)
            release.complete(Unit); read.join()
            assertTrue(read.isCancelled)
            f.owner.close()
            assertEquals(1, f.storage.closes)
        } finally { release.complete(Unit); f.owner.close(); f.api.close() }
    }

    @Test fun closeFencesAPendingOpenAndWaitsForTheActualStorageClose() = runTest {
        val f = fixture()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val closing = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        f.beforeOpenReturn = { entered.complete(Unit); release.await() }
        f.storage.beforeClose = { closing.complete(Unit); finish.await() }
        try {
            val read = async { assertFailsWith<CancellationException> { f.owner.readOfflineSession() } }
            entered.await()
            val close = launch { f.owner.close() }
            yield(); assertFalse(close.isCompleted)
            release.complete(Unit); read.await(); closing.await()
            assertFalse(close.isCompleted)
            assertFailsWith<CancellationException> { f.owner.page() }
            finish.complete(Unit); close.join()
            assertEquals(1, f.storage.closes)
        } finally { release.complete(Unit); finish.complete(Unit); f.owner.close(); f.api.close() }
    }

    @Test fun failedCloseRetainsTheSameOwnerAndRetriesOnlyItsOriginalAction() = runTest {
        val f = fixture()
        val failure = IllegalStateException("synthetic close failure")
        try {
            f.owner.readOfflineSession()
            f.storage.beforeClose = { throw failure }
            val actual = assertFailsWith<IllegalStateException> { f.owner.close() }
            assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === failure })
            assertFailsWith<CancellationException> { f.owner.page() }
            f.storage.beforeClose = null
            f.owner.close(); f.owner.close()
            assertEquals(2, f.storage.closes)
            assertEquals(0, f.storage.clears)
            assertEquals(1, f.openModes.size)
        } finally { f.owner.close(); f.api.close() }
    }

    @Test fun closeDoesNotWaitForNetworkAndLateRejectionCannotErasePreservedFacts() = runTest {
        val f = fixture()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.source.beforeRead = { entered.complete(Unit); release.await(); throw DomainFailure("forbidden", 403, FailureCategory.RECOVERABLE) }
        try {
            val refresh = async { assertFailsWith<CancellationException> { f.owner.refresh(session) } }
            entered.await(); f.owner.close()
            assertEquals(1, f.storage.closes)
            assertEquals(0, f.storage.clears)
            release.complete(Unit); refresh.await()
            assertEquals(f.snapshot, f.storage.facts)
            assertEquals(0, f.storage.clears)
        } finally { release.complete(Unit); f.owner.close(); f.api.close() }
    }

    @Test fun revocationCleanupFailureIsRetainedAndCloseFinishesItsChosenErasure() = runTest {
        val f = fixture()
        val cleanup = IllegalStateException("synthetic erasure failure")
        f.source.beforeRead = { throw DomainFailure("forbidden", 403, FailureCategory.RECOVERABLE) }
        f.storage.beforeClear = { throw cleanup }
        try {
            val actual = assertFailsWith<IllegalStateException> { f.owner.refresh(session) }
            assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === cleanup })
            f.storage.beforeClear = null
            f.owner.close()
            assertEquals(2, f.storage.clears)
            assertEquals(0, f.storage.closes)
            assertNull(f.storage.facts)
        } finally { f.owner.close(); f.api.close() }
    }

    @Test fun aNewLoginDuringRefreshCannotPublishTheOldIdentity() = runTest {
        val f = fixture(published = false, hasFacts = false)
        f.source.beforeRead = { f.api.writeToken("later-token") }
        try {
            assertFailsWith<CancellationException> { f.owner.refresh(session) }
            assertNull(f.storage.saved)
            assertNull(f.directory.current(f.ticket))
        } finally { f.owner.close(); f.api.close() }
    }

    private suspend fun fixture(published: Boolean = true, hasFacts: Boolean = true): Fixture {
        val f = Fixture()
        f.ticket = f.directory.begin(origin)
        f.directory.register(f.ticket, scope)
        if (published) f.directory.publish(f.ticket, scope)
        f.storage.facts = f.snapshot.takeIf { hasFacts }
        f.storage.saved = session.takeIf { published }
        f.owner = LedgerSessionCacheOwner(f.api, f.directory, f.ticket, scope,
            ApiCredentialSnapshot("original-token", origin), f.source) { existingOnly, registration ->
            f.openModes += existingOnly
            registration.beforeOpen()
            f.beforeOpenReturn?.invoke()
            f.storage
        }
        return f
    }

    private inner class Fixture {
        val snapshot = localCashLedgerOwnerSnapshot()
        val storage = Storage()
        val source = Source(snapshot)
        val directory = LedgerSessionDirectory(object : LedgerSessionLocatorStorage {
            var value: String? = null
            override suspend fun read(key: String) = value
            override suspend fun compareAndSet(key: String, expected: String?, replacement: String): Boolean {
                if (value != expected) return false
                value = replacement
                return true
            }
        })
        val api = FinanceApiClient({ origin }, object : TokenStore {
            var value = "original-token"
            override suspend fun get() = value
            override suspend fun set(value: String) { this.value = value }
            override suspend fun clear() = error("No implicit logout")
        }, MockEngine { error("No direct HTTP from this protocol fixture") })
        val openModes = mutableListOf<Boolean>()
        var beforeOpenReturn: (suspend () -> Unit)? = null
        lateinit var owner: LedgerSessionCacheOwner
        lateinit var ticket: LedgerSessionTicket
    }

    private class Source(private val snapshot: LedgerSnapshotDto) : LedgerSnapshotSource {
        var reads = 0
        var beforeRead: (suspend () -> Unit)? = null
        override suspend fun version(workspaceId: String): LedgerVersionDto { reads++; beforeRead?.invoke(); return LedgerVersionDto(snapshot.ledgerSeq) }
        override suspend fun snapshot(workspaceId: String): LedgerSnapshotDto { reads++; beforeRead?.invoke(); return snapshot }
    }

    private class Storage : LedgerStorage {
        var facts: LedgerSnapshotDto? = null
        var saved: SessionDto? = null
        var savedToken: String? = "original-token"
        var closes = 0
        var clears = 0
        var beforeClose: (suspend () -> Unit)? = null
        var beforeClear: (suspend () -> Unit)? = null
        var afterReadSession: (suspend () -> Unit)? = null
        override suspend fun read(scope: LedgerCacheScope) = facts
        override suspend fun replace(scope: LedgerCacheScope, snapshot: LedgerSnapshotDto) { facts = snapshot }
        override suspend fun readOfflineSession(scope: LedgerCacheScope, token: String): SessionDto? {
            val result = saved.takeIf { facts != null && token == savedToken }
            afterReadSession?.invoke()
            return result
        }
        override suspend fun saveOfflineSession(scope: LedgerCacheScope, token: String, session: SessionDto) {
            check(facts != null)
            saved = session; savedToken = token
        }
        override suspend fun close() { closes++; beforeClose?.invoke() }
        override suspend fun clear(scope: LedgerCacheScope) { clears++; beforeClear?.invoke(); facts = null; saved = null }
    }
}
