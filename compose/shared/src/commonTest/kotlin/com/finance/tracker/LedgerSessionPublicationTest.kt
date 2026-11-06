package com.finance.tracker

import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.contracts.Role
import com.finance.tracker.business.contracts.SessionDto
import com.finance.tracker.business.contracts.UserDto
import com.finance.tracker.business.contracts.WorkspaceDto
import com.finance.tracker.core.TokenStore
import com.finance.tracker.data.*
import com.finance.tracker.domain.LedgerCacheScope
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LedgerSessionPublicationTest {
    @Test fun cancelledWhileWaitingForCredentialsDoesNotStartLocalPublication() = runTest {
        fixture {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val locking = launch { api.credentialMutex.withLock { entered.complete(Unit); release.await() } }
            try {
                entered.await()
                val publishing = launch { publish() }; runCurrent()
                publishing.cancelAndJoin()
                assertEquals(0, storage.saves)
                assertNull(directory.current(ticket))
            } finally { release.complete(Unit); locking.join() }
        }
    }

    @Test fun credentialStorageFailureIsNotOfflineSuccessAndDoesNotChangeReferences() = runTest {
        fixture {
            val failure = IllegalStateException("synthetic token store failure")
            tokens.readFailure = failure
            val actual = assertFailsWith<ApiFailure> { publish() }
            assertEquals("token_store_unavailable", actual.code)
            assertTrue(generateSequence(actual as Throwable?) { it.cause }.any { it === failure })
            assertEquals(0, storage.saves)
            assertNull(directory.current(ticket))
        }
    }

    @Test fun verifiedIdentityIsSavedBeforeItsReferenceIsPublished() = runTest {
        fixture {
            locator.beforeWrite = { assertEquals(session, storage.saved) }
            publish()
            assertEquals(scope, directory.current(ticket))
            assertEquals(session, storage.readOfflineSession(scope, token))
            assertNull(storage.readOfflineSession(scope, "different-token"))
            assertEquals(1, storage.saves)
            assertEquals(0, requests)
        }
    }

    @Test fun changedCredentialsRejectPublicationBeforeTouchingTheStoredIdentity() = runTest {
        fixture {
            api.writeToken("new-login")
            assertFailsWith<CancellationException> { publish() }
            assertEquals(0, storage.saves)
            assertNull(directory.current(ticket))
            assertEquals("new-login", tokens.value)
        }
    }

    @Test fun sameTokenWorkspaceChangesInvalidateTheOldOperationBeforeSaving() = runTest {
        fixture {
            val next = directory.begin(scope.apiOrigin)
            assertFailsWith<CancellationException> { publish() }
            assertEquals(0, storage.saves)
            assertNull(directory.current(next))
            assertEquals(token, tokens.value)
        }
    }

    @Test fun invalidBindingsFailBeforeAnyIdentityOrReferenceWrite() = runTest {
        fixture {
            for (bad in listOf(ApiCredentialSnapshot(null, scope.apiOrigin),
                ApiCredentialSnapshot(token, "https://other.example.test"))) {
                assertFailsWith<IllegalArgumentException> {
                    api.publishVerifiedLedgerSession(bad, directory, ticket, scope, storage, session)
                }
            }
            assertEquals(0, storage.saves)
            assertNull(directory.current(ticket))
        }
    }

    @Test fun storageFailureKeepsTheOldReferenceAndLeavesCleanupToItsOwner() = runTest {
        fixture {
            val failure = IllegalStateException("synthetic session write failure")
            storage.failure = failure
            val actual = assertFailsWith<IllegalStateException> { publish() }
            assertTrue(generateSequence(actual as Throwable?) { it.cause }.any { it === failure })
            assertNull(directory.current(ticket))
            assertNull(storage.saved)
            assertEquals(0, storage.closes)
            assertEquals(0, storage.clears)
            api.writeToken("new-login")
            assertEquals("new-login", tokens.value)
        }
    }

    @Test fun publicationFailureDoesNotEraseFactsOrPretendTheIdentityWasNotSaved() = runTest {
        fixture {
            val failure = IllegalStateException("synthetic locator write failure")
            locator.beforeWrite = { throw failure }
            val actual = assertFailsWith<IllegalStateException> { publish() }
            assertTrue(generateSequence(actual as Throwable?) { it.cause }.any { it === failure })
            assertEquals(session, storage.saved)
            assertNull(directory.current(ticket))
            assertEquals(0, storage.closes)
            assertEquals(0, storage.clears)
        }
    }

    @Test fun cancellationWaitsForPublicationAndTheSameClientCannotReplaceCredentialsMidCommit() = runTest {
        fixture {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            locator.beforeWrite = { entered.complete(Unit); release.await() }
            val publishing = launch { publish() }
            try {
                entered.await()
                val login = async { api.writeToken("new-login") }; runCurrent()
                assertFalse(login.isCompleted)
                publishing.cancel(); runCurrent()
                assertFalse(publishing.isCompleted)
                assertEquals(session, storage.saved)
                assertEquals(token, tokens.value)
                release.complete(Unit)
                publishing.join(); login.await()
                assertTrue(publishing.isCancelled)
                assertEquals(scope, directory.current(ticket))
                assertEquals("new-login", tokens.value)
                assertNull(storage.readOfflineSession(scope, "new-login"))
            } finally { release.complete(Unit) }
        }
    }

    @Test fun anExternalCredentialChangeDuringSaveCannotPublishAnOldCandidate() = runTest {
        fixture {
            storage.beforeSave = { tokens.value = "external-login" }
            assertFailsWith<CancellationException> { publish() }
            assertEquals(session, storage.saved)
            assertNull(directory.current(ticket))
            assertEquals("external-login", tokens.value)
        }
    }

    @Test fun aNewDirectoryGenerationDuringSaveCannotPublishAnOldCandidate() = runTest {
        fixture {
            var next: LedgerSessionTicket? = null
            storage.beforeSave = { next = directory.begin(scope.apiOrigin) }
            assertFailsWith<CancellationException> { publish() }
            assertEquals(session, storage.saved)
            assertNull(directory.current(assertNotNull(next)))
        }
    }

    @Test fun closingStorageDuringReferencePublicationCannotReturnAReadableCandidate() = runTest {
        fixture {
            locator.beforeWrite = { storage.close() }
            assertFailsWith<CancellationException> { publish() }
            assertEquals(1, storage.closes)
            assertEquals(0, storage.clears)
        }
    }

    @Test fun externalTokenChangesDuringReferencePublicationRejectTheOldCandidate() = runTest {
        fixture {
            locator.beforeWrite = { tokens.value = "external-login" }
            assertFailsWith<CancellationException> { publish() }
            assertEquals(scope, directory.current(ticket)) // Discovery alone never authorizes access.
            assertNull(storage.readOfflineSession(scope, "external-login"))
        }
    }

    @Test fun aGenerationChangeDuringTheReferenceCasCannotPublishTheOldOperation() = runTest {
        fixture {
            var next: LedgerSessionTicket? = null
            locator.beforeWrite = {
                locator.beforeWrite = {}
                next = directory.begin(scope.apiOrigin)
            }
            assertFailsWith<CancellationException> { publish() }
            assertNull(directory.current(assertNotNull(next)))
        }
    }

    private class Tokens(var value: String?) : TokenStore {
        var readFailure: Throwable? = null
        override suspend fun get(): String? { readFailure?.let { throw it }; return value }
        override suspend fun set(value: String) { this.value = value }
        override suspend fun clear() { value = null }
    }

    private class Locator : LedgerSessionLocatorStorage {
        private val values = mutableMapOf<String, String>()
        var beforeWrite: suspend () -> Unit = {}
        override suspend fun read(key: String) = values[key]
        override suspend fun compareAndSet(key: String, expected: String?, replacement: String): Boolean {
            beforeWrite()
            if (values[key] != expected) return false
            values[key] = replacement
            return true
        }
    }

    /** Protocol fake only; real SQL eligibility and encrypted storage are covered separately. */
    private class Storage : LedgerStorage {
        var saved: SessionDto? = null
        var savedToken: String? = null
        var saves = 0; var closes = 0; var clears = 0
        var failure: Throwable? = null
        var beforeSave: suspend () -> Unit = {}
        private var closed = false
        override suspend fun read(scope: LedgerCacheScope) = localCashLedgerOwnerSnapshot()
        override suspend fun replace(scope: LedgerCacheScope, snapshot: LedgerSnapshotDto) = error("Unexpected fact write")
        override suspend fun close() { closes++; closed = true }
        override suspend fun clear(scope: LedgerCacheScope) { clears++ }
        override suspend fun readOfflineSession(scope: LedgerCacheScope, token: String): SessionDto? {
            if (closed) throw CancellationException("ledger_cache_closed")
            return saved.takeIf { token == savedToken }
        }
        override suspend fun saveOfflineSession(scope: LedgerCacheScope, token: String, session: SessionDto) {
            failure?.let { throw it }
            beforeSave(); saved = session; savedToken = token; saves++
        }
    }

    private class Fixture {
        val token = "synthetic-original-token"
        val session = SessionDto(UserDto("synthetic@example.test"), "workspace",
            listOf(WorkspaceDto("workspace", "Synthetic", Role.EDITOR)))
        val scope = LedgerCacheScope("https://synthetic.example.test", ledgerOfflinePrincipal(session.user.email), "workspace")
        val tokens = Tokens(token)
        var requests = 0
        val api = FinanceApiClient({ scope.apiOrigin }, tokens, MockEngine { requests++; error("No network during local publication") })
        val locator = Locator()
        val directory = LedgerSessionDirectory(locator)
        val storage = Storage()
        lateinit var ticket: LedgerSessionTicket
        suspend fun prepare() { ticket = directory.begin(scope.apiOrigin); directory.register(ticket, scope) }
        suspend fun publish(): Unit = api.publishVerifiedLedgerSession(ApiCredentialSnapshot(token, scope.apiOrigin),
            directory, ticket, scope, storage, session)
    }

    private suspend fun fixture(action: suspend Fixture.() -> Unit) {
        val fixture = Fixture()
        try { fixture.prepare(); fixture.action() }
        finally { fixture.api.close(); fixture.storage.close() }
    }
}
