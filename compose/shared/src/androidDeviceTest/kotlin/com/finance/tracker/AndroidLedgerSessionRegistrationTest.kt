package com.finance.tracker

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.finance.tracker.business.contracts.Role
import com.finance.tracker.business.contracts.SessionDto
import com.finance.tracker.business.contracts.UserDto
import com.finance.tracker.business.contracts.WorkspaceDto
import com.finance.tracker.data.*
import com.finance.tracker.domain.LedgerCacheScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.test.*

@RunWith(AndroidJUnit4::class)
class AndroidLedgerSessionRegistrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun registrationIsCommittedUnderTheScopeLeaseBeforeAnyCryptoMaterialExists() = runTest {
        withScope {
            val ticket = directory.begin(scope.apiOrigin)
            val registration = LedgerSessionRegistration(scope, directory, ticket)
            var observed = false
            metadata.beforeWrite = {
                assertFails { AndroidLedgerStorageLease.acquire(root, scope) }
                assertFalse(File(root, "facts").exists())
                assertNull(vault.read())
                observed = true
            }
            val cache = createAndroidLedgerFactCache(context, scope, root, registration = registration,
                openDriver = { directory, key ->
                    val stored = File(context.applicationInfo.dataDir, "shared_prefs/$preferencesName.xml").readText()
                    assertTrue(stored.contains(scope.principal) && stored.contains(scope.workspaceId))
                    assertFalse(stored.contains(session.user.email))
                    openAndroidLedgerSqlDriver(directory, key)
                })
            try {
                assertTrue(observed)
                assertEquals(listOf(scope), directory.scopes(scope.apiOrigin))
                assertNull(directory.current(ticket))
                cache.replace(scope, localCashLedgerOwnerSnapshot())
                cache.saveOfflineSession(scope, "synthetic-token", session)
                directory.publish(ticket, scope)
                assertEquals(scope, directory.current(ticket))
            } finally { metadata.beforeWrite = null; cache.clear(scope) }
            assertNull(directory.current(ticket))
            assertTrue(directory.scopes(scope.apiOrigin).isEmpty())
        }
    }

    @Test fun aRegistrationFailureCreatesNoFactsOrKeyAndReleasesTheUnopenedLease() = runTest {
        withScope {
            val ticket = directory.begin(scope.apiOrigin)
            val failure = IllegalStateException("synthetic metadata failure")
            metadata.failure = failure
            var unexpected: LedgerStorage? = null
            try {
                assertSame(failure, assertFails {
                    createAndroidLedgerFactCache(context, scope, root,
                        registration = LedgerSessionRegistration(scope, directory, ticket)).also { unexpected = it }
                })
                assertFalse(File(root, "facts").exists())
                assertNull(vault.read())
                AndroidLedgerStorageLease.acquire(root, scope).release()
            } finally { metadata.failure = null; unexpected?.clear(scope) }
        }
    }

    @Test fun failedReferenceRemovalKeepsTheLeaseUntilItsOwnCleanupRetryCompletes() = runTest {
        withScope {
            val ticket = directory.begin(scope.apiOrigin)
            val registration = LedgerSessionRegistration(scope, directory, ticket)
            val cache = createAndroidLedgerFactCache(context, scope, root, registration = registration)
            try {
                cache.replace(scope, localCashLedgerOwnerSnapshot())
                cache.saveOfflineSession(scope, "synthetic-token", session)
                directory.publish(ticket, scope)
                val failure = IllegalStateException("synthetic removal failure")
                metadata.failure = failure
                assertSame(failure, assertFails { cache.clear(scope) })
                assertFalse(File(root, "facts/${ledgerStorageId(scope)}").exists())
                assertNull(vault.read())
                assertEquals(scope, directory.current(ticket))
                assertFails { AndroidLedgerStorageLease.acquire(root, scope) }
                metadata.failure = null
                cache.clear(scope)
                assertNull(directory.current(ticket))
                assertTrue(directory.scopes(scope.apiOrigin).isEmpty())
                val fresh = createAndroidLedgerFactCache(context, scope, root, registration = registration)
                try {
                    val expected = localCashLedgerOwnerSnapshot("8")
                    fresh.replace(scope, expected)
                    cache.clear(scope) // Completed old cleanup cannot erase the newly opened scope.
                    assertEquals(expected, fresh.read(scope))
                } finally { fresh.clear(scope) }
            } finally { metadata.failure = null; cache.clear(scope) }
        }
    }

    @Test fun staleRegistrationCannotReopenPreservedMaterialsOrReachTheDriver() = runTest {
        withScope {
            val old = directory.begin(scope.apiOrigin)
            val original = createAndroidLedgerFactCache(context, scope, root,
                registration = LedgerSessionRegistration(scope, directory, old))
            try { original.replace(scope, localCashLedgerOwnerSnapshot()) } finally { original.close() }
            assertEquals(listOf(scope), directory.scopes(scope.apiOrigin))
            val next = directory.begin(scope.apiOrigin)
            assertFailsWith<CancellationException> {
                createAndroidLedgerFactCache(context, scope, root, existingOnly = true,
                    registration = LedgerSessionRegistration(scope, directory, old),
                    openDriver = { _, _ -> error("stale registration reached the driver") })
            }
            val restored = createAndroidLedgerFactCache(context, scope, root, existingOnly = true,
                registration = LedgerSessionRegistration(scope, directory, next))
            try { assertEquals(localCashLedgerOwnerSnapshot(), restored.read(scope)) }
            finally { restored.clear(scope) }
        }
    }

    @Test fun aForeignBindingIsRejectedBeforeAcquiringOrCreatingTheScope() = runTest {
        withScope {
            val ticket = directory.begin(scope.apiOrigin)
            var unexpected: LedgerStorage? = null
            try {
                assertFailsWith<IllegalArgumentException> {
                    createAndroidLedgerFactCache(context, scope, root,
                        registration = LedgerSessionRegistration(scope.copy(workspaceId = "other"), directory, ticket))
                        .also { unexpected = it }
                }
                assertFalse(root.exists())
                assertNull(vault.read())
                assertTrue(directory.scopes(scope.apiOrigin).isEmpty())
            } finally { unexpected?.clear(scope) }
        }
    }

    private class Metadata(private val storage: LedgerSessionLocatorStorage) : LedgerSessionLocatorStorage {
        var failure: Throwable? = null
        var beforeWrite: (suspend () -> Unit)? = null
        override suspend fun read(key: String): String? = storage.read(key)
        override suspend fun compareAndSet(key: String, expected: String?, replacement: String): Boolean {
            failure?.let { throw it }
            val hook = beforeWrite; beforeWrite = null; hook?.invoke()
            return storage.compareAndSet(key, expected, replacement)
        }
    }

    private inner class Fixture {
        val root = File(context.cacheDir, "ledger-registration-${UUID.randomUUID()}")
        val session = SessionDto(UserDto("synthetic-${UUID.randomUUID()}@example.test"), "workspace",
            listOf(WorkspaceDto("workspace", "Synthetic workspace", Role.EDITOR)))
        val scope = LedgerCacheScope("https://synthetic.example.test", ledgerOfflinePrincipal(session.user.email), "workspace")
        val vault = AndroidLedgerKeyVault(context, ledgerStorageId(scope))
        val preferencesName = "ledger-registration-test-${UUID.randomUUID()}"
        val metadata = Metadata(AndroidLedgerSessionLocatorStorage(context, preferencesName))
        val directory = LedgerSessionDirectory(metadata)
    }

    private suspend fun withScope(action: suspend Fixture.() -> Unit) {
        val fixture = Fixture()
        try { fixture.action() }
        finally {
            fixture.metadata.failure = null
            fixture.vault.erase()
            fixture.root.deleteRecursively()
            assertTrue(context.deleteSharedPreferences(fixture.preferencesName))
        }
    }
}
