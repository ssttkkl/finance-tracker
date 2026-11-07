package com.finance.tracker

import com.finance.tracker.data.*
import com.finance.tracker.domain.LedgerCacheScope
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LedgerSessionCacheCleanupTest {
    private val origin = "https://cleanup.example.test"
    private val principal = ledgerOfflinePrincipal("synthetic@example.test")
    private val first = LedgerCacheScope(origin, principal, "first")
    private val second = first.copy(workspaceId = "second")
    private val otherUser = first.copy(principal = ledgerOfflinePrincipal("other@example.test"))
    private val foreign = first.copy(apiOrigin = "https://foreign.example.test")

    @Test fun closeDuringTheNextScopeCheckCannotAcquireOrDeleteThatScope() = runTest {
        val f = fixture(); val entered = CompletableDeferred<Unit>(); val proceed = CompletableDeferred<Unit>()
        var reads = 0
        f.locator.beforeRead = { if (++reads == 2) { entered.complete(Unit); proceed.await() } }
        val cleanup = LedgerSessionCacheCleanup(f.directory, f.ticket, principal, emptySet(), f::eraser)
        val clearing = async { assertFailsWith<CancellationException> { cleanup.clear() } }
        var closing: Deferred<Unit>? = null
        try {
            entered.await(); closing = async { cleanup.close() }; yield()
            assertFalse(closing.isCompleted)
            proceed.complete(Unit); clearing.await(); closing.await()
            assertTrue(f.created.isEmpty()); assertTrue(f.deleted.isEmpty())
        } finally { proceed.complete(Unit); clearing.cancelAndJoin(); closing?.cancelAndJoin(); cleanup.close() }
    }

    @Test fun closeBeforeCleanupDoesNotReadMetadataOrSelectMaterials() = runTest {
        val f = fixture(); f.locator.failure = IllegalStateException("metadata unreadable")
        val cleanup = LedgerSessionCacheCleanup(f.directory, f.ticket, principal, emptySet(), f::eraser)
        cleanup.close(); cleanup.close()
        assertFailsWith<CancellationException> { cleanup.clear() }
        assertTrue(f.created.isEmpty())
    }

    @Test fun closeFinishesTheSelectedPartialErasureButNeverStartsTheNextScope() = runTest {
        val f = fixture(); f.beforeErase = { error("partial first") }
        val cleanup = LedgerSessionCacheCleanup(f.directory, f.ticket, principal, emptySet(), f::eraser)
        assertFailsWith<IllegalStateException> { cleanup.clear() }
        f.beforeErase = null; f.directory.begin(origin)
        cleanup.close(); cleanup.close()
        assertEquals(listOf(first), f.created); assertEquals(listOf(first), f.deleted)
        assertEquals(listOf(second, otherUser), f.directory.scopes(origin))
        assertFailsWith<CancellationException> { cleanup.clear() }
    }

    @Test fun revocationSelectsOnlyTheOriginalUsersInaccessibleWorkspaces() = runTest {
        val f = fixture()
        val keep = mutableSetOf("second")
        val cleanup = LedgerSessionCacheCleanup(f.directory, f.ticket, principal, keep, f::eraser)
        keep.clear() // Caller mutation cannot change the selected authorization result.
        cleanup.clear(); cleanup.clear()
        assertEquals(listOf(first), f.created)
        assertEquals(listOf(first), f.deleted)
        assertEquals(listOf(second, otherUser), f.directory.scopes(origin))
        assertEquals(listOf(foreign), f.directory.scopes(foreign.apiOrigin))
    }

    @Test fun logoutWithNoRetainedWorkspacesClearsEveryScopeForThatUser() = runTest {
        val f = fixture()
        val cleanup = LedgerSessionCacheCleanup(f.directory, f.ticket, principal, emptySet(), f::eraser)
        cleanup.clear(); cleanup.clear()
        assertEquals(listOf(first, second), f.created)
        assertEquals(listOf(first, second), f.deleted)
        assertEquals(listOf(otherUser), f.directory.scopes(origin))
        assertEquals(listOf(foreign), f.directory.scopes(foreign.apiOrigin))
    }

    @Test fun failedErasureRetainsItsExactObjectAndDoesNotRepeatCompletedScopes() = runTest {
        val f = fixture(); val failure = IllegalStateException("partial second erasure")
        f.beforeErase = { if (it == second) throw failure }
        val cleanup = LedgerSessionCacheCleanup(f.directory, f.ticket, principal, emptySet(), f::eraser)
        assertOriginal(failure, assertFailsWith<IllegalStateException> { cleanup.clear() })
        assertEquals(listOf(first, second), f.created)
        assertEquals(listOf(first), f.deleted)
        f.beforeErase = null
        cleanup.clear()
        assertEquals(listOf(first, second), f.created)
        assertEquals(listOf(first, second), f.deleted)
        assertEquals(listOf(first, second, second), f.attempts)
    }

    @Test fun retriesDoNotEnumerateScopesCreatedAfterTheInitialSelection() = runTest {
        val f = fixture(); f.beforeErase = { error("synthetic deletion blocked") }
        val cleanup = LedgerSessionCacheCleanup(f.directory, f.ticket, principal, emptySet(), f::eraser)
        assertFailsWith<IllegalStateException> { cleanup.clear() }
        val later = first.copy(workspaceId = "later")
        f.directory.register(f.ticket, later)
        f.beforeErase = null; cleanup.clear()
        assertEquals(listOf(first, second), f.deleted)
        assertEquals(listOf(otherUser, later), f.directory.scopes(origin))
    }

    @Test fun staleEnumerationCannotCreateAnErasureOrDiscoverLaterReferences() = runTest {
        val f = fixture(); val next = f.directory.begin(origin)
        val later = first.copy(workspaceId = "later")
        f.directory.register(next, later)
        val cleanup = LedgerSessionCacheCleanup(f.directory, f.ticket, principal, emptySet(), f::eraser)
        assertFailsWith<CancellationException> { cleanup.clear() }
        assertTrue(f.created.isEmpty()); assertTrue(f.deleted.isEmpty())
        assertEquals(listOf(first, second, otherUser, later), f.directory.scopes(next))
    }

    @Test fun aFailedEnumerationDoesNotMasqueradeAsAnEmptySuccessfulCleanup() = runTest {
        val f = fixture(); val failure = IllegalStateException("locator unreadable")
        f.locator.failure = failure
        val cleanup = LedgerSessionCacheCleanup(f.directory, f.ticket, principal, emptySet(), f::eraser)
        assertOriginal(failure, assertFailsWith<IllegalStateException> { cleanup.clear() })
        assertTrue(f.created.isEmpty())
        f.locator.failure = null; cleanup.clear()
        assertEquals(listOf(first, second), f.deleted)
    }

    @Test fun aLaterOperationStopsTheNextScopeButAlreadySelectedPartialWorkCanFinish() = runTest {
        val f = fixture(); var fail = true
        f.beforeErase = { if (fail) error("partial first") }
        val cleanup = LedgerSessionCacheCleanup(f.directory, f.ticket, principal, emptySet(), f::eraser)
        assertFailsWith<IllegalStateException> { cleanup.clear() }
        f.directory.begin(origin); fail = false
        assertFailsWith<CancellationException> { cleanup.clear() }
        assertEquals(listOf(first), f.created)
        assertEquals(listOf(first), f.deleted)
        assertEquals(listOf(second, otherUser), f.directory.scopes(origin))
    }

    @Test fun cancellationKeepsTheSelectedCleanupOwnedUntilItCompletes() = runTest {
        val f = fixture(); val entered = CompletableDeferred<Unit>(); val proceed = CompletableDeferred<Unit>()
        f.beforeErase = { if (it == first) { entered.complete(Unit); proceed.await() } }
        val cleanup = LedgerSessionCacheCleanup(f.directory, f.ticket, principal, emptySet(), f::eraser)
        val running = launch { cleanup.clear() }
        try {
            entered.await(); running.cancel(); yield(); assertFalse(running.isCompleted)
            proceed.complete(Unit); running.join(); assertTrue(running.isCancelled)
            cleanup.clear(); assertEquals(listOf(first, second), f.deleted)
            assertEquals(listOf(first, second), f.created)
        } finally { proceed.complete(Unit); running.cancelAndJoin() }
    }

    @Test fun anEmptySelectionCompletesOnceAndNeverDeletesLaterMaterials() = runTest {
        val f = fixture()
        val cleanup = LedgerSessionCacheCleanup(f.directory, f.ticket, principal, setOf("first", "second"), f::eraser)
        cleanup.clear()
        f.directory.register(f.ticket, first.copy(workspaceId = "later"))
        f.directory.begin(origin)
        cleanup.clear(); assertTrue(f.created.isEmpty())
    }

    @Test fun preselectedTargetsAreErasedWithoutPrincipalFilteringOrRediscovery() = runTest {
        val f = fixture()
        val later = first.copy(workspaceId = "later")
        f.directory.register(f.ticket, later)
        val cleanup = LedgerSessionCacheCleanup(f.directory, f.ticket, "unused-principal", emptySet(), f::eraser,
            preselected = listOf(first))
        cleanup.clear(); cleanup.clear()
        assertEquals(listOf(first), f.created)
        assertEquals(listOf(first), f.deleted)
        assertEquals(listOf(second, otherUser, later), f.directory.scopes(origin))
    }

    @Test fun aFailedPreselectedErasureKeepsTheSameFrozenTargets() = runTest {
        val f = fixture()
        val later = first.copy(workspaceId = "later")
        f.directory.register(f.ticket, later)
        var blocked = true
        f.beforeErase = { if (blocked && it == second) error("blocked") }
        val cleanup = LedgerSessionCacheCleanup(f.directory, f.ticket, principal, emptySet(), f::eraser,
            preselected = listOf(first, second))
        assertFailsWith<IllegalStateException> { cleanup.clear() }
        blocked = false
        cleanup.clear()
        assertEquals(listOf(first, second), f.created)
        assertEquals(listOf(first, second), f.deleted)
        assertEquals(listOf(otherUser, later), f.directory.scopes(origin))
    }

        private suspend fun fixture(): Fixture = Fixture().also { f ->
        f.ticket = f.directory.begin(origin)
        for (scope in listOf(first, second, otherUser)) f.directory.register(f.ticket, scope)
        f.directory.register(f.directory.begin(foreign.apiOrigin), foreign)
    }

    private class Locator : LedgerSessionLocatorStorage {
        val values = mutableMapOf<String, String>(); var failure: Throwable? = null
        var beforeRead: (suspend () -> Unit)? = null
        override suspend fun read(key: String): String? { beforeRead?.invoke(); failure?.let { throw it }; return values[key] }
        override suspend fun compareAndSet(key: String, expected: String?, replacement: String): Boolean {
            failure?.let { throw it }
            if (values[key] != expected) return false
            values[key] = replacement; return true
        }
    }

    private class Fixture {
        val locator = Locator(); val directory = LedgerSessionDirectory(locator)
        lateinit var ticket: LedgerSessionTicket
        val created = mutableListOf<LedgerCacheScope>(); val deleted = mutableListOf<LedgerCacheScope>()
        val attempts = mutableListOf<LedgerCacheScope>()
        var beforeErase: (suspend (LedgerCacheScope) -> Unit)? = null
        fun eraser(scope: LedgerCacheScope, directory: LedgerSessionDirectory, ticket: LedgerSessionTicket): LedgerCacheErasure {
            created += scope
            return LedgerCacheErasureOwner(acquire = { Any() },
                beforeErasure = ledgerCacheErasureCheck(scope, directory, ticket),
                erase = { attempts += scope; beforeErase?.invoke(scope); directory.forget(scope); deleted += scope }, release = {})
        }
    }
    private fun assertOriginal(expected: Throwable, actual: Throwable) =
        assertTrue(generateSequence(actual) { it.cause }.any { it === expected })
}
