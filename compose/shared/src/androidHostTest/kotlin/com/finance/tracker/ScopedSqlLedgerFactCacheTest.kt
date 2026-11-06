package com.finance.tracker

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.data.ScopedSqlLedgerFactCache
import com.finance.tracker.domain.LedgerCacheScope
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ScopedSqlLedgerFactCacheTest {
    private val scope = LedgerCacheScope("https://cache.test", "synthetic-user", "synthetic-workspace")
    private fun snapshot(): LedgerSnapshotDto = Json.decodeFromJsonElement<LedgerSnapshotDto>(
        Json.parseToJsonElement(File(requireNotNull(System.getProperty("ft.ledgerSnapshotFixture"))).readText())
            .jsonObject.getValue("expected")
    ).copy(workspaceId = scope.workspaceId, ledgerSeq = "9223372036854775807")

    private suspend fun open(driver: SqlDriver, erase: suspend () -> Unit) =
        ScopedSqlLedgerFactCache.open(scope, driver, closeDriver = { driver.close() }, eraseStorage = erase)

    @Test fun normalClosePreservesFactsAndReleasesOwnershipOnlyAfterShutdown() = runTest {
        val file = File.createTempFile("ledger-close-", ".db")
        var closes = 0; var released = 0; var erased = 0
        try {
            val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
            val first = ScopedSqlLedgerFactCache.open(scope, driver,
                closeDriver = { driver.close(); closes++ }, eraseStorage = { erased++ },
                releaseStorage = { assertEquals(1, closes); released++ })
            val expected = snapshot()
            first.replace(scope, expected)
            first.close(); first.close()
            assertEquals(1, closes); assertEquals(1, released); assertEquals(0, erased)
            assertFails { first.read(scope) }
            assertFails { first.replace(scope, expected) }
            assertFails { first.clear(scope) }
            val reopened = open(JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")) { erased++ }
            try { assertEquals(expected, reopened.read(scope)) }
            finally { reopened.clear(scope) }
            assertEquals(1, erased)
        } finally { file.delete() }
    }

    @Test fun failedNormalCloseNeverReleasesOrErasesAndCanOnlyRetryClose() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val failure = IllegalStateException("synthetic close failure")
        var closes = 0; var released = 0; var erased = 0
        val cache = ScopedSqlLedgerFactCache.open(scope, driver,
            closeDriver = { if (++closes == 1) throw failure; driver.close() },
            eraseStorage = { erased++ }, releaseStorage = { released++ })
        assertSame(failure, assertFails { cache.close() })
        assertEquals(0, released); assertEquals(0, erased)
        assertFails { cache.clear(scope) }; assertFails { cache.read(scope) }
        cache.close(); cache.close()
        assertEquals(2, closes); assertEquals(1, released); assertEquals(0, erased)
    }

    @Test fun failedNormalReleaseRetriesWithoutClosingTheDriverAgain() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        var closes = 0; var released = 0; var erased = 0
        val failure = IllegalStateException("synthetic lease release failure")
        val cache = ScopedSqlLedgerFactCache.open(scope, driver,
            closeDriver = { driver.close(); closes++ }, eraseStorage = { erased++ },
            releaseStorage = { if (++released == 1) throw failure })
        assertSame(failure, assertFails { cache.close() })
        assertFails { cache.clear(scope) }
        cache.close(); cache.close()
        assertEquals(1, closes); assertEquals(2, released); assertEquals(0, erased)
    }

    @Test fun exactSnapshotAndScopeChecksUseTheRealSqlStore() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        var erased = 0
        val cache = open(driver) { erased++ }
        try {
            assertNull(cache.read(scope))
            val expected = snapshot()
            cache.replace(scope, expected)
            assertEquals(expected, cache.read(scope))
            for (foreign in listOf(scope.copy(apiOrigin = "https://other.test"),
                scope.copy(principal = "other"), scope.copy(workspaceId = "other"))) {
                assertFailsWith<IllegalArgumentException> { cache.read(foreign) }
                assertFailsWith<IllegalArgumentException> { cache.replace(foreign, expected) }
                assertFailsWith<IllegalArgumentException> { cache.clear(foreign) }
            }
            assertEquals(expected, cache.read(scope))
            assertEquals(0, erased)
        } finally { cache.clear(scope) }
        cache.clear(scope)
        assertEquals(1, erased)
        assertFails { cache.read(scope) }
        assertFails { cache.replace(scope, snapshot()) }
    }

    @Test fun actualSqlFailurePreservesThePreviousSnapshot() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val cache = open(driver) {}
        try {
            val original = snapshot().copy(ledgerSeq = "7")
            cache.replace(scope, original)
            driver.execute(null, "CREATE TRIGGER fail_update BEFORE UPDATE ON ledger_cache_snapshots BEGIN SELECT RAISE(ABORT, 'synthetic storage failure'); END", 0).await()
            assertFails { cache.replace(scope, snapshot()) }
            assertEquals(original, cache.read(scope))
        } finally { cache.clear(scope) }
    }

    @Test fun failedDriverCloseDoesNotEraseAndExplicitRetryRemainsPossible() = runTest {
        val real = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val failure = IllegalStateException("synthetic close failure")
        var closes = 0; var erases = 0
        val driver = object : SqlDriver by real {
            override fun close() { if (++closes == 1) throw failure; real.close() }
        }
        val cache = open(driver) { erases++ }
        cache.replace(scope, snapshot())
        assertSame(failure, assertFails { cache.clear(scope) })
        assertEquals(0, erases)
        assertFails { cache.read(scope) }
        cache.clear(scope); cache.clear(scope)
        assertEquals(2, closes); assertEquals(1, erases)
    }

    @Test fun failedStorageErasureKeepsTheFenceAndRetriesWithoutClosingTwice() = runTest {
        val real = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val failure = IllegalStateException("synthetic erasure failure")
        var closes = 0; var erases = 0
        val driver = object : SqlDriver by real { override fun close() { closes++; real.close() } }
        val cache = open(driver) { if (++erases == 1) throw failure }
        assertSame(failure, assertFails { cache.clear(scope) })
        assertFails { cache.read(scope) }
        assertFails { cache.replace(scope, snapshot()) }
        cache.clear(scope); cache.clear(scope)
        assertEquals(1, closes); assertEquals(2, erases)
    }

    @Test fun clearingFencesAnAlreadyReadResultBeforeItCanReturn() = runTest {
        val real = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var pause = false; var erased = false
        val driver = object : SqlDriver by real {
            override fun <R> executeQuery(identifier: Int?, sql: String, mapper: (SqlCursor) -> QueryResult<R>,
                parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<R> = QueryResult.AsyncValue {
                val result = real.executeQuery(identifier, sql, mapper, parameters, binders).await()
                if (pause) { entered.complete(Unit); release.await() }
                result
            }
        }
        val cache = open(driver) { erased = true }
        cache.replace(scope, snapshot()); pause = true
        val reading = async { assertFails { cache.read(scope) } }
        entered.await()
        val clearing = launch { cache.clear(scope) }
        runCurrent(); assertFalse(erased)
        release.complete(Unit)
        reading.await(); clearing.join(); assertTrue(erased)
    }

    @Test fun cancelledAsynchronousShutdownCannotEraseAndCanBeRetried() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var closes = 0; var erases = 0
        val cache = ScopedSqlLedgerFactCache.open(scope, driver, closeDriver = {
            closes++; entered.complete(Unit); release.await(); driver.close()
        }, eraseStorage = { erases++ })
        cache.replace(scope, snapshot())
        val clearing = launch { cache.clear(scope) }
        entered.await(); assertEquals(0, erases)
        clearing.cancelAndJoin()
        assertFails { cache.read(scope) }
        assertEquals(0, erases)
        release.complete(Unit)
        cache.clear(scope); cache.clear(scope)
        assertEquals(2, closes); assertEquals(1, erases)
    }

    @Test fun cancelledErasureRetriesWithoutClosingTheDriverAgain() = runTest {
        val real = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var closes = 0; var erases = 0
        val driver = object : SqlDriver by real { override fun close() { closes++; real.close() } }
        val cache = open(driver) { erases++; entered.complete(Unit); release.await() }
        val clearing = launch { cache.clear(scope) }
        entered.await(); clearing.cancelAndJoin()
        assertFails { cache.read(scope) }
        release.complete(Unit)
        cache.clear(scope); cache.clear(scope)
        assertEquals(1, closes); assertEquals(2, erases)
    }

    @Test fun cancelledInitializationWaitsForCleanupAndNeverErasesExistingStorage() = runTest {
        val real = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val sqlDone = CompletableDeferred<Unit>(); val pause = CompletableDeferred<Unit>()
        val closeEntered = CompletableDeferred<Unit>(); val releaseClose = CompletableDeferred<Unit>()
        var closes = 0; var erases = 0
        val driver = object : SqlDriver by real {
            override fun execute(identifier: Int?, sql: String, parameters: Int,
                binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> = QueryResult.AsyncValue {
                val result = real.execute(identifier, sql, parameters, binders).await()
                sqlDone.complete(Unit); pause.await(); result
            }
        }
        val opening = launch {
            ScopedSqlLedgerFactCache.open(scope, driver, closeDriver = {
                closes++; closeEntered.complete(Unit); releaseClose.await(); real.close()
            }, eraseStorage = { erases++ })
        }
        sqlDone.await(); opening.cancel(); closeEntered.await()
        assertFalse(opening.isCompleted)
        assertEquals(1, closes); assertEquals(0, erases)
        releaseClose.complete(Unit); opening.join()
        assertTrue(opening.isCancelled); assertEquals(0, erases)
    }

    @Test fun rethrowingTheSameFailureDuringClosePreservesTheOriginalException() = runTest {
        val real = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val failure = IllegalStateException("synthetic reused failure")
        val driver = object : SqlDriver by real {
            override fun execute(identifier: Int?, sql: String, parameters: Int,
                binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> = throw failure
            override fun close() { real.close(); throw failure }
        }
        assertSame(failure, assertFails { open(driver) { error("must not erase") } })
        assertTrue(failure.suppressedExceptions.isEmpty())
    }

    @Test fun initializationFailureClosesButNeverErasesExistingStorage() = runTest {
        val real = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val failure = IllegalStateException("synthetic schema failure")
        val closeFailure = IllegalStateException("synthetic close failure")
        var closes = 0; var erases = 0
        val driver = object : SqlDriver by real {
            override fun execute(identifier: Int?, sql: String, parameters: Int,
                binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> = throw failure
            override fun close() { closes++; real.close(); throw closeFailure }
        }
        assertSame(failure, assertFails { open(driver) { erases++ } })
        assertEquals(listOf(closeFailure), failure.suppressedExceptions)
        assertEquals(1, closes); assertEquals(0, erases)
    }
}
