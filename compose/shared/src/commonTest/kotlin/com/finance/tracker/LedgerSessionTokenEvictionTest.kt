package com.finance.tracker

import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.contracts.Role
import com.finance.tracker.business.contracts.SessionDto
import com.finance.tracker.business.contracts.UserDto
import com.finance.tracker.business.contracts.WorkspaceDto
import com.finance.tracker.data.*
import com.finance.tracker.domain.LedgerCacheScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
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
                    override suspend fun read(scope: LedgerCacheScope): LedgerSnapshotDto? = null
                    override suspend fun replace(scope: LedgerCacheScope, snapshot: LedgerSnapshotDto) = error("read-only probe")
                    override suspend fun clear(scope: LedgerCacheScope) = error("no implicit erasure")
                    override suspend fun close() { closes++ }
                    override suspend fun readOfflineSession(scope: LedgerCacheScope, token: String): SessionDto? =
                        if (bindings[scope] == token) SessionDto(
                            UserDto("user@example.test"), scope.workspaceId,
                            listOf(WorkspaceDto(scope.workspaceId, "Workspace", Role.ADMIN)),
                        ) else null
                    override suspend fun saveOfflineSession(scope: LedgerCacheScope, token: String, session: SessionDto) =
                        error("read-only probe")
                }
            }

        val erasure: (LedgerCacheScope, LedgerSessionDirectory, LedgerSessionTicket) -> LedgerCacheErasure =
            { scope, owner, _ ->
                created += scope
                object : LedgerCacheErasure {
                    override suspend fun clear() {
                        beforeErase?.invoke(scope)
                        owner.forget(scope)
                        erased += scope
                    }
                    override suspend fun close() {}
                }
            }

        fun eviction(token: String) = LedgerSessionTokenEviction(directory, origin, token, open, erasure)
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

    @Test fun closeAfterAPartialFailureReleasesWithoutErasingTheRemainingScope() = runTest {
        val f = Fixture(); f.seed()
        f.bindings[first] = "expired"; f.bindings[second] = "expired"; f.bindings[otherUser] = "other"
        f.beforeErase = { if (it == second) error("blocked") }
        val eviction = f.eviction("expired")
        assertFailsWith<IllegalStateException> { eviction.clear() }
        eviction.close()
        assertEquals(listOf(first), f.erased)
        assertEquals(listOf(second, otherUser), f.directory.scopes(origin))
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
