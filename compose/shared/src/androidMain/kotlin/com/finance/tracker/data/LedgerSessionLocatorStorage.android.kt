package com.finance.tracker.data

import android.content.Context
import com.finance.tracker.core.AndroidTokenStoreContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal actual fun createPlatformLedgerSessionLocatorStorage(): LedgerSessionLocatorStorage =
    AndroidLedgerSessionLocatorStorage(AndroidTokenStoreContext.applicationContext ?: error("ledger_session_storage_unavailable"))

/** The native application has one process; all its locator instances share this short storage lock. */
internal class AndroidLedgerSessionLocatorStorage(context: Context,
    preferencesName: String = "finance-tracker-ledger-session-locator") : LedgerSessionLocatorStorage {
    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    override suspend fun read(key: String): String? = ledgerLocatorMutex.withLock { preferences.getString(key, null) }
    override suspend fun compareAndSet(key: String, expected: String?, replacement: String): Boolean = ledgerLocatorMutex.withLock {
        if (preferences.getString(key, null) != expected) false
        else {
            check(preferences.edit().putString(key, replacement).commit()) { "ledger_session_storage_unavailable" }
            true
        }
    }
}

private val ledgerLocatorMutex = Mutex()
