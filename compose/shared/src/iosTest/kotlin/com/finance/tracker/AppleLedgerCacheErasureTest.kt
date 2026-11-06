@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.finance.tracker

import com.finance.tracker.business.domain.sha256Hex
import com.finance.tracker.data.*
import com.finance.tracker.domain.LedgerCacheScope
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults
import kotlin.test.*

/** Requires the explicit entitled Keychain mode, like the existing Apple cache factory tests. */
class AppleLedgerCacheErasureTest {
    @Test fun invalidReferencesPreserveExistingFilesKeysAndLeaseAvailability() = runTest {
        for (badPrincipal in listOf(true, false)) withScope {
            val invalid = if (badPrincipal) scope.copy(principal = "synthetic-non-hash")
                else scope.copy(apiOrigin = scope.apiOrigin + "/")
            val cache = createAppleLedgerFactCache(invalid, root)
            try { cache.replace(invalid, localCashLedgerOwnerSnapshot()) } finally { cache.close() }
            val path = "$root/facts/" + ledgerStorageId(invalid) + "/ledger.db"
            val before = bytes(path)
            val invalidVault = AppleLedgerKeyVault(ledgerStorageId(invalid))
            val digest = keyDigest(invalidVault)
            try {
                assertFailsWith<IllegalArgumentException> { createAppleLedgerCacheErasure(invalid, directory, root).clear() }
                assertContentEquals(before, bytes(path))
                assertEquals(digest, keyDigest(invalidVault))
                AppleLedgerStorageLease.acquire(root, invalid).release()
            } finally { invalidVault.erase() }
        }
    }

    @Test fun missingWrongKeysAndEmptyMainFilesAreErasedWithoutReopeningTheDatabase() = runTest {
        for (mode in listOf("missing-key", "wrong-key", "empty-main")) withScope {
            seed()
            when (mode) {
                "missing-key" -> vault.erase()
                "wrong-key" -> { vault.erase(); vault.create().fill(0) }
                else -> {
                    assertTrue(files.removeItemAtPath("$facts/ledger.db", null))
                    assertTrue(files.createFileAtPath("$facts/ledger.db", null, null))
                    assertEquals(0uL, checkNotNull(files.contentsAtPath("$facts/ledger.db")).length)
                }
            }
            val erasure = createAppleLedgerCacheErasure(scope, directory, root)
            erasure.clear()
            assertFalse(files.fileExistsAtPath(facts))
            assertNull(vault.read())
            assertTrue(directory.scopes(scope.apiOrigin).isEmpty())
            AppleLedgerStorageLease.acquire(root, scope).release()
            erasure.clear()
            assertFalse(files.fileExistsAtPath(facts))
            assertNull(vault.read())
        }
    }

    @Test fun orphanLogsAreRemovedWithoutChangingAnotherWorkspaceOrItsCurrentReference() = runTest {
        withScope {
            seed()
            assertTrue(files.removeItemAtPath("$facts/ledger.db", null))
            assertTrue(files.createFileAtPath("$facts/ledger.db-journal", null, null))
            val other = scope.copy(workspaceId = "other")
            val ticket = directory.begin(scope.apiOrigin)
            val cache = createAppleLedgerFactCache(other, root,
                registration = LedgerSessionRegistration(other, directory, ticket))
            try {
                directory.publish(ticket, other)
                createAppleLedgerCacheErasure(scope, directory, root).clear()
                assertFalse(files.fileExistsAtPath(facts))
                assertNull(vault.read())
                assertEquals(other, directory.current(ticket))
                assertEquals(listOf(other), directory.scopes(scope.apiOrigin))
                assertNull(cache.read(other))
            } finally { cache.clear(other) }
        }
    }

    @Test fun failedForgetRetainsOwnershipAndCompletedOldCleanupCannotEraseANewCache() = runTest {
        withScope {
            seed()
            val failure = IllegalStateException("synthetic erase-only metadata failure")
            metadataFailure = failure
            val erasure = createAppleLedgerCacheErasure(scope, directory, root)
            try {
                assertSame(failure, assertFails { erasure.clear() })
                assertFalse(files.fileExistsAtPath(facts))
                assertNull(vault.read())
                assertEquals(listOf(scope), directory.scopes(scope.apiOrigin))
                assertFails { AppleLedgerStorageLease.acquire(root, scope) }
                metadataFailure = null
                erasure.clear()
                assertTrue(directory.scopes(scope.apiOrigin).isEmpty())
                val cache = createAppleLedgerFactCache(scope, root)
                val expected = localCashLedgerOwnerSnapshot()
                try {
                    cache.replace(scope, expected)
                    val digest = keyDigest(vault)
                    erasure.clear()
                    assertTrue(files.fileExistsAtPath("$facts/ledger.db"))
                    assertEquals(digest, keyDigest(vault))
                    assertEquals(expected, cache.read(scope))
                } finally { cache.clear(scope) }
            } finally { metadataFailure = null; erasure.clear() }
        }
    }

    private class Fixture {
        private val marker = NSUUID().UUIDString
        val root = NSTemporaryDirectory() + "ledger-erasure-$marker"
        val scope = LedgerCacheScope("https://synthetic-$marker.example.test".lowercase(),
            ledgerOfflinePrincipal("synthetic-$marker@example.test"), "workspace")
        val facts = "$root/facts/" + ledgerStorageId(scope)
        val vault = AppleLedgerKeyVault(ledgerStorageId(scope))
        val files = NSFileManager.defaultManager
        private val actual = AppleLedgerSessionLocatorStorage()
        var metadataFailure: Throwable? = null
        val directory = LedgerSessionDirectory(object : LedgerSessionLocatorStorage {
            override suspend fun read(key: String) = actual.read(key)
            override suspend fun compareAndSet(key: String, expected: String?, replacement: String): Boolean {
                metadataFailure?.let { throw it }
                return actual.compareAndSet(key, expected, replacement)
            }
        })
        suspend fun seed() {
            val ticket = directory.begin(scope.apiOrigin)
            val cache = createAppleLedgerFactCache(scope, root,
                registration = LedgerSessionRegistration(scope, directory, ticket))
            try { cache.replace(scope, localCashLedgerOwnerSnapshot()) } finally { cache.close() }
        }
        fun clean() {
            vault.erase()
            if (files.fileExistsAtPath(root)) assertTrue(files.removeItemAtPath(root, null))
            NSUserDefaults.standardUserDefaults.removeObjectForKey("ledger-session-current-v1:" +
                sha256Hex(scope.apiOrigin.encodeToByteArray()))
        }
    }

    private suspend fun withScope(action: suspend Fixture.() -> Unit) {
        val fixture = Fixture()
        try { fixture.action() } finally { fixture.clean() }
    }

    private fun keyDigest(vault: AppleLedgerKeyVault): String {
        val raw = checkNotNull(vault.read())
        return try { sha256Hex(raw) } finally { raw.fill(0) }
    }

    private fun bytes(path: String): ByteArray {
        val data = checkNotNull(NSFileManager.defaultManager.contentsAtPath(path))
        assertTrue(data.length <= Int.MAX_VALUE.toULong())
        val length = data.length.toInt()
        if (length == 0) return byteArrayOf()
        val pointer = checkNotNull(data.bytes).reinterpret<ByteVar>()
        return ByteArray(length) { pointer[it] }
    }
}
