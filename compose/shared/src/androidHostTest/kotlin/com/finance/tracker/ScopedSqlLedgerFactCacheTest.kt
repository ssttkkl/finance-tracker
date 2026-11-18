package com.finance.tracker

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.data.ScopedSqlLedgerFactCache
import com.finance.tracker.data.LocalCashImportSubmission
import com.finance.tracker.domain.LedgerCacheScope
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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

    @Test fun independentAppendsWaitForTheOriginalSqlOwnerAndMergeInsteadOfReplacingEachOther() = runTest {
        val real = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var pause = false
        val driver = object : SqlDriver by real {
            override fun <R> executeQuery(identifier: Int?, sql: String, mapper: (SqlCursor) -> QueryResult<R>,
                parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<R> = QueryResult.AsyncValue {
                val value = real.executeQuery(identifier, sql, mapper, parameters, binders).await()
                if (pause) { pause = false; entered.complete(Unit); release.await() }
                value
            }
        }
        val cache = open(driver) {}
        try {
            cache.imports.save("batch", listOf(localCashImportFileFixture("original"))); pause = true
            val first = async { cache.imports.append("batch", listOf(localCashImportFileFixture("a"))) }
            entered.await()
            val second = async { cache.imports.append("batch", listOf(localCashImportFileFixture("b"))) }
            runCurrent(); assertFalse(second.isCompleted)
            release.complete(Unit); first.await(); second.await()
            assertEquals(listOf("original", "a", "b"), cache.imports.read("batch")?.map { it.fileRef })
        } finally { release.complete(Unit); cache.clear(scope) }
    }

    @Test fun anAppendFreezesItsListBeforeWaitingForTheSqlMutex() = runTest {
        val real = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var pause = false
        val driver = object : SqlDriver by real {
            override fun <R> executeQuery(identifier: Int?, sql: String, mapper: (SqlCursor) -> QueryResult<R>,
                parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<R> = QueryResult.AsyncValue {
                val value = real.executeQuery(identifier, sql, mapper, parameters, binders).await()
                if (pause) { pause = false; entered.complete(Unit); release.await() }
                value
            }
        }
        val cache = open(driver) {}
        try {
            cache.imports.save("batch", listOf(localCashImportFileFixture("original"))); pause = true
            val holding = async { cache.read(scope) }; entered.await()
            val files = mutableListOf(localCashImportFileFixture("a"), localCashImportFileFixture("b"))
            val writing = async { cache.imports.append("batch", files) }
            runCurrent(); files.clear(); release.complete(Unit); holding.await(); writing.await()
            assertEquals(listOf("original", "a", "b"), cache.imports.read("batch")?.map { it.fileRef })
        } finally { release.complete(Unit); cache.clear(scope) }
    }

    @Test fun submissionCleanupReadsItsActualCompleteSnapshotInsteadOfATrustedCallerVersion() = runTest {
        val cache = open(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)) {}
        try {
            cache.imports.save("batch", listOf(localCashImportFileFixture()))
            cache.imports.prepareSubmission("batch", LocalCashImportSubmission.freeze("batch", localCashWriteSetFixture()))
            cache.imports.recordReceipt("batch", localCashWriteSetReceiptFixture())
            assertFailsWith<IllegalStateException> { cache.imports.completeSubmission("batch") }
            cache.replace(scope, snapshot().copy(ledgerSeq = "8"))
            assertFailsWith<IllegalStateException> { cache.imports.completeSubmission("batch") }
            assertNotNull(cache.imports.readSubmission("batch")?.receipt())
            cache.replace(scope, snapshot().copy(ledgerSeq = "9"))
            cache.imports.completeSubmission("batch")
            assertNull(cache.imports.read("batch")); assertNull(cache.imports.readSubmission("batch"))
        } finally { cache.clear(scope) }
    }

    @Test fun importFilesSurviveFactRefreshAndCloseButAreErasedWithTheirOwnedFile() = runTest {
        val file = File.createTempFile("ledger-import-close-", ".db")
        val files = listOf(localCashImportFileFixture())
        try {
            val first = open(JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")) { check(file.delete()) }
            val inputs = first.imports
            inputs.save("batch", files)
            first.replace(scope, snapshot().copy(ledgerSeq = "7"))
            first.replace(scope, snapshot().copy(ledgerSeq = "8"))
            assertEquals(files.single().encode(), assertNotNull(inputs.read("batch")).single().encode())
            first.close()
            assertFailsWith<CancellationException> { inputs.list() }; assertFailsWith<CancellationException> { inputs.read("batch") }
            assertFailsWith<CancellationException> { inputs.save("other", files) }; assertFailsWith<CancellationException> { inputs.remove("batch") }
            val second = open(JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")) { check(file.delete()) }
            assertEquals(listOf("batch"), second.imports.list())
            assertEquals(files.single().encode(), assertNotNull(second.imports.read("batch")).single().encode())
            second.clear(scope)
            assertFalse(file.exists())
        } finally { file.delete() }
    }

    @Test fun clearingFencesAnAlreadyReadImportBatchBeforeItCanReturn() = runTest {
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
        cache.imports.save("batch", listOf(localCashImportFileFixture())); pause = true
        val reading = async { assertFailsWith<CancellationException> { cache.imports.read("batch") } }
        entered.await()
        val clearing = launch { cache.clear(scope) }
        runCurrent(); assertFalse(erased)
        release.complete(Unit)
        reading.await(); clearing.join(); assertTrue(erased)
    }

    @Test fun normalCloseFencesAnInFlightImportSaveAndItsOriginalBatchCanBeRecovered() = runTest {
        val file = File.createTempFile("ledger-import-pending-", ".db")
        val real = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val driver = object : SqlDriver by real {
            override fun execute(identifier: Int?, sql: String, parameters: Int,
                binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> = QueryResult.AsyncValue {
                val result = real.execute(identifier, sql, parameters, binders).await()
                if (sql.trimStart().startsWith("INSERT INTO ledger_cache_import_files")) {
                    entered.complete(Unit); release.await()
                }
                result
            }
        }
        val cache = open(driver) { check(file.delete()) }
        val expected = listOf(localCashImportFileFixture())
        try {
            val saving = async { assertFailsWith<CancellationException> { cache.imports.save("original_batch", expected) } }
            entered.await()
            val closing = launch { cache.close() }
            runCurrent(); assertFalse(closing.isCompleted)
            release.complete(Unit)
            saving.await(); closing.join()
            val reopened = open(JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")) { check(file.delete()) }
            try {
                assertEquals(expected.single().encode(), assertNotNull(reopened.imports.read("original_batch")).single().encode())
                reopened.imports.save("original_batch", expected)
                assertEquals(listOf("original_batch"), reopened.imports.list())
            } finally { reopened.clear(scope) }
        } finally { release.complete(Unit); cache.close(); file.delete() }
    }

    @Test fun importSaveFreezesTheBatchBeforeWaitingForTheSqlMutex() = runTest {
        val real = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var pause = true
        val driver = object : SqlDriver by real {
            override fun <R> executeQuery(identifier: Int?, sql: String, mapper: (SqlCursor) -> QueryResult<R>,
                parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<R> = QueryResult.AsyncValue {
                val result = real.executeQuery(identifier, sql, mapper, parameters, binders).await()
                if (pause) { entered.complete(Unit); release.await() }
                result
            }
        }
        val cache = open(driver) {}
        val files = mutableListOf(localCashImportFileFixture("a"), localCashImportFileFixture("b"))
        val expected = files.map { it.encode() }
        try {
            val holding = async { cache.read(scope) }
            entered.await()
            val saving = async { cache.imports.save("batch", files) }
            runCurrent(); files.reverse(); pause = false; release.complete(Unit)
            holding.await(); saving.await()
            assertEquals(expected, assertNotNull(cache.imports.read("batch")).map { it.encode() })
        } finally { release.complete(Unit); cache.clear(scope) }
    }

    @Test fun cancelledImportTableInitializationCannotStartAnInsert() = runTest {
        val real = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var inserts = 0
        val driver = object : SqlDriver by real {
            override fun execute(identifier: Int?, sql: String, parameters: Int,
                binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> = QueryResult.AsyncValue {
                if (sql.contains("CREATE TABLE IF NOT EXISTS ledger_cache_import_files")) {
                    withContext(NonCancellable) {
                        entered.complete(Unit); release.await()
                        real.execute(identifier, sql, parameters, binders).await()
                    }
                } else {
                    if (sql.trimStart().startsWith("INSERT INTO ledger_cache_import_files")) inserts++
                    real.execute(identifier, sql, parameters, binders).await()
                }
            }
        }
        val cache = open(driver) {}
        try {
            val saving = launch { cache.imports.save("batch", listOf(localCashImportFileFixture())) }
            entered.await(); saving.cancel(); release.complete(Unit); saving.join()
            assertTrue(saving.isCancelled)
            assertEquals(0, inserts)
        } finally { release.complete(Unit); cache.clear(scope) }
    }

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
