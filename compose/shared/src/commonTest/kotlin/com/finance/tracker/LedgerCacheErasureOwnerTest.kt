package com.finance.tracker

import com.finance.tracker.data.LedgerCacheErasureOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LedgerCacheErasureOwnerTest {
    @Test fun repeatedClearOwnsOneResourceAndChecksBeforeDeleting() = runTest {
        val events = mutableListOf<String>(); val resource = Any()
        val owner = LedgerCacheErasureOwner(acquire = { events += "acquire"; resource },
            beforeErasure = { events += "check" }, erase = { assertSame(resource, it); events += "erase" },
            release = { assertSame(resource, it); events += "release" })
        owner.clear(); owner.clear()
        assertEquals(listOf("acquire", "check", "erase", "release"), events)
    }

    @Test fun anOperationSupersededWhileAcquiringCannotDeleteLaterMaterials() = runTest {
        val acquired = CompletableDeferred<Unit>(); val deliver = CompletableDeferred<Unit>()
        var generation = 1; var materials = "original"; var released = false; var acquires = 0
        val owner = LedgerCacheErasureOwner(acquire = { acquires++; acquired.complete(Unit); deliver.await(); Any() },
            beforeErasure = { if (generation != 1) throw CancellationException("superseded") },
            erase = { materials = "deleted" }, release = { released = true })
        val clearing = async { assertFailsWith<CancellationException> { owner.clear() } }
        try {
            acquired.await(); generation = 2; materials = "later login"; deliver.complete(Unit); clearing.await()
            assertTrue(released); assertEquals("later login", materials)
            assertFailsWith<CancellationException> { owner.clear() }
            assertEquals(1, acquires); assertEquals("later login", materials)
        } finally { deliver.complete(Unit); clearing.cancelAndJoin() }
    }

    @Test fun aRejectedCheckReleasesBeforePropagatingAndNeverRechecksOrReacquires() = runTest {
        val failure = IllegalStateException("metadata unavailable"); val events = mutableListOf<String>()
        val owner = LedgerCacheErasureOwner(acquire = { events += "acquire"; Any() },
            beforeErasure = { events += "check"; throw failure }, erase = { error("must preserve materials") },
            release = { events += "release" })
        repeat(2) { assertOriginalCause(failure, assertFailsWith<IllegalStateException> { owner.clear() }) }
        assertEquals(listOf("acquire", "check", "release"), events)
    }

    @Test fun rejectionWithFailedReleaseRetainsTheSameResourceForReleaseOnlyRetry() = runTest {
        val failure = IllegalStateException("superseded metadata"); val releaseFailure = IllegalArgumentException("close failed")
        val resource = Any(); var releases = 0; var acquires = 0; var checks = 0
        val owner = LedgerCacheErasureOwner(acquire = { acquires++; resource },
            beforeErasure = { checks++; throw failure }, erase = { error("must preserve materials") },
            release = { assertSame(resource, it); if (++releases == 1) throw releaseFailure })
        assertOriginalCause(failure, assertFailsWith<IllegalStateException> { owner.clear() })
        assertTrue(releaseFailure in failure.suppressedExceptions)
        assertOriginalCause(failure, assertFailsWith<IllegalStateException> { owner.clear() })
        assertOriginalCause(failure, assertFailsWith<IllegalStateException> { owner.clear() })
        assertEquals(1, acquires); assertEquals(1, checks); assertEquals(2, releases)
    }

    @Test fun anAlreadyStartedPartialErasureFinishesWithoutObtainingNewAuthorization() = runTest {
        val resource = Any(); var checks = 0; var erases = 0; var releases = 0; var authorized = true
        val owner = LedgerCacheErasureOwner(acquire = { resource },
            beforeErasure = { checks++; check(authorized) }, erase = {
                assertSame(resource, it); if (++erases == 1) error("partial erase")
            }, release = { assertSame(resource, it); releases++ })
        assertFailsWith<IllegalStateException> { owner.clear() }
        assertEquals(0, releases); authorized = false
        owner.clear()
        assertEquals(1, checks); assertEquals(2, erases); assertEquals(1, releases)
    }

    @Test fun aLostReleaseAcknowledgementCannotCauseAnotherErase() = runTest {
        val resource = Any(); var erases = 0; var releases = 0; var materials = "old"
        val owner = LedgerCacheErasureOwner(acquire = { resource }, beforeErasure = {},
            erase = { erases++; materials = "deleted" }, release = {
                assertSame(resource, it); if (++releases == 1) error("release acknowledgement lost")
            })
        assertFailsWith<IllegalStateException> { owner.clear() }
        assertEquals("deleted", materials); materials = "new owner"
        owner.clear(); owner.clear()
        assertEquals(1, erases); assertEquals(2, releases); assertEquals("new owner", materials)
    }

    @Test fun cancellationDuringAcquisitionWaitsForTheSelectedCleanup() = runTest {
        val acquired = CompletableDeferred<Unit>(); val deliver = CompletableDeferred<Unit>(); var released = false
        val owner = LedgerCacheErasureOwner(acquire = { acquired.complete(Unit); deliver.await(); Any() },
            beforeErasure = {}, erase = {}, release = { released = true })
        val clearing = launch { owner.clear() }
        try {
            acquired.await(); clearing.cancel(); yield(); assertFalse(clearing.isCompleted)
            deliver.complete(Unit); clearing.join(); assertTrue(clearing.isCancelled); assertTrue(released)
            owner.clear()
        } finally { deliver.complete(Unit); clearing.cancelAndJoin() }
    }

    private fun assertOriginalCause(expected: Throwable, actual: Throwable) {
        assertTrue(generateSequence(actual) { it.cause }.any { it === expected }, "Original diagnostic must remain in the cause chain")
    }
}
