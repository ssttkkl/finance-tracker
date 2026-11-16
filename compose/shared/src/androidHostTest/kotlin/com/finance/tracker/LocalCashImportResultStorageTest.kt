package com.finance.tracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.finance.tracker.data.*
import com.finance.tracker.domain.LedgerCacheScope
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LocalCashImportResultStorageTest {
    private val scope = LedgerCacheScope("https://result.test", "synthetic-user", "workspace")
    private val input get() = localCashImportResultInputFixture()
    private fun command(key: String = "batch") = LocalCashImportSubmission.freeze(key, localCashWriteSetFixture(), input)

    @Test fun archiveSurvivesCleanupSnapshotReplacementAndReopenButPhysicalErasureDeletesIt() = runTest {
        val file = File.createTempFile("ledger-result-", ".db")
        suspend fun open(): ScopedSqlLedgerFactCache {
            val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
            return ScopedSqlLedgerFactCache.open(scope, driver, closeDriver = { driver.close() }, eraseStorage = { check(file.delete()) })
        }
        try {
            val first = open()
            try {
                first.imports.save("batch", listOf(localCashImportFileFixture()))
                first.imports.prepareSubmission("batch", command())
                first.imports.recordReceipt("batch", localCashWriteSetReceiptFixture())
                first.replace(scope, localCashLedgerOwnerSnapshot("9"))
                assertEquals("cash_import_result_pending", assertFailsWith<IllegalStateException> { first.imports.completeSubmission("batch") }.message)
                val archived = assertNotNull(first.imports.archiveResult("batch"))
                assertEquals(archived.encode(), first.imports.archiveResult("batch")?.encode())
                first.imports.completeSubmission("batch")
                assertNull(first.imports.readSubmission("batch")); assertNull(first.imports.read("batch"))
                first.replace(scope, localCashLedgerOwnerSnapshot("10"))
                assertEquals(archived.encode(), first.imports.readResultByBatchId("batch")?.encode())
            } finally { first.close() }
            assertFailsWith<CancellationException> { first.imports.readResult("receipt") }
            val second = open()
            try {
                val saved = assertNotNull(second.imports.readResult("receipt"))
                assertEquals(input.encode(), saved.input.encode())
                assertEquals(saved.encode(), second.imports.readResultByKey("batch")?.encode())
                assertEquals(localCashWriteSetReceiptFixture(), saved.authoritativeReceipt())
                assertEquals(2L, saved.displayReceipt().summary["duplicate"])
                assertEquals("cash_import_batch_completed", assertFailsWith<IllegalStateException> {
                    second.imports.save("batch", listOf(localCashImportFileFixture()))
                }.message)
                second.clear(scope)
                assertFalse(file.exists())
            } finally { second.clear(scope) }
        } finally { file.delete() }
    }

    @Test fun exactArchiveReplayCannotOverwriteAnotherBatchReceiptOrAttempt() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val store = SqlLedgerImportBatchStore.open(driver)
            for (batch in listOf("one", "two")) {
                store.save(scope, batch, listOf(localCashImportFileFixture()))
                store.prepareSubmission(scope, batch, command(batch))
                store.recordReceipt(scope, batch, LocalCashImportSubmission.freezeReceipt(localCashWriteSetReceiptFixture()))
            }
            val first = assertNotNull(store.archiveResult(scope, "one"))
            assertEquals("cash_import_result_changed", assertFailsWith<IllegalStateException> { store.archiveResult(scope, "two") }.message)
            assertEquals(first.encode(), store.readResult(scope, "receipt")?.encode())
            assertNull(store.readResultByBatchId(scope, "two")); assertNotNull(store.readSubmission(scope, "two")?.receipt())
            assertNull(store.readResult(scope.copy(workspaceId = "other"), "receipt"))
            assertNull(store.readResult(scope.copy(principal = "other"), "receipt"))
            assertNull(store.readResult(scope.copy(apiOrigin = "https://other.test"), "receipt"))
        } finally { driver.close() }
    }

    @Test fun preparingAndRebasingCannotReplaceTheOriginalLocalOutcomes() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val store = SqlLedgerImportBatchStore.open(driver)
            store.save(scope, "batch", listOf(localCashImportFileFixture()))
            store.prepareSubmission(scope, "batch", command())
            assertFails { store.prepareSubmission(scope, "batch", LocalCashImportSubmission.freeze("batch", localCashWriteSetFixture())) }
            val original = assertNotNull(store.readSubmission(scope, "batch"))
            store.recordBaseRejection(scope, "batch", original)
            val rejected = assertNotNull(store.readSubmission(scope, "batch"))
            val updated = localCashWriteSetFixture().copy(baseLedgerSeq = "8")
            assertFails { store.retireRejectedSubmission(scope, "batch", rejected, "8", LocalCashImportSubmission.freeze("batch", updated)) }
            store.retireRejectedSubmission(scope, "batch", rejected, "8", LocalCashImportSubmission.freeze("batch", updated, input))
            val rebased = assertNotNull(store.readSubmission(scope, "batch"))
            assertEquals("batch:attempt:1", rebased.idempotencyKey); assertEquals(input.encode(), rebased.resultInput?.encode())
            store.recordReceipt(scope, "batch", LocalCashImportSubmission.freezeReceipt(localCashWriteSetReceiptFixture()))
            val result = assertNotNull(store.archiveResult(scope, "batch"))
            assertEquals(result.encode(), store.readResultByKey(scope, "batch:attempt:1")?.encode())
            assertNull(store.readResultByKey(scope, "batch"))
        } finally { driver.close() }
    }

    @Test fun corruptedArchiveCannotBeReturnedOrUsedToAuthorizeCleanup() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val store = SqlLedgerImportBatchStore.open(driver)
            store.save(scope, "batch", listOf(localCashImportFileFixture())); store.prepareSubmission(scope, "batch", command())
            store.recordReceipt(scope, "batch", LocalCashImportSubmission.freezeReceipt(localCashWriteSetReceiptFixture()))
            store.archiveResult(scope, "batch")
            driver.execute(null, "UPDATE ledger_cache_import_results SET submission_key = 'different'", 0).await()
            assertFails { store.readResult(scope, "receipt") }
            assertFails { store.completeSubmission(scope, "batch", "9") }
            assertNotNull(store.readSubmission(scope, "batch")); assertNotNull(store.read(scope, "batch"))
        } finally { driver.close() }
    }
}
