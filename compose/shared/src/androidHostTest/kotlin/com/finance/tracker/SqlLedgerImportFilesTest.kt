package com.finance.tracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.finance.tracker.data.SqlLedgerImportBatchStore
import com.finance.tracker.data.LocalCashImportSubmission
import com.finance.tracker.domain.LedgerCacheScope
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SqlLedgerImportFilesTest {
    private val scope = LedgerCacheScope("https://import.example.test", "synthetic", "workspace")

    @Test fun corruptReviewIsAnErrorRatherThanMissingRecoveryInput() = runTest {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            val store = SqlLedgerImportBatchStore.open(driver)
            store.save(scope, "batch", listOf(localCashImportFileFixture()))
            store.saveReview(scope, "batch", localCashImportReviewFixture())
            driver.execute(null, "UPDATE ledger_cache_import_files SET files_json = json_set(files_json, '$.review', 'invalid encoding')", 0).await()
            assertFails { store.readReview(scope, "batch") }
            assertEquals(listOf("batch"), store.list(scope))
            assertNotNull(store.read(scope, "batch"))
        }
    }

    @Test fun reviewUpdatesAreAtomicScopedAndFrozenWithTheOriginalSubmission() = runTest {
        val file = File.createTempFile("import-review-", ".db")
        val first = localCashImportReviewFixture()
        val latest = localCashImportReviewFixture("latest")
        try {
            JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
                val store = SqlLedgerImportBatchStore.open(driver)
                assertNull(store.readReview(scope, "batch"))
                assertFails { store.saveReview(scope, "batch", first) }
                store.save(scope, "batch", listOf(localCashImportFileFixture()))
                assertNull(store.readReview(scope, "batch"))
                store.saveReview(scope, "batch", first)
                driver.execute(null, "CREATE TRIGGER reject_review BEFORE UPDATE ON ledger_cache_import_files BEGIN SELECT RAISE(ABORT, 'synthetic review storage failure'); END", 0).await()
                assertFails { store.saveReview(scope, "batch", latest) }
                assertEquals(first.encode(), store.readReview(scope, "batch")?.encode())
                driver.execute(null, "DROP TRIGGER reject_review", 0).await()
                store.saveReview(scope, "batch", latest)
            }
            JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
                val store = SqlLedgerImportBatchStore.open(driver)
                assertEquals(latest.encode(), store.readReview(scope, "batch")?.encode())
                for (foreign in listOf(scope.copy(apiOrigin = "https://other.test"), scope.copy(principal = "other"), scope.copy(workspaceId = "other"))) {
                    assertNull(store.readReview(foreign, "batch"))
                    assertFails { store.saveReview(foreign, "batch", first) }
                }
                store.prepareSubmission(scope, "batch", LocalCashImportSubmission.freeze("batch", localCashWriteSetFixture()))
                assertFails { store.saveReview(scope, "batch", first) }
                assertFails { store.saveReview(scope, "batch", latest) }
                assertEquals(latest.encode(), store.readReview(scope, "batch")?.encode())
                store.recordReceipt(scope, "batch", LocalCashImportSubmission.freezeReceipt(localCashWriteSetReceiptFixture()))
                store.completeSubmission(scope, "batch", "9")
                assertNull(store.readReview(scope, "batch"))
            }
        } finally { file.delete() }
    }

    @Test fun orderedFilesSurviveReopeningWithExactEncodingAndScopeIsolation() = runTest {
        val file = File.createTempFile("import-inputs-", ".db")
        val expected = listOf(localCashImportFileFixture("b"), localCashImportFileFixture("a"))
        try {
            JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
                val store = SqlLedgerImportBatchStore.open(driver)
                assertNull(store.read(scope, "batch"))
                store.save(scope, "batch", expected)
                store.save(scope, "batch", expected)
                for (foreign in listOf(scope.copy(apiOrigin = "https://other.test"),
                    scope.copy(principal = "other"), scope.copy(workspaceId = "other"))) {
                    assertNull(store.read(foreign, "batch")); assertEquals(emptyList(), store.list(foreign))
                    store.save(foreign, "batch", listOf(localCashImportFileFixture("foreign")))
                    store.remove(foreign, "batch")
                }
            }
            JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
                val store = SqlLedgerImportBatchStore.open(driver)
                assertEquals(listOf("batch"), store.list(scope))
                assertEquals(expected.map { it.encode() }, assertNotNull(store.read(scope, "batch")).map { it.encode() })
                assertFailsWith<IllegalStateException> { store.save(scope, "batch", expected.reversed()) }
                assertEquals(expected.map { it.encode() }, assertNotNull(store.read(scope, "batch")).map { it.encode() })
                store.remove(scope, "batch"); store.remove(scope, "batch")
                assertEquals(emptyList(), store.list(scope)); assertNull(store.read(scope, "batch"))
            }
        } finally { file.delete() }
    }

    @Test fun failedInsertAndInvalidBatchCannotDamageSavedInputs() = runTest {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            val store = SqlLedgerImportBatchStore.open(driver)
            val files = listOf(localCashImportFileFixture())
            store.save(scope, "saved", files)
            assertFailsWith<IllegalArgumentException> { store.save(scope, "empty", emptyList()) }
            assertFailsWith<IllegalArgumentException> { store.save(scope, "duplicate", files + files) }
            assertFailsWith<IllegalArgumentException> { store.save(scope, "bad?", files) }
            driver.execute(null, "CREATE TRIGGER reject_import BEFORE INSERT ON ledger_cache_import_files BEGIN SELECT RAISE(ABORT, 'synthetic storage failure'); END", 0).await()
            assertFails { store.save(scope, "failed", files) }
            assertEquals(listOf("saved"), store.list(scope))
            assertEquals(files.single().encode(), assertNotNull(store.read(scope, "saved")).single().encode())
        }
    }

    @Test fun corruptStoredFileIsNotReportedAsMissingOrPartiallyRecovered() = runTest {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            val store = SqlLedgerImportBatchStore.open(driver)
            store.save(scope, "batch", listOf(localCashImportFileFixture()))
            driver.execute(null, "UPDATE ledger_cache_import_files SET files_json = '[\"invalid encoding\"]'", 0).await()
            assertFails { store.read(scope, "batch") }
            assertEquals(listOf("batch"), store.list(scope))
        }
    }

    @Test fun originalRequestAndReceiptSurviveReopenAndOnlyTheirSnapshotAllowsCleanup() = runTest {
        val file = File.createTempFile("import-operation-", ".db")
        val command = LocalCashImportSubmission.freeze("original-key", localCashWriteSetFixture())
        val files = listOf(localCashImportFileFixture())
        try {
            JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
                val store = SqlLedgerImportBatchStore.open(driver)
                store.save(scope, "original-key", files)
                store.prepareSubmission(scope, "original-key", command)
                store.prepareSubmission(scope, "original-key", command)
                assertFails { store.prepareSubmission(scope, "original-key", LocalCashImportSubmission.freeze("other", localCashWriteSetFixture())) }
                assertFails { store.remove(scope, "original-key") }
                assertEquals(command.requestBody, store.readSubmission(scope, "original-key")?.requestBody)
                store.recordReceipt(scope, "original-key", LocalCashImportSubmission.freezeReceipt(localCashWriteSetReceiptFixture()))
                assertFails { store.completeSubmission(scope, "original-key", "8") }
                assertEquals(listOf("original-key"), store.list(scope))
            }
            JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
                val store = SqlLedgerImportBatchStore.open(driver)
                val restored = assertNotNull(store.readSubmission(scope, "original-key"))
                assertEquals(command.requestBody, restored.requestBody)
                assertEquals("original-key", restored.idempotencyKey)
                assertEquals(localCashWriteSetReceiptFixture(), restored.receipt())
                store.save(scope, "original-key", files)
                store.recordReceipt(scope, "original-key", LocalCashImportSubmission.freezeReceipt(localCashWriteSetReceiptFixture()))
                assertFails { store.recordReceipt(scope, "original-key", LocalCashImportSubmission.freezeReceipt(localCashWriteSetReceiptFixture().copy(receiptId = "other"))) }
                store.completeSubmission(scope, "original-key", "9")
                store.completeSubmission(scope, "original-key", "9")
                assertNull(store.read(scope, "original-key")); assertNull(store.readSubmission(scope, "original-key"))
            }
        } finally { file.delete() }
    }

    @Test fun failedTransitionKeepsTheOriginalFileArrayAndCannotPublishAPartialOperation() = runTest {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            val store = SqlLedgerImportBatchStore.open(driver)
            val files = listOf(localCashImportFileFixture())
            store.save(scope, "batch", files)
            assertNull(store.readSubmission(scope, "batch"))
            driver.execute(null, "CREATE TRIGGER reject_operation BEFORE UPDATE ON ledger_cache_import_files BEGIN SELECT RAISE(ABORT, 'synthetic storage failure'); END", 0).await()
            assertFails { store.prepareSubmission(scope, "batch", LocalCashImportSubmission.freeze("batch", localCashWriteSetFixture())) }
            assertNull(store.readSubmission(scope, "batch"))
            assertEquals(files.single().encode(), assertNotNull(store.read(scope, "batch")).single().encode())
        }
    }
}
