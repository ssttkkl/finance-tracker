package com.finance.tracker

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.finance.tracker.data.AndroidLedgerDriverOpenCloseFailure
import com.finance.tracker.data.AndroidLedgerKeyVault
import com.finance.tracker.data.AndroidLedgerStorageLease
import com.finance.tracker.data.SqlLedgerSnapshotStore
import com.finance.tracker.data.createAndroidLedgerFactCache
import com.finance.tracker.data.ledgerStorageId
import com.finance.tracker.data.openAndroidLedgerSqlDriver
import com.finance.tracker.domain.LedgerCacheScope
import com.finance.tracker.domain.LedgerFactCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import net.zetetic.database.Logger
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.ref.WeakReference
import java.util.UUID
import kotlin.test.*

@RunWith(AndroidJUnit4::class)
class AndroidLedgerFactCacheTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun exactFactsAreEncryptedAndClearRemovesOnlyTheirScope() = runTest {
        withScope { root, scope ->
            val other = scope.copy(workspaceId = "other")
            val first = createAndroidLedgerFactCache(context, scope, root)
            val second = createAndroidLedgerFactCache(context, other, root)
            try {
                val expected = localCashLedgerOwnerSnapshot("9223372036854775807").let { snapshot ->
                    snapshot.copy(workspaceId = scope.workspaceId, facts = snapshot.facts.copy(
                        cashTransactions = snapshot.facts.cashTransactions.map {
                            it.copy(amount = "-12345678901234567890.123456789012345678",
                                sourcePayload = Json.parseToJsonElement("[9007199254740993,18446744073709551616,-0,1.2300,1e400]"))
                        }))
                }
                assertNull(first.read(scope))
                first.replace(scope, expected)
                assertEquals(expected, first.read(scope))
                val otherSnapshot = expected.copy(workspaceId = other.workspaceId)
                second.replace(other, otherSnapshot)
                assertFails { createAndroidLedgerFactCache(context, scope, root) }
                assertFails { first.read(other) }
                val bytes = File(directory(root, scope), "ledger.db").readBytes()
                assertFalse(bytes.take(16).toByteArray().contentEquals("SQLite format 3\u0000".toByteArray()))
                first.clear(scope); first.clear(scope)
                assertFails { first.read(scope) }
                assertFalse(directory(root, scope).exists())
                assertNull(vault(scope).read())
                assertEquals(otherSnapshot, second.read(other))
                assertNotNull(vault(other).read()?.also { it.fill(0) })
                val fresh = createAndroidLedgerFactCache(context, scope, root)
                try { assertNull(fresh.read(scope)) } finally { fresh.clear(scope) }
            } finally {
                first.clear(scope); second.clear(other)
                vault(other).erase()
            }
        }
    }

    @Test fun existingEncryptedSnapshotReopensWithItsOriginalKeystoreKey() = runTest {
        withScope { root, scope ->
            val key = vault(scope).create()
            val dir = directory(root, scope)
            assertTrue(dir.mkdirs()); Os.chmod(File(root, "facts").path, 0x1C0); Os.chmod(dir.path, 0x1C0)
            val expected = localCashLedgerOwnerSnapshot().copy(workspaceId = scope.workspaceId)
            val driver = openAndroidLedgerSqlDriver(dir, key)
            try { SqlLedgerSnapshotStore.open(driver).replace(scope, expected) } finally { driver.close() }
            val cache = createAndroidLedgerFactCache(context, scope, root)
            try {
                assertEquals(expected, cache.read(scope))
                val stored = assertNotNull(vault(scope).read())
                try { assertContentEquals(key, stored) } finally { stored.fill(0) }
            } finally { key.fill(0); cache.clear(scope) }
        }
    }

    @Test fun missingAndWrongKeysNeverReplaceExistingFactsAndReleaseTheLease() = runTest {
        withScope { root, scope ->
            val dir = directory(root, scope)
            assertTrue(dir.mkdirs()); Os.chmod(File(root, "facts").path, 0x1C0); Os.chmod(dir.path, 0x1C0)
            val key = ByteArray(32) { (it + 3).toByte() }
            val driver = openAndroidLedgerSqlDriver(dir, key)
            try { SqlLedgerSnapshotStore.open(driver) } finally { driver.close(); key.fill(0) }
            val before = File(dir, "ledger.db").readBytes()
            repeat(2) {
                assertEquals("ledger_key_missing", assertFails { createAndroidLedgerFactCache(context, scope, root) }.message)
                assertNull(vault(scope).read())
                assertContentEquals(before, File(dir, "ledger.db").readBytes())
                AndroidLedgerStorageLease.acquire(root, scope).release()
            }
            val wrong = vault(scope).create()
            try {
                repeat(2) {
                    val failure = assertFails { createAndroidLedgerFactCache(context, scope, root) }
                    assertFalse(failure is AndroidLedgerDriverOpenCloseFailure)
                    assertContentEquals(before, File(dir, "ledger.db").readBytes())
                    val stored = assertNotNull(vault(scope).read())
                    try { assertContentEquals(wrong, stored) } finally { stored.fill(0) }
                    AndroidLedgerStorageLease.acquire(root, scope).release()
                }
            } finally { wrong.fill(0) }
        }
    }

    @Test fun orphanJournalIsNotTreatedAsANewDatabase() = runTest {
        withScope { root, scope ->
            val dir = directory(root, scope)
            assertTrue(dir.mkdirs()); Os.chmod(File(root, "facts").path, 0x1C0); Os.chmod(dir.path, 0x1C0)
            File(dir, "ledger.db-journal").writeText("synthetic orphan")
            assertEquals("ledger_database_missing", assertFails { createAndroidLedgerFactCache(context, scope, root) }.message)
            assertNull(vault(scope).read())
            assertEquals("synthetic orphan", File(dir, "ledger.db-journal").readText())
            assertFalse(File(dir, "ledger.db").exists())
            AndroidLedgerStorageLease.acquire(root, scope).release()
        }
    }

    @Test fun actualDeletionFailureRetainsKeyAndLeaseUntilExplicitRetry() = runTest {
        withScope { root, scope ->
            val cache = createAndroidLedgerFactCache(context, scope, root)
            val facts = File(root, "facts")
            try {
                Os.chmod(facts.path, 0x140)
                assertFails { cache.clear(scope) }
                assertFails { cache.read(scope) }
                assertFails { createAndroidLedgerFactCache(context, scope, root) }
                assertNotNull(vault(scope).read()?.also { it.fill(0) })
                assertTrue(directory(root, scope).exists())
                Os.chmod(facts.path, 0x1C0)
                cache.clear(scope)
                assertNull(vault(scope).read())
                AndroidLedgerStorageLease.acquire(root, scope).release()
            } finally { Os.chmod(facts.path, 0x1C0); cache.clear(scope) }
        }
    }

    @Test fun symlinkedFactsParentOrDatabaseNeverReachesTheOutsideFile() = runTest {
        withScope { root, scope ->
            val outside = File(context.cacheDir, "ledger-outside-${UUID.randomUUID()}")
            assertTrue(outside.mkdir()); Os.chmod(outside.path, 0x1C0)
            try {
                Os.symlink(outside.path, File(root, "facts").path)
                assertFails { createAndroidLedgerFactCache(context, scope, root) }
                assertNull(vault(scope).read())
                assertTrue(outside.listFiles()!!.isEmpty())
                Os.remove(File(root, "facts").path)
                val dir = directory(root, scope)
                assertTrue(dir.mkdirs()); Os.chmod(File(root, "facts").path, 0x1C0); Os.chmod(dir.path, 0x1C0)
                val file = File(outside, "preserved").apply { writeText("untouched") }
                Os.symlink(file.path, File(dir, "ledger.db").path)
                assertFails { createAndroidLedgerFactCache(context, scope, root) }
                assertNull(vault(scope).read())
                assertEquals("untouched", file.readText())
                Os.remove(File(dir, "ledger.db").path)
            } finally { outside.deleteRecursively() }
        }
    }

    @Test fun uncertainOpeningKeepsTheActualLeaseAndBothDiagnostics() = runTest {
        withScope { root, scope ->
            val opening = IllegalStateException("synthetic opening failure")
            val closing = IllegalStateException("synthetic close acknowledgement failure")
            val failure = assertFailsWith<AndroidLedgerDriverOpenCloseFailure> {
                createAndroidLedgerFactCache(context, scope, root) { dir, key ->
                    openAndroidLedgerSqlDriver(dir, key, afterKey = {
                        Logger.e("SQLiteConnectionPool", "synthetic resource acknowledgement", closing)
                        throw opening
                    })
                }
            }
            assertSame(opening, failure.cause)
            assertTrue(failure.suppressedExceptions.any { it.cause === closing })
            assertFails { AndroidLedgerStorageLease.acquire(root, scope) }
            assertNotNull(vault(scope).read()?.also { it.fill(0) })
            val lock = File(root, "locks/${ledgerStorageId(scope)}.lock").canonicalPath
            fun openLockDescriptors() = File("/proc/self/fd").listFiles()!!.count {
                runCatching { File(Os.readlink(it.path)).canonicalPath == lock }.getOrDefault(false)
            }
            assertEquals(1, openLockDescriptors())
            System.gc(); System.runFinalization()
            assertEquals(1, openLockDescriptors())
            // This unique synthetic lease intentionally remains owned until the test process exits.
        }
    }

    @Test fun uncertainClearKeepsTheLeaseEvenAfterTheCallerDropsTheCache() = runTest {
        withScope { root, scope ->
            val closing = IllegalStateException("synthetic close acknowledgement failure")
            var cache: LedgerFactCache? = createAndroidLedgerFactCache(context, scope, root) { dir, key ->
                openAndroidLedgerSqlDriver(dir, key, closeDatabase = { it.close(); throw closing })
            }
            val weak = WeakReference(cache)
            val lock = File(root, "locks/${ledgerStorageId(scope)}.lock").canonicalPath
            fun lockDescriptors() = File("/proc/self/fd").listFiles()!!.count {
                runCatching { File(Os.readlink(it.path)).canonicalPath == lock }.getOrDefault(false)
            }
            assertEquals(1, lockDescriptors())
            assertSame(closing, assertFails { cache!!.clear(scope) }.cause)
            cache = null
            repeat(30) {
                System.gc(); System.runFinalization(); Thread.sleep(20)
            }
            assertNull(weak.get(), "The failed cache must be unreachable for this regression")
            assertEquals(1, lockDescriptors(), "Uncertain close must retain the lease independently of its cache")
            assertNotNull(vault(scope).read()?.also { it.fill(0) })
            assertTrue(File(directory(root, scope), "ledger.db").isFile)
            assertFails { AndroidLedgerStorageLease.acquire(root, scope) }
        }
    }

    @Test fun cancellationAfterRealOpenClosesTheDriverButPreservesFilesAndKeyForRetry() = runTest {
        withScope { root, scope ->
            var closes = 0
            var delivered = false
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                val operation = this
                createAndroidLedgerFactCache(context, scope, root) { dir, key ->
                    openAndroidLedgerSqlDriver(dir, key, closeDatabase = { closes++; it.close() }).also {
                        operation.cancel(CancellationException("synthetic initialization cancellation"))
                    }
                }
                delivered = true
            }
            job.join()
            assertTrue(job.isCancelled)
            assertFalse(delivered)
            assertEquals(1, closes)
            assertTrue(File(directory(root, scope), "ledger.db").isFile)
            assertNotNull(vault(scope).read()?.also { it.fill(0) })
            AndroidLedgerStorageLease.acquire(root, scope).release()
            val retried = createAndroidLedgerFactCache(context, scope, root)
            try { assertNull(retried.read(scope)) } finally { retried.clear(scope) }
        }
    }

    @Test fun defaultRootIsInsideTheApplicationNoBackupDirectory() = runTest {
        withScope { _, scope ->
            val cache = createAndroidLedgerFactCache(context, scope)
            val dir = File(context.applicationContext.noBackupFilesDir, "ledger-cache/facts/${ledgerStorageId(scope)}")
            try {
                assertTrue(File(dir, "ledger.db").isFile)
                assertEquals(0, Os.lstat(dir.path).st_mode and 0x3F)
                assertNull(cache.read(scope))
            } finally { cache.clear(scope) }
            assertFalse(dir.exists())
            assertNull(vault(scope).read())
        }
    }

    private fun vault(scope: LedgerCacheScope) = AndroidLedgerKeyVault(context, ledgerStorageId(scope))
    private fun directory(root: File, scope: LedgerCacheScope) = File(root, "facts/${ledgerStorageId(scope)}")
    private suspend fun withScope(block: suspend (File, LedgerCacheScope) -> Unit) {
        val marker = UUID.randomUUID().toString()
        val root = File(context.cacheDir, "ledger-factory-$marker")
        assertTrue(root.mkdir()); Os.chmod(root.path, 0x1C0)
        val scope = LedgerCacheScope("https://example.test", "synthetic-$marker", "workspace")
        try { block(root, scope) }
        finally { vault(scope).erase(); root.deleteRecursively() }
    }
}
