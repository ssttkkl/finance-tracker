@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.finance.tracker.data

import com.finance.tracker.domain.LedgerCacheScope
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.Foundation.NSFileManager
import platform.Foundation.NSLock
import platform.Foundation.NSFilePosixPermissions
import platform.posix.LOCK_EX
import platform.posix.LOCK_NB
import platform.posix.O_CLOEXEC
import platform.posix.O_CREAT
import platform.posix.O_NOFOLLOW
import platform.posix.O_RDWR
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.close
import platform.posix.errno
import platform.posix.fchmod
import platform.posix.flock
import platform.posix.lstat
import platform.posix.open
import platform.posix.stat

internal fun appleLedgerStorageId(scope: LedgerCacheScope): String = ledgerStorageId(scope)

/** Retain through database shutdown and erasure. The lock inode is never part of erased facts. */
internal class AppleLedgerStorageLease private constructor(
    val storageId: String,
    val factsDirectory: String,
    private var descriptor: Int,
) {
    private val lock = NSLock()
    private var closeFailure: Throwable? = null

    fun release() {
        lock.lock()
        try {
            closeFailure?.let { throw it }
            if (descriptor < 0) return
            val owned = descriptor
            descriptor = -1
            if (close(owned) != 0) {
                val failure = IllegalStateException("ledger_lease_close_failed:$errno")
                closeFailure = failure
                throw failure
            }
        } finally { lock.unlock() }
    }

    companion object {
        fun acquire(root: String, scope: LedgerCacheScope): AppleLedgerStorageLease {
            require(root.startsWith('/') && '\u0000' !in root) { "ledger_directory_invalid" }
            val id = appleLedgerStorageId(scope)
            val locks = "$root/locks"
            for (directory in listOf(root, locks)) {
                check(NSFileManager.defaultManager.createDirectoryAtPath(directory, true,
                    mapOf(NSFilePosixPermissions to 0x1C0), null)) { "ledger_directory_create_failed" }
                memScoped {
                    val info = alloc<stat>()
                    check(lstat(directory, info.ptr) == 0 &&
                        info.st_mode.toInt() and S_IFMT == S_IFDIR) { "ledger_directory_invalid" }
                }
            }
            val descriptor = open("$locks/$id.lock", O_CREAT or O_RDWR or O_CLOEXEC or O_NOFOLLOW, 0x180)
            check(descriptor >= 0) { "ledger_lease_open_failed:$errno" }
            try {
                check(fchmod(descriptor, 0x180u) == 0) { "ledger_lease_permissions_failed:$errno" }
                check(flock(descriptor, LOCK_EX or LOCK_NB) == 0) { "ledger_lease_unavailable:$errno" }
                return AppleLedgerStorageLease(id, "$root/facts/$id", descriptor)
            } catch (failure: Throwable) {
                if (close(descriptor) != 0) {
                    failure.addSuppressed(IllegalStateException("ledger_lease_close_failed:$errno"))
                }
                throw failure
            }
        }
    }
}
