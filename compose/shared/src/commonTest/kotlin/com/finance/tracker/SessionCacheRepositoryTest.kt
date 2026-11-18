package com.finance.tracker

import com.finance.tracker.business.contracts.AccountReferenceDto
import com.finance.tracker.business.contracts.CashPageDto
import com.finance.tracker.business.contracts.CategoryDirectoryDto
import com.finance.tracker.business.contracts.LedgerOptionsDto
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.data.*
import com.finance.tracker.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.test.*
import io.ktor.client.engine.mock.respond

class SessionCacheRepositoryTest {
    @Test fun queuedWorkspaceWritesCannotAdoptAReplacementSession() = runTest {
        val operations: List<suspend (SessionRepository) -> Any?> = listOf(
            { it.createWorkspace("Another") }, { it.acceptInvitation("invite") },
            { it.updateWorkspace("Renamed") }, { it.deleteWorkspace("First") },
            { it.invite(WorkspaceRole.VIEWER) }, { it.updateMember("member", WorkspaceRole.EDITOR) },
            { it.removeMember("member") },
        )
        for (operation in operations) {
            val f = Fixture(); val repository = f.repository()
            repository.restoreSession()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.delegate.beforeSession = { entered.complete(Unit); release.await() }
            val verification = async { repository.session() }
            entered.await()
            val queued = async(start = CoroutineStart.UNDISPATCHED) { runCatching { operation(repository) } }
            release.complete(Unit)
            verification.await()
            val callsAfterVerification = f.delegate.calls.toList()
            assertIs<CancellationException>(queued.await().exceptionOrNull())
            assertEquals(callsAfterVerification, f.delegate.calls)
        }
    }

    @Test fun aWorkspaceWriteCannotSendAfterLogoutDuringLocalClose() = runTest {
        val f = Fixture(); f.seedScopes(); val repository = f.repository()
        repository.restoreSession()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var paused = false
        f.local.beforeClose = {
            if (!paused) { paused = true; entered.complete(Unit); release.await() }
        }
        val writing = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { repository.createWorkspace("Another") }
        }
        entered.await()
        repository.logout()
        release.complete(Unit)
        assertIs<CancellationException>(writing.await().exceptionOrNull())
        assertFalse("create" in f.delegate.calls)
    }

    @Test fun offlineWorkspaceWritesStopBeforeDelegationOrClosingTheCachedSession() = runTest {
        val operations: List<suspend (SessionRepository) -> Any?> = listOf(
            { it.createWorkspace("Another") }, { it.acceptInvitation("invite") },
            { it.updateWorkspace("Renamed") }, { it.deleteWorkspace("First") },
            { it.invite(WorkspaceRole.VIEWER) }, { it.updateMember("member", WorkspaceRole.EDITOR) },
            { it.removeMember("member") },
        )
        for (operation in operations) {
            val f = Fixture(); val repository = f.repository()
            val session = repository.restoreSession(); f.local.markOffline()
            val originalCalls = f.delegate.calls.toList()
            val failure = assertFailsWith<DomainFailure> { operation(repository) }
            assertEquals("network_unavailable", failure.code)
            assertEquals(originalCalls, f.delegate.calls)
            assertEquals(0, f.local.closes)
            assertTrue(repository.offline.first())
            assertNotNull(repository.localReader())
            assertEquals(WorkspaceRole.ADMIN, session.workspaces.single().role)
        }
    }

    @Test fun memberWritesBindTheOriginalCredentialsInsteadOfReadingALaterToken() = runTest {
        val f = Fixture(); var tokenReads = 0
        val paths = mutableListOf<String>()
        val api = FinanceApiClient({ origin }, object : com.finance.tracker.core.TokenStore {
            override suspend fun get(): String { tokenReads++; return "later-token" }
            override suspend fun set(token: String) = Unit
            override suspend fun clear() = Unit
        }, io.ktor.client.engine.mock.MockEngine { request ->
            assertEquals("Bearer first-token", request.headers[io.ktor.http.HttpHeaders.Authorization])
            paths += request.url.encodedPath
            respond(if (request.url.encodedPath.endsWith("invitations")) """{"token":"created-invite"}""" else """{"ok":true}""",
                io.ktor.http.HttpStatusCode.OK,
                io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json"))
        })
        try {
            val actual = KtorSessionRepository(api)
            val repository = f.repository { actual.boundTo(it) }
            repository.restoreSession()
            assertEquals("created-invite", repository.invite(WorkspaceRole.VIEWER))
            repository.updateMember("member", WorkspaceRole.EDITOR)
            repository.removeMember("member")
            assertEquals(listOf("/api/v1/auth/invitations", "/api/v1/auth/members/member", "/api/v1/auth/members/member"), paths)
            assertEquals(0, tokenReads)
        } finally { api.close() }
    }

    @Test fun aMemberTransportFailureClosesOnlyTheOriginalWriteQualification() = runTest {
        val f = Fixture(); var requests = 0
        val api = FinanceApiClient({ origin }, object : com.finance.tracker.core.TokenStore {
            override suspend fun get() = "first-token"
            override suspend fun set(token: String) = Unit
            override suspend fun clear() = Unit
        }, io.ktor.client.engine.mock.MockEngine { requests++; throw kotlinx.io.IOException("synthetic member disconnect") })
        try {
            val actual = KtorSessionRepository(api)
            val repository = f.repository { actual.boundTo(it) }
            repository.restoreSession()
            assertEquals("network_unavailable", assertFailsWith<DomainFailure> { repository.invite(WorkspaceRole.VIEWER) }.code)
            assertTrue(repository.offline.first())
            assertEquals("network_unavailable", assertFailsWith<DomainFailure> { repository.removeMember("member") }.code)
            assertEquals(1, requests)
            assertEquals(0, f.local.closes)
            assertNotNull(repository.localReader())
        } finally { api.close() }
    }

    @Test fun aVerifiedReconnectWithFailedLocalRestorationCannotReopenWrites() = runTest {
        val f = Fixture(); val repository = f.repository()
        repository.restoreSession()
        assertTrue(repository.ledgerSessionReady.first())
        f.local.markOffline()
        f.createFailure = DomainFailure("projection.unavailable", 503, FailureCategory.RECOVERABLE)
        assertFailsWith<DomainFailure> { repository.session() }
        assertFalse(repository.ledgerSessionReady.first())
        assertNull(repository.localReader())
        f.createFailure = null
        repository.session()
        assertTrue(repository.ledgerSessionReady.first())
        assertFalse(repository.offline.first())
    }

    @Test fun offlineCashWritesAreRejectedBeforeHttpWithoutChangingTheHistoricalRoleOrCache() = runTest {
        val f = Fixture(); f.seedScopes(); f.local.offline.value = true
        val repository = f.repository(); val session = repository.restoreSession()
        var requests = 0
        val api = FinanceApiClient({ origin }, object : com.finance.tracker.core.TokenStore {
            override suspend fun get() = "first-token"
            override suspend fun set(value: String) = Unit
            override suspend fun clear() = Unit
        }, io.ktor.client.engine.mock.MockEngine {
            requests++
            respond("""{"error":{"code":"unexpected_offline_write","message":"synthetic"}}""",
                io.ktor.http.HttpStatusCode.InternalServerError,
                io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json"))
        })
        try {
            val operations = repository.businessOperations(api)
            val finance = KtorFinanceRepository(api, operations)
            val creation = KtorCashCreationRepository(api, operations)
            val maintenance = KtorCashMaintenanceRepository(api, operations)
            val input = StructuredObject(emptyMap())
            val writes: List<suspend () -> Any?> = listOf(
                { finance.classifyCashProjections(listOf("cash-entry:one"), 1, null) },
                { finance.deleteCashProjections(listOf("cash-entry:one"), 1) },
                { finance.createCashCategory("category", null, "", 1) },
                { finance.updateCashCategory("category", "changed", null, "", 1) },
                { finance.reorderCashCategory("category", "up", 1) },
                { finance.deleteCashCategory("category", CashCategoryDeleteImpact("category", 1, 1, 0, 0)) },
                { creation.createRecord(input) },
                { creation.createTransfer(input) },
                { maintenance.saveDraft("1", 1, input, "key") },
                { maintenance.commit(ImportFrozenCommand("plan", 1, "key")) },
            )
            for (write in writes) {
                val failure = assertFailsWith<DomainFailure> { write() }
                assertEquals("network_unavailable", failure.code)
                assertNull(failure.commitState)
            }
            assertEquals(0, requests)
            val preview = assertFailsWith<DomainFailure> { finance.fetchCashProjectionDeleteImpact(listOf("cash-entry:one"), 1) }
            assertEquals("unexpected_offline_write", preview.code)
            val maintenancePreview = assertFailsWith<DomainFailure> { maintenance.preview(CashMaintenanceRequest("edit_entry", "1", 1, entryId = "cash-entry:one", expectedEntryRevision = 1)) }
            assertEquals("unexpected_offline_write", maintenancePreview.code)
            assertEquals(2, requests)
            assertEquals(WorkspaceRole.ADMIN, session.workspaces.single().role)
            assertNotNull(repository.localReader())
            assertTrue(f.local.refreshes.isEmpty())
        } finally { api.close() }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun anObservedTransportFailureClosesLaterWritesUntilActualSessionVerification() = runTest {
        val f = Fixture(); f.seedScopes()
        val repository = f.repository(); repository.restoreSession()
        val observed = mutableListOf<Boolean>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            repository.offline.collect { observed += it }
        }
        var requests = 0
        val api = FinanceApiClient({ origin }, object : com.finance.tracker.core.TokenStore {
            override suspend fun get() = "first-token"
            override suspend fun set(value: String) = Unit
            override suspend fun clear() = Unit
        }, io.ktor.client.engine.mock.MockEngine {
            requests++; throw kotlinx.io.IOException("synthetic disconnected write")
        })
        try {
            val operations = repository.businessOperations(api)
            val creation = KtorCashCreationRepository(api, operations)
            val finance = KtorFinanceRepository(api, operations)
            assertEquals("network_unavailable", assertFailsWith<DomainFailure> { creation.createRecord(StructuredObject(emptyMap())) }.code)
            assertTrue(f.local.offline.value)
            testScheduler.runCurrent()
            assertEquals(listOf(false, true), observed)
            assertEquals("network_unavailable", assertFailsWith<DomainFailure> { finance.classifyCashProjections(listOf("cash-entry:one"), 1, null) }.code)
            assertEquals(1, requests)
            assertNotNull(repository.localReader())
            repository.refreshBrowsingFacts()
            assertTrue(f.local.offline.value)
            assertEquals("network_unavailable", assertFailsWith<DomainFailure> { creation.createTransfer(StructuredObject(emptyMap())) }.code)
            assertEquals(1, requests)
            val verified = repository.session()
            assertFalse(f.local.offline.value)
            testScheduler.runCurrent()
            assertEquals(listOf(false, true, false), observed)
            assertEquals(WorkspaceRole.ADMIN, verified.workspaces.single().role)
            assertEquals("network_unavailable", assertFailsWith<DomainFailure> { creation.createRecord(StructuredObject(emptyMap())) }.code)
            assertEquals(2, requests)
            testScheduler.runCurrent()
            assertEquals(listOf(false, true, false, true), observed)
        } finally { api.close() }
    }

    @Test fun localProgramFailuresAndBusinessRejectionsDoNotCloseOnlineQualification() = runTest {
        val f = Fixture(); f.seedScopes()
        val repository = f.repository(); repository.restoreSession()
        val api = FinanceApiClient({ origin }, object : com.finance.tracker.core.TokenStore {
            override suspend fun get() = "first-token"
            override suspend fun set(value: String) = Unit
            override suspend fun clear() = Unit
        }, io.ktor.client.engine.mock.MockEngine { error("No HTTP expected") })
        try {
            for (failure in listOf(IllegalStateException("synthetic program failure"),
                kotlinx.io.IOException("synthetic local storage failure"),
                ApiFailure("permission_denied", 403), CancellationException("synthetic cancellation"))) {
                assertSame(failure, assertFailsWith<Throwable> { repository.businessOperations(api).run<Nothing> { _, _ -> throw failure } })
                assertFalse(repository.offline.first())
            }
        } finally { api.close() }
    }

    @Test fun aLateTransportFailureCannotMarkTheReplacementPublicationOffline() = runTest {
        val f = Fixture(); f.seedScopes()
        val repository = f.repository(); repository.restoreSession()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val api = FinanceApiClient({ origin }, object : com.finance.tracker.core.TokenStore {
            override suspend fun get() = "first-token"
            override suspend fun set(value: String) = Unit
            override suspend fun clear() = Unit
        }, io.ktor.client.engine.mock.MockEngine {
            entered.complete(Unit); release.await(); throw kotlinx.io.IOException("synthetic late transport failure")
        })
        try {
            val oldWrite = async {
                assertFailsWith<DomainFailure> { KtorCashCreationRepository(api, repository.businessOperations(api)).createRecord(StructuredObject(emptyMap())) }
            }
            entered.await(); repository.restoreSession()
            release.complete(Unit); oldWrite.await()
            assertFalse(f.local.offline.value)
            assertFalse(repository.offline.first())
            assertNotNull(repository.localReader())
        } finally { release.complete(Unit); api.close() }
    }

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
        var afterLogout: (suspend () -> Unit)? = null
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
        override suspend fun logout() { calls += "logout"; events += "logout"; logoutFailure?.let { throw it }; afterLogout?.invoke() }
        override suspend fun selectWorkspace(id: String) = next.also { calls += "select" }
        override suspend fun createWorkspace(name: String) = next.also { calls += "create" }
        override suspend fun invitationPreview(token: String) = InvitationPreview(InvitationWorkspace("x"), WorkspaceRole.VIEWER, true)
        override suspend fun acceptInvitation(token: String) = next.also { calls += "accept" }
        override suspend fun workspaceDetails() = WorkspaceMembers(WorkspaceDetails("first", "First"), emptyList())
        override suspend fun updateWorkspace(name: String) = next.also { calls += "update" }
        override suspend fun deleteWorkspace(name: String) = next.also { calls += "delete" }
        override suspend fun invite(role: WorkspaceRole) = "token".also { calls += "invite" }
        override suspend fun members() = WorkspaceMembers(WorkspaceDetails("first", "First"), emptyList())
        override suspend fun updateMember(id: String, role: WorkspaceRole) { calls += "update-member" }
        override suspend fun removeMember(id: String) { calls += "remove-member" }
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

    private class FakeLocal(
        var session: SessionDto,
        private val events: MutableList<String>,
        offline: Boolean = false,
        override val token: String? = "first-token",
    ) : LedgerSessionLocal {
        override val offline = MutableStateFlow(offline)
        override fun markOffline() { offline.value = true }
        var beforeRestore: (suspend () -> Unit)? = null
        var beforeClose: (suspend () -> Unit)? = null
        var restoreFailure: Throwable? = null
        var closeFailures = 0
        var restores = 0
        var closes = 0
        val refreshes = mutableListOf<String?>()
        var refreshFailure: Throwable? = null
        var beforeRefresh: (suspend () -> Unit)? = null
        var beforeRead: suspend () -> Unit = {}
        var page: CashPageDto? = null
        var accounts: List<AccountReferenceDto>? = null
        var categories: CategoryDirectoryDto? = null
        var options: LedgerOptionsDto? = null

        override suspend fun imports(): LedgerSessionImports? = null

        override suspend fun restore(): SessionDto {
            restores++
            beforeRestore?.invoke()
            restoreFailure?.let { throw it }
            return session
        }

        override suspend fun refreshFacts(committedLedgerSeq: String?) {
            beforeRefresh?.invoke()
            refreshFailure?.let { throw it }
            refreshes += committedLedgerSeq
        }

        override suspend fun close() {
            beforeClose?.invoke()
            closes++
            events += "local:close"
            if (closeFailures > 0) { closeFailures--; throw IllegalStateException("unconfirmed close") }
        }

        override suspend fun page(filters: CashFiltersDto, timeZoneId: String?, cursor: String?,
            startIndex: Int?, expectedLedgerSeq: Long?, limit: Int): CashPageDto? { beforeRead(); return page }

        override suspend fun currentGroup(projectionId: String): com.finance.tracker.business.contracts.CashCurrentMembersDto? { beforeRead(); return null }
        override suspend fun record(transactionId: String): com.finance.tracker.business.contracts.CashCurrentRecordDto? { beforeRead(); return null }
        override suspend fun sourceRecords(projectionId: String): com.finance.tracker.core.LocalCashSourceRecords? { beforeRead(); return null }
        override suspend fun categories(): CategoryDirectoryDto? { beforeRead(); return categories }
        override suspend fun options(): LedgerOptionsDto? { beforeRead(); return options }
        override suspend fun accounts(): List<AccountReferenceDto>? { beforeRead(); return accounts }
    }

    private inner class Fixture {
        val diagnostics = RecordingDiagnosticLogger()
        val events = mutableListOf<String>()
        val delegate = Delegate(events)
        val directory = LedgerSessionDirectory(Locator())
        val erasures = Erasures(events)
        val bindings = mutableMapOf<LedgerCacheScope, String>()
        var probes = 0
        var createFailure: Throwable? = null
        val local = FakeLocal(
            SessionDto(UserDto("first@example.test"), "first",
                listOf(WorkspaceDto("first", "First", Role.ADMIN))),
            events,
        )

        suspend fun seedScopes() {
            val ticket = directory.begin(origin)
            listOf(firstScope, secondScope, secondUserScope).forEach { directory.register(ticket, it) }
            bindings[firstScope] = "first-token"
            bindings[secondScope] = "first-token"
            bindings[secondUserScope] = "second-token"
        }

        fun repository(bind: ((ApiCredentialSnapshot) -> SessionRepository)? = null) = SessionCacheRepository(
            delegate = delegate,
            diagnosticLogger = diagnostics,
            apiOrigin = origin,
            captureCredentials = { ApiCredentialSnapshot("first-token", origin) },
            clearRejectedToken = {},
            createRestoration = { _, _, _ -> createFailure?.let { throw it } ?: local },
            authenticate = { email, password, registering, _, _ ->
                val session = if (registering) delegate.register(email, password) else delegate.login(email, password)
                val dto = SessionDto(UserDto(session.user.email), session.activeWorkspaceId,
                    session.workspaces.map { WorkspaceDto(it.id, it.name, it.role.toData()) })
                local.session = dto
                local.offline.value = false
                AuthenticatedApiSession(dto, ApiCredentialSnapshot("first-token", origin))
            },
            bindSession = bind ?: {
                object : SessionRepository by delegate {
                    override suspend fun session(): Session = delegate.session().also { local.offline.value = false }
                }
            },
            directory = { directory },
            createErasure = erasures.factory,
            openCache = { scope, owner, ticket ->
                probes++
                object : LedgerStorage {
                    val erasure by lazy { erasures.factory(scope, owner, ticket) }
                    override val imports: com.finance.tracker.data.LedgerImportBatches get() = error("No import file access in this fixture")
                    override suspend fun read(scope: LedgerCacheScope): LedgerSnapshotDto? = null
                    override suspend fun replace(scope: LedgerCacheScope, snapshot: LedgerSnapshotDto) = error("read-only probe")
                    override suspend fun clear(scope: LedgerCacheScope) = erasure.clear()
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

    @Test fun cancellationAfterLogoutHttpKeepsDetachedResourcesOwnedUntilTheyCanClose() = runTest {
        val f = Fixture(); f.seedScopes()
        val repository = f.repository()
        f.erasures.failures[secondUserScope] = IllegalStateException("synthetic retained token eviction")
        f.delegate.sessionFailure = unauthorized("second-token")
        assertFailsWith<DomainFailure> { repository.session() }
        f.delegate.sessionFailure = null
        repository.restoreSession()
        val closes = f.local.closes
        f.delegate.afterLogout = { currentCoroutineContext().cancel() }
        val signingOut = launch { repository.logout() }
        signingOut.join()
        assertTrue(signingOut.isCancelled)
        f.delegate.afterLogout = null
        repository.restoreSession()
        assertTrue(f.local.closes > closes, "the detached original resource must close before a replacement is opened")
    }

    @Test fun logoutRevokesTheTokenBeforeClearingEveryScopeOfTheLastVerifiedUser() = runTest {
        val f = Fixture(); f.seedScopes()
        val repository = f.repository()
        repository.login("first@example.test", "password")
        f.delegate.calls.clear(); f.events.clear()
        repository.logout()
        assertEquals(listOf("logout", "local:close", "erase:first", "erase:second"), f.events)
        assertEquals(listOf(firstScope, secondScope), f.erasures.erased)
        assertEquals(listOf("logout"), f.delegate.calls)
        assertEquals(listOf(secondUserScope), f.directory.scopes(origin))
        repository.logout()
        assertEquals(listOf(firstScope, secondScope), f.erasures.created)
        assertEquals(listOf("logout", "local:close", "erase:first", "erase:second", "logout"), f.events)
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
        f.local.restoreFailure = IllegalStateException("offline")
        val repository = f.repository()
        assertFailsWith<IllegalStateException> { repository.restoreSession() }
        assertEquals(1, f.local.closes)
        assertNull(repository.localReader())
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
        val signingOut = async { runCatching { repository.logout() } }
        entered.await()
        repository.login("second@example.test", "password")
        release.complete(Unit)
        assertEquals("sign_out_incomplete", assertIs<DomainFailure>(signingOut.await().exceptionOrNull()).code)
        // The newer directory operation fences the old cleanup; the new login remains tracked.
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

    @Test fun restoredOfflineSessionExposesTheLocalReaderForBusinessReads() = runTest {
        val f = Fixture(); f.seedScopes()
        f.local.offline.value = true
        f.local.page = CashPageDto(projectionVersion = 1, items = emptyList(), monthlySummaries = emptyList(),
            totalCount = 0, pendingItemCount = 0)
        val repository = f.repository()
        val session = repository.restoreSession()
        assertEquals("first", session.activeWorkspaceId)
        assertEquals(f.local.page, repository.localReader()?.page(CashFiltersDto()))
        assertEquals(1, f.local.restores)
    }

    @Test fun anOnlineRestoreUsesTheSameLocalPageAndCatalogs() = runTest {
        val f = Fixture(); f.seedScopes()
        f.local.offline.value = false
        val repository = f.repository()
        repository.restoreSession()
        f.local.page = CashPageDto(projectionVersion = 7)
        f.local.accounts = listOf(AccountReferenceDto(1, "Local", "cash"))
        f.local.categories = CategoryDirectoryDto(9, emptyList())
        f.local.options = LedgerOptionsDto()
        val reader = assertNotNull(repository.localReader())
        assertEquals(f.local.page, reader.page())
        assertEquals(f.local.accounts, reader.accounts())
        assertEquals(f.local.categories, reader.categories())
        assertEquals(f.local.options, reader.options())
        val remote = object : FinanceRepository by DemoFinanceRepository() {
            override suspend fun fetchCashAccounts(): List<Account> = error("Online accounts must use the verified local facts")
            override suspend fun fetchCashCategories(): CashCategoryDirectory = error("Online categories must use the verified local facts")
            override suspend fun fetchLedgerOptions(): LedgerOptions = error("Online options must use the verified local facts")
        }
        val finance = LocalFirstFinanceRepository(remote, { repository.localReader() })
        assertEquals(listOf(Account(1, "Local", "cash")), finance.fetchCashAccounts())
        assertEquals(9, finance.fetchCashCategories().revision)
        assertEquals(LedgerOptions(), finance.fetchLedgerOptions())
    }


    private suspend fun LedgerLocalReader.readSurface(surface: String): Any? = when (surface) {
        "page" -> page()
        "accounts" -> accounts()
        "categories" -> categories()
        "group" -> currentGroup("cash-entry:one")
        "record" -> record("1")
        "sources" -> sourceRecords("cash-entry:one")
        else -> options()
    }

    @Test fun aCapturedReaderCannotReadAfterTheSessionPublicationChanges() = runTest {
        for (surface in listOf("page", "accounts", "categories", "options", "group", "record", "sources")) {
            val f = Fixture(); f.seedScopes()
            val repository = f.repository()
            f.local.offline.value = true
            repository.restoreSession()
            val original = assertNotNull(repository.localReader())
            repository.login("second@example.test", "password")
            f.local.beforeRead = { error("A superseded reader must stop before reading any facts") }
            assertFailsWith<CancellationException> { original.readSurface(surface) }
            assertNotNull(repository.localReader())
        }
    }

    @Test fun aReadCrossingSessionPublicationNeverDeliversTheOldResult() = runTest {
        for (surface in listOf("page", "accounts", "categories", "options", "group", "record", "sources")) {
            val f = Fixture(); f.seedScopes(); f.local.offline.value = true
            val repository = f.repository(); repository.restoreSession()
            val reader = assertNotNull(repository.localReader())
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.local.beforeRead = { entered.complete(Unit); release.await() }
            val reading = async { runCatching { reader.readSurface(surface) } }
            entered.await()
            repository.login("second@example.test", "password")
            release.complete(Unit)
            assertIs<CancellationException>(reading.await().exceptionOrNull())
        }
    }

    @Test fun aReadCrossingACompletedRefreshCannotPublishItsOldCapture() = runTest {
        for (surface in listOf("page", "accounts", "categories", "options", "group", "record", "sources")) {
            val f = Fixture(); f.seedScopes(); f.local.offline.value = true
            val repository = f.repository(); repository.restoreSession()
            val reader = assertNotNull(repository.localReader())
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.local.beforeRead = { entered.complete(Unit); release.await() }
            val reading = async { runCatching { reader.readSurface(surface) } }
            entered.await()
            repository.captureRefresh()("9")
            release.complete(Unit)
            val failure = assertIs<DomainFailure>(reading.await().exceptionOrNull())
            assertEquals("read_snapshot_changed", failure.code)
            assertEquals(409, failure.status)
            f.local.beforeRead = {}
            assertNotNull(repository.localReader())
        }
    }

    @Test fun aRefreshDuringTheRealRepositoryReadInvalidatesPagingWithoutAnotherServerRead() = runTest {
        val f = Fixture(); f.seedScopes(); f.local.offline.value = true
        val repository = f.repository(); repository.restoreSession()
        f.local.page = CashPageDto(projectionVersion = 9, startIndex = 0, totalCount = 0)
        var serverReads = 0
        val delegate = object : FinanceRepository by DemoFinanceRepository() {
            override suspend fun fetchCashPage(filters: CashFilters, cursor: String?, startIndex: Int?,
                projectionVersion: Long?, limit: Int): CashPage {
                serverReads++
                error("An invalidated local read cannot select the remote version namespace")
            }
        }
        val local = LocalFirstFinanceRepository(delegate, { repository.localReader() })
        var invalidated = 0; var delivered = 0
        val paging = com.finance.tracker.presentation.CashProjectionPagingSource(local, CashFilters(),
            onSnapshotInvalidated = { invalidated++ }, onPageLoaded = { _, _ -> delivered++ })
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.local.beforeRead = { entered.complete(Unit); release.await() }
        val loading = async { runCatching {
            paging.load(androidx.paging.PagingSource.LoadParams.Refresh(null, 20, true))
        } }
        entered.await()
        repository.captureRefresh()("10")
        release.complete(Unit)
        assertIs<androidx.paging.PagingSource.LoadResult.Invalid<Int, CashProjection>>(loading.await().getOrThrow())
        assertEquals(1, invalidated); assertEquals(0, delivered); assertEquals(0, serverReads)
        f.local.beforeRead = {}
        val refreshedPaging = com.finance.tracker.presentation.CashProjectionPagingSource(local, CashFilters(),
            onPageLoaded = { _, _ -> delivered++ })
        assertIs<androidx.paging.PagingSource.LoadResult.Page<Int, CashProjection>>(
            refreshedPaging.load(androidx.paging.PagingSource.LoadParams.Refresh(null, 20, true)))
        assertEquals(1, delivered); assertEquals(0, serverReads)
    }

    @Test fun cancellingTheCallerDuringALocalReadKeepsCancellationAndNeverCallsTheServer() = runTest {
        val f = Fixture(); f.seedScopes(); f.local.offline.value = true
        val repository = f.repository(); repository.restoreSession()
        val entered = CompletableDeferred<Unit>()
        f.local.beforeRead = { entered.complete(Unit); awaitCancellation() }
        var serverReads = 0
        val delegate = object : FinanceRepository by DemoFinanceRepository() {
            override suspend fun fetchCashPage(filters: CashFilters, cursor: String?, startIndex: Int?,
                projectionVersion: Long?, limit: Int): CashPage {
                serverReads++; error("Caller cancellation cannot fall back to HTTP")
            }
        }
        val local = LocalFirstFinanceRepository(delegate, { repository.localReader() })
        val reading = async { local.fetchCashPage(CashFilters()) }
        entered.await()
        reading.cancelAndJoin()
        assertTrue(reading.isCancelled); assertEquals(0, serverReads)
        assertNotNull(repository.localReader())
    }

    @Test fun aSecondRestoreClosesThePreviousLocalReader() = runTest {
        val f = Fixture(); f.seedScopes()
        val repository = f.repository()
        repository.restoreSession()
        repository.restoreSession()
        assertEquals(2, f.local.restores)
        assertTrue(f.events.count { it == "local:close" } >= 1)
    }

    @Test fun loginDetachesAndClosesTheLocalReaderBeforeUsingTheNewToken() = runTest {
        val f = Fixture(); f.seedScopes(); f.local.offline.value = true
        val repository = f.repository()
        repository.restoreSession()
        f.events.clear()
        repository.login("second@example.test", "password")
        assertNotNull(repository.localReader())
        assertEquals(listOf("local:close"), f.events)
    }

    @Test fun logoutClosesTheLocalReaderBeforeErasingScopes() = runTest {
        val f = Fixture(); f.seedScopes(); f.local.offline.value = true
        val repository = f.repository()
        repository.restoreSession()
        f.events.clear()
        repository.logout()
        assertEquals(listOf("logout", "local:close", "erase:first", "erase:second"), f.events)
    }

    @Test fun anUnauthorizedSessionReadClosesTheLocalReaderBeforeEviction() = runTest {
        val f = Fixture(); f.seedScopes(); f.local.offline.value = true
        val repository = f.repository()
        repository.restoreSession()
        f.delegate.sessionFailure = unauthorized("first-token")
        f.events.clear()
        assertFailsWith<DomainFailure> { repository.session() }
        assertEquals("local:close", f.events.first())
        assertEquals(listOf(firstScope, secondScope), f.erasures.erased)
    }

    @Test fun aFailedLocalCloseIsRetainedAndRetriedByTheNextDetach() = runTest {
        val f = Fixture(); f.seedScopes(); f.local.offline.value = true
        val repository = f.repository()
        repository.restoreSession()
        f.local.closeFailures = 2
        repository.login("second@example.test", "password")
        assertNotNull(repository.localReader())
        repository.login("second@example.test", "password")
        assertEquals(4, f.local.closes)
    }

    @Test fun aLateRejectionForAnotherTokenKeepsTheActiveReader() = runTest {
        val f = Fixture(); f.seedScopes(); f.local.offline.value = true
        val repository = f.repository()
        repository.restoreSession()
        f.delegate.sessionFailure = unauthorized("second-token")
        f.events.clear()
        assertFailsWith<DomainFailure> { repository.session() }
        assertTrue("local:close" !in f.events)
        assertNotNull(repository.localReader())
    }

    @Test fun aRestoreSupersededByLogoutPublishesNothing() = runTest {
        val f = Fixture(); f.seedScopes(); f.local.offline.value = true
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.local.beforeRestore = { entered.complete(Unit); release.await() }
        val repository = f.repository()
        val restoring = async { runCatching { repository.restoreSession() } }
        entered.await()
        repository.logout()
        release.complete(Unit)
        val thrown = assertIs<DomainFailure>(restoring.await().exceptionOrNull())
        assertEquals("session_superseded", thrown.code)
        assertNull(repository.localReader())
    }

    @Test fun aSuccessfulSessionReadReplacesTheStaleOfflineReaderWithAnOnlineReader() = runTest {
        val f = Fixture(); f.seedScopes(); f.local.offline.value = true
        val repository = f.repository()
        repository.restoreSession()
        f.events.clear()
        repository.session()
        assertEquals(listOf("local:close"), f.events)
        assertNotNull(repository.localReader())
    }

    @Test fun aFailedSessionReadKeepsTheOfflineReader() = runTest {
        val f = Fixture(); f.seedScopes(); f.local.offline.value = true
        val repository = f.repository()
        repository.restoreSession()
        f.delegate.sessionFailure = DomainFailure("network_unavailable", 0, FailureCategory.RECOVERABLE)
        assertFailsWith<DomainFailure> { repository.session() }
        assertNotNull(repository.localReader())
    }

    @Test fun explicitBrowsingRefreshRetainsReadableFactsOnlyForTransportFailure() = runTest {
        val f = Fixture(); f.seedScopes()
        val repository = f.repository(); repository.restoreSession()
        f.local.refreshFailure = DomainFailure("network_unavailable", 0, FailureCategory.RECOVERABLE)
        repository.refreshBrowsingFacts()
        assertNotNull(repository.localReader())
        for (failure in listOf(DomainFailure("server_error", 503, FailureCategory.RECOVERABLE), IllegalStateException("bad snapshot"))) {
            f.local.refreshFailure = failure
            assertSame(failure, assertFailsWith<Throwable> { repository.refreshBrowsingFacts() })
            assertNull(repository.localReader())
            f.local.refreshFailure = null; repository.refreshBrowsingFacts()
            assertNotNull(repository.localReader())
        }
        f.local.refreshFailure = DomainFailure("network_unavailable", 0, FailureCategory.RECOVERABLE)
        repository.captureRefresh()("42")
        assertNull(repository.localReader())
        assertFailsWith<DomainFailure> { repository.refreshBrowsingFacts() }
        assertNull(repository.localReader())
    }

    @Test fun anOlderBrowsingFailureCannotReopenFactsClosedByANewerConfirmedWrite() = runTest {
        val f = Fixture(); f.seedScopes()
        val repository = f.repository(); repository.restoreSession()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.local.beforeRefresh = { entered.complete(Unit); release.await() }
        val reading = async { assertFailsWith<CancellationException> { repository.refreshBrowsingFacts() } }
        entered.await()
        f.local.beforeRefresh = null
        f.local.refreshFailure = DomainFailure("network_unavailable", 0, FailureCategory.RECOVERABLE)
        repository.captureRefresh()("43")
        release.complete(Unit); reading.await()
        assertNull(repository.localReader())
    }

    @Test fun capturedBusinessResultsCannotPublishAfterAnEqualNewSessionPublication() = runTest {
        for (write in listOf(false, true)) {
            val f = Fixture(); f.seedScopes()
            val repository = f.repository(); repository.restoreSession()
            val api = FinanceApiClient({ origin }, object : com.finance.tracker.core.TokenStore {
                override suspend fun get() = "later-global-token"
                override suspend fun set(value: String) = Unit
                override suspend fun clear() = Unit
            }, io.ktor.client.engine.mock.MockEngine { error("No HTTP in this publication fence test") })
            try {
                val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
                val reading = async { assertFailsWith<CancellationException> {
                    repository.businessOperations(api).run { fixed, refresh ->
                        assertEquals("first-token", fixed.boundCredentials?.token)
                        assertEquals("first", fixed.expectedWorkspace)
                        assertSame(api.credentialMutex, fixed.credentialMutex)
                        entered.complete(Unit); release.await()
                        if (write) refresh("44")
                        "late original result"
                    }
                } }
                entered.await(); repository.restoreSession(); release.complete(Unit); reading.await()
                assertNotNull(repository.localReader())
            } finally { api.close() }
        }
    }

    @Test fun aRealBusinessHttpResultKeepsOriginalCredentialsAndCannotPublishIntoAnotherWorkspace() = runTest {
        for (write in listOf(false, true)) {
            val f = Fixture(); f.seedScopes()
            val repository = f.repository(); repository.restoreSession()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val api = FinanceApiClient({ origin }, object : com.finance.tracker.core.TokenStore {
                override suspend fun get() = "later-token"
                override suspend fun set(value: String) = Unit
                override suspend fun clear() = Unit
            }, io.ktor.client.engine.mock.MockEngine { request ->
                assertEquals("Bearer first-token", request.headers[io.ktor.http.HttpHeaders.Authorization])
                assertEquals("first", request.headers["X-FT-Workspace-ID"])
                entered.complete(Unit); release.await()
                respond("{}", headers = io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json"))
            })
            try {
                val reading = async { assertFailsWith<CancellationException> {
                    repository.businessOperations(api).run { fixed, refresh ->
                        val result = fixed.request<kotlinx.serialization.json.JsonObject>(
                            if (write) io.ktor.http.HttpMethod.Post else io.ktor.http.HttpMethod.Get, "/original")
                        if (write) refresh("45")
                        result
                    }
                } }
                entered.await()
                f.local.session = f.local.session.copy(activeWorkspaceId = "second", workspaces = listOf(WorkspaceDto("second", "Second", Role.ADMIN)))
                repository.restoreSession(); release.complete(Unit); reading.await()
                assertTrue(f.local.refreshes.isEmpty())
            } finally { api.close() }
        }
    }

    @Test fun businessRejectionsReuseExactTokenCleanupAndSyntheticRejectionsDoNotErase() = runTest {
        for (token in listOf("first-token", null)) {
            val f = Fixture(); f.seedScopes()
            val repository = f.repository(); repository.restoreSession()
            val api = FinanceApiClient({ origin }, object : com.finance.tracker.core.TokenStore {
                override suspend fun get() = "first-token"
                override suspend fun set(value: String) = Unit
                override suspend fun clear() = Unit
            }, io.ktor.client.engine.mock.MockEngine { error("No HTTP in cleanup identity test") })
            try {
                val failure = ApiFailure("authentication_required", 401).also { it.rejectedToken = token }
                assertSame(failure, assertFailsWith<ApiFailure> { repository.businessOperations(api).run<Nothing> { _, _ -> throw failure } })
                assertEquals(if (token == null) emptyList() else listOf(firstScope, secondScope), f.erasures.erased)
                if (token == null) assertNotNull(repository.localReader()) else assertNull(repository.localReader())
            } finally { api.close() }
        }
    }

    @Test fun aConfirmedWriteRefreshesTheActiveLocalFactsWithItsReceiptFloor() = runTest {
        val f = Fixture(); f.seedScopes(); f.local.offline.value = false
        val repository = f.repository()
        repository.restoreSession()
        repository.captureRefresh()("42")
        repository.captureRefresh()(null)
        assertEquals(listOf<String?>("42", null), f.local.refreshes)
    }

    @Test fun aConfirmedWriteAfterOfflineRestorationRefreshesTheOriginalCache() = runTest {
        val f = Fixture(); f.seedScopes(); f.local.offline.value = true
        val repository = f.repository()
        repository.restoreSession()
        repository.captureRefresh()("7")
        assertEquals(listOf<String?>("7"), f.local.refreshes)
        assertNotNull(repository.localReader())
    }

    @Test fun aRefreshFailureKeepsTheWriteSuccessfulAndRecordsDiagnostics() = runTest {
        val f = Fixture(); f.seedScopes()
        val repository = f.repository()
        repository.restoreSession()
        f.local.refreshFailure = IllegalStateException("snapshot unavailable")
        repository.captureRefresh()("9")
        assertNull(repository.localReader())
        assertEquals("IllegalStateException", f.diagnostics.entries.single().exceptionType)
    }

    @Test fun aRefreshWithoutAnActiveRestorationIsIgnored() = runTest {
        val f = Fixture(); f.seedScopes()
        f.repository().captureRefresh()("1")
        assertTrue(f.local.refreshes.isEmpty())
    }
    @Test fun anInternallySupersededRefreshDoesNotReplaceAConfirmedWrite() = runTest {
        val f = Fixture(); f.seedScopes()
        val repository = f.repository()
        repository.restoreSession()
        f.local.refreshFailure = CancellationException("ledger_session_operation_superseded")
        repository.captureRefresh()("9")
        assertTrue(currentCoroutineContext().isActive)
    }

    @Test fun aLateCommitCannotRefreshANewerRestorationEvenWithTheSameToken() = runTest {
        val f = Fixture(); f.seedScopes()
        val repository = f.repository()
        repository.restoreSession()
        val original = repository.captureRefresh()
        repository.restoreSession()
        original("900")
        assertTrue(f.local.refreshes.isEmpty())
        repository.captureRefresh()("3")
        assertEquals(listOf<String?>("3"), f.local.refreshes)
    }

    @Test fun aWriteWithoutALocalOwnerDoesNotAttachToALaterRestoration() = runTest {
        val f = Fixture(); f.seedScopes()
        val repository = f.repository()
        val original = repository.captureRefresh()
        repository.restoreSession()
        original("900")
        assertTrue(f.local.refreshes.isEmpty())
    }

    @Test fun aFailedRefreshHidesOfflineFactsUntilASubsequentRefreshSucceeds() = runTest {
        val f = Fixture(); f.seedScopes(); f.local.offline.value = true
        val repository = f.repository()
        repository.restoreSession()
        assertNotNull(repository.localReader())
        f.local.refreshFailure = IllegalStateException("private snapshot diagnostic")
        repository.captureRefresh()("9")
        assertNull(repository.localReader())
        assertEquals("IllegalStateException", f.diagnostics.entries.single().exceptionType)
        f.local.refreshFailure = null
        repository.captureRefresh()("10")
        assertNotNull(repository.localReader())
        assertEquals(listOf<String?>("10"), f.local.refreshes)
    }

    @Test fun anEarlierRefreshCannotPublishReadabilityOverALaterFailedWriteRefresh() = runTest {
        val f = Fixture(); f.seedScopes(); f.local.offline.value = true
        val repository = f.repository()
        repository.restoreSession()
        val reached = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var attempt = 0
        f.local.beforeRefresh = {
            if (++attempt == 1) { reached.complete(Unit); release.await() }
            else throw IllegalStateException("later refresh unavailable")
        }
        val first = async { repository.captureRefresh()("9") }
        reached.await()
        repository.captureRefresh()("10")
        assertNull(repository.localReader())
        release.complete(Unit); first.await()
        assertNull(repository.localReader())
        f.local.beforeRefresh = null
        repository.captureRefresh()("10")
        assertNotNull(repository.localReader())
    }

    @Test fun actualCallerCancellationPropagatesAndDoesNotReenableOldFacts() = runTest {
        val f = Fixture(); f.seedScopes(); f.local.offline.value = true
        val repository = f.repository()
        repository.restoreSession()
        val reached = CompletableDeferred<Unit>()
        f.local.beforeRefresh = { reached.complete(Unit); awaitCancellation() }
        val refresh = launch { repository.captureRefresh()("9") }
        reached.await(); refresh.cancelAndJoin()
        assertTrue(refresh.isCancelled)
        assertNull(repository.localReader())
        assertTrue(f.diagnostics.entries.isEmpty())
    }

}
