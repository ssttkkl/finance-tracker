package com.finance.tracker

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.finance.tracker.data.AndroidLedgerStorageLease
import com.finance.tracker.data.ledgerStorageId
import com.finance.tracker.domain.LedgerCacheScope
import java.io.File
import java.io.IOException
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import kotlin.test.*

@RunWith(AndroidJUnit4::class)
class AndroidLedgerStorageLeaseTest {
    private val scope = LedgerCacheScope("https://example.test", "synthetic-user", "one")

    @Test fun exclusiveLeaseSurvivesFactRemovalAndNormalReleaseIsIdempotent() = withRoot { root ->
        val first = AndroidLedgerStorageLease.acquire(root, scope)
        try {
            assertEquals(ledgerStorageId(scope), first.storageId)
            assertFalse(first.factsDirectory.exists())
            assertFalse(first.factsDirectory.path.contains("synthetic-user"))
            assertFails { AndroidLedgerStorageLease.acquire(root, scope) }
            assertTrue(first.factsDirectory.mkdirs())
            assertTrue(first.factsDirectory.delete())
            assertFails { AndroidLedgerStorageLease.acquire(root, scope) }
            val other = AndroidLedgerStorageLease.acquire(root, scope.copy(workspaceId = "two"))
            other.release(); other.release()
            AndroidLedgerStorageLease.acquire(File(root, "other-root"), scope).release()
        } finally { first.release() }
        val next = AndroidLedgerStorageLease.acquire(root, scope)
        try {
            first.release()
            assertFails { AndroidLedgerStorageLease.acquire(root, scope) }
            assertEquals(first.factsDirectory, next.factsDirectory)
        } finally { next.release() }
        assertTrue(File(root, "locks/${ledgerStorageId(scope)}.lock").isFile)
    }

    @Test fun symlinksCannotRedirectTheRootLocksOrLockFile() = withRoot { root ->
        val outside = File(root, "outside").apply { mkdir() }
        Os.chmod(outside.path, 0x1C0)
        val linkedRoot = File(root, "linked-root")
        Os.symlink(outside.path, linkedRoot.path)
        assertFails { AndroidLedgerStorageLease.acquire(linkedRoot, scope) }
        val linkedLocks = File(root, "locks")
        Os.symlink(outside.path, linkedLocks.path)
        assertFails { AndroidLedgerStorageLease.acquire(root, scope) }
        assertTrue(linkedLocks.delete())
        assertTrue(linkedLocks.mkdir()); Os.chmod(linkedLocks.path, 0x1C0)
        val untouched = File(outside, "unrelated").apply { writeText("synthetic lock target") }
        val before = untouched.readBytes()
        Os.symlink(untouched.path, File(linkedLocks, "${ledgerStorageId(scope)}.lock").path)
        assertFails { AndroidLedgerStorageLease.acquire(root, scope) }
        assertContentEquals(before, untouched.readBytes())
        assertEquals(listOf("unrelated"), outside.listFiles()!!.map { it.name })
    }

    @Test fun existingWidePermissionsAreRejectedWithoutChangingTheFile() = withRoot { root ->
        val initial = AndroidLedgerStorageLease.acquire(root, scope)
        initial.release()
        val file = File(root, "locks/${ledgerStorageId(scope)}.lock")
        file.writeText("synthetic marker")
        Os.chmod(file.path, 0x1A4)
        assertFails { AndroidLedgerStorageLease.acquire(root, scope) }
        assertEquals("synthetic marker", file.readText())
        assertEquals(0x1A4, Os.stat(file.path).st_mode and 0x1FF)
        Os.chmod(file.path, 0x180)
        AndroidLedgerStorageLease.acquire(root, scope).release()
    }

    @Test fun closeConfirmationFailureIsStickyAndDoesNotCloseTheNextOwner() = withRoot { root ->
        val failure = IOException("Synthetic close confirmation failure")
        var closes = 0
        val first = AndroidLedgerStorageLease.acquire(root, scope, closeStream = {
            closes++; it.close(); throw failure
        })
        assertSame(failure, assertFails { first.release() })
        var unexpected: AndroidLedgerStorageLease? = null
        try {
            assertFails { unexpected = AndroidLedgerStorageLease.acquire(File(root, "."), scope) }
        } finally { unexpected?.release() }
        val otherScope = scope.copy(workspaceId = "other-owner")
        val next = AndroidLedgerStorageLease.acquire(root, otherScope)
        try {
            repeat(2) { assertSame(failure, assertFails { first.release() }) }
            assertEquals(1, closes)
            assertFails { AndroidLedgerStorageLease.acquire(root, otherScope) }
        } finally { next.release() }
    }

    @Test fun rejectedDuplicateCannotDropTheKernelLockSeenByAnotherProcess() = withRoot { root ->
        assumeTrue("The separate-process probe requires API 26", Build.VERSION.SDK_INT >= 26)
        val first = AndroidLedgerStorageLease.acquire(root, scope)
        try {
            probe(root, "HELD")
            assertFails { AndroidLedgerStorageLease.acquire(File(root, "."), scope) }
            probe(root, "HELD")
        } finally { first.release() }
        probe(root, "AVAILABLE")
    }

    private fun probe(root: File, expected: String) {
        val output = File(root, "probe.log")
        val process = ProcessBuilder("/system/bin/app_process", "/system/bin",
            "com.finance.tracker.AndroidLedgerLeaseProbe", File(root, "locks/${ledgerStorageId(scope)}.lock").path)
            .apply { environment()["CLASSPATH"] = InstrumentationRegistry.getInstrumentation().context.applicationInfo.sourceDir }
            .redirectErrorStream(true).redirectOutput(output).start()
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Child lease probe timed out")
            val text = output.readText()
            assertEquals(0, process.exitValue(), text)
            assertEquals(listOf("LEASE_PROBE:$expected"), text.lineSequence().filter { it.startsWith("LEASE_PROBE:") }.toList(), text)
        } finally { process.destroyForcibly() }
    }

    private fun withRoot(block: (File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "ledger-lease-${UUID.randomUUID()}")
        assertTrue(root.mkdir()); Os.chmod(root.path, 0x1C0)
        try { block(root) } finally { assertTrue(root.deleteRecursively()) }
    }
}

/** Separate ART process, same synthetic test UID and file; no application or Keystore access. */
object AndroidLedgerLeaseProbe {
    @JvmStatic fun main(args: Array<String>) {
        FileOutputStream(File(args.single()), true).use { stream ->
            val lock = stream.channel.tryLock()
            println("LEASE_PROBE:" + if (lock == null) "HELD" else "AVAILABLE")
            lock?.release()
        }
    }
}
