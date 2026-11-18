package com.finance.tracker.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.Foundation.NSUserDefaults

internal actual fun createPlatformLedgerSessionLocatorStorage(): LedgerSessionLocatorStorage = AppleLedgerSessionLocatorStorage()

/** Only opaque references use defaults. Identity and financial content stay in the encrypted scope. */
internal class AppleLedgerSessionLocatorStorage(private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults) : LedgerSessionLocatorStorage {
    override suspend fun read(key: String): String? = ledgerLocatorMutex.withLock { readValue(key) }
    override suspend fun compareAndSet(key: String, expected: String?, replacement: String): Boolean = ledgerLocatorMutex.withLock {
        if (readValue(key) != expected) false
        else {
            defaults.setObject(replacement, forKey = key)
            check(defaults.synchronize()) { "ledger_session_storage_unavailable" }
            true
        }
    }
    private fun readValue(key: String): String? {
        val value = defaults.objectForKey(key) ?: return null
        check(value is String) { "ledger_session_storage_invalid" }
        return value
    }
}

private val ledgerLocatorMutex = Mutex()
