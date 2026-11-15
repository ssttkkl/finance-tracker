package com.finance.tracker

import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.contracts.Role
import com.finance.tracker.business.contracts.SessionDto
import com.finance.tracker.business.contracts.UserDto
import com.finance.tracker.business.contracts.WorkspaceDto
import com.finance.tracker.data.*
import com.finance.tracker.domain.LedgerCacheScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import kotlin.test.*

class LedgerSessionTokenEvictionTest {
    private val origin = "https://expiry.example.test"
    private val firstUser = ledgerOfflinePrincipal("first@example.test")
    private val secondUser = ledgerOfflinePrincipal("second@example.test")
    private val first = LedgerCacheScope(origin, firstUser, "first")
    private val second = first.copy(workspaceId = "second")
    private val otherUser = first.copy(principal = secondUser)
    private val foreign = first.copy(apiOrigin = "https://foreign.example.test")

    private class Locator : LedgerSessionLocatorStorage {
        private val values = mutableMapOf<String, String>()
        override suspend fun read(key: String) = values[key]
        override suspend fun compareAndSet(key: String, expected: String?, replacement: String): Boolean {
            if (values[key] != expected) return false
            values[key] = replacement
            return true
        }
    }

    private inner class Fixture {
        val directory = LedgerSessionDirectory(Locator())
        val bindings = mutableMapOf<LedgerCacheScope, String>()
        val erased = mutableListOf<LedgerCacheScope>()
        val created = mutableListOf<LedgerCacheScope>()
        var probes = 0
        var closes = 0
        var unreadable = false
        var readFailure: Throwable? = null
        var beforeErase: (suspend (LedgerCacheScope) -> Unit)? = null

        suspend fun seed() {
            val ticket = directory.begin(origin)
            listOf(first, second, otherUser).forEach { directory.register(ticket, it) }
        }

        val open: suspend (LedgerCacheScope, LedgerSessionDirectory, LedgerSessionTicket) -> LedgerStorage =
            { scope, _, _ ->
                probes++
                if (unreadable) error("ledger_session_storage_unavailable")
                object : LedgerStorage {
                    var selected = false
                    var terminated = false
                    override val imports: com.finance.tracker.data.LedgerImportBatches get() = error("No import file access in this fixture")
                    override suspend fun read(scope: LedgerCacheScope): LedgerSnapshotDto? = null
                    override suspend fun replace(scope: LedgerCacheScope, snapshot: LedgerSnapshotDto) = error("read-only probe")
                    override suspend fun clear(scope: LedgerCacheScope) {
                        if (!selected) { selected = true; created += scope }
                        if (!terminated) { terminated = true; closes++ }
                        beforeErase?.invoke(scope)
                        directory.forget(scope); erased += scope
                    }
                    override suspend fun close() { if (!terminated) { terminated = true; closes++ } }
                    override suspend fun readOfflineSession(scope: LedgerCacheScope, token: String): SessionDto? {
                        readFailure?.let { throw it }
                        return if (bindings[scope] == token) SessionDto(
                            UserDto("user@example.test"), scope.workspaceId,
                            listOf(WorkspaceDto(scope.workspaceId, "Workspace", Role.ADMIN)),
                        ) else null
                    }
                    override suspend fun saveOfflineSession(scope: LedgerCacheScope, token: String, session: SessionDto) =
                        error("read-only probe")
                }
            }

        fun eviction(token: String) = LedgerSessionTokenEviction(directory, origin, token, open)
    }

    @Test fun erasesOnlyTheScopesBoundToThatToken() = runTest {
        val f = Fixture(); f.seed()
        f.bindings[first] = "expired"; f.bindings[second] = "expired"; f.bindings[otherUser] = "other"
        f.eviction("expired").clear()
        assertEquals(listOf(first, second), f.erased)
        assertEquals(listOf(otherUser), f.directory.scopes(origin))
        assertEquals(3, f.probes); assertEquals(3, f.closes)
        assertTrue(f.directory.scopes(foreign.apiOrigin).isEmpty())
    }

    @Test fun rejectingAnOldTokenDoesNotSupersedeTheCurrentUnrelatedSession() = runTest {
        val f = Fixture(); f.seed()
        f.bindings[first] = "expired"; f.bindings[second] = "other"; f.bindings[otherUser] = "other"
        val current = f.directory.begin(origin)
        f.directory.publish(current, otherUser)
        f.eviction("expired").clear()
        assertEquals(listOf(first), f.erased)
        assertTrue(f.directory.isCurrent(current))
        assertEquals(otherUser, f.directory.current(current))
    }

    @Test fun aNewIdentityBeforeTheErasureLeaseStillSupersedesTheObservedCleanupTicket() = runTest {
        val f = Fixture(); f.seed()
        f.bindings[first] = "expired"; f.bindings[second] = "other"; f.bindings[otherUser] = "other"
        var releases = 0
        val eviction = LedgerSessionTokenEviction(f.directory, origin, "expired", openCache = { scope, directory, ticket ->
            val storage = f.open(scope, directory, ticket)
            val later = directory.begin(origin)
            directory.publish(later, scope)
            f.bindings[scope] = "new-token"
            object : LedgerStorage by storage {
                override suspend fun close() { storage.close(); releases++ }
            }
        })
        assertFailsWith<CancellationException> { eviction.clear() }
        assertTrue(eviction.superseded())
        assertEquals("new-token", f.bindings[first]); assertTrue(f.erased.isEmpty())
        eviction.close(); assertEquals(1, releases)
        assertEquals(3, f.directory.scopes(origin).size)
    }

    @Test fun tokenEvidenceAndErasureRetainTheSameLeaseAgainstASameGenerationIdentityWrite() = runTest {
        val f = Fixture()
        val current = f.directory.begin(origin)
        f.directory.register(current, first); f.directory.publish(current, first)
        val lease = Mutex(); var binding = "expired"; var material = true; var opens = 0
        val writerStarted = CompletableDeferred<Unit>(); var writer: kotlinx.coroutines.Deferred<Unit>? = null
        val eviction = LedgerSessionTokenEviction(f.directory, origin, "expired", openCache = { scope, directory, _ ->
            assertEquals(first, scope); lease.lock(); opens++
            var released = false
            fun release() { if (!released) { released = true; lease.unlock() } }
            object : LedgerStorage {
                override val imports: LedgerImportBatches get() = error("No import access")
                override suspend fun read(scope: LedgerCacheScope): LedgerSnapshotDto? = null
                override suspend fun replace(scope: LedgerCacheScope, snapshot: LedgerSnapshotDto) = error("No fact replacement")
                override suspend fun saveOfflineSession(scope: LedgerCacheScope, token: String, session: SessionDto) = error("No identity save through probe")
                override suspend fun readOfflineSession(scope: LedgerCacheScope, token: String): SessionDto? {
                    assertEquals("expired", binding)
                    writer = async {
                        writerStarted.complete(Unit)
                        lease.withLock {
                            binding = "new-token"; material = true
                            directory.register(current, scope); directory.publish(current, scope)
                        }
                    }
                    writerStarted.await(); yield(); assertFalse(checkNotNull(writer).isCompleted)
                    return SessionDto(UserDto("first@example.test"), scope.workspaceId,
                        listOf(WorkspaceDto(scope.workspaceId, "First", Role.ADMIN)))
                }
                override suspend fun clear(scope: LedgerCacheScope) {
                    assertEquals("expired", binding)
                    assertFalse(checkNotNull(writer).isCompleted)
                    material = false; directory.forget(scope); release()
                }
                override suspend fun close() = release()
            }
        })
        eviction.clear(); checkNotNull(writer).await()
        assertEquals(1, opens); assertFalse(lease.isLocked)
        assertEquals("new-token", binding); assertTrue(material)
        assertTrue(f.directory.isCurrent(current)); assertEquals(first, f.directory.current(current))
    }

    @Test fun aTransientIdentityReadFailureCanRetryWithoutChangingTheDirectoryGeneration() = runTest {
        val f = Fixture(); f.seed()
        f.bindings[first] = "expired"; f.bindings[second] = "other"; f.bindings[otherUser] = "other"
        val current = checkNotNull(f.directory.currentTicket(origin))
        val failure = kotlinx.io.IOException("synthetic transient identity read")
        f.readFailure = failure
        val eviction = f.eviction("expired")
        assertEquals(failure.message, assertFailsWith<kotlinx.io.IOException> { eviction.clear() }.message)
        assertTrue(f.erased.isEmpty()); assertEquals(1, f.closes)
        f.readFailure = null
        eviction.clear()
        assertEquals(listOf(first), f.erased)
        assertTrue(f.directory.isCurrent(current))
    }

    @Test fun aTokenWithoutMatchingRecoveryRecordsDeletesNothing() = runTest {
        val f = Fixture(); f.seed()
        f.bindings[first] = "other"; f.bindings[second] = "other"; f.bindings[otherUser] = "other"
        f.eviction("expired").clear()
        assertTrue(f.erased.isEmpty()); assertTrue(f.created.isEmpty())
        assertEquals(3, f.directory.scopes(origin).size)
    }

    @Test fun materialThatCannotBeOpenedKeepsTheDecisionIncompleteForRetry() = runTest {
        val f = Fixture(); f.seed()
        f.bindings[otherUser] = "expired"; f.unreadable = true
        val eviction = f.eviction("expired")
        assertFailsWith<IllegalStateException> { eviction.clear() }
        assertTrue(f.erased.isEmpty())
        assertEquals(3, f.directory.scopes(origin).size)
        f.unreadable = false
        eviction.clear()
        assertEquals(listOf(otherUser), f.erased)
        assertEquals(2, f.directory.scopes(origin).size)
    }

    @Test fun aFailedErasureRetriesTheOriginalTargetsWithoutProbingAgain() = runTest {
        val f = Fixture(); f.seed()
        f.bindings[first] = "expired"; f.bindings[second] = "expired"; f.bindings[otherUser] = "other"
        var blocked = true
        f.beforeErase = { if (blocked && it == second) error("blocked") }
        val eviction = f.eviction("expired")
        assertFailsWith<IllegalStateException> { eviction.clear() }
        assertEquals(listOf(first), f.erased)
        blocked = false
        eviction.clear()
        assertEquals(listOf(first, second), f.erased)
        assertEquals(listOf(first, second), f.created)
        assertEquals(3, f.probes)
        assertEquals(listOf(otherUser), f.directory.scopes(origin))
    }

    @Test fun closeNeverDeletesScopesAndReleasesOnlyTheSelectedErasure() = runTest {
        val f = Fixture(); f.seed()
        f.bindings[first] = "expired"; f.bindings[second] = "expired"; f.bindings[otherUser] = "other"
        val eviction = f.eviction("expired")
        eviction.close()
        assertTrue(f.created.isEmpty()); assertEquals(3, f.directory.scopes(origin).size)
        assertEquals(0, f.probes)
    }

    @Test fun closeFinishesOnlySelectedErasureAndNeverOpensRemainingScopes() = runTest {
        val f = Fixture(); f.seed()
        f.bindings[first] = "expired"; f.bindings[second] = "expired"; f.bindings[otherUser] = "other"
        f.beforeErase = { if (it == second) error("blocked") }
        val eviction = f.eviction("expired")
        assertFailsWith<IllegalStateException> { eviction.clear() }
        assertFailsWith<IllegalStateException> { eviction.close() }
        assertEquals(listOf(first), f.erased)
        assertEquals(2, f.probes)
        f.beforeErase = null; eviction.close()
        assertEquals(listOf(first, second), f.erased)
        assertEquals(listOf(otherUser), f.directory.scopes(origin))
        assertEquals(2, f.probes)
    }

    @Test fun concurrentClearCallsEraseEachScopeOnce() = runTest {
        val f = Fixture(); f.seed()
        f.bindings[first] = "expired"; f.bindings[second] = "expired"; f.bindings[otherUser] = "other"
        val eviction = f.eviction("expired")
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.beforeErase = { if (it == first) { entered.complete(Unit); release.await() } }
        val firstClear = async { eviction.clear() }
        entered.await()
        val secondClear = async { eviction.clear() }
        release.complete(Unit)
        firstClear.await(); secondClear.await()
        assertEquals(listOf(first, second), f.erased)
        assertEquals(listOf(otherUser), f.directory.scopes(origin))
    }

        @Test fun aRejectedEmptyTokenIsNotAccepted() {
        val f = Fixture()
        assertFailsWith<IllegalArgumentException> { f.eviction("") }
    }
}
