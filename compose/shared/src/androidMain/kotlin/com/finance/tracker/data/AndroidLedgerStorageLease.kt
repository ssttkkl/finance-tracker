package com.finance.tracker.data

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.finance.tracker.domain.LedgerCacheScope
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.nio.channels.OverlappingFileLockException

/** The caller retains this lease through actual database close, file erasure and key erasure. */
internal class AndroidLedgerStorageLease private constructor(
    val storageId: String,
    val factsDirectory: File,
    private val closeStream: () -> Unit,
) {
    private var releasing = false
    private var closeFailure: Throwable? = null

    @Synchronized fun release() {
        closeFailure?.let { throw it }
        if (releasing) return
        // Never retry a close that may already have freed its underlying descriptor.
        releasing = true
        try { closeStream() }
        catch (failure: Throwable) { closeFailure = failure; throw failure }
    }

    companion object {
        private val owners = mutableSetOf<String>()

        fun acquire(
            root: File,
            scope: LedgerCacheScope,
            closeStream: (FileOutputStream) -> Unit = { it.close() },
        ): AndroidLedgerStorageLease = synchronized(owners) {
            require(root.isAbsolute && '\u0000' !in root.path) { "ledger_directory_invalid" }
            val id = ledgerStorageId(scope)
            val locks = File(root, "locks")
            for (directory in listOf(root, locks)) {
                try { Os.mkdir(directory.path, 0x1C0) }
                catch (failure: ErrnoException) { if (failure.errno != OsConstants.EEXIST) throw failure }
                val stat = Os.lstat(directory.path)
                check(OsConstants.S_ISDIR(stat.st_mode) && stat.st_mode and 0x3F == 0) {
                    "ledger_directory_invalid"
                }
            }
            // A second channel's close may release this process's native locks.
            // Reject before open, including equivalent root/./ path spellings.
            val lockPath = File(locks.canonicalFile, "$id.lock")
            check(owners.add(lockPath.path)) { "ledger_lease_unavailable" }
            var descriptor: FileDescriptor? = null
            var stream: FileOutputStream? = null
            try {
                descriptor = Os.open(lockPath.path,
                    OsConstants.O_CREAT or OsConstants.O_RDWR or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0x180)
                val owned = FileOutputStream(descriptor)
                stream = owned
                val stat = Os.fstat(descriptor)
                check(OsConstants.S_ISREG(stat.st_mode) && stat.st_mode and 0x3F == 0) { "ledger_lease_file_invalid" }
                val lock = try { owned.channel.tryLock() }
                catch (failure: OverlappingFileLockException) {
                    throw IllegalStateException("ledger_lease_unavailable", failure)
                }
                checkNotNull(lock) { "ledger_lease_unavailable" }
                AndroidLedgerStorageLease(id, File(root, "facts/$id")) {
                    closeStream(owned)
                    synchronized(owners) { owners.remove(lockPath.path) }
                }
            } catch (failure: Throwable) {
                var closed = false
                try {
                    if (stream != null) stream.close() else descriptor?.let { Os.close(it) }
                    closed = true
                } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                if (closed) owners.remove(lockPath.path)
                throw failure
            }
        }
    }
}
