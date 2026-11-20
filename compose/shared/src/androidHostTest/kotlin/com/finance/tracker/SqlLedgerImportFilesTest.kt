package com.finance.tracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.finance.tracker.data.SqlLedgerImportBatchStore
import com.finance.tracker.data.LocalCashImportFile
import com.finance.tracker.data.LocalCashImportSubmission
import com.finance.tracker.domain.LedgerCacheScope
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class SqlLedgerImportFilesTest {
    @Test fun mixedFileStatesSurviveDiskReopenAndReplacementKeepsTheSubmissionFreeze() = runTest {
        val file = File.createTempFile("import-states-", ".db")
        val f = LocalCashImportRebaseFixture()
        val parsed = f.files.single()
        val failed = LocalCashImportFile.captureFailure(localCashImportFileFixture("bad").originalInput(),
            "synthetic-private-failed-body".encodeToByteArray(), "password_incorrect")
        try {
            JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
                val store = SqlLedgerImportBatchStore.open(driver)
                store.save(scope, "batch", listOf(parsed, failed)); store.saveReview(scope, "batch", f.review)
            }
            JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
                val store = SqlLedgerImportBatchStore.open(driver)
                val restoredFiles = assertNotNull(store.read(scope, "batch"))
                assertEquals(parsed.encode(), restoredFiles.first().encode())
                val restored = restoredFiles.last()
                assertEquals(failed.encode(), restored.encode())
                assertEquals("needs_password", restored.parsedFile().status)
                assertFalse(restored.encode().contains("synthetic-private-failed-body"))
                assertFailsWith<IllegalStateException> { restored.originalInput() }
                val replacement = LocalCashImportFile.decode(
                    JsonObject(Json.parseToJsonElement(parsed.encode()).jsonObject + ("clientFileRef" to JsonPrimitive("bad"))).toString())
                store.replaceFile(scope, "batch", restored, replacement)
                assertNull(store.readReview(scope, "batch")?.conclusion())
                assertEquals("parsed", store.read(scope, "batch")?.last()?.parsedFile()?.status)
                store.prepareSubmission(scope, "batch", LocalCashImportSubmission.freeze("batch", f.request))
                assertFails { store.replaceFile(scope, "batch", replacement, failed) }
                assertEquals(listOf(parsed.encode(), replacement.encode()), store.read(scope, "batch")?.map { it.encode() })
            }
        } finally { file.delete() }
    }

    private val scope = LedgerCacheScope("https://import.example.test", "synthetic", "workspace")

    @Test fun compactTransportIsPersistedBeforeSendAndDroppedAfterAReceipt() = runTest {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            val store = SqlLedgerImportBatchStore.open(driver)
            val f = LocalCashImportRebaseFixture()
            store.save(scope, "batch", listOf(f.files.single()))
            store.prepareSubmission(scope, "batch", LocalCashImportSubmission.freeze("batch", f.request))
            val prepared = assertNotNull(store.readSubmission(scope, "batch"))
            val compact = JsonObject(Json.parseToJsonElement(prepared.requestBody).jsonObject +
                ("command_version" to JsonPrimitive(2)) +
                ("request_digest" to JsonPrimitive(com.finance.tracker.business.domain.cashWriteSetRequestDigest(prepared.request())))).toString()
            store.recordTransport(scope, "batch", prepared, compact)
            assertEquals(compact, store.readSubmission(scope, "batch")?.transportBody)
            assertFailsWith<IllegalStateException> {
                SqlLedgerImportBatchStore.open(driver).recordTransport(scope, "batch", prepared, compact + " ")
            }
            store.discardUnprovableSubmission(scope, "batch", assertNotNull(store.readSubmission(scope, "batch")))
            assertNull(store.readSubmission(scope, "batch"))
            store.prepareSubmission(scope, "batch", LocalCashImportSubmission.freeze("batch", f.request))
            store.recordReceipt(scope, "batch", localCashWriteSetReceiptFixture())
            assertNull(store.readSubmission(scope, "batch")?.transportBody)
        }
    }

    @Test fun appendsMergeCurrentFilesAndExactRetriesDoNotInvalidateANewerReview() = runTest {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            val store = SqlLedgerImportBatchStore.open(driver)
            val a = localCashImportFileFixture("file"); val b = localCashImportFileFixture("second"); val c = localCashImportFileFixture("third")
            store.save(scope, "batch", listOf(a)); store.saveReview(scope, "batch", localCashImportConfirmedReviewFixture())
            store.append(scope, "batch", listOf(b)); store.append(scope, "batch", listOf(c))
            assertEquals(listOf(a, b, c).map { it.encode() }, store.read(scope, "batch")?.map { it.encode() })
            assertNull(store.readReview(scope, "batch")?.conclusion())
            assertEquals(localCashImportReviewFixture().inputJson(), store.readReview(scope, "batch")?.inputJson())
            val latest = localCashImportConfirmedReviewFixture("latest")
            store.saveReview(scope, "batch", latest)
            driver.execute(null, "CREATE TRIGGER no_mutation BEFORE UPDATE ON ledger_cache_import_files BEGIN SELECT RAISE(ABORT, 'unexpected write'); END", 0).await()
            store.append(scope, "batch", listOf(b, a)); store.replaceFile(scope, "batch", a, a)
            assertEquals(latest.encode(), store.readReview(scope, "batch")?.encode())
            assertFails { store.append(scope, "batch", listOf(changed(a))) }
            assertEquals(listOf(a, b, c).map { it.encode() }, store.read(scope, "batch")?.map { it.encode() })
        }
    }

    @Test fun replacementsAndRemovalsAreScopedAtomicAndKeepOtherFiles() = runTest {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            val store = SqlLedgerImportBatchStore.open(driver)
            val a = localCashImportFileFixture("file"); val b = localCashImportFileFixture("other"); val replacement = changed(a)
            val originalReview = localCashImportConfirmedReviewFixture()
            store.save(scope, "batch", listOf(a, b)); store.saveReview(scope, "batch", originalReview)
            driver.execute(null, "CREATE TRIGGER reject_mutation BEFORE UPDATE ON ledger_cache_import_files BEGIN SELECT RAISE(ABORT, 'synthetic write failure'); END", 0).await()
            assertFails { store.replaceFile(scope, "batch", a, replacement) }
            assertEquals(listOf(a, b).map { it.encode() }, store.read(scope, "batch")?.map { it.encode() })
            assertEquals(originalReview.encode(), store.readReview(scope, "batch")?.encode())
            driver.execute(null, "DROP TRIGGER reject_mutation", 0).await()
            for (foreign in listOf(scope.copy(apiOrigin = "https://other.test"), scope.copy(principal = "other"), scope.copy(workspaceId = "other"))) {
                assertFails { store.replaceFile(foreign, "batch", a, replacement) }
                assertFails { store.append(foreign, "batch", listOf(b)) }
                assertFails { store.removeFile(foreign, "batch", a) }
            }
            store.replaceFile(scope, "batch", a, replacement)
            assertEquals(listOf(replacement, b).map { it.encode() }, store.read(scope, "batch")?.map { it.encode() })
            assertEquals(emptyList<Any?>(), store.readReview(scope, "batch")?.input()?.get("row_choices"))
            assertNull(store.readReview(scope, "batch")?.conclusion())
            val newer = localCashImportConfirmedReviewFixture("after-replacement")
            store.saveReview(scope, "batch", newer)
            driver.execute(null, "CREATE TRIGGER no_repeat BEFORE UPDATE ON ledger_cache_import_files BEGIN SELECT RAISE(ABORT, 'unexpected retry write'); END", 0).await()
            store.replaceFile(scope, "batch", a, replacement)
            assertEquals(newer.encode(), store.readReview(scope, "batch")?.encode())
            driver.execute(null, "DROP TRIGGER no_repeat", 0).await()
            assertFails { store.replaceFile(scope, "batch", a, changed(a, "third.pdf")) }
            assertFails { store.removeFile(scope, "batch", a) }
            assertFails { store.replaceFile(scope, "batch", replacement, b) }
            store.removeFile(scope, "batch", replacement); store.removeFile(scope, "batch", replacement)
            assertEquals(listOf(b.encode()), store.read(scope, "batch")?.map { it.encode() })
        }
    }

    @Test fun emptyBatchSurvivesReopenWithoutResettingItsRetiredAttemptKey() = runTest {
        val file = File.createTempFile("import-empty-", ".db")
        val original = LocalCashImportSubmission.freeze("batch", localCashWriteSetFixture())
        val a = localCashImportFileFixture("file")
        try {
            JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
                val store = SqlLedgerImportBatchStore.open(driver)
                store.save(scope, "batch", listOf(a)); store.prepareSubmission(scope, "batch", original)
                store.recordBaseRejection(scope, "batch", original)
                store.retireRejectedSubmission(scope, "batch", original, "9", null)
                store.removeFile(scope, "batch", a)
                assertEquals(emptyList(), store.read(scope, "batch")); assertEquals(listOf("batch"), store.list(scope))
                assertFails { store.prepareSubmission(scope, "batch", original) }
            }
            JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
                val store = SqlLedgerImportBatchStore.open(driver)
                assertEquals(emptyList(), store.read(scope, "batch"))
                store.append(scope, "batch", listOf(a)); store.prepareSubmission(scope, "batch", original)
                assertEquals("batch:attempt:1", store.readSubmission(scope, "batch")?.idempotencyKey)
            }
        } finally { file.delete() }
    }

    @Test fun pendingAndCommittedOperationsFreezeEveryFileMutationIncludingExactRetries() = runTest {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            val store = SqlLedgerImportBatchStore.open(driver)
            val a = localCashImportFileFixture(); val b = localCashImportFileFixture("b")
            store.save(scope, "batch", listOf(a))
            val original = LocalCashImportSubmission.freeze("batch", localCashWriteSetFixture())
            store.prepareSubmission(scope, "batch", original)
            repeat(2) { committed ->
                if (committed == 1) store.recordReceipt(scope, "batch", LocalCashImportSubmission.freezeReceipt(localCashWriteSetReceiptFixture()))
                assertFails { store.append(scope, "batch", listOf(b)) }
                assertFails { store.append(scope, "batch", listOf(a)) }
                assertFails { store.replaceFile(scope, "batch", a, a) }
                assertFails { store.removeFile(scope, "batch", a) }
                assertEquals(listOf(a.encode()), store.read(scope, "batch")?.map { it.encode() })
                assertEquals(original.requestBody, store.readSubmission(scope, "batch")?.requestBody)
            }
        }
    }

    @Test fun invalidOrOverLimitAppendsCannotChangeTheBatch() = runTest {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            val store = SqlLedgerImportBatchStore.open(driver)
            val files = (1..20).map { localCashImportFileFixture("f$it") }
            store.save(scope, "batch", files)
            assertFails { store.append(scope, "batch", listOf(localCashImportFileFixture("overflow"))) }
            assertFails { store.append(scope, "batch", emptyList()) }
            assertFails { store.append(scope, "batch", listOf(files[0], files[0])) }
            assertEquals(files.map { it.encode() }, store.read(scope, "batch")?.map { it.encode() })
        }
    }

    private fun changed(file: com.finance.tracker.data.LocalCashImportFile, name: String = "replacement.pdf") =
        LocalCashImportFile.decode(file.encode().replace("synthetic-original.pdf", name))

    @Test fun retiringAProvenStaleAttemptAtomicallyAdvancesItsUniqueKeyAndPreservesReview() = runTest {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            val store = SqlLedgerImportBatchStore.open(driver)
            val original = LocalCashImportSubmission.freeze("batch", localCashWriteSetFixture())
            val next = LocalCashImportSubmission.freeze("batch", localCashWriteSetFixture().copy(baseLedgerSeq = "9"))
            val review = localCashImportConfirmedReviewFixture()
            store.save(scope, "batch", listOf(localCashImportFileFixture()))
            store.saveReview(scope, "batch", review); store.prepareSubmission(scope, "batch", original)
            assertFails { store.retireRejectedSubmission(scope, "batch", original, "9", next) }
            store.recordBaseRejection(scope, "batch", original)
            for (base in listOf("7", "6", "09")) assertFails { store.retireRejectedSubmission(scope, "batch", original, base, next) }
            driver.execute(null, "CREATE TRIGGER reject_attempt BEFORE UPDATE ON ledger_cache_import_files BEGIN SELECT RAISE(ABORT, 'synthetic attempt failure'); END", 0).await()
            assertFails { store.retireRejectedSubmission(scope, "batch", original, "9", next) }
            assertEquals("batch", store.readSubmission(scope, "batch")?.idempotencyKey)
            driver.execute(null, "DROP TRIGGER reject_attempt", 0).await()
            store.retireRejectedSubmission(scope, "batch", original, "9", next)
            val replacement = assertNotNull(store.readSubmission(scope, "batch"))
            assertEquals("batch:attempt:1", replacement.idempotencyKey)
            assertEquals(next.requestBody, replacement.requestBody); assertFalse(replacement.baseRejected)
            assertEquals(review.encode(), store.readReview(scope, "batch")?.encode())
            assertFails { store.retireRejectedSubmission(scope, "batch", original, "9", next) }
            store.recordBaseRejection(scope, "batch", replacement)
            store.retireRejectedSubmission(scope, "batch", replacement, "10", null)
            assertNull(store.readSubmission(scope, "batch")); assertEquals(review.encode(), store.readReview(scope, "batch")?.encode())
            store.saveReview(scope, "batch", localCashImportReviewFixture("reconfirmed"))
            store.prepareSubmission(scope, "batch", LocalCashImportSubmission.freeze("batch", localCashWriteSetFixture().copy(baseLedgerSeq = "10")))
            assertEquals("batch:attempt:2", store.readSubmission(scope, "batch")?.idempotencyKey)
        }
    }

    @Test fun corruptAttemptCountersCannotReuseAnOperationKey() = runTest {
        for (value in listOf("-1", "\"1\"", "1.0", "1e0", "2147483648", "null", "true")) {
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
                val store = SqlLedgerImportBatchStore.open(driver)
                store.save(scope, "batch", listOf(localCashImportFileFixture()))
                store.prepareSubmission(scope, "batch", LocalCashImportSubmission.freeze("batch", localCashWriteSetFixture()))
                driver.execute(null, "UPDATE ledger_cache_import_files SET files_json = json_set(files_json, '$.attempt', json(?))", 1) { bindString(0, value) }.await()
                assertFails { store.readSubmission(scope, "batch") }
                assertFails { store.prepareSubmission(scope, "batch", LocalCashImportSubmission.freeze("batch", localCashWriteSetFixture())) }
            }
        }
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            val store = SqlLedgerImportBatchStore.open(driver)
            val original = LocalCashImportSubmission.freeze("batch", localCashWriteSetFixture())
            store.save(scope, "batch", listOf(localCashImportFileFixture())); store.prepareSubmission(scope, "batch", original)
            driver.execute(null, "UPDATE ledger_cache_import_files SET files_json = json_set(files_json, '$.attempt', 1)", 0).await()
            assertFails { store.readSubmission(scope, "batch") }
        }
    }

    @Test fun exhaustedAttemptCounterAndForeignOrMismatchedReplacementLeaveTheOldOperationIntact() = runTest {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            val store = SqlLedgerImportBatchStore.open(driver)
            val original = LocalCashImportSubmission.freeze("batch", localCashWriteSetFixture())
            val next = LocalCashImportSubmission.freeze("batch", localCashWriteSetFixture().copy(baseLedgerSeq = "9"))
            store.save(scope, "batch", listOf(localCashImportFileFixture())); store.prepareSubmission(scope, "batch", original)
            store.recordBaseRejection(scope, "batch", original)
            for (foreign in listOf(scope.copy(apiOrigin = "https://other.test"), scope.copy(principal = "other"), scope.copy(workspaceId = "other"))) {
                assertFails { store.retireRejectedSubmission(foreign, "batch", original, "9", next) }
            }
            assertFails { store.retireRejectedSubmission(scope, "batch", original, "10", next) }
            assertEquals("batch", store.readSubmission(scope, "batch")?.idempotencyKey)
            val exhausted = LocalCashImportSubmission.restore("batch:attempt:2147483647", original.requestBody).withBaseRejection()
            driver.execute(null, "UPDATE ledger_cache_import_files SET files_json = json_set(files_json, '$.attempt', 2147483647, '$.submission', ?)", 1) { bindString(0, exhausted.encode()) }.await()
            assertFails { store.retireRejectedSubmission(scope, "batch", exhausted, "9", next) }
            assertEquals(exhausted.encode(), store.readSubmission(scope, "batch")?.encode())
        }
    }

    @Test fun baseRejectionIsAtomicScopedAndSurvivesReopeningWithTheOriginalRequest() = runTest {
        val file = File.createTempFile("import-rejection-", ".db")
        val original = LocalCashImportSubmission.freeze("batch", localCashWriteSetFixture())
        try {
            JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
                val store = SqlLedgerImportBatchStore.open(driver)
                store.save(scope, "batch", listOf(localCashImportFileFixture()))
                store.prepareSubmission(scope, "batch", original)
                driver.execute(null, "CREATE TRIGGER reject_marker BEFORE UPDATE ON ledger_cache_import_files BEGIN SELECT RAISE(ABORT, 'synthetic marker failure'); END", 0).await()
                assertFails { store.recordBaseRejection(scope, "batch", original) }
                assertFalse(assertNotNull(store.readSubmission(scope, "batch")).baseRejected)
                driver.execute(null, "DROP TRIGGER reject_marker", 0).await()
                assertFails { store.recordBaseRejection(scope.copy(workspaceId = "other"), "batch", original) }
                assertFails { store.recordBaseRejection(scope, "batch", LocalCashImportSubmission.freeze("batch", localCashWriteSetFixture().copy(baseLedgerSeq = "8"))) }
                store.recordBaseRejection(scope, "batch", original)
                store.recordBaseRejection(scope, "batch", original)
            }
            JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
                val store = SqlLedgerImportBatchStore.open(driver)
                val rejected = assertNotNull(store.readSubmission(scope, "batch"))
                assertTrue(rejected.baseRejected); assertEquals(original.requestBody, rejected.requestBody)
                assertFails { store.remove(scope, "batch") }
                assertFails { store.prepareSubmission(scope, "batch", LocalCashImportSubmission.freeze("batch", localCashWriteSetFixture().copy(baseLedgerSeq = "9"))) }
                store.recordReceipt(scope, "batch", LocalCashImportSubmission.freezeReceipt(localCashWriteSetReceiptFixture()))
                assertFalse(assertNotNull(store.readSubmission(scope, "batch")).baseRejected)
                assertFails { store.recordBaseRejection(scope, "batch", original) }
                store.completeSubmission(scope, "batch", "9")
                assertNull(store.readSubmission(scope, "batch"))
            }
        } finally { file.delete() }
    }

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
        val latest = localCashImportConfirmedReviewFixture("latest")
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
                assertEquals(assertNotNull(latest.conclusion()), store.readReview(scope, "batch")?.conclusion())
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
