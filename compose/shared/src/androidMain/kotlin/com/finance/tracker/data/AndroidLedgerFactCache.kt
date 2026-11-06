package com.finance.tracker.data

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import app.cash.sqldelight.db.SqlDriver
import com.finance.tracker.domain.LedgerCacheScope
import java.io.File
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Internal platform composition; the application must own the returned cache until clear completes. */
internal suspend fun createAndroidLedgerFactCache(
    context: Context,
    scope: LedgerCacheScope,
    root: File = File(context.applicationContext.noBackupFilesDir, "ledger-cache"),
    existingOnly: Boolean = false,
    openDriver: (File, ByteArray) -> SqlDriver = { directory, key -> openAndroidLedgerSqlDriver(directory, key) },
): LedgerStorage {
    val lease = AndroidLedgerStorageLease.acquire(root, scope)
    val vault = AndroidLedgerKeyVault(context, lease.storageId)
    var driver: SqlDriver? = null
    try {
        val facts = File(root, "facts")
        val factsExist = ledgerItemExists(facts, directory = true)
        check(!existingOnly || factsExist) { "ledger_cache_missing" }
        if (!factsExist) ensureLedgerDirectory(facts)
        val directory = lease.factsDirectory
        val directoryExists = ledgerItemExists(directory, directory = true)
        val existing = directoryExists && ledgerItemExists(File(directory, "ledger.db"), directory = false,
            requireContents = existingOnly)
        check(!existingOnly || existing) { "ledger_cache_missing" }
        if (directoryExists && !existing) {
            check(checkNotNull(directory.list()).isEmpty()) { "ledger_database_missing" }
        }
        val storedKey = vault.read()
        check(!existing || storedKey != null) { "ledger_key_missing" }
        val key = storedKey ?: vault.create()
        try {
            ensureLedgerDirectory(directory)
            driver = openDriver(directory, key)
        } finally { key.fill(0) }
        val owned = checkNotNull(driver)
        return ScopedSqlLedgerFactCache.open(scope, owned, closeDriver = {
            try { owned.close() }
            catch (failure: Throwable) { retainLedgerLease(lease); throw failure }
        }, releaseStorage = { releaseLedgerLease(lease) }, eraseStorage = {
            if (ledgerItemExists(facts, directory = true)) eraseLedgerDirectory(directory)
            vault.erase()
            releaseLedgerLease(lease)
        })
    } catch (failure: Throwable) {
        withContext(NonCancellable) {
            var confirmedClosed = failure !is AndroidLedgerDriverOpenCloseFailure
            try { driver?.close() }
            catch (cleanup: Throwable) { confirmedClosed = false; failure.addSuppressed(cleanup) }
            if (confirmedClosed) {
                try { releaseLedgerLease(lease) }
                catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            } else retainLedgerLease(lease)
        }
        throw failure
    }
}

private fun ensureLedgerDirectory(file: File) {
    try { Os.mkdir(file.path, 0x1C0) }
    catch (failure: ErrnoException) { if (failure.errno != OsConstants.EEXIST) throw failure }
    check(ledgerItemExists(file, directory = true)) { "ledger_directory_missing" }
}

private fun ledgerItemExists(file: File, directory: Boolean, requireContents: Boolean = false): Boolean {
    val stat = try { Os.lstat(file.path) }
    catch (failure: ErrnoException) {
        if (failure.errno == OsConstants.ENOENT) return false
        throw failure
    }
    check(if (directory) OsConstants.S_ISDIR(stat.st_mode) else OsConstants.S_ISREG(stat.st_mode)) {
        "ledger_storage_type_invalid"
    }
    if (directory) check(stat.st_mode and 0x3F == 0) { "ledger_directory_permissions_invalid" }
    if (requireContents) check(stat.st_size > 0) { "ledger_cache_invalid" }
    return true
}

/** lstat every child; never let recursive file helpers follow a link outside this scope. */
private fun eraseLedgerDirectory(directory: File) {
    if (!ledgerItemExists(directory, directory = true)) return
    val children = checkNotNull(directory.listFiles()) { "ledger_directory_unavailable" }
    for (child in children) {
        val stat = Os.lstat(child.path)
        when {
            OsConstants.S_ISDIR(stat.st_mode) -> eraseLedgerDirectory(child)
            OsConstants.S_ISREG(stat.st_mode) -> Os.remove(child.path)
            else -> error("ledger_storage_type_invalid")
        }
    }
    Os.remove(directory.path)
}

private fun releaseLedgerLease(lease: AndroidLedgerStorageLease) {
    try { lease.release() }
    catch (failure: Throwable) { retainLedgerLease(lease); throw failure }
}

private fun retainLedgerLease(lease: AndroidLedgerStorageLease) {
    synchronized(uncertainLedgerLeases) { uncertainLedgerLeases.add(lease) }
}

// A Java stream finalizer must not unlock a scope whose database shutdown is unconfirmed.
private val uncertainLedgerLeases = mutableSetOf<AndroidLedgerStorageLease>()
