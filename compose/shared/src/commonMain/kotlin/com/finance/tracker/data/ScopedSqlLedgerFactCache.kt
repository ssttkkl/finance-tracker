package com.finance.tracker.data

import app.cash.sqldelight.db.SqlDriver
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.contracts.SessionDto
import com.finance.tracker.domain.LedgerCacheScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A platform factory supplies the encrypted driver and owns physical files and key material. */
internal class ScopedSqlLedgerFactCache private constructor(
    private val scope: LedgerCacheScope,
    private val driver: SqlDriver,
    private val store: SqlLedgerSnapshotStore,
    private val closeDriver: suspend () -> Unit,
    private val eraseStorage: suspend () -> Unit,
    private val releaseStorage: suspend () -> Unit,
) : LedgerStorage {
    private val mutex = Mutex()
    private val closed = MutableStateFlow(false)
    private var driverClosed = false
    private var erased = false
    private var released = false
    private enum class Termination { PRESERVE, ERASE }
    private var termination: Termination? = null // Access only while holding mutex.
    private var sessions: SqlLedgerSessionStore? = null // Access only while holding mutex.
    private var importStore: SqlLedgerImportBatchStore? = null // Access only while holding mutex.

    override val imports: LedgerImportBatches = object : LedgerImportBatches {
        override suspend fun list(): List<String> = withImports { it.list(scope) }
        override suspend fun read(batchId: String): List<LocalCashImportFile>? = withImports { it.read(scope, batchId) }
        override suspend fun save(batchId: String, files: List<LocalCashImportFile>) {
            val frozen = files.toList()
            withImports { it.save(scope, batchId, frozen) }
        }
        override suspend fun append(batchId: String, files: List<LocalCashImportFile>) {
            val frozen = files.toList()
            withImports { it.append(scope, batchId, frozen) }
        }
        override suspend fun replaceFile(batchId: String, expected: LocalCashImportFile, replacement: LocalCashImportFile) =
            withImports { it.replaceFile(scope, batchId, expected, replacement) }
        override suspend fun removeFile(batchId: String, expected: LocalCashImportFile) =
            withImports { it.removeFile(scope, batchId, expected) }
        override suspend fun remove(batchId: String) = withImports { it.remove(scope, batchId) }
        override suspend fun readReview(batchId: String): com.finance.tracker.business.domain.LocalCashImportReview? =
            withImports { it.readReview(scope, batchId) }
        override suspend fun saveReview(batchId: String, input: com.finance.tracker.business.domain.LocalCashImportReview) =
            withImports { it.saveReview(scope, batchId, input) }
        override suspend fun readSubmission(batchId: String): LocalCashImportSubmission? = withImports { it.readSubmission(scope, batchId) }
        override suspend fun prepareSubmission(batchId: String, input: LocalCashImportSubmission) =
            withImports { it.prepareSubmission(scope, batchId, input) }
        override suspend fun recordBaseRejection(batchId: String, expected: LocalCashImportSubmission) =
            withImports { it.recordBaseRejection(scope, batchId, expected) }
        override suspend fun retireRejectedSubmission(batchId: String, expected: LocalCashImportSubmission, newerBase: String, replacement: LocalCashImportSubmission?) =
            withImports { it.retireRejectedSubmission(scope, batchId, expected, newerBase, replacement) }
        override suspend fun recordReceipt(batchId: String, receipt: com.finance.tracker.business.contracts.CashMutationReceiptDto) {
            val frozen = LocalCashImportSubmission.freezeReceipt(receipt)
            withImports { it.recordReceipt(scope, batchId, frozen) }
        }
        override suspend fun archiveResult(batchId: String, serverItems: List<com.finance.tracker.business.contracts.CashMutationResultDto>?) = withImports { it.archiveResult(scope, batchId, serverItems) }
        override suspend fun readResult(receiptId: String) = withImports { it.readResult(scope, receiptId) }
        override suspend fun readResultByBatchId(batchId: String) = withImports { it.readResultByBatchId(scope, batchId) }
        override suspend fun readResultByKey(key: String) = withImports { it.readResultByKey(scope, key) }
        override suspend fun completeSubmission(batchId: String) = withImports { batches ->
            val facts = store.read(scope)
            currentCoroutineContext().ensureActive()
            requireActive()
            batches.completeSubmission(scope, batchId, facts?.ledgerSeq)
        }
    }

    private suspend fun <T> withImports(action: suspend (SqlLedgerImportBatchStore) -> T): T = mutex.withLock {
        currentCoroutineContext().ensureActive()
        requireActive()
        val store = importStore ?: SqlLedgerImportBatchStore.open(driver).also { importStore = it }
        currentCoroutineContext().ensureActive()
        requireActive()
        val result = action(store)
        currentCoroutineContext().ensureActive()
        requireActive()
        result
    }

    companion object {
        /** closeDriver must await actual connection shutdown, including Worker/VFS handles. */
        suspend fun open(scope: LedgerCacheScope, driver: SqlDriver, closeDriver: suspend () -> Unit,
            eraseStorage: suspend () -> Unit, releaseStorage: suspend () -> Unit = {}): ScopedSqlLedgerFactCache = try {
            val store = SqlLedgerSnapshotStore.open(driver)
            currentCoroutineContext().ensureActive()
            ScopedSqlLedgerFactCache(scope, driver, store, closeDriver, eraseStorage, releaseStorage)
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                try { closeDriver() }
                catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            }
            throw failure
        }
    }

    override suspend fun read(scope: LedgerCacheScope): LedgerSnapshotDto? {
        requireScope(scope)
        return mutex.withLock {
            requireActive()
            val snapshot = store.read(scope)
            requireActive()
            snapshot
        }
    }

    override suspend fun replace(scope: LedgerCacheScope, snapshot: LedgerSnapshotDto) {
        requireScope(scope)
        mutex.withLock {
            requireActive()
            store.replace(scope, snapshot)
            requireActive()
        }
    }

    override suspend fun readOfflineSession(scope: LedgerCacheScope, token: String): SessionDto? {
        requireScope(scope)
        return mutex.withLock {
            requireActive()
            val facts = store.read(scope)
            requireActive()
            if (facts == null) return@withLock null
            val session = sessionStore().read(scope, token)
            requireActive()
            session
        }
    }

    override suspend fun saveOfflineSession(scope: LedgerCacheScope, token: String, session: SessionDto) {
        requireScope(scope)
        mutex.withLock {
            requireActive()
            check(store.read(scope) != null) { "ledger_cache_missing" }
            requireActive()
            sessionStore().save(scope, token, session)
            requireActive()
        }
    }

    private suspend fun sessionStore(): SqlLedgerSessionStore = sessions
        ?: SqlLedgerSessionStore.open(driver).also { sessions = it }

    override suspend fun clear(scope: LedgerCacheScope) {
        requireScope(scope)
        terminate(Termination.ERASE)
    }

    override suspend fun close() = terminate(Termination.PRESERVE)

    private suspend fun terminate(requested: Termination) {
        closed.value = true
        mutex.withLock {
            check(termination == null || termination == requested) { "ledger_cache_close_mode_invalid" }
            termination = requested
            if (!driverClosed) { closeDriver(); driverClosed = true }
            if (requested == Termination.ERASE) {
                if (!erased) { eraseStorage(); erased = true }
            } else if (!released) { releaseStorage(); released = true }
        }
    }

    private fun requireScope(scope: LedgerCacheScope) {
        require(scope == this.scope) { "ledger_cache_scope_invalid" }
    }

    private fun requireActive() {
        if (closed.value) throw CancellationException("ledger_cache_closed")
    }
}
