package com.finance.tracker

import com.finance.tracker.business.contracts.*
import com.finance.tracker.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LedgerSnapshotSyncTest {
    private val scope = LedgerCacheScope("https://one.test", "person@one.test", "workspace")
    private val empty = LedgerFactsDto(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "0")
    private fun snapshot(version: String = "1") = LedgerSnapshotDto(scope.workspaceId, version, "cash-rules-v1", empty)
    private class Cache : LedgerFactCache {
        val rows = mutableMapOf<LedgerCacheScope, LedgerSnapshotDto>()
        var writes = 0
        var failWrite = false
        var clearFailure: Throwable? = null
        override suspend fun read(scope: LedgerCacheScope) = rows[scope]
        override suspend fun replace(scope: LedgerCacheScope, snapshot: LedgerSnapshotDto) {
            if (failWrite) error("synthetic storage failure")
            rows[scope] = snapshot; writes++
        }
        override suspend fun clear(scope: LedgerCacheScope) { clearFailure?.let { throw it }; rows.remove(scope) }
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
        sync.close()
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
        val close = async { sync.close() }; runCurrent()
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
        old.close()
        val replacement = snapshot("5")
        val reopened = LedgerSnapshotSync(scope,cache,Source(replacement))
        assertEquals(replacement,reopened.refresh())
        release.complete(Unit)
        assertTrue(pending.await().exceptionOrNull() is CancellationException)
        assertEquals(replacement,reopened.cached())
        old.close()
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
            assertSame(clearFailure,runCatching {sync.refresh()}.exceptionOrNull())
            assertTrue(clearFailure.suppressedExceptions.contains(denied))
            assertNotNull(cache.rows[scope]);assertFailsWith<CancellationException> {sync.cached()}
            cache.clearFailure=null;sync.close();assertNull(cache.rows[scope])
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
