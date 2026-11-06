@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.finance.tracker

import com.finance.tracker.data.AppleLedgerKeyVault
import com.finance.tracker.data.AppleLedgerDriverOpenCloseFailure
import com.finance.tracker.data.AppleLedgerStorageLease
import com.finance.tracker.data.SqlLedgerSnapshotStore
import com.finance.tracker.data.appleLedgerStorageId
import com.finance.tracker.data.createAppleLedgerFactCache
import com.finance.tracker.data.openAppleLedgerSqlDriver
import com.finance.tracker.domain.LedgerCacheScope
import com.finance.tracker.domain.LedgerFactCache
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.chmod
import platform.posix.close
import platform.posix.fcntl
import platform.posix.F_GETPATH
import platform.posix._SC_OPEN_MAX
import platform.posix.sysconf
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertSame

/** Uses real Keychain services and must run through the explicit entitled test mode. */
class AppleLedgerFactCacheTest {
    @Test
    fun factoryPersistsExactFactsAndErasesFilesKeyAndLease() = runTest {
        withScope { root, scope ->
            val cache = createAppleLedgerFactCache(scope, root)
            val base = localCashLedgerOwnerSnapshot("9223372036854775807").copy(workspaceId = scope.workspaceId)
            val expected = base.copy(facts = base.facts.copy(cashTransactions = base.facts.cashTransactions.map {
                it.copy(amount = "-12345678901234567890.123456789012345678",
                    sourcePayload = Json.parseToJsonElement("[9007199254740993,18446744073709551616,-0,1.2300,1e400]"))
            }))
            try {
                val backup = NSURL.fileURLWithPath(root).resourceValuesForKeys(listOf(NSURLIsExcludedFromBackupKey), null)
                assertEquals(true, (backup?.get(NSURLIsExcludedFromBackupKey) as NSNumber).boolValue)
                assertNull(cache.read(scope))
                cache.replace(scope, expected)
                assertEquals(expected, cache.read(scope))
                assertFails { createAppleLedgerFactCache(scope, root) }
                assertFails { cache.read(scope.copy(principal = "other")) }
                assertNotNull(AppleLedgerKeyVault(appleLedgerStorageId(scope)).read()?.also { it.fill(0) })
            } finally { cache.clear(scope) }
            cache.clear(scope)
            assertFails { cache.read(scope) }
            assertNull(AppleLedgerKeyVault(appleLedgerStorageId(scope)).read())
            assertTrue(!NSFileManager.defaultManager.fileExistsAtPath(directory(root, scope)))
            val fresh = createAppleLedgerFactCache(scope, root)
            try { assertNull(fresh.read(scope)) } finally { fresh.clear(scope) }
        }
    }

    @Test
    fun factoryOpensExistingEncryptedFactsWithoutReplacingTheKey() = runTest {
        withScope { root, scope ->
            val key = AppleLedgerKeyVault(appleLedgerStorageId(scope)).create()
            val directory = directory(root, scope)
            assertTrue(NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null))
            val expected = localCashLedgerOwnerSnapshot().copy(workspaceId = scope.workspaceId)
            val driver = openAppleLedgerSqlDriver(directory, key)
            try { SqlLedgerSnapshotStore.open(driver).replace(scope, expected) }
            finally { driver.close() }
            val cache = createAppleLedgerFactCache(scope, root)
            try {
                assertEquals(expected, cache.read(scope))
                val actual = checkNotNull(AppleLedgerKeyVault(appleLedgerStorageId(scope)).read())
                try { assertContentEquals(key, actual) } finally { actual.fill(0) }
            } finally { key.fill(0); cache.clear(scope) }
        }
    }

    @Test
    fun existingDatabaseWithoutAKeyIsNeverRecreatedAndReleasesTheLease() = runTest {
        withScope { root, scope ->
            val directory = directory(root, scope)
            assertTrue(NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null))
            val key = ByteArray(32) { (it + 2).toByte() }
            val driver = openAppleLedgerSqlDriver(directory, key)
            try { SqlLedgerSnapshotStore.open(driver) } finally { driver.close(); key.fill(0) }
            val before = databaseBytes(directory)
            repeat(2) {
                assertTrue(assertFails { createAppleLedgerFactCache(scope, root) }.message.orEmpty()
                    .contains("ledger_key_missing"))
                assertNull(AppleLedgerKeyVault(appleLedgerStorageId(scope)).read())
                assertContentEquals(before, databaseBytes(directory))
                val lease = AppleLedgerStorageLease.acquire(root, scope)
                lease.release()
            }
        }
    }

    @Test
    fun wrongKeyPreservesTheActualFileAndKeyAndAllowsExplicitRetry() = runTest {
        withScope { root, scope ->
            val vault = AppleLedgerKeyVault(appleLedgerStorageId(scope))
            val wrong = vault.create()
            try {
                val directory = directory(root, scope)
                assertTrue(NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null))
                val actualKey = ByteArray(32) { (it + 2).toByte() }
                val driver = openAppleLedgerSqlDriver(directory, actualKey)
                try { SqlLedgerSnapshotStore.open(driver) } finally { driver.close(); actualKey.fill(0) }
                val before = databaseBytes(directory)
                repeat(2) {
                    assertFails { createAppleLedgerFactCache(scope, root) }
                    assertContentEquals(before, databaseBytes(directory))
                    val persisted = checkNotNull(vault.read())
                    try { assertContentEquals(wrong, persisted) } finally { persisted.fill(0) }
                    AppleLedgerStorageLease.acquire(root, scope).release()
                }
            } finally { wrong.fill(0) }
        }
    }

    @Test
    fun orphanSideFileDoesNotBecomeANewDatabase() = runTest {
        withScope { root, scope ->
            val directory = directory(root, scope)
            val files = NSFileManager.defaultManager
            assertTrue(files.createDirectoryAtPath(directory, true, null, null))
            assertTrue(files.createFileAtPath("$directory/ledger.db-journal", null, null))
            assertTrue(assertFails { createAppleLedgerFactCache(scope, root) }.message.orEmpty()
                .contains("ledger_database_missing"))
            assertNull(AppleLedgerKeyVault(appleLedgerStorageId(scope)).read())
            assertTrue(files.fileExistsAtPath("$directory/ledger.db-journal"))
            assertTrue(!files.fileExistsAtPath("$directory/ledger.db"))
            AppleLedgerStorageLease.acquire(root, scope).release()
        }
    }

    @Test
    fun actualFileRemovalFailureKeepsTheKeyAndLeaseUntilExplicitRetry() = runTest {
        withScope { root, scope ->
            val cache = createAppleLedgerFactCache(scope, root)
            val facts = "$root/facts"
            try {
                assertEquals(0, chmod(facts, 0x140u))
                assertFails { cache.clear(scope) }
                assertFails { cache.read(scope) }
                assertFails { createAppleLedgerFactCache(scope, root) }
                assertNotNull(AppleLedgerKeyVault(appleLedgerStorageId(scope)).read()?.also { it.fill(0) })
                assertTrue(NSFileManager.defaultManager.fileExistsAtPath(directory(root, scope)))
                assertEquals(0, chmod(facts, 0x1C0u))
                cache.clear(scope)
                assertNull(AppleLedgerKeyVault(appleLedgerStorageId(scope)).read())
                AppleLedgerStorageLease.acquire(root, scope).release()
            } finally {
                assertEquals(0, chmod(facts, 0x1C0u))
                cache.clear(scope)
            }
        }
    }

    @Test
    fun failedOpenWithUncertainCleanupNeverReleasesItsScopeLease() = runTest {
        withScope { root, scope ->
            val opening = IllegalStateException("Synthetic opening failure")
            val closing = IllegalStateException("Synthetic close acknowledgement failure")
            try {
                val failure = assertFailsWith<AppleLedgerDriverOpenCloseFailure> {
                    createAppleLedgerFactCache(scope, root) { directory, key ->
                        failAppleLedgerDriverOpening(directory, key, opening, closing)
                    }
                }
                assertSame(opening, failure.cause)
                assertTrue(failure.suppressedExceptions.any { it === closing })
                assertFails { AppleLedgerStorageLease.acquire(root, scope) }
                assertNotNull(AppleLedgerKeyVault(appleLedgerStorageId(scope)).read()?.also { it.fill(0) })
                assertTrue(NSFileManager.defaultManager.fileExistsAtPath(directory(root, scope)))
            } finally {
                // The injected close returned from the real C close before throwing.
                // Only this test tears down the deliberately retained lease; production waits for exit.
                val suffix = "/${root.substringAfterLast('/')}/locks/${appleLedgerStorageId(scope)}.lock"
                memScoped {
                    val buffer = allocArray<ByteVar>(1024)
                    val limit = sysconf(_SC_OPEN_MAX).toInt()
                    assertTrue(limit > 0)
                    val descriptor = (0 until limit).single {
                        fcntl(it, F_GETPATH, buffer) == 0 && buffer.toKString().endsWith(suffix)
                    }
                    assertEquals(0, close(descriptor))
                }
            }
        }
    }

    private fun directory(root: String, scope: LedgerCacheScope) = "$root/facts/${appleLedgerStorageId(scope)}"

    private fun databaseBytes(directory: String): ByteArray {
        val data = checkNotNull(NSFileManager.defaultManager.contentsAtPath("$directory/ledger.db"))
        assertTrue(data.length > 0u && data.length <= Int.MAX_VALUE.toULong())
        val bytes = checkNotNull(data.bytes).reinterpret<ByteVar>()
        return ByteArray(data.length.toInt()) { bytes[it] }
    }

    @Test
    fun factsParentSymlinkIsRejectedBeforeCreatingAKeyOrDatabase() = runTest {
        withScope { root, scope ->
            val files = NSFileManager.defaultManager
            val outside = NSTemporaryDirectory() + "ledger-outside-${NSUUID().UUIDString}"
            assertTrue(files.createDirectoryAtPath(root, true, null, null))
            assertTrue(files.createDirectoryAtPath(outside, true, null, null))
            var accidentallyOpened: LedgerFactCache? = null
            try {
                assertTrue(files.createSymbolicLinkAtPath("$root/facts", outside, null))
                assertFails { accidentallyOpened = createAppleLedgerFactCache(scope, root) }
                assertNull(AppleLedgerKeyVault(appleLedgerStorageId(scope)).read())
                assertEquals(emptyList<String>(), files.contentsOfDirectoryAtPath(outside, null))
            } finally {
                accidentallyOpened?.clear(scope)
                assertTrue(files.removeItemAtPath(outside, null))
            }
        }
    }

    private suspend fun withScope(block: suspend (String, LedgerCacheScope) -> Unit) {
        val marker = NSUUID().UUIDString
        val root = NSTemporaryDirectory() + "ledger-factory-$marker"
        val scope = LedgerCacheScope("https://example.test", "synthetic-$marker", "workspace")
        try { block(root, scope) }
        finally {
            AppleLedgerKeyVault(appleLedgerStorageId(scope)).erase()
            if (NSFileManager.defaultManager.fileExistsAtPath(root)) {
                assertTrue(NSFileManager.defaultManager.removeItemAtPath(root, null))
            }
        }
    }
}
