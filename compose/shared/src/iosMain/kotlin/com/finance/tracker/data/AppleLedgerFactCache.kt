@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.finance.tracker.data

import app.cash.sqldelight.db.SqlDriver
import com.finance.tracker.domain.LedgerCacheScope
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSFilePosixPermissions
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask
import platform.posix.ENOENT
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.errno
import platform.posix.lstat
import platform.posix.stat

internal suspend fun createAppleLedgerFactCache(
    scope: LedgerCacheScope,
    root: String = appleLedgerCacheRoot(),
    existingOnly: Boolean = false,
    registration: LedgerSessionRegistration? = null,
    openDriver: (String, ByteArray) -> SqlDriver = { directory, key -> openAppleLedgerSqlDriver(directory, key) },
): LedgerStorage {
    registration?.requireScope(scope)
    val lease = AppleLedgerStorageLease.acquire(root, scope)
    val vault = AppleLedgerKeyVault(lease.storageId)
    var driver: SqlDriver? = null
    try {
        registration?.beforeOpen()
        storageOperation { NSURL.fileURLWithPath(root).setResourceValue(true, NSURLIsExcludedFromBackupKey, it) }
        val factsParent = "$root/facts"
        val factsExist = itemExists(factsParent, S_IFDIR)
        check(!existingOnly || factsExist) { "ledger_cache_missing" }
        if (!factsExist) {
            storageOperation { NSFileManager.defaultManager.createDirectoryAtPath(factsParent, false,
                mapOf(NSFilePosixPermissions to 0x1C0), it) }
        }
        val directory = lease.factsDirectory
        val directoryExists = itemExists(directory, S_IFDIR)
        val existing = directoryExists && itemExists("$directory/ledger.db", S_IFREG, requireContents = existingOnly)
        check(!existingOnly || existing) { "ledger_cache_missing" }
        if (directoryExists && !existing) {
            val contents = storageOperationValue { NSFileManager.defaultManager.contentsOfDirectoryAtPath(directory, it) }
            check(contents.isEmpty()) { "ledger_database_missing" }
        }
        val storedKey = vault.read()
        check(!existing || storedKey != null) { "ledger_key_missing" }
        val key = storedKey ?: vault.create()
        try {
            storageOperation { NSFileManager.defaultManager.createDirectoryAtPath(directory, true,
                mapOf(NSFilePosixPermissions to 0x1C0), it) }
            driver = openDriver(directory, key)
        } finally { key.fill(0) }
        val owned = checkNotNull(driver)
        return ScopedSqlLedgerFactCache.open(scope, owned, closeDriver = { owned.close() },
            releaseStorage = { lease.release() }, eraseStorage = {
            if (itemExists(factsParent, S_IFDIR) && itemExists(directory, S_IFDIR)) {
                storageOperation { NSFileManager.defaultManager.removeItemAtPath(directory, it) }
            }
            vault.erase()
            registration?.afterErasure()
            lease.release()
        })
    } catch (failure: Throwable) {
        withContext(NonCancellable) {
            var confirmedClosed = failure !is AppleLedgerDriverOpenCloseFailure
            try { driver?.close() }
            catch (cleanup: Throwable) { confirmedClosed = false; failure.addSuppressed(cleanup) }
            if (confirmedClosed) {
                try { lease.release() }
                catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            }
        }
        throw failure
    }
}

internal fun createAppleLedgerCacheErasure(
    scope: LedgerCacheScope,
    directory: LedgerSessionDirectory,
    root: String = appleLedgerCacheRoot(),
): LedgerCacheErasure {
    directory.validateScope(scope)
    return object : LedgerCacheErasure {
        private val mutex = Mutex()
        private var lease: AppleLedgerStorageLease? = null
        private var erased = false
        private var released = false

        override suspend fun clear() {
            mutex.withLock {
                withContext(NonCancellable) {
                    if (!released) {
                        val owned = lease ?: AppleLedgerStorageLease.acquire(root, scope).also { lease = it }
                        if (!erased) {
                            if (itemExists("$root/facts", S_IFDIR) && itemExists(owned.factsDirectory, S_IFDIR)) {
                                storageOperation { NSFileManager.defaultManager.removeItemAtPath(owned.factsDirectory, it) }
                            }
                            AppleLedgerKeyVault(owned.storageId).erase()
                            directory.forget(scope)
                            erased = true
                        }
                        owned.release()
                        released = true
                    }
                }
            }
        }
    }
}

private fun appleLedgerCacheRoot(): String {
    val support = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
        .firstOrNull() as? String ?: error("ledger_directory_unavailable")
    return "$support/FinanceTracker/LedgerCache"
}

private fun itemExists(path: String, type: Int, requireContents: Boolean = false): Boolean = memScoped {
    val info = alloc<stat>()
    if (lstat(path, info.ptr) == 0) {
        check(info.st_mode.toInt() and S_IFMT == type) { "ledger_storage_type_invalid" }
        if (requireContents) check(info.st_size > 0) { "ledger_cache_invalid" }
        true
    } else {
        val code = errno
        check(code == ENOENT) { "ledger_storage_stat_failed:$code" }
        false
    }
}

private fun storageOperation(operation: (CPointer<ObjCObjectVar<NSError?>>) -> Boolean) =
    storageOperationValue { error -> if (operation(error)) Unit else null }

private fun <T : Any> storageOperationValue(operation: (CPointer<ObjCObjectVar<NSError?>>) -> T?): T = memScoped {
    val error = alloc<ObjCObjectVar<NSError?>>()
    error.value = null
    checkNotNull(operation(error.ptr)) { "ledger_storage_failed:${error.value}" }
}
