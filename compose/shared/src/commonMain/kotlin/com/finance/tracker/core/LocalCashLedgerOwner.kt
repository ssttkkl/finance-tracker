package com.finance.tracker.core

import com.finance.tracker.business.contracts.AccountReferenceDto
import com.finance.tracker.business.contracts.CashFiltersDto
import com.finance.tracker.business.contracts.CashPageDto
import com.finance.tracker.business.contracts.CategoryDirectoryDto
import com.finance.tracker.business.contracts.LedgerOptionsDto
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.domain.buildCashLedgerOptions
import com.finance.tracker.business.domain.buildLocalCashCategoryDirectory
import com.finance.tracker.business.domain.buildLocalCashAccountReferences
import com.finance.tracker.domain.LedgerCacheScope
import com.finance.tracker.domain.LedgerFactCache
import com.finance.tracker.domain.LedgerSnapshotSource
import com.finance.tracker.domain.LedgerSnapshotSync
import com.finance.tracker.domain.PreparedLedgerSnapshot
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One authenticated scope's committed browsing state; the supplied cache owns encryption and erasure. */
internal class LocalCashLedgerOwner(scope: LedgerCacheScope, cache: LedgerFactCache, source: LedgerSnapshotSource) {
    private val sync = LedgerSnapshotSync(scope, cache, source)
    private val readerMutex = Mutex()
    private val background = createBackgroundCashLedger(scope.workspaceId)
    private data class Capture(val ledgerSeq: String, val rulesVersion: String, val reader: LocalCashLedgerReader)
    private var capture: Capture? = null // Access only while holding readerMutex.
    private fun active() = sync.requireActive()

    /** Null means no committed cache; an empty committed ledger returns a real page. Never contacts the server. */
    suspend fun page(filters: CashFiltersDto = CashFiltersDto(), timeZoneId: String? = null,
        cursor: String? = null, startIndex: Int? = null, expectedLedgerSeq: Long? = null, limit: Int = 20): CashPageDto? {
        val backgroundReader = background ?: return readSnapshot { snapshot ->
            reader(snapshot).page(filters, timeZoneId, cursor, startIndex, expectedLedgerSeq, limit)
        }
        return readerMutex.withLock {
            active()
            val text = sync.cachedText()
            if (text == null) {
                backgroundReader.close()
                return@withLock null
            }
            backgroundReader.page(text, filters, timeZoneId, cursor, startIndex, expectedLedgerSeq, limit).also { active() }
        }
    }

    suspend fun projectionRefs(transactionIds: List<String>): LocalCashProjectionReferences? {
        val frozen = transactionIds.toList()
        return readSnapshot { snapshot ->
            if (frozen.isEmpty()) LocalCashProjectionReferences(snapshot.ledgerSeq.toLong(), emptyList())
            else reader(snapshot).projectionRefs(frozen)
        }
    }

    /** Both reads reuse one derived capture; the caller holds readerMutex. */
    private fun reader(snapshot: LedgerSnapshotDto): LocalCashLedgerReader =
        (capture?.takeIf { it.ledgerSeq == snapshot.ledgerSeq && it.rulesVersion == snapshot.rulesVersion }
            ?: Capture(snapshot.ledgerSeq, snapshot.rulesVersion, LocalCashLedgerReader(snapshot)).also { capture = it }).reader

    suspend fun currentGroup(projectionId: String) = readSnapshot { reader(it).currentGroup(projectionId) }
    suspend fun record(transactionId: String, canWrite: Boolean) = readSnapshot { reader(it).record(transactionId, canWrite) }
    suspend fun sourceRecords(projectionId: String, canWrite: Boolean) = readSnapshot { reader(it).sourceRecords(projectionId, canWrite) }

    suspend fun categories(): CategoryDirectoryDto? = readSnapshot { snapshot ->
        buildLocalCashCategoryDirectory(snapshot.facts.cashCategories, snapshot.facts.categoryDirectoryRevision)
    }

    suspend fun options(): LedgerOptionsDto? = readSnapshot { buildCashLedgerOptions() }

    suspend fun accounts(): List<AccountReferenceDto>? = readSnapshot { snapshot ->
        buildLocalCashAccountReferences(snapshot.facts.accounts)
    }

    suspend fun snapshot(): LedgerSnapshotDto? = readSnapshot { it }
    suspend fun snapshotText(): String? = sync.cachedText()
    internal suspend fun preparedSnapshot() = sync.cachedPreparedSnapshot()
    suspend fun ledgerVersion(): Long? = sync.cachedVersion()

    /** Catalogs and pages share committed-fact eligibility and the existing read/close boundary. */
    private suspend fun <T> readSnapshot(read: (LedgerSnapshotDto) -> T): T? = readerMutex.withLock {
        active()
        val snapshot = sync.cached()
        active()
        if (snapshot == null) { capture = null; return@withLock null }
        val result = read(snapshot)
        active()
        result
    }

    /** The network runs outside the reader lock. Only successfully published complete facts become readable. */
    suspend fun refresh(committedLedgerSeq: String? = null) = refresh(committedLedgerSeq, null)

    internal suspend fun refresh(committedLedgerSeq: String?, prepare: (suspend () -> PreparedLedgerSnapshot)?) {
        active()
        try {
            if (prepare == null) sync.refresh(committedLedgerSeq) else sync.refreshStored(committedLedgerSeq, prepare)
            active()
        } catch (failure: Throwable) {
            releaseReader()
            throw failure
        }
    }

    suspend fun close() {
        try { sync.close() } finally { releaseReader() }
    }

    suspend fun clear() {
        try { sync.clear() } finally { releaseReader() }
    }

    private suspend fun releaseReader() = withContext(NonCancellable) {
        readerMutex.withLock { capture = null; background?.close() }
    }
}
