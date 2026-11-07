package com.finance.tracker

import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.data.*
import com.finance.tracker.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SessionCacheRepositoryTest {
    private val origin = "https://session-cache.example.test"
    private val firstUser = ledgerOfflinePrincipal("first@example.test")
    private val secondUser = ledgerOfflinePrincipal("second@example.test")
    private val firstScope = LedgerCacheScope(origin, firstUser, "first")
    private val secondScope = firstScope.copy(workspaceId = "second")
    private val secondUserScope = firstScope.copy(principal = secondUser)

    private class Locator : LedgerSessionLocatorStorage {
        private val values = mutableMapOf<String, String>()
        override suspend fun read(key: String) = values[key]
        override suspend fun compareAndSet(key: String, expected: String?, replacement: String): Boolean {
            if (values[key] != expected) return false
            values[key] = replacement
            return true
        }
    }

    private class Delegate(private val events: MutableList<String>) : SessionRepository {
        val calls = mutableListOf<String>()
        var logoutFailure: Throwable? = null
        var restoreFailure: Throwable? = null
        var sessionFailure: Throwable? = null
        var loginFailure: Throwable? = null
        var beforeSession: (suspend () -> Unit)? = null
        var next = Session(User("first@example.test"), "first",
            listOf(Workspace("first", "First", WorkspaceRole.ADMIN)))

        override suspend fun hasStoredSessionToken() = true
        override suspend fun restoreSession(): Session { calls += "restore"; restoreFailure?.let { throw it }; return next }
        override suspend fun session(): Session {
            calls += "session"; beforeSession?.invoke(); sessionFailure?.let { throw it }; return next
        }
        override suspend fun login(email: String, password: String): Session {
            calls += "login"; loginFailure?.let { throw it }; return next.copy(user = User(email))
        }
        override suspend fun register(email: String, password: String) = next.copy(user = User(email)).also { calls += "register" }
        override suspend fun logout() { calls += "logout"; events += "logout"; logoutFailure?.let { throw it } }
        override suspend fun selectWorkspace(id: String) = next.also { calls += "select" }
        override suspend fun createWorkspace(name: String) = next.also { calls += "create" }
        override suspend fun invitationPreview(token: String) = InvitationPreview(InvitationWorkspace("x"), WorkspaceRole.VIEWER, true)
        override suspend fun acceptInvitation(token: String) = next
        override suspend fun workspaceDetails() = WorkspaceMembers(WorkspaceDetails("first", "First"), emptyList())
        override suspend fun updateWorkspace(name: String) = next
        override suspend fun deleteWorkspace(name: String) = next
        override suspend fun invite(role: WorkspaceRole) = "token"
        override suspend fun members() = WorkspaceMembers(WorkspaceDetails("first", "First"), emptyList())
        override suspend fun updateMember(id: String, role: WorkspaceRole) {}
        override suspend fun removeMember(id: String) {}
    }

    private class Erasures(private val events: MutableList<String>) {
        val created = mutableListOf<LedgerCacheScope>()
        val erased = mutableListOf<LedgerCacheScope>()
        val failures = mutableMapOf<LedgerCacheScope, Throwable>()
        var beforeErase: (suspend (LedgerCacheScope) -> Unit)? = null

        val factory: (LedgerCacheScope, LedgerSessionDirectory, LedgerSessionTicket) -> LedgerCacheErasure =
            { scope, directory, _ ->
                created += scope
                object : LedgerCacheErasure {
                    override suspend fun clear() {
                        beforeErase?.invoke(scope)
                        failures[scope]?.let { throw it }
                        directory.forget(scope)
                        erased += scope
                        events += "erase:${scope.workspaceId}"
                    }
                    override suspend fun close() {}
                }
            }
    }

    private inner class Fixture {
        val events = mutableListOf<String>()
        val delegate = Delegate(events)
        val directory = LedgerSessionDirectory(Locator())
        val erasures = Erasures(events)
        val bindings = mutableMapOf<LedgerCacheScope, String>()
        var probes = 0

        suspend fun seedScopes() {
            val ticket = directory.begin(origin)
            listOf(firstScope, secondScope, secondUserScope).forEach { directory.register(ticket, it) }
            bindings[firstScope] = "first-token"
            bindings[secondScope] = "first-token"
            bindings[secondUserScope] = "second-token"
        }

        fun repository() = SessionCacheRepository(
            delegate = delegate,
            apiOrigin = origin,
            directory = { directory },
            createErasure = erasures.factory,
            openCache = { scope, _, _ ->
                probes++
                object : LedgerStorage {
                    override suspend fun read(scope: LedgerCacheScope): LedgerSnapshotDto? = null
                    override suspend fun replace(scope: LedgerCacheScope, snapshot: LedgerSnapshotDto) = error("read-only probe")
                    override suspend fun clear(scope: LedgerCacheScope) = error("no implicit erasure")
                    override suspend fun close() {}
                    override suspend fun readOfflineSession(scope: LedgerCacheScope, token: String): SessionDto? =
                        if (bindings[scope] == token) SessionDto(
                            UserDto("user@example.test"), scope.workspaceId,
                            listOf(WorkspaceDto(scope.workspaceId, "Workspace", Role.ADMIN)),
                        ) else null
                    override suspend fun saveOfflineSession(scope: LedgerCacheScope, token: String, session: SessionDto) =
                        error("read-only probe")
                }
            },
        )
    }

    @Test fun logoutRevokesTheTokenBeforeClearingEveryScopeOfTheLastVerifiedUser() = runTest {
        val f = Fixture(); f.seedScopes()
        val repository = f.repository()
        repository.login("first@example.test", "password")
        f.delegate.calls.clear(); f.events.clear()
        repository.logout()
        assertEquals(listOf("logout", "erase:first", "erase:second"), f.events)
        assertEquals(listOf(firstScope, secondScope), f.erasures.erased)
        assertEquals(listOf("logout"), f.delegate.calls)
        assertEquals(listOf(secondUserScope), f.directory.scopes(origin))
        repository.logout()
        assertEquals(listOf(firstScope, secondScope), f.erasures.created)
        assertEquals(listOf("logout", "erase:first", "erase:second", "logout"), f.events)
    }

    @Test fun logoutWithoutAVerifiedIdentityOnlyRevokesTheTokenAndDeletesNothing() = runTest {
        val f = Fixture(); f.seedScopes()
        f.repository().logout()
        assertTrue(f.erasures.created.isEmpty())
        assertEquals(3, f.directory.scopes(origin).size)
        assertEquals(listOf("logout"), f.delegate.calls)
    }

    @Test fun aFailedRestoreRecordsNoIdentityAndCannotAuthorizeDeletion() = runTest {
        val f = Fixture(); f.seedScopes()
        f.delegate.restoreFailure = IllegalStateException("offline")
        val repository = f.repository()
        assertFailsWith<IllegalStateException> { repository.restoreSession() }
        repository.logout()
        assertTrue(f.erasures.created.isEmpty())
        assertEquals(3, f.directory.scopes(origin).size)
    }

    @Test fun aFailedErasureStillRevokesTheTokenAndResumesTheOriginalTargets() = runTest {
        val f = Fixture(); f.seedScopes()
        val repository = f.repository()
        repository.login("first@example.test", "password")
        f.erasures.failures[secondScope] = IllegalStateException("blocked")
        val thrown = assertFailsWith<DomainFailure> { repository.logout() }
        assertEquals("sign_out_incomplete", thrown.code)
        assertEquals(listOf("login", "logout"), f.delegate.calls)
        assertEquals(listOf(firstScope), f.erasures.erased)
        f.erasures.failures.clear()
        repository.logout()
        assertEquals(listOf(firstScope, secondScope), f.erasures.created)
        assertEquals(listOf(firstScope, secondScope), f.erasures.erased)
        assertEquals(listOf(secondUserScope), f.directory.scopes(origin))
        assertEquals(listOf("login", "logout", "logout"), f.delegate.calls)
    }

    @Test fun aRetryAfterAnotherOperationAdvancedTheDirectoryStartsAFreshCleanup() = runTest {
        val f = Fixture(); f.seedScopes()
        val repository = f.repository()
        repository.login("first@example.test", "password")
        f.erasures.failures[secondScope] = IllegalStateException("blocked")
        assertFailsWith<DomainFailure> { repository.logout() }
        f.directory.begin(origin) // A later login or restore advanced the generation.
        f.erasures.failures.clear()
        repository.logout()
        assertEquals(listOf(secondScope), f.erasures.created.drop(2))
        assertEquals(listOf(secondScope), f.erasures.erased.drop(1))
        assertEquals(listOf(secondUserScope), f.directory.scopes(origin))
    }

    @Test fun aConcurrentOperationDuringCleanupAbortsDeletionAndReportsIncomplete() = runTest {
        val f = Fixture(); f.seedScopes()
        val repository = f.repository()
        repository.login("first@example.test", "password")
        val later = firstScope.copy(workspaceId = "later")
        f.erasures.beforeErase = { scope ->
            if (scope == firstScope) {
                f.directory.register(f.directory.begin(origin), later)
                f.erasures.beforeErase = null
            }
        }
        val thrown = assertFailsWith<DomainFailure> { repository.logout() }
        assertEquals("sign_out_incomplete", thrown.code)
        assertTrue(thrown.cause is CancellationException)
        assertEquals(listOf(firstScope), f.erasures.erased)
        assertEquals(listOf(secondScope, secondUserScope, later), f.directory.scopes(origin))
    }

        @Test fun loggingInAsAnotherUserReplacesWhichScopesSignOutClears() = runTest {
        val f = Fixture(); f.seedScopes()
        val repository = f.repository()
        repository.login("first@example.test", "password")
        repository.login("second@example.test", "password")
        repository.logout()
        assertEquals(listOf(secondUserScope), f.erasures.erased)
        assertEquals(listOf(firstScope, secondScope), f.directory.scopes(origin))
    }

    @Test fun aLoginThatCompletesDuringCleanupKeepsTheNewIdentity() = runTest {
        val f = Fixture(); f.seedScopes()
        val repository = f.repository()
        repository.login("first@example.test", "password")
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.erasures.beforeErase = { if (it == firstScope) { entered.complete(Unit); release.await() } }
        val signingOut = async { repository.logout() }
        entered.await()
        repository.login("second@example.test", "password")
        release.complete(Unit)
        signingOut.await()
        // The completed sign-out cleared the first user only; the new login remains tracked.
        f.erasures.beforeErase = null
        f.erasures.erased.clear()
        repository.logout()
        assertEquals(listOf(secondUserScope), f.erasures.erased)
    }

    private fun unauthorized(rejectedToken: String? = "first-token"): DomainFailure =
        DomainFailure("authentication_required", 401, FailureCategory.RECOVERABLE,
            cause = ApiFailure("authentication_required", 401).also { it.rejectedToken = rejectedToken })

    @Test fun aServerRejectionClearsScopesBoundToThatTokenWithoutAVerifiedIdentity() = runTest {
        val f = Fixture(); f.seedScopes()
        f.delegate.sessionFailure = unauthorized()
        assertFailsWith<DomainFailure> { f.repository().session() }
        assertEquals(listOf(firstScope, secondScope), f.erasures.erased)
        assertEquals(listOf(secondUserScope), f.directory.scopes(origin))
        assertEquals(3, f.probes)
    }

    @Test fun aRejectionAfterANewLoginKeepsTheNewScopes() = runTest {
        val f = Fixture(); f.seedScopes()
        val repository = f.repository()
        val fresh = firstScope.copy(workspaceId = "fresh")
        f.delegate.beforeSession = {
            f.directory.register(f.directory.begin(origin), fresh)
            f.bindings[fresh] = "second-token"
        }
        f.delegate.sessionFailure = unauthorized("first-token")
        assertFailsWith<DomainFailure> { repository.session() }
        assertEquals(listOf(firstScope, secondScope), f.erasures.erased)
        assertEquals(listOf(secondUserScope, fresh), f.directory.scopes(origin))
    }

    @Test fun aNetworkFailureKeepsTokenBoundScopes() = runTest {
        val f = Fixture(); f.seedScopes()
        f.delegate.sessionFailure = DomainFailure("network_unavailable", 0, FailureCategory.RECOVERABLE)
        assertFailsWith<DomainFailure> { f.repository().session() }
        assertTrue(f.erasures.created.isEmpty()); assertEquals(3, f.directory.scopes(origin).size)
        assertEquals(0, f.probes)
    }

    @Test fun aLocalUnauthorizedFailureWithoutARejectedTokenDeletesNothing() = runTest {
        val f = Fixture(); f.seedScopes()
        f.delegate.sessionFailure = unauthorized(rejectedToken = null)
        assertFailsWith<DomainFailure> { f.repository().session() }
        assertTrue(f.erasures.created.isEmpty()); assertEquals(3, f.directory.scopes(origin).size)
    }

    @Test fun invalidCredentialsDuringLoginDoNotEvictScopes() = runTest {
        val f = Fixture(); f.seedScopes()
        f.delegate.loginFailure = unauthorized()
        assertFailsWith<DomainFailure> { f.repository().login("first@example.test", "wrong") }
        assertTrue(f.erasures.created.isEmpty()); assertEquals(3, f.directory.scopes(origin).size)
        assertEquals(0, f.probes)
    }

    @Test fun aFailedEvictionKeepsTheAuthenticationFailureAndRetriesLater() = runTest {
        val f = Fixture(); f.seedScopes()
        val repository = f.repository()
        f.delegate.sessionFailure = unauthorized()
        f.erasures.failures[secondScope] = IllegalStateException("blocked")
        val thrown = assertFailsWith<DomainFailure> { repository.session() }
        assertEquals(401, thrown.status)
        assertEquals(listOf(firstScope), f.erasures.erased)
        f.erasures.failures.clear()
        assertFailsWith<DomainFailure> { repository.session() }
        assertEquals(listOf(firstScope, secondScope), f.erasures.created)
        assertEquals(listOf(secondScope), f.erasures.erased.drop(1))
        assertEquals(listOf(secondUserScope), f.directory.scopes(origin))
    }

    @Test fun theTokenFailureIsReportedWithTheCleanupFailureRetained() = runTest {
        val f = Fixture(); f.seedScopes()
        val repository = f.repository()
        repository.login("first@example.test", "password")
        val tokenFailure = IllegalStateException("remote sign-out failed")
        f.delegate.logoutFailure = tokenFailure
        f.erasures.failures[secondScope] = IllegalStateException("blocked")
        val thrown = assertFailsWith<DomainFailure> { repository.logout() }
        assertEquals("sign_out_incomplete", thrown.code)
        val paired = thrown.cause as SessionSignOutFailure
        assertSame(tokenFailure, paired.tokenFailure)
        assertEquals("sign_out_incomplete", (paired.cacheFailure as DomainFailure).code)
        assertEquals(listOf(firstScope), f.erasures.erased)
    }
}
