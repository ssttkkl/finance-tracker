package com.finance.tracker

import com.finance.tracker.business.contracts.*
import com.finance.tracker.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LedgerSnapshotSyncTest {
    @Test fun closingAfterRevocationRetriesOnlyTheAlreadyChosenErasure() = runTest {
        val cache = Cache(); cache.rows[scope] = snapshot()
        val denied = DomainFailure("access_denied", 403, FailureCategory.RECOVERABLE)
        val source = Source(snapshot()); source.failure = denied
        val sync = LedgerSnapshotSync(scope, cache, source)
        cache.clearFailure = IllegalStateException("synthetic erase failure")
        assertFailsWith<IllegalStateException> { sync.refresh() }
        assertNotNull(cache.rows[scope])
        cache.clearFailure = null
        sync.close()
        assertNull(cache.rows[scope]); assertEquals(0, cache.closeCalls)
        val fresh = snapshot("2"); cache.rows[scope] = fresh
        sync.close()
        assertSame(fresh, cache.rows[scope]); assertEquals(2, cache.clearCalls)
    }

    @Test fun closingDuringRevocationWaitsForTheOriginalErasureWithoutSwitchingModes() = runTest {
        val cache = Cache(); cache.rows[scope] = snapshot()
        val erasing = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        cache.beforeClear = { erasing.complete(Unit); release.await() }
        val denied = DomainFailure("access_denied", 401, FailureCategory.RECOVERABLE)
        val source = Source(snapshot()); source.failure = denied
        val sync = LedgerSnapshotSync(scope, cache, source)
        val refresh = async { runCatching { sync.refresh() } }
        try {
            erasing.await()
            val close = async { runCatching { sync.close() } }; runCurrent()
            assertFalse(close.isCompleted)
            release.complete(Unit)
            assertSame(denied, refresh.await().exceptionOrNull())
            assertTrue(close.await().isSuccess)
            assertNull(cache.rows[scope]); assertEquals(1, cache.clearCalls); assertEquals(0, cache.closeCalls)
        } finally { release.complete(Unit) }
    }

    @Test fun normalShutdownKeepsCommittedFactsForTheNextOwner() = runTest {
        val cache = Cache(); val expected = snapshot(); cache.rows[scope] = expected
        val sync = LedgerSnapshotSync(scope, cache, Source(expected))
        sync.close(); sync.close()
        assertSame(expected, cache.rows[scope], "Normal shutdown must preserve offline facts")
        assertEquals(1, cache.closeCalls)
        assertFailsWith<CancellationException> { sync.cached() }
        assertFailsWith<IllegalStateException> { sync.clear() }
        assertSame(expected, cache.rows[scope])
    }

    @Test fun failedPreserveClosePinsItsModeUntilThatSameOperationSucceeds() = runTest {
        val cache = Cache(); val expected = snapshot(); cache.rows[scope] = expected
        val failure = IllegalStateException("synthetic close failure"); cache.closeFailure = failure
        val sync = LedgerSnapshotSync(scope, cache, Source(expected))
        val actual = assertFailsWith<IllegalStateException> { sync.close() }
        assertTrue(generateSequence(actual as Throwable?) { it.cause }.any { it === failure })
        assertFailsWith<CancellationException> { sync.cached() }
        assertEquals("ledger_cache_close_mode_invalid", assertFailsWith<IllegalStateException> { sync.clear() }.message)
        assertSame(expected, cache.rows[scope])
        cache.closeFailure = null
        sync.close(); sync.close()
        assertEquals(2, cache.closeCalls)
        assertSame(expected, cache.rows[scope])
    }

    @Test fun cancelledShutdownWaitsForActualCloseAndKeepsLateRejectionsFromErasingFacts() = runTest {
        val cache = Cache(); val expected = snapshot(); cache.rows[scope] = expected
        val closing = CompletableDeferred<Unit>(); val finishClose = CompletableDeferred<Unit>()
        cache.beforeClose = { closing.complete(Unit); finishClose.await() }
        val requested = CompletableDeferred<Unit>(); val reply = CompletableDeferred<Unit>()
        val source = Source(expected)
        source.beforeVersion = { requested.complete(Unit); reply.await() }
        val denied = DomainFailure("access_denied", 403, FailureCategory.RECOVERABLE); source.failure = denied
        val sync = LedgerSnapshotSync(scope, cache, source)
        val refresh = async { runCatching { sync.refresh() } }; requested.await()
        val shutdown = launch { sync.close() }
        try {
            closing.await()
            shutdown.cancel(); runCurrent()
            assertFalse(shutdown.isCompleted, "Cancellation cannot release an unfinished cache owner")
            val read = async { runCatching { sync.cached() } }; runCurrent()
            assertFalse(read.isCompleted)
            reply.complete(Unit)
            val late = assertIs<CancellationException>(refresh.await().exceptionOrNull())
            assertTrue(late.suppressedExceptions.contains(denied))
            assertSame(expected, cache.rows[scope])
            finishClose.complete(Unit); shutdown.join()
            assertIs<CancellationException>(read.await().exceptionOrNull())
            sync.close()
            assertEquals(1, cache.closeCalls)
            assertSame(expected, cache.rows[scope])
        } finally { reply.complete(Unit); finishClose.complete(Unit) }
    }
    private val scope = LedgerCacheScope("https://one.test", "person@one.test", "workspace")
    private val empty = LedgerFactsDto(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "0")
    private fun snapshot(version: String = "1") = LedgerSnapshotDto(scope.workspaceId, version, "cash-rules-v1", empty)
    private class Cache : LedgerFactCache {
        val rows = mutableMapOf<LedgerCacheScope, LedgerSnapshotDto>()
        var writes = 0
        var failWrite = false
        var clearFailure: Throwable? = null
        var clearCalls = 0
        var beforeClear: suspend () -> Unit = {}
        override suspend fun read(scope: LedgerCacheScope) = rows[scope]
        override suspend fun replace(scope: LedgerCacheScope, snapshot: LedgerSnapshotDto) {
            if (failWrite) error("synthetic storage failure")
            rows[scope] = snapshot; writes++
        }
        override suspend fun clear(scope: LedgerCacheScope) {
            clearCalls++; beforeClear(); clearFailure?.let { throw it }; rows.remove(scope)
        }
        var closeCalls = 0
        var closeFailure: Throwable? = null
        var beforeClose: suspend () -> Unit = {}
        override suspend fun close() { closeCalls++; beforeClose(); closeFailure?.let { throw it } }
    }
    private class Source(var current: LedgerSnapshotDto) : LedgerSnapshotSource {
        var downloads = 0
        var failure: Throwable? = null
        var beforeVersion: suspend () -> Unit = {}
        override suspend fun version(workspaceId: String): LedgerVersionDto {
            beforeVersion(); failure?.let { throw it }; return LedgerVersionDto(current.ledgerSeq)
        }
        override suspend fun snapshot(workspaceId: String): LedgerSnapshotDto { downloads++; return current }
    }
    @Test fun unchangedFactsAreNotDownloadedAndLargeChangedVersionsReplaceAsOneSnapshot() = runTest {
        val cache = Cache(); val original = snapshot(); cache.rows[scope] = original
        val source = Source(original); val sync = LedgerSnapshotSync(scope, cache, source)
        assertSame(original, sync.refresh()); assertEquals(0, source.downloads)
        source.current = snapshot("9007199254740993")
        assertEquals(source.current, sync.refresh()); assertEquals(1, source.downloads); assertEquals(1, cache.writes)
        assertEquals(source.current, sync.cached())
        val other = scope.copy(principal = "other@one.test"); cache.rows[other] = snapshot("17")
        sync.clear()
        assertNull(cache.rows[scope]); assertEquals("17", cache.rows[other]?.ledgerSeq)
    }
    @Test fun aConfirmedCommitRequiresItsFactsEvenWhenTheVersionQueryIsStillOld() = runTest {
        val cache = Cache(); val original = snapshot("9007199254740992"); cache.rows[scope] = original
        val committed = snapshot("9007199254740993")
        var downloads = 0
        val source = object : LedgerSnapshotSource {
            override suspend fun version(workspaceId: String) = LedgerVersionDto(original.ledgerSeq)
            override suspend fun snapshot(workspaceId: String): LedgerSnapshotDto { downloads++; return committed }
        }
        val sync = LedgerSnapshotSync(scope, cache, source)
        assertSame(committed, sync.refresh(committedLedgerSeq = committed.ledgerSeq))
        assertSame(committed, sync.cached()); assertEquals(1, downloads); assertEquals(1, cache.writes)
    }
    @Test fun aSnapshotBelowTheReceiptNeverAdvancesLocalFactsAndCanBeRetried() = runTest {
        val cache = Cache(); val original = snapshot("1"); cache.rows[scope] = original
        val source = Source(original); val sync = LedgerSnapshotSync(scope, cache, source)
        assertEquals("ledger_snapshot_version_regressed", assertFailsWith<IllegalStateException> {
            sync.refresh(committedLedgerSeq = "2")
        }.message)
        assertSame(original, sync.cached()); assertEquals(0, cache.writes); assertEquals(1, source.downloads)
        source.current = snapshot("3")
        assertSame(source.current, sync.refresh(committedLedgerSeq = "2"))
        assertEquals(1, cache.writes)
        assertSame(source.current, sync.refresh(committedLedgerSeq = "2"))
        assertEquals(2, source.downloads)
    }
    @Test fun networkAndStorageFailuresKeepThePreviousCompleteSnapshot() = runTest {
        val cache = Cache(); val original = snapshot(); cache.rows[scope] = original
        val source = Source(snapshot("2")); val sync = LedgerSnapshotSync(scope, cache, source)
        val offline = IllegalStateException("synthetic network failure"); source.failure = offline
        assertSame(offline, assertFailsWith<IllegalStateException> { sync.refresh() })
        assertSame(original, sync.cached())
        source.failure = null; cache.failWrite = true
        assertEquals("synthetic storage failure", assertFailsWith<IllegalStateException> { sync.refresh() }.message)
        assertSame(original, sync.cached())
    }
    @Test fun aForeignOrOlderThanTheQueriedSnapshotNeverPublishes() = runTest {
        for (foreign in listOf(true, false)) {
            val cache = Cache(); val original = snapshot(); cache.rows[scope] = original
            val source = object : LedgerSnapshotSource {
                override suspend fun version(workspaceId: String) = LedgerVersionDto("3")
                override suspend fun snapshot(workspaceId: String) = if (foreign) this@LedgerSnapshotSyncTest.snapshot("3").copy(workspaceId = "foreign") else this@LedgerSnapshotSyncTest.snapshot("2")
            }
            val sync = LedgerSnapshotSync(scope, cache, source)
            assertFailsWith<IllegalStateException> { sync.refresh() }
            assertSame(original, sync.cached()); assertEquals(0, cache.writes)
        }
    }
    @Test fun closingDuringNetworkReadCannotPublishOrReopenTheClearedScope() = runTest {
        val cache = Cache(); cache.rows[scope] = snapshot()
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val source = Source(snapshot("2")); source.beforeVersion = { started.complete(Unit); release.await() }
        val sync = LedgerSnapshotSync(scope, cache, source)
        val refresh = async { sync.refresh() }; started.await()
        val close = async { sync.clear() }; runCurrent()
        assertTrue(close.isCompleted, "closing must clear without waiting for the network")
        close.await(); release.complete(Unit)
        assertFailsWith<CancellationException> { refresh.await() }; close.await()
        assertNull(cache.rows[scope]); assertEquals(0, cache.writes)
        assertFailsWith<CancellationException> { sync.refresh() }
        assertFailsWith<CancellationException> { sync.cached() }
    }
    @Test fun accessRevocationClearsFactsButCancellationPreservesThem() = runTest {
        for (status in listOf(401, 403, 0)) {
            val cache = Cache(); val original = snapshot(); cache.rows[scope] = original
            val source = Source(original)
            source.failure = if (status == 0) CancellationException("cancelled") else DomainFailure("access_denied", status, FailureCategory.RECOVERABLE)
            val sync = LedgerSnapshotSync(scope, cache, source)
            if (status == 0) {
                assertFailsWith<CancellationException> { sync.refresh() }; assertSame(original, sync.cached())
            } else {
                assertSame(source.failure, assertFailsWith<DomainFailure> { sync.refresh() })
                assertNull(cache.rows[scope]); assertFailsWith<CancellationException> { sync.cached() }
            }
        }
    }
    @Test fun aClosedOldLoginCannotClearAReopenedScopeAfterALateAccessFailure() = runTest {
        val cache = Cache(); cache.rows[scope] = snapshot()
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val source = Source(snapshot("2"))
        source.beforeVersion = { started.complete(Unit); release.await() }
        source.failure = DomainFailure("access_denied",403,FailureCategory.RECOVERABLE)
        val old = LedgerSnapshotSync(scope,cache,source)
        val pending = async { runCatching { old.refresh() } }; started.await()
        old.clear()
        val replacement = snapshot("5")
        val reopened = LedgerSnapshotSync(scope,cache,Source(replacement))
        assertEquals(replacement,reopened.refresh())
        release.complete(Unit)
        assertTrue(pending.await().exceptionOrNull() is CancellationException)
        assertEquals(replacement,reopened.cached())
        old.clear()
        assertEquals(replacement,reopened.cached())
    }
    @Test fun aServerVersionRollbackPreservesTheNewerCompleteCache() = runTest {
        val cache = Cache(); val original = snapshot("10"); cache.rows[scope] = original
        val sync = LedgerSnapshotSync(scope,cache,Source(snapshot("3")))
        assertFailsWith<IllegalStateException> { sync.refresh() }
        assertSame(original,sync.cached()); assertEquals(0,cache.writes)
    }
    @Test fun failedOrCancelledCleanupKeepsTheScopeClosedAndCanBeRetriedWithoutLosingTheAuthCause() = runTest {
        for (clearFailure in listOf(IllegalStateException("clear failed"),CancellationException("clear cancelled"))) {
            val cache=Cache();cache.rows[scope]=snapshot();cache.clearFailure=clearFailure
            val denied=DomainFailure("access_denied",403,FailureCategory.RECOVERABLE)
            val source=Source(snapshot());source.failure=denied
            val sync=LedgerSnapshotSync(scope,cache,source)
            val actual = assertNotNull(runCatching {sync.refresh()}.exceptionOrNull())
            val chain = generateSequence(actual as Throwable?) { it.cause }.toList()
            assertEquals(clearFailure::class, actual::class)
            assertTrue(chain.any { it === clearFailure })
            assertTrue(chain.any { it.suppressedExceptions.contains(denied) })
            assertNotNull(cache.rows[scope]);assertFailsWith<CancellationException> {sync.cached()}
            cache.clearFailure=null;sync.clear();assertNull(cache.rows[scope])
        }
    }
    @Test fun overlappingRefreshesPublishOnceAndThenObserveTheNewVersion() = runTest {
        val cache = Cache(); val source = Source(snapshot("2")); source.beforeVersion = { yield() }
        val sync = LedgerSnapshotSync(scope, cache, source)
        val results = awaitAll(async { sync.refresh() }, async { sync.refresh() })
        assertEquals(listOf(source.current, source.current), results)
        assertEquals(1, cache.writes); assertEquals(1, source.downloads)
    }
}
