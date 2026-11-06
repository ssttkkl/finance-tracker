package com.finance.tracker

import com.finance.tracker.data.*
import com.finance.tracker.domain.LedgerCacheScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class LedgerSessionDirectoryTest {
    private val origin = "https://synthetic.example.test"
    private val first = LedgerCacheScope(origin, ledgerOfflinePrincipal("first@example.test"), "workspace-one")
    private val second = first.copy(workspaceId = "workspace-two")

    @Test fun referencesSurviveNewDirectoryObjectsWithoutExposingIdentityOrTokens() = runTest {
        val storage = MemoryLocator()
        val directory = LedgerSessionDirectory(storage)
        val ticket = directory.begin(origin)
        directory.register(ticket, first)
        assertNull(directory.current(ticket)) // Merely registered material is not offline authorization.
        directory.publish(ticket, first)
        val reopened = LedgerSessionDirectory(storage)
        val next = reopened.begin(origin)
        assertEquals(first, reopened.current(next))
        assertEquals(listOf(first), reopened.scopes(origin))
        assertFalse(storage.values.values.any { it.contains("first@example.test") })
        assertTrue(storage.values.keys.all { it.startsWith("ledger-session-") })
    }

    @Test fun aNewOperationKeepsTheLastCommittedReferenceButFencesOldPublishers() = runTest {
        val directory = LedgerSessionDirectory(MemoryLocator())
        val old = directory.begin(origin)
        assertFails { directory.publish(old, first) }
        directory.register(old, first)
        directory.publish(old, first)
        val next = directory.begin(origin)
        directory.register(next, second)
        assertEquals(first, directory.current(next))
        assertFailsWith<CancellationException> { directory.publish(old, second) }
        assertFailsWith<CancellationException> { directory.register(old, first.copy(workspaceId = "uncreated")) }
        assertEquals(setOf(first, second), directory.scopes(origin).toSet())
        assertFailsWith<CancellationException> { directory.current(old) }
        directory.publish(next, second)
        assertEquals(second, directory.current(next))
    }

    @Test fun forgettingAnOldScopePreservesTheNewCurrentAndOtherOrigins() = runTest {
        val directory = LedgerSessionDirectory(MemoryLocator())
        val ticket = directory.begin(origin)
        val foreign = first.copy(apiOrigin = "https://other.example.test")
        val otherTicket = directory.begin(foreign.apiOrigin)
        for (scope in listOf(first, second)) directory.register(ticket, scope)
        directory.register(otherTicket, foreign)
        directory.publish(ticket, first)
        directory.publish(otherTicket, foreign)
        directory.publish(ticket, second)
        directory.forget(first)
        assertEquals(second, directory.current(ticket))
        assertEquals(listOf(second), directory.scopes(origin))
        assertEquals(foreign, directory.current(otherTicket))
        directory.forget(second)
        assertNull(directory.current(ticket))
        assertTrue(directory.scopes(origin).isEmpty())
        directory.forget(second) // Cleanup retries after a completed deletion are harmless.
    }

    @Test fun invalidOriginsScopesAndForeignPublicationCannotChangeValidReferences() = runTest {
        val directory = LedgerSessionDirectory(MemoryLocator())
        val ticket = directory.begin(origin)
        directory.register(ticket, first)
        directory.publish(ticket, first)
        assertFails { directory.begin("$origin/path") }
        assertFails { directory.begin(" $origin/") }
        assertFails { directory.register(ticket, first.copy(principal = "raw-email@example.test")) }
        assertFails { directory.register(ticket, first.copy(workspaceId = "bad\uD800")) }
        val foreign = first.copy(apiOrigin = "https://other.example.test")
        directory.register(directory.begin(foreign.apiOrigin), foreign)
        assertFails { directory.publish(ticket, foreign) }
        assertEquals(first, directory.current(ticket))
    }

    @Test fun metadataFailuresAndCancellationDoNotFabricateAnEmptyOrSuccessfulDirectory() = runTest {
        val storage = MemoryLocator()
        val directory = LedgerSessionDirectory(storage)
        val ticket = directory.begin(origin)
        directory.register(ticket, first)
        directory.publish(ticket, first)
        val failure = IllegalStateException("synthetic storage failure")
        storage.failure = failure
        assertSame(failure, assertFails { directory.forget(first) })
        storage.failure = CancellationException("synthetic cancellation")
        assertFailsWith<CancellationException> { directory.begin(origin) }
        storage.failure = null
        assertEquals(first, directory.current(ticket))
        assertEquals(listOf(first), directory.scopes(origin))
        val currentKey = storage.values.keys.single { it.startsWith("ledger-session-current-v1:") }
        storage.values[currentKey] = "broken"
        assertFails { LedgerSessionDirectory(storage).begin(origin) }
        assertEquals("broken", storage.values[currentKey])
    }

    @Test fun concurrentDirectoryObjectsDoNotLoseRegistrationsOrReusePublicationTickets() = runTest {
        val storage = MemoryLocator()
        val left = LedgerSessionDirectory(storage)
        val right = LedgerSessionDirectory(storage)
        val tickets = listOf(async { left.begin(origin) }, async { right.begin(origin) }).awaitAll()
        val latest = tickets.maxBy { it.generation }
        listOf(async { left.register(latest, first) }, async { right.register(latest, second) }).awaitAll()
        assertEquals(setOf(first, second), left.scopes(origin).toSet())
        val stale = tickets.minBy { it.generation }
        assertNotEquals(latest.generation, stale.generation)
        assertFailsWith<CancellationException> { left.publish(stale, first) }
        right.publish(latest, second)
        assertEquals(second, left.current(latest))
    }

    @Test fun registrationCannotSlipInAfterAnotherOperationAdvancesTheDirectory() = runTest {
        val storage = MemoryLocator()
        val directory = LedgerSessionDirectory(storage)
        val old = directory.begin(origin)
        storage.beforeCompare = { directory.begin(origin) }
        assertFailsWith<CancellationException> { directory.register(old, first) }
        assertTrue(directory.scopes(origin).isEmpty())
    }

    @Test fun anExistingDirectoryCannotResetItsGenerationOrReviveAnOldTicket() = runTest {
        val storage = MemoryLocator()
        val directory = LedgerSessionDirectory(storage)
        val old = directory.begin(origin)
        directory.register(old, first)
        directory.begin(origin)
        val key = storage.values.keys.single()
        val original = Json.parseToJsonElement(storage.values.getValue(key)).jsonObject
        for (broken in listOf(JsonObject(original - "generation"), JsonObject(original + ("generation" to JsonPrimitive(0))))) {
            storage.values[key] = broken.toString()
            assertFails { directory.begin(origin) }
            assertEquals(broken.toString(), storage.values[key])
        }
    }

    @Test fun damagedOrMisaddressedScopeRecordsAreRejectedRatherThanSilentlyDropped() = runTest {
        val storage = MemoryLocator()
        val directory = LedgerSessionDirectory(storage)
        val ticket = directory.begin(origin)
        directory.register(ticket, first)
        directory.publish(ticket, first)
        val key = storage.values.keys.single { it.startsWith("ledger-session-current-v1:") }
        storage.values[key] = storage.values.getValue(key).replace("workspace-one", "workspace-two")
        assertFails { directory.scopes(origin) }
        assertFails { directory.current(ticket) }
        assertTrue(storage.values.containsKey(key))
    }

    private class MemoryLocator : LedgerSessionLocatorStorage {
        val values = mutableMapOf<String, String>()
        var failure: Throwable? = null
        var beforeCompare: (suspend () -> Unit)? = null
        private val mutex = Mutex()
        override suspend fun read(key: String): String? = mutex.withLock { values[key] }
        override suspend fun compareAndSet(key: String, expected: String?, replacement: String): Boolean {
            failure?.let { throw it }
            val hook = beforeCompare
            beforeCompare = null
            hook?.invoke()
            yield() // Independently created directories can contend at the actual CAS boundary.
            return mutex.withLock {
                if (values[key] != expected) false
                else { values[key] = replacement; true }
            }
        }
    }
}
