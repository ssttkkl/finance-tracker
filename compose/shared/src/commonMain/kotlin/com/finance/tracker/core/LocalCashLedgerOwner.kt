package com.finance.tracker.core

import com.finance.tracker.business.contracts.CashFiltersDto
import com.finance.tracker.business.contracts.CashPageDto
import com.finance.tracker.domain.LedgerCacheScope
import com.finance.tracker.domain.LedgerFactCache
import com.finance.tracker.domain.LedgerSnapshotSource
import com.finance.tracker.domain.LedgerSnapshotSync
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One authenticated scope's committed browsing state; the supplied cache owns encryption and erasure. */
internal class LocalCashLedgerOwner(scope: LedgerCacheScope, cache: LedgerFactCache, source: LedgerSnapshotSource) {
    private val sync = LedgerSnapshotSync(scope, cache, source)
    private val readerMutex = Mutex()
    private data class Capture(val ledgerSeq: String, val rulesVersion: String, val reader: LocalCashLedgerReader)
    private var capture: Capture? = null // Access only while holding readerMutex.
    private fun active() = sync.requireActive()

    /** Null means no committed cache; an empty committed ledger returns a real page. Never contacts the server. */
    suspend fun page(filters: CashFiltersDto = CashFiltersDto(), timeZoneId: String? = null,
        cursor: String? = null, startIndex: Int? = null, expectedLedgerSeq: Long? = null, limit: Int = 20): CashPageDto? =
        readerMutex.withLock {
            active()
            val snapshot = sync.cached()
            active()
            if (snapshot == null) { capture = null; return@withLock null }
            val current = capture?.takeIf { it.ledgerSeq == snapshot.ledgerSeq && it.rulesVersion == snapshot.rulesVersion }
                ?: Capture(snapshot.ledgerSeq, snapshot.rulesVersion, LocalCashLedgerReader(snapshot)).also { capture = it }
            val page = current.reader.page(filters, timeZoneId, cursor, startIndex, expectedLedgerSeq, limit)
            active()
            page
        }

    /** The network runs outside the reader lock. Only successfully published complete facts become readable. */
    suspend fun refresh(committedLedgerSeq: String? = null) {
        active()
        try { sync.refresh(committedLedgerSeq); active() }
        catch (failure: Throwable) { releaseReader(); throw failure }
    }

    suspend fun close() {
        try { sync.close() } finally { releaseReader() }
    }

    private suspend fun releaseReader() = withContext(NonCancellable) {
        readerMutex.withLock { capture = null }
    }
}
