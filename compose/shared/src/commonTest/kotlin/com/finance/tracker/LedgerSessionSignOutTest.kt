package com.finance.tracker

import com.finance.tracker.data.*
import com.finance.tracker.domain.LedgerCacheScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LedgerSessionSignOutTest {
    private val origin = "https://signout.example.test"
    private val principal = ledgerOfflinePrincipal("session@example.test")
    private val other = ledgerOfflinePrincipal("other@example.test")
    private val first = LedgerCacheScope(origin, principal, "first")
    private val second = first.copy(workspaceId = "second")
    private val foreignUser = first.copy(principal = other)

    private class Locator : LedgerSessionLocatorStorage {
        private var value: String? = null
        override suspend fun read(key: String) = value
        override suspend fun compareAndSet(key: String, expected: String?, replacement: String): Boolean {
            if (value != expected) return false
            value = replacement
            return true
        }
    }

    private inner class Fixture {
        val directory = LedgerSessionDirectory(Locator())
        val events = mutableListOf<String>()
        val created = mutableListOf<LedgerCacheScope>()
        val erased = mutableListOf<LedgerCacheScope>()
        var beforeErase: (suspend (LedgerCacheScope) -> Unit)? = null

        suspend fun seed() {
            val ticket = directory.begin(origin)
            directory.register(ticket, first)
            directory.register(ticket, second)
            directory.register(ticket, foreignUser)
        }

        val erasure: (LedgerCacheScope, LedgerSessionDirectory, LedgerSessionTicket) -> LedgerCacheErasure =
            { scope, owner, _ ->
                created += scope
                object : LedgerCacheErasure {
                    override suspend fun clear() {
                        beforeErase?.invoke(scope)
                        owner.forget(scope)
                        erased += scope
                        events += "erase:${scope.workspaceId}"
                    }
                    override suspend fun close() { events += "close:${scope.workspaceId}" }
                }
            }
    }

    @Test fun closesTheActiveRestorationBeforeAcquiringAnyErasureLease() = runTest {
        val f = Fixture(); f.seed()
        val resource = object : LedgerSessionResource {
            override suspend fun close() { f.events += "resource:close" }
        }
        LedgerSessionSignOut(f.directory, origin, principal, resource, f.erasure).clear()
        assertEquals(listOf("resource:close", "erase:first", "erase:second"), f.events)
        assertEquals(listOf(foreignUser), f.directory.scopes(origin))
    }

    @Test fun advancesTheOperationSoAnEarlierRestorationCannotPublish() = runTest {
        val f = Fixture()
        val earlier = f.directory.begin(origin)
        f.directory.register(earlier, first)
        LedgerSessionSignOut(f.directory, origin, principal, null, f.erasure).clear()
        assertFailsWith<CancellationException> { f.directory.current(earlier) }
        assertEquals(listOf(first), f.erased)
    }

    @Test fun aFailedClosePreventsDeletionAndCanBeRetried() = runTest {
        val f = Fixture(); f.seed()
        var failure: Throwable? = IllegalStateException("unconfirmed close")
        val resource = object : LedgerSessionResource {
            override suspend fun close() { failure?.let { throw it } }
        }
        val signOut = LedgerSessionSignOut(f.directory, origin, principal, resource, f.erasure)
        val thrown = assertFailsWith<IllegalStateException> { signOut.clear() }
        assertEquals("unconfirmed close", thrown.message)
        assertTrue(f.created.isEmpty()); assertEquals(3, f.directory.scopes(origin).size)
        failure = null
        signOut.clear()
        assertEquals(listOf(first, second), f.erased)
        assertEquals(listOf(foreignUser), f.directory.scopes(origin))
    }

    @Test fun aFailedErasureRetainsTheOriginalTargetsAndObjectAcrossRetries() = runTest {
        val f = Fixture(); f.seed(); var blocked = true
        f.beforeErase = { if (blocked && it == second) throw IllegalStateException("blocked") }
        val signOut = LedgerSessionSignOut(f.directory, origin, principal, null, f.erasure)
        assertFailsWith<IllegalStateException> { signOut.clear() }
        assertEquals(listOf(first, second), f.created)
        assertEquals(listOf(first), f.erased)
        blocked = false
        signOut.clear()
        assertEquals(listOf(first, second), f.created)
        assertEquals(listOf(first, second), f.erased)
        assertEquals(listOf(foreignUser), f.directory.scopes(origin))
    }

    @Test fun aUserWithoutRegisteredScopesSettlesWithoutCreatingAnyErasure() = runTest {
        val f = Fixture()
        f.directory.begin(origin)
        LedgerSessionSignOut(f.directory, origin, principal, null, f.erasure).clear()
        assertTrue(f.created.isEmpty())
        assertTrue(f.directory.scopes(origin).isEmpty())
    }
}
