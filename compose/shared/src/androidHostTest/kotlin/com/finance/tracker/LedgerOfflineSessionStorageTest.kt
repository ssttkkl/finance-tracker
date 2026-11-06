package com.finance.tracker

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.finance.tracker.business.contracts.Role
import com.finance.tracker.business.contracts.SessionDto
import com.finance.tracker.business.contracts.UserDto
import com.finance.tracker.business.contracts.WorkspaceDto
import com.finance.tracker.data.ScopedSqlLedgerFactCache
import com.finance.tracker.data.ledgerOfflinePrincipal
import com.finance.tracker.data.ApiCredentialSnapshot
import com.finance.tracker.data.FinanceApiClient
import com.finance.tracker.data.LedgerSessionDirectory
import com.finance.tracker.data.LedgerSessionLocatorStorage
import com.finance.tracker.data.publishVerifiedLedgerSession
import com.finance.tracker.core.TokenStore
import io.ktor.client.engine.mock.MockEngine
import com.finance.tracker.domain.LedgerCacheScope
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LedgerOfflineSessionStorageTest {
    @Test fun publicationRequiresActualCommittedSqlFactsBeforePublishingTheIdentityReference() = runTest {
        val records = mutableMapOf<String, String>()
        val directory = LedgerSessionDirectory(object : LedgerSessionLocatorStorage {
            override suspend fun read(key: String) = records[key]
            override suspend fun compareAndSet(key: String, expected: String?, replacement: String): Boolean {
                if (records[key] != expected) return false
                records[key] = replacement
                return true
            }
        })
        val ticket = directory.begin(scope.apiOrigin)
        directory.register(ticket, scope)
        val api = FinanceApiClient({ scope.apiOrigin }, object : TokenStore {
            override suspend fun get() = token
            override suspend fun set(value: String) = error("No credential writes during local publication")
            override suspend fun clear() = error("No logout during local publication")
        }, MockEngine { error("Local publication cannot request the network") })
        val cache = cache(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val credentials = ApiCredentialSnapshot(token, scope.apiOrigin)
        try {
            assertFails { api.publishVerifiedLedgerSession(credentials, directory, ticket, scope, cache, session) }
            assertNull(directory.current(ticket))
            cache.replace(scope, localCashLedgerOwnerSnapshot())
            api.publishVerifiedLedgerSession(credentials, directory, ticket, scope, cache, session)
            assertEquals(scope, directory.current(ticket))
            assertEquals(session, cache.readOfflineSession(scope, token))
            assertEquals(localCashLedgerOwnerSnapshot(), cache.read(scope))
            assertFalse(records.values.any { token in it || session.user.email in it })
        } finally { api.close(); cache.clear(scope) }
    }

    private val session = SessionDto(UserDto("synthetic@example.test"), "workspace",
        listOf(WorkspaceDto("workspace", "Synthetic workspace", Role.EDITOR)))
    private val scope get() = LedgerCacheScope("https://example.test", ledgerOfflinePrincipal(session.user.email), "workspace")
    private val token = "synthetic-local-token-that-must-not-be-copied"
    private suspend fun cache(driver: SqlDriver) = ScopedSqlLedgerFactCache.open(scope, driver,
        closeDriver = { driver.close() }, eraseStorage = {})

    @Test fun identityKeysRejectInvalidUtf16AndDoNotChangeTheServerIdentityText() {
        assertNotEquals(ledgerOfflinePrincipal("Synthetic@example.test"), ledgerOfflinePrincipal(session.user.email))
        assertFails { ledgerOfflinePrincipal(" ") }
        assertFails { ledgerOfflinePrincipal("synthetic-" + '\uD800') }
    }

    @Test fun restorationRequiresCommittedFactsAndTheOriginalToken() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val cache = cache(driver)
        try {
            assertNull(cache.readOfflineSession(scope, token))
            assertFails { cache.saveOfflineSession(scope, token, session) }
            cache.replace(scope, localCashLedgerOwnerSnapshot())
            assertNull(cache.readOfflineSession(scope, token))
            cache.saveOfflineSession(scope, token, session)
            assertEquals(session, cache.readOfflineSession(scope, token))
            assertNull(cache.readOfflineSession(scope, token + "changed"))
            assertFails { cache.saveOfflineSession(scope, "", session) }
            assertFails { cache.readOfflineSession(scope, "") }
            val text = driver.executeQuery(null, "SELECT session_json FROM ledger_cache_sessions", { cursor ->
                assertTrue(cursor.next().value); QueryResult.Value(assertNotNull(cursor.getString(0)))
            }, 0).await()
            assertFalse(token in text)
            assertTrue("synthetic@example.test" in text)
            assertEquals(localCashLedgerOwnerSnapshot(), cache.read(scope))
        } finally { cache.clear(scope) }
        assertFails { cache.readOfflineSession(scope, token) }
        assertFails { cache.saveOfflineSession(scope, token, session) }
    }

    @Test fun sessionSurvivesRealSqlCloseAndReopen() = runTest {
        val file = File.createTempFile("ledger-session-", ".db")
        try {
            val first = cache(JdbcSqliteDriver("jdbc:sqlite:${file.path}"))
            try {
                first.replace(scope, localCashLedgerOwnerSnapshot())
                first.saveOfflineSession(scope, token, session)
            } finally { first.clear(scope) }
            val second = cache(JdbcSqliteDriver("jdbc:sqlite:${file.path}"))
            try { assertEquals(session, second.readOfflineSession(scope, token)) }
            finally { second.clear(scope) }
        } finally { file.delete() }
    }

    @Test fun invalidIdentityOrWorkspaceCannotOverwriteTheConfirmedRecord() = runTest {
        val cache = cache(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        try {
            cache.replace(scope, localCashLedgerOwnerSnapshot())
            cache.saveOfflineSession(scope, token, session)
            for (invalid in listOf(session.copy(user = UserDto("other@example.test")),
                session.copy(activeWorkspaceId = "other"), session.copy(workspaces = emptyList()),
                session.copy(workspaces = session.workspaces + session.workspaces))) {
                assertFails { cache.saveOfflineSession(scope, token, invalid) }
                assertEquals(session, cache.readOfflineSession(scope, token))
            }
            for (foreign in listOf(scope.copy(principal = "other"), scope.copy(apiOrigin = "https://other.test"),
                scope.copy(workspaceId = "other"))) {
                assertFails { cache.saveOfflineSession(foreign, token, session) }
                assertFails { cache.readOfflineSession(foreign, token) }
            }
        } finally { cache.clear(scope) }
    }

    @Test fun sqlAbortPreservesThePreviousSessionAndFacts() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val cache = cache(driver)
        try {
            cache.replace(scope, localCashLedgerOwnerSnapshot())
            cache.saveOfflineSession(scope, token, session)
            driver.execute(null, "CREATE TRIGGER reject_session BEFORE UPDATE ON ledger_cache_sessions BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END", 0).await()
            assertFails { cache.saveOfflineSession(scope, token, session.copy(workspaces =
                listOf(session.workspaces.single().copy(name = "Changed")))) }
            assertEquals(session, cache.readOfflineSession(scope, token))
            assertEquals(localCashLedgerOwnerSnapshot(), cache.read(scope))
        } finally { cache.clear(scope) }
    }

    @Test fun damagedMetadataIsAnErrorAndDoesNotEraseCommittedFacts() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val cache = cache(driver)
        try {
            cache.replace(scope, localCashLedgerOwnerSnapshot())
            cache.saveOfflineSession(scope, token, session)
            driver.execute(null, "UPDATE ledger_cache_sessions SET session_json = '{'", 0).await()
            assertFails { cache.readOfflineSession(scope, token) }
            assertEquals(localCashLedgerOwnerSnapshot(), cache.read(scope))
        } finally { cache.clear(scope) }
    }

    @Test fun clearingFencesAnAlreadyReadSessionBeforeItCanReturn() = runTest {
        val real = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var pause = false
        val driver = object : SqlDriver by real {
            override fun <R> executeQuery(identifier: Int?, sql: String, mapper: (SqlCursor) -> QueryResult<R>,
                parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<R> = QueryResult.AsyncValue {
                val result = real.executeQuery(identifier, sql, mapper, parameters, binders).await()
                if (pause && "ledger_cache_sessions" in sql) { entered.complete(Unit); release.await() }
                result
            }
        }
        val cache = cache(driver)
        cache.replace(scope, localCashLedgerOwnerSnapshot())
        cache.saveOfflineSession(scope, token, session)
        pause = true
        val reading = async { assertFails { cache.readOfflineSession(scope, token) } }
        entered.await()
        val clearing = launch { cache.clear(scope) }
        runCurrent()
        release.complete(Unit)
        reading.await(); clearing.join()
        assertFails { cache.readOfflineSession(scope, token) }
    }
}
