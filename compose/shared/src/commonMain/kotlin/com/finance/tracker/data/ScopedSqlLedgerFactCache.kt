package com.finance.tracker.data

import app.cash.sqldelight.db.SqlDriver
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.contracts.SessionDto
import com.finance.tracker.domain.LedgerCacheScope
import com.finance.tracker.domain.CashCreationRecovery
import com.finance.tracker.domain.LedgerSnapshotTextCache
import com.finance.tracker.domain.PreparedLedgerSnapshot
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
    private val retainSnapshotText: Boolean,
) : LedgerStorage, LedgerSnapshotTextCache {
    private val mutex = Mutex()
    private val closed = MutableStateFlow(false)
    private var driverClosed = false
    private var erased = false
    private var released = false
    private enum class Termination { PRESERVE, ERASE }
    private var termination: Termination? = null // Access only while holding mutex.
    private var sessions: SqlLedgerSessionStore? = null // Access only while holding mutex.
    private var cashCreationStore: SqlCashCreationJournal? = null
    private var assistantStore: SqlLedgerAssistantConversations? = null
    private var importStore: SqlLedgerImportBatchStore? = null // Access only while holding mutex.
    private data class RetainedSnapshot(val text: String, val ledgerVersion: Long)
    private var retainedSnapshot: RetainedSnapshot? = null // Scoped owner and storage mutex.

    override val imports: LedgerImportBatches = object : LedgerImportBatches {
        override suspend fun list(): List<String> = withImports { it.list(scope) }
        override suspend fun read(batchId: String): List<LocalCashImportFile>? = withImports { it.read(scope, batchId) }
        override suspend fun readEncoded(batchId: String): List<String>? = withImports { it.readEncoded(scope, batchId) }
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
        override suspend fun readEncodedReview(batchId: String) = withImports { it.readEncodedReview(scope, batchId) }
        override suspend fun saveReview(batchId: String, input: com.finance.tracker.business.domain.LocalCashImportReview) =
            withImports { it.saveReview(scope, batchId, input) }
        override suspend fun readSubmission(batchId: String): LocalCashImportSubmission? = withImports { it.readSubmission(scope, batchId) }
        override suspend fun prepareSubmission(batchId: String, input: LocalCashImportSubmission) =
            withImports { it.prepareSubmission(scope, batchId, input) }
        override suspend fun recordTransport(batchId: String, expected: LocalCashImportSubmission, transport: String) =
            withImports { it.recordTransport(scope, batchId, expected, transport) }
        override suspend fun clearTransport(batchId: String, expected: LocalCashImportSubmission) =
            withImports { it.clearTransport(scope, batchId, expected) }
        override suspend fun discardUnprovableSubmission(batchId: String, expected: LocalCashImportSubmission) =
            withImports { it.discardUnprovableSubmission(scope, batchId, expected) }
        override suspend fun recordBaseRejection(batchId: String, expected: LocalCashImportSubmission) =
            withImports { it.recordBaseRejection(scope, batchId, expected) }
        override suspend fun retireRejectedSubmission(batchId: String, expected: LocalCashImportSubmission, newerBase: String, replacement: LocalCashImportSubmission?) =
            withImports { it.retireRejectedSubmission(scope, batchId, expected, newerBase, replacement) }
        override suspend fun recordReceipt(batchId: String, receipt: com.finance.tracker.business.contracts.CashMutationReceiptDto) =
            withImports { it.recordReceipt(scope, batchId, receipt) }
        override suspend fun archiveResult(batchId: String, serverItems: List<com.finance.tracker.business.contracts.CashMutationResultDto>?) = withImports { it.archiveResult(scope, batchId, serverItems) }
        override suspend fun archiveResultPages(batchId: String, pages: List<String>) = withImports { it.archiveResultPages(scope, batchId, pages) }
        override suspend fun readResult(receiptId: String) = withImports { it.readResult(scope, receiptId) }
        override suspend fun readResultByBatchId(batchId: String) = withImports { it.readResultByBatchId(scope, batchId) }
        override suspend fun readResultByKey(key: String) = withImports { it.readResultByKey(scope, key) }
        override suspend fun completeSubmission(batchId: String) = completeSubmission(batchId, null)
        override suspend fun completeSubmission(batchId: String, archived: LocalCashImportResult?) = withImports { batches ->
            val version = if (retainSnapshotText) {
                currentSnapshotText()
                retainedSnapshot?.ledgerVersion?.toString()
            } else store.read(scope)?.ledgerSeq
            currentCoroutineContext().ensureActive()
            requireActive()
            batches.completeSubmission(scope, batchId, version, archived)
        }
    }

    override fun cashCreationJournal(token: String): CashCreationJournal {
        val tokenKey = ledgerOfflineTokenDigest(token)
        return object : CashCreationJournal {
            override suspend fun read() = withCashCreation { it.read(scope, tokenKey) }
            override suspend fun swap(previous: CashCreationRecovery?, next: CashCreationRecovery?) =
                withCashCreation { it.swap(scope, tokenKey, previous, next) }
        }
    }

    override fun assistantConversations(): LedgerAssistantConversations = object : LedgerAssistantConversations {
        override suspend fun list() = withAssistantStore { it.list() }
        override suspend fun delete(previous: LedgerAssistantConversation) = withAssistantStore { it.delete(previous) }
        override suspend fun swap(previous: LedgerAssistantConversation?, next: LedgerAssistantConversation) =
            withAssistantStore { it.swap(previous, next) }
    }

    private suspend fun <T> withAssistantStore(action: suspend (SqlLedgerAssistantConversations) -> T): T = mutex.withLock {
        currentCoroutineContext().ensureActive()
        requireActive()
        val conversations = assistantStore ?: SqlLedgerAssistantConversations.open(driver).also { assistantStore = it }
        requireActive()
        action(conversations).also {
            currentCoroutineContext().ensureActive()
            requireActive()
        }
    }

    private suspend fun <T> withCashCreation(call: suspend (SqlCashCreationJournal) -> T): T = mutex.withLock {
        currentCoroutineContext().ensureActive()
        requireActive()
        val journal = cashCreationStore ?: SqlCashCreationJournal.open(driver).also { cashCreationStore = it }
        requireActive()
        val result = call(journal)
        requireActive()
        currentCoroutineContext().ensureActive()
        result
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
            eraseStorage: suspend () -> Unit, releaseStorage: suspend () -> Unit = {},
            retainSnapshotText: Boolean = false): ScopedSqlLedgerFactCache = try {
            val store = SqlLedgerSnapshotStore.open(driver)
            currentCoroutineContext().ensureActive()
            ScopedSqlLedgerFactCache(scope, driver, store, closeDriver, eraseStorage, releaseStorage, retainSnapshotText)
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                try { closeDriver() }
                catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            }
            throw failure
        }
    }

    override suspend fun readSnapshotText(scope: LedgerCacheScope): String? {
        requireScope(scope)
        return mutex.withLock {
            requireActive()
            val text = currentSnapshotText()
            requireActive()
            text
        }
    }

    override suspend fun readSnapshotVersion(scope: LedgerCacheScope): Long? {
        requireScope(scope)
        return mutex.withLock {
            requireActive()
            val version = if (retainSnapshotText) {
                currentSnapshotText()
                retainedSnapshot?.ledgerVersion
            } else store.read(scope)?.ledgerSeq?.toLong()
            requireActive()
            version
        }
    }

    override suspend fun readPreparedSnapshot(scope: LedgerCacheScope): PreparedLedgerSnapshot? {
        requireScope(scope)
        return mutex.withLock {
            requireActive()
            val text = currentSnapshotText() ?: return@withLock null
            val sequence = retainedSnapshot?.ledgerVersion?.toString() ?: store.decode(scope, text).ledgerSeq
            requireActive()
            PreparedLedgerSnapshot(text, scope.workspaceId, sequence)
        }
    }

    private suspend fun currentSnapshotText(): String? = retainedSnapshot?.text ?: store.readText(scope)?.also { text ->
        if (retainSnapshotText) {
            val version = validateCachedLedgerVersion(scope.workspaceId, text) ?: store.decode(scope, text).ledgerSeq.toLong()
            retainedSnapshot = RetainedSnapshot(text, version)
        }
    }

    override suspend fun read(scope: LedgerCacheScope): LedgerSnapshotDto? {
        requireScope(scope)
        return mutex.withLock {
            requireActive()
            val snapshot = if (retainSnapshotText) currentSnapshotText()?.let { store.decode(scope, it) } else store.read(scope)
            requireActive()
            snapshot
        }
    }

    override suspend fun replace(scope: LedgerCacheScope, snapshot: LedgerSnapshotDto) {
        requireScope(scope)
        mutex.withLock {
            requireActive()
            retainedSnapshot = null // A failed acknowledgement can follow a successful storage write.
            val text = store.replace(scope, snapshot)
            if (retainSnapshotText) retainedSnapshot = RetainedSnapshot(text, snapshot.ledgerSeq.toLong())
            requireActive()
        }
    }

    override suspend fun replaceSnapshot(scope: LedgerCacheScope, snapshot: PreparedLedgerSnapshot) {
        requireScope(scope)
        check(snapshot.workspaceId == scope.workspaceId) { "ledger_snapshot_workspace_mismatch" }
        val version = checkNotNull(snapshot.ledgerSeq.toLongOrNull()?.takeIf { it >= 0 }) { "ledger_version_invalid" }
        mutex.withLock {
            requireActive()
            retainedSnapshot = null
            store.replaceText(scope, snapshot.text)
            if (retainSnapshotText) retainedSnapshot = RetainedSnapshot(snapshot.text, version)
            requireActive()
        }
    }

    override suspend fun matchesOfflineSession(scope: LedgerCacheScope, token: String): Boolean {
        requireScope(scope)
        return mutex.withLock {
            requireActive()
            val matches = sessionStore().read(scope, token) != null
            requireActive()
            matches
        }
    }

    override suspend fun readOfflineSession(scope: LedgerCacheScope, token: String): SessionDto? {
        requireScope(scope)
        return mutex.withLock {
            requireActive()
            val hasFacts = if (retainSnapshotText) currentSnapshotText() != null else store.read(scope) != null
            requireActive()
            if (!hasFacts) return@withLock null
            val session = sessionStore().read(scope, token)
            requireActive()
            session
        }
    }

    override suspend fun saveOfflineSession(scope: LedgerCacheScope, token: String, session: SessionDto) {
        requireScope(scope)
        mutex.withLock {
            requireActive()
            check(if (retainSnapshotText) currentSnapshotText() != null else store.read(scope) != null) { "ledger_cache_missing" }
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
            retainedSnapshot = null
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
