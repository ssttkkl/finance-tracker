package com.finance.tracker

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.finance.tracker.business.domain.sha256Hex
import com.finance.tracker.data.*
import com.finance.tracker.domain.LedgerCacheScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.test.*

@RunWith(AndroidJUnit4::class)
class AndroidLedgerCacheErasureTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun invalidPrincipalIsRejectedBeforeDeletingExistingMaterialsOrAcquiringTheirLease() = runTest {
        withScope { assertInvalidScopePreserved(scope.copy(principal = "synthetic-non-hash-principal")) }
    }

    @Test fun unnormalizedOriginIsRejectedBeforeDeletingExistingMaterialsOrAcquiringTheirLease() = runTest {
        withScope { assertInvalidScopePreserved(scope.copy(apiOrigin = scope.apiOrigin + "/")) }
    }

    @Test fun missingKeyAndCorruptDatabaseAreErasedWithoutOpeningOrRecreatingThem() = runTest {
        for (missingKey in listOf(true, false)) withScope {
            seed()
            if (missingKey) vault.erase() else File(facts, "ledger.db").writeText("synthetic corrupt database")
            val erasure = createAndroidLedgerCacheErasure(context, scope, directory, root)
            erasure.clear()
            assertFalse(facts.exists())
            assertNull(vault.read())
            assertTrue(directory.scopes(scope.apiOrigin).isEmpty())
            AndroidLedgerStorageLease.acquire(root, scope).release()
            erasure.clear()
            assertFalse(facts.exists())
            assertNull(vault.read())
        }
    }

    @Test fun orphanSideFilesAreRemovedWithoutChangingAnotherWorkspaceOrItsCurrentReference() = runTest {
        withScope {
            seed()
            assertTrue(File(facts, "ledger.db").delete())
            File(facts, "ledger.db-journal").writeText("synthetic orphan")
            val other = scope.copy(workspaceId = "other")
            val ticket = directory.begin(scope.apiOrigin)
            val cache = createAndroidLedgerFactCache(context, other, root,
                registration = LedgerSessionRegistration(other, directory, ticket))
            try {
                directory.publish(ticket, other)
                createAndroidLedgerCacheErasure(context, scope, directory, root).clear()
                assertFalse(facts.exists())
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
            val erasure = createAndroidLedgerCacheErasure(context, scope, directory, root)
            try {
                assertSame(failure, assertFails { erasure.clear() })
                assertFalse(facts.exists())
                assertNull(vault.read())
                assertEquals(listOf(scope), directory.scopes(scope.apiOrigin))
                assertFails { AndroidLedgerStorageLease.acquire(root, scope) }
                metadataFailure = null
                erasure.clear()
                assertTrue(directory.scopes(scope.apiOrigin).isEmpty())
                val cache = createAndroidLedgerFactCache(context, scope, root)
                val expected = localCashLedgerOwnerSnapshot()
                try {
                    cache.replace(scope, expected)
                    erasure.clear()
                    assertEquals(expected, cache.read(scope))
                } finally { cache.clear(scope) }
            } finally { metadataFailure = null; erasure.clear() }
        }
    }

    private inner class Fixture {
        val root = File(context.cacheDir, "ledger-erasure-" + UUID.randomUUID())
        val scope = LedgerCacheScope("https://synthetic.example.test",
            ledgerOfflinePrincipal("synthetic-" + UUID.randomUUID() + "@example.test"), "workspace")
        val facts = File(root, "facts/" + ledgerStorageId(scope))
        val vault = AndroidLedgerKeyVault(context, ledgerStorageId(scope))
        val preferences = "ledger-erasure-test-" + UUID.randomUUID()
        private val actual = AndroidLedgerSessionLocatorStorage(context, preferences)
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
            val cache = createAndroidLedgerFactCache(context, scope, root,
                registration = LedgerSessionRegistration(scope, directory, ticket))
            try { cache.replace(scope, localCashLedgerOwnerSnapshot()) } finally { cache.close() }
        }
    }

    private suspend fun Fixture.assertInvalidScopePreserved(invalid: LedgerCacheScope) {
        // The existing low-level cache accepts opaque identities; cleanup must validate first.
        val cache = createAndroidLedgerFactCache(context, invalid, root)
        try { cache.replace(invalid, localCashLedgerOwnerSnapshot()) } finally { cache.close() }
        val file = File(root, "facts/" + ledgerStorageId(invalid) + "/ledger.db")
        val before = file.readBytes()
        val invalidVault = AndroidLedgerKeyVault(context, ledgerStorageId(invalid))
        val raw = checkNotNull(invalidVault.read())
        val keyDigest = try { sha256Hex(raw) } finally { raw.fill(0) }
        try {
            assertFailsWith<IllegalArgumentException> {
                createAndroidLedgerCacheErasure(context, invalid, directory, root).clear()
            }
            assertTrue(file.exists(), "Scope validation must precede file deletion")
            assertContentEquals(before, file.readBytes())
            val after = checkNotNull(invalidVault.read())
            try { assertEquals(keyDigest, sha256Hex(after)) } finally { after.fill(0) }
            AndroidLedgerStorageLease.acquire(root, invalid).release()
        } finally { invalidVault.erase() }
    }

    private suspend fun withScope(action: suspend Fixture.() -> Unit) {
        val fixture = Fixture()
        try { fixture.action() }
        finally {
            fixture.metadataFailure = null
            fixture.vault.erase()
            fixture.root.deleteRecursively()
            assertTrue(context.deleteSharedPreferences(fixture.preferences))
        }
    }
}
