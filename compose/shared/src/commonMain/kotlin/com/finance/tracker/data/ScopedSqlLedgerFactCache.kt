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
) : LedgerStorage {
    private val mutex = Mutex()
    private val closed = MutableStateFlow(false)
    private var driverClosed = false
    private var erased = false
    private var sessions: SqlLedgerSessionStore? = null // Access only while holding mutex.

    companion object {
        /** closeDriver must await actual connection shutdown, including Worker/VFS handles. */
        suspend fun open(scope: LedgerCacheScope, driver: SqlDriver, closeDriver: suspend () -> Unit,
            eraseStorage: suspend () -> Unit): ScopedSqlLedgerFactCache = try {
            val store = SqlLedgerSnapshotStore.open(driver)
            currentCoroutineContext().ensureActive()
            ScopedSqlLedgerFactCache(scope, driver, store, closeDriver, eraseStorage)
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
        closed.value = true
        mutex.withLock {
            if (!driverClosed) { closeDriver(); driverClosed = true }
            if (!erased) { eraseStorage(); erased = true }
        }
    }

    private fun requireScope(scope: LedgerCacheScope) {
        require(scope == this.scope) { "ledger_cache_scope_invalid" }
    }

    private fun requireActive() {
        if (closed.value) throw CancellationException("ledger_cache_closed")
    }
}
