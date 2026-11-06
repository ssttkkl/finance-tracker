@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.finance.tracker

import com.finance.tracker.data.AppleLedgerStorageLease
import com.finance.tracker.data.appleLedgerStorageId
import com.finance.tracker.domain.LedgerCacheScope
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.F_GETFD
import platform.posix.F_GETPATH
import platform.posix.O_CREAT
import platform.posix.O_RDWR
import platform.posix._SC_OPEN_MAX
import platform.posix.close
import platform.posix.dup2
import platform.posix.fcntl
import platform.posix.open
import platform.posix.sysconf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AppleLedgerStorageLeaseTest {
    @Test
    fun completeScopeHasAStableUnambiguousUnicodeIdentity() {
        assertEquals("8e900c852f51a91ebb3419b17c602a895bcea053aa19ddb4a320bb11220f44c2",
            appleLedgerStorageId(LedgerCacheScope("https://example.test", "user-一", "workspace-😀")))
        val original = LedgerCacheScope("ab", "c", "d")
        val id = appleLedgerStorageId(original)
        for (scope in listOf(original.copy(apiOrigin = "a"), original.copy(principal = "bc"),
            original.copy(workspaceId = "cd"), LedgerCacheScope("a", "bc", "d"))) {
            assertNotEquals(id, appleLedgerStorageId(scope))
        }
        for (invalid in listOf("bad\uD800", "\uDC00")) {
            assertFails { appleLedgerStorageId(original.copy(apiOrigin = invalid)) }
            assertFails { appleLedgerStorageId(original.copy(principal = invalid)) }
            assertFails { appleLedgerStorageId(original.copy(workspaceId = invalid)) }
        }
    }

    @Test
    fun realFileLeaseRemainsExclusiveAcrossFactDirectoryRemoval() = withRoot { root ->
        val scope = LedgerCacheScope("https://example.test", "synthetic-user", "one")
        val first = AppleLedgerStorageLease.acquire(root, scope)
        try {
            assertFalse(first.factsDirectory.contains("synthetic-user"))
            assertFalse(NSFileManager.defaultManager.fileExistsAtPath(first.factsDirectory))
            assertFails { AppleLedgerStorageLease.acquire(root, scope) }
            assertTrue(NSFileManager.defaultManager.createDirectoryAtPath(first.factsDirectory, true, null, null))
            assertTrue(NSFileManager.defaultManager.removeItemAtPath(first.factsDirectory, null))
            assertFails { AppleLedgerStorageLease.acquire(root, scope) }
            val other = AppleLedgerStorageLease.acquire(root, scope.copy(workspaceId = "two"))
            other.release()
            other.release()
        } finally { first.release() }
        val reopened = AppleLedgerStorageLease.acquire(root, scope)
        assertEquals(first.factsDirectory, reopened.factsDirectory)
        reopened.release()
        first.release()
        val final = AppleLedgerStorageLease.acquire(root, scope)
        final.release()
    }

    @Test
    fun lockSymlinkCannotRedirectTheLease() = withRoot { root ->
        val scope = LedgerCacheScope("https://example.test", "synthetic-user", "one")
        val id = appleLedgerStorageId(scope)
        val files = NSFileManager.defaultManager
        assertTrue(files.createDirectoryAtPath("$root/locks", true, null, null))
        assertTrue(files.createSymbolicLinkAtPath("$root/locks/$id.lock", "$root/unrelated", null))
        assertFails { AppleLedgerStorageLease.acquire(root, scope) }
        assertFalse(files.fileExistsAtPath("$root/unrelated"))
    }

    @Test
    fun failedCloseIsRetainedAndNeverClosesAReusedDescriptor() = withRoot { root ->
        val scope = LedgerCacheScope("https://example.test", "synthetic-user", "one")
        val lease = AppleLedgerStorageLease.acquire(root, scope)
        // F_GETPATH resolves the simulator's /var -> /private/var alias.
        val suffix = "/${root.substringAfterLast('/')}/locks/${appleLedgerStorageId(scope)}.lock"
        val descriptor = memScoped {
            val limit = sysconf(_SC_OPEN_MAX).toInt()
            assertTrue(limit > 0)
            val buffer = allocArray<ByteVar>(1024)
            (0 until limit).single { fcntl(it, F_GETPATH, buffer) == 0 && buffer.toKString().endsWith(suffix) }
        }
        // Break ownership deliberately to obtain a real EBADF, not a mocked close result.
        assertEquals(0, close(descriptor))
        val failure = assertFails { lease.release() }
        assertTrue(failure.message.orEmpty().startsWith("ledger_lease_close_failed:"))
        val unrelated = open("$root/unrelated", O_RDWR or O_CREAT, 0x180)
        assertTrue(unrelated >= 0)
        val reused = if (unrelated == descriptor) descriptor else {
            assertEquals(descriptor, dup2(unrelated, descriptor))
            assertEquals(0, close(unrelated))
            descriptor
        }
        try {
            assertSame(failure, assertFails { lease.release() })
            assertTrue(fcntl(reused, F_GETFD) >= 0)
        } finally { assertEquals(0, close(reused)) }
    }

    private fun withRoot(block: (String) -> Unit) {
        val root = NSTemporaryDirectory() + "ledger-lease-${NSUUID().UUIDString}"
        assertTrue(NSFileManager.defaultManager.createDirectoryAtPath(root, true, null, null))
        try { block(root) }
        finally { assertTrue(NSFileManager.defaultManager.removeItemAtPath(root, null)) }
    }
}
