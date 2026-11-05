package com.finance.tracker

import com.finance.tracker.business.contracts.*
import com.finance.tracker.business.domain.CASH_RULES_VERSION
import com.finance.tracker.business.domain.CashProjectionDomainFailure
import com.finance.tracker.core.LocalCashLedgerOwner
import com.finance.tracker.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

internal fun localCashLedgerOwnerSnapshot(sequence: String = "9007199254740993", amount: String = "-2"): LedgerSnapshotDto {
    val empty = LedgerFactsDto(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "0")
    val rows = (1..2).map { id -> LedgerCashTransactionFactDto(id.toString(), "10", "posted", "legacy", null,
        "1", "atomic", null, "record:$id", JsonNull, JsonNull, null, JsonArray(emptyList()), "2026-04-0${id}T00:00:00Z",
        if (id == 1) "-1" else amount, "CNY", "source", "", JsonArray(emptyList()), "source", null,
        "consumption", "not_applicable", "2026-04-03T00:00:00Z", null, "", "") }
    return LedgerSnapshotDto("workspace", sequence, CASH_RULES_VERSION, empty.copy(
        accounts = listOf(LedgerAccountFactDto("10", "Cash", "cash", true, JsonArray(emptyList()), "1")),
        cashTransactions = rows,
        cashEntries = rows.map { LedgerCashEntryFactDto("entry:${it.id}", it.id, it.id, "1", null, "public note",
            "Cafe", "", JsonArray(emptyList()), JsonArray(emptyList()), null) },
        cashEntryMembers = rows.map { LedgerCashEntryMemberFactDto(it.id, "entry:${it.id}", JsonObject(emptyMap()), JsonArray(emptyList())) }))
}

@OptIn(ExperimentalCoroutinesApi::class)
class LocalCashLedgerOwnerTest {
    private val scope = LedgerCacheScope("https://one.test", "person", "workspace")
    private fun snapshot(sequence: String = "9007199254740993", amount: String = "-2") = localCashLedgerOwnerSnapshot(sequence, amount)
    private class Cache : LedgerFactCache {
        val rows = mutableMapOf<LedgerCacheScope, LedgerSnapshotDto>()
        var failure: Throwable? = null
        var clearFailure: Throwable? = null
        var beforeRead: suspend () -> Unit = {}
        override suspend fun read(scope: LedgerCacheScope): LedgerSnapshotDto? { beforeRead(); return rows[scope] }
        override suspend fun replace(scope: LedgerCacheScope, snapshot: LedgerSnapshotDto) { failure?.let { throw it }; rows[scope] = snapshot }
        override suspend fun clear(scope: LedgerCacheScope) { clearFailure?.let { throw it }; rows.remove(scope) }
    }
    private class Source(var current: LedgerSnapshotDto) : LedgerSnapshotSource {
        var versionCalls = 0
        var downloads = 0
        var failure: Throwable? = null
        var beforeVersion: suspend () -> Unit = {}
        override suspend fun version(workspaceId: String): LedgerVersionDto {
            versionCalls++; beforeVersion(); failure?.let { throw it }; return LedgerVersionDto(current.ledgerSeq)
        }
        override suspend fun snapshot(workspaceId: String): LedgerSnapshotDto { downloads++; return current }
    }
    @Test fun offlinePagesUseOneCommittedVersionAndRefreshInvalidatesItsCursor() = runTest {
        val cache = Cache(); cache.rows[scope] = snapshot()
        val source = Source(snapshot()); val owner = LocalCashLedgerOwner(scope, cache, source)
        val filters = CashFiltersDto(counterparty = "cafe")
        val first = assertNotNull(owner.page(filters, "UTC", limit = 1))
        assertEquals("2", first.items.single().transactionId); assertNotNull(first.nextCursor)
        assertEquals("1", owner.page(filters, "UTC", cursor = first.nextCursor, limit = 1)?.items?.single()?.transactionId)
        assertEquals(0, source.versionCalls); assertEquals(0, source.downloads)
        owner.refresh(); assertEquals(1, source.versionCalls); assertEquals(0, source.downloads)
        source.current = snapshot("9007199254740994", "-20"); owner.refresh()
        assertEquals(1, source.downloads)
        val updated = assertNotNull(owner.page(filters, "UTC", limit = 1))
        assertEquals("-20", updated.items.single().amount)
        assertEquals(listOf(CashMonthlySummaryDto("2026-04", listOf(CashMonthlyCurrencySummaryDto("CNY", "0", "-21")))), updated.monthlySummaries)
        assertEquals("read_snapshot_changed", assertFailsWith<CashProjectionDomainFailure> {
            owner.page(filters, "UTC", cursor = first.nextCursor, limit = 1)
        }.code)
    }
    @Test fun anAbsentCacheIsNotAnEmptyLedgerAndOnlyItsFixedScopeIsCleared() = runTest {
        val cache = Cache(); val other = scope.copy(principal = "other"); cache.rows[other] = snapshot()
        val original = snapshot(); val empty = original.copy(facts = original.facts.copy(cashTransactions = emptyList(),
            cashEntries = emptyList(), cashEntryMembers = emptyList()))
        val owner = LocalCashLedgerOwner(scope, cache, Source(empty))
        assertNull(owner.page(timeZoneId = "UTC")); owner.refresh()
        assertEquals(0, assertNotNull(owner.page(timeZoneId = "UTC", startIndex = 0)).totalCount)
        owner.close(); assertNull(cache.rows[scope]); assertNotNull(cache.rows[other])
        assertFailsWith<CancellationException> { owner.page(timeZoneId = "UTC") }
    }
    @Test fun failedRefreshPreservesOldCompleteFactsWithoutHidingTheFailure() = runTest {
        val cache = Cache(); cache.rows[scope] = snapshot()
        val source = Source(snapshot("9007199254740994", "-20")); val owner = LocalCashLedgerOwner(scope, cache, source)
        val original = assertNotNull(owner.page(timeZoneId = "UTC"))
        val network = DomainFailure("network_unavailable", 503, FailureCategory.RECOVERABLE); source.failure = network
        assertSame(network, assertFailsWith<DomainFailure> { owner.refresh() })
        assertEquals(original, owner.page(timeZoneId = "UTC"))
        source.failure = null; val storage = IllegalStateException("synthetic storage failure"); cache.failure = storage
        assertSame(storage, assertFailsWith<IllegalStateException> { owner.refresh() })
        assertEquals(original, owner.page(timeZoneId = "UTC"))
        cache.failure = null; val cancelled = CancellationException("synthetic cancellation"); source.failure = cancelled
        assertSame(cancelled, assertFailsWith<CancellationException> { owner.refresh() })
        assertEquals(original, owner.page(timeZoneId = "UTC"))
    }
    @Test fun closingFencesLateOldAuthenticationFailuresWithoutClearingTheNewOwner() = runTest {
        val cache = Cache(); cache.rows[scope] = snapshot()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val source = Source(snapshot()); source.beforeVersion = { entered.complete(Unit); release.await() }
        source.failure = DomainFailure("access_denied", 403, FailureCategory.RECOVERABLE)
        val old = LocalCashLedgerOwner(scope, cache, source); assertNotNull(old.page(timeZoneId = "UTC"))
        val refresh = async { runCatching { old.refresh() } }; entered.await()
        old.close()
        val fresh = LocalCashLedgerOwner(scope, cache, Source(snapshot("9007199254740994", "-20")))
        fresh.refresh(); release.complete(Unit)
        assertTrue(refresh.await().exceptionOrNull() is CancellationException)
        assertFailsWith<CancellationException> { old.page(timeZoneId = "UTC") }
        old.close()
        assertEquals("-20", fresh.page(timeZoneId = "UTC", limit = 1)?.items?.single()?.amount)
    }
    @Test fun aCloseImmediatelyFencesAReadThatIsWaitingForStorage() = runTest {
        val cache = Cache(); cache.rows[scope] = snapshot()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        cache.beforeRead = { entered.complete(Unit); release.await() }
        val owner = LocalCashLedgerOwner(scope, cache, Source(snapshot()))
        val read = async { runCatching { owner.page(timeZoneId = "UTC") } }; entered.await()
        val close = async { owner.close() }; runCurrent(); assertFalse(close.isCompleted)
        release.complete(Unit)
        assertTrue(read.await().exceptionOrNull() is CancellationException); close.await()
        assertNull(cache.rows[scope]); assertFailsWith<CancellationException> { owner.refresh() }
    }
    @Test fun revokedAccessFencesCachedPagesEvenWhenCleanupNeedsARetry() = runTest {
        val cache = Cache(); cache.rows[scope] = snapshot(); cache.clearFailure = IllegalStateException("cleanup failed")
        val source = Source(snapshot()); source.failure = DomainFailure("access_denied", 401, FailureCategory.RECOVERABLE)
        val owner = LocalCashLedgerOwner(scope, cache, source); assertNotNull(owner.page(timeZoneId = "UTC"))
        val failure = assertFailsWith<IllegalStateException> { owner.refresh() }
        assertEquals("cleanup failed", failure.message); assertSame(source.failure, failure.suppressedExceptions.single())
        assertFailsWith<CancellationException> { owner.page(timeZoneId = "UTC") }
        cache.clearFailure = null; owner.close(); assertNull(cache.rows[scope])
    }
}
