package com.finance.tracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.contracts.LedgerVersionDto
import com.finance.tracker.business.contracts.SessionDto
import com.finance.tracker.business.contracts.UserDto
import com.finance.tracker.business.contracts.WorkspaceDto
import com.finance.tracker.business.contracts.Role
import com.finance.tracker.business.domain.CashImportPlanError
import com.finance.tracker.business.domain.LocalCashImportReview
import com.finance.tracker.core.TokenStore
import com.finance.tracker.data.*
import com.finance.tracker.domain.LedgerCacheScope
import com.finance.tracker.domain.LedgerSnapshotSource
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.*
import io.ktor.http.content.TextContent
import java.io.File
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.io.IOException
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LocalCashImportRecoveryTest {
    @Test fun capturedBatchPersistsAcrossReopenAndADifferentCaptureCannotReplaceIt() = runTest {
        val f = openFixture { error("CSV capture must stay offline") }
        try {
            val original = f.owner.captureImportFiles("captured", listOf(localCashImportStatementInput("a") { localCashImportCsvBytes() }))
            val expected = original.map { it.encode() }
            f.reopen()
            assertEquals(expected, f.owner.importFiles("captured")!!.map { it.encode() })
            val repeated = f.owner.captureImportFiles("captured", listOf(localCashImportStatementInput("a") { localCashImportCsvBytes() }))
            assertEquals(expected, repeated.map { it.encode() })
            val failed = assertFailsWith<IllegalStateException> {
                f.owner.captureImportFiles("captured", listOf(localCashImportStatementInput("a") {
                    localCashImportCsvBytes().decodeToString().replace("0000123", "different-order").encodeToByteArray()
                }))
            }
            assertEquals("cash_import_files_changed", failed.message)
            assertEquals(expected, f.owner.importFiles("captured")!!.map { it.encode() })
            assertEquals(0, f.source.reads)
        } finally { f.close() }
    }

    @Test fun productionPreviewAndRebaseUseTheCompleteFileCalculatorWithoutAnInjectedAdapter() = runTest {
        val plan = LocalCashImportRebaseFixture(includeRefund = true); val posts = mutableListOf<String>()
        lateinit var f: Fixture
        f = openFixture(plan.source, plan.files, null) { http ->
            if (http.method == HttpMethod.Get) missing() else {
                val key = assertNotNull(http.headers["Idempotency-Key"]); posts += key
                val saved = assertNotNull(f.readSubmission())
                assertEquals(saved.requestBody, (http.body as TextContent).text)
                if (key == "batch") rejected() else {
                    assertEquals("batch:attempt:1", key)
                    assertEquals("9", saved.request().baseLedgerSeq)
                    assertEquals(plan.original.second, saved.request().changes)
                    receipt()
                }
            }
        }
        try {
            assertNotNull(f.owner.readOfflineSession())
            val calculated = f.owner.calculateImport("batch", plan.input)
            assertEquals(plan.request, calculated.request)
            assertEquals(2, calculated.request.changes.transactions.size)
            assertEquals(1, calculated.request.changes.relations.size)
            assertEquals(plan.review.conclusion(), calculated.review.conclusion())
            assertEquals(0, f.source.reads); assertEquals(emptyList(), posts)
            assertNull(f.readSubmission())
            assertNull(f.owner.importReview("batch"))
            f.owner.saveImportReview("batch", calculated.review)
            f.owner.prepareImportSubmission("batch", calculated.request)
            f.reopen()
            f.source.current = plan.source.copy(ledgerSeq = "9")
            val result = f.owner.resumeLocalCashImport("batch")
            assertNull(result.recoveryFailure)
            assertEquals(listOf("batch", "batch:attempt:1"), posts)
            assertNull(f.readSubmission())
        } finally { f.close() }
    }

    @Test fun aStaleBaselineRecalculatesTheRealPlanAndPersistsANewKeyBeforeItsPost() = runTest {
        val plan = LocalCashImportRebaseFixture(); val posts = mutableListOf<String>(); var calculations = 0
        lateinit var f: Fixture
        f = openFixture(plan.source, plan.files, plan.review) { http ->
            if (http.method == HttpMethod.Get) {
                val key = http.url.parameters["key"]
                if (key == "batch" && posts.isNotEmpty()) assertTrue(f.source.reads >= 2)
                missing()
            } else {
                val key = assertNotNull(http.headers["Idempotency-Key"]); posts += key
                val saved = assertNotNull(f.readSubmission())
                assertEquals(key, saved.idempotencyKey); assertEquals(saved.requestBody, (http.body as TextContent).text)
                if (key == "batch") rejected() else {
                    assertEquals("9", saved.request().baseLedgerSeq); assertEquals(plan.original.second, saved.request().changes)
                    receipt()
                }
            }
        }
        try {
            f.source.current = plan.source.copy(ledgerSeq = "9")
            f.owner.prepareImportSubmission("batch", plan.request)
            val result = f.owner.resumeImportSubmission("batch") { files, source, input, previous, ids ->
                calculations++; plan.calculate(files, source, input, previous, ids)
            }
            assertNull(result.recoveryFailure); assertEquals(3, calculations)
            assertEquals(listOf("batch", "batch:attempt:1"), posts); assertNull(f.readSubmission())
        } finally { f.close() }
    }

    @Test fun responseLossOfTheRebasedAttemptReopensOnlyItsNewOriginalKey() = runTest {
        val plan = LocalCashImportRebaseFixture(); var committed = false; var posts = 0
        val f = openFixture(plan.source, plan.files, plan.review) { http ->
            if (http.method == HttpMethod.Get) {
                if (committed) { assertEquals("batch:attempt:1", http.url.parameters["key"]); receipt() } else missing()
            } else {
                posts++
                if (http.headers["Idempotency-Key"] == "batch") rejected()
                else { committed = true; throw IOException("synthetic rebased response loss") }
            }
        }
        try {
            f.source.current = plan.source.copy(ledgerSeq = "9")
            f.owner.prepareImportSubmission("batch", plan.request)
            assertFailsWith<IOException> { f.owner.resumeImportSubmission("batch", plan.calculate) }
            val saved = assertNotNull(f.readSubmission()); assertEquals("batch:attempt:1", saved.idempotencyKey)
            f.reopen()
            assertEquals(localCashWriteSetReceiptFixture(), f.owner.resumeImportSubmission("batch").receipt)
            assertEquals(2, posts); assertNull(f.readSubmission())
        } finally { f.close() }
    }

    @Test fun anEqualOrFutureOriginalBaseCannotBeRetiredFromASingleRejectedAttempt() = runTest {
        for (base in listOf("9", "10")) {
            val plan = LocalCashImportRebaseFixture(); var posts = 0; var calculations = 0
            val f = openFixture(plan.source, plan.files, plan.review) { http ->
                if (http.method == HttpMethod.Get) missing() else { posts++; rejected() }
            }
            try {
                f.source.current = plan.source.copy(ledgerSeq = "9")
                f.owner.prepareImportSubmission("batch", plan.request.copy(baseLedgerSeq = base))
                assertFailsWith<ApiFailure> { f.owner.resumeImportSubmission("batch") { _, _, _, _, _ -> calculations++; error("No stale-base proof") } }
                assertEquals(1, posts); assertEquals(0, calculations)
                assertEquals("batch", f.readSubmission()?.idempotencyKey)
            } finally { f.close() }
        }
    }

    @Test fun aReceiptFoundAfterTheRefreshWinsWithoutAnotherCalculationOrPost() = runTest {
        val plan = LocalCashImportRebaseFixture(); var posts = 0; var gets = 0
        val f = openFixture(plan.source, plan.files, plan.review) { http ->
            if (http.method == HttpMethod.Get) { if (++gets == 2) receipt() else missing() } else { posts++; rejected() }
        }
        try {
            f.source.current = plan.source.copy(ledgerSeq = "9")
            f.owner.prepareImportSubmission("batch", plan.request)
            assertEquals(localCashWriteSetReceiptFixture(), f.owner.resumeImportSubmission("batch") { _, _, _, _, _ -> error("Receipt already exists") }.receipt)
            assertEquals(1, posts); assertEquals(2, gets); assertNull(f.readSubmission())
        } finally { f.close() }
    }

    @Test fun repeatedBaselineChangesStopAfterThreeRebasesWithBoundedBackoff() = runTest {
        val plan = LocalCashImportRebaseFixture(); val posts = mutableListOf<String>(); var calculations = 0
        lateinit var f: Fixture
        f = openFixture(plan.source, plan.files, plan.review) { http ->
            if (http.method == HttpMethod.Get) missing() else {
                posts += assertNotNull(http.headers["Idempotency-Key"])
                f.source.current = plan.source.copy(ledgerSeq = (7 + posts.size).toString()); rejected()
            }
        }
        try {
            f.owner.prepareImportSubmission("batch", plan.request)
            val start = testScheduler.currentTime
            val failure = assertFailsWith<LocalCashImportRetryLimitReached> {
                f.owner.resumeImportSubmission("batch") { files, source, input, previous, ids ->
                    calculations++; plan.calculate(files, source, input, previous, ids)
                }
            }
            assertEquals("ledger_base_changed", failure.rejection.code)
            assertEquals(listOf("batch", "batch:attempt:1", "batch:attempt:2", "batch:attempt:3"), posts)
            assertEquals(9, calculations); assertEquals(1750, testScheduler.currentTime - start)
            val saved = assertNotNull(f.readSubmission())
            assertEquals("batch:attempt:3", saved.idempotencyKey); assertEquals("10", saved.request().baseLedgerSeq)
            assertTrue(saved.baseRejected); assertEquals(plan.review.encode(), f.owner.importReview("batch")?.encode())
            assertFailsWith<ApiFailure> { f.owner.resumeImportSubmission("batch") }; assertEquals(4, posts.size)
        } finally { f.close() }
    }

    @Test fun failureOfThePostRefreshReceiptLookupPreservesTheRejectedOriginal() = runTest {
        for (disconnect in listOf(false, true)) {
            val plan = LocalCashImportRebaseFixture(); var gets = 0; var posts = 0; var calculations = 0
            val networkFailure = IOException("synthetic verification disconnect")
            val f = openFixture(plan.source, plan.files, plan.review) { http ->
                if (http.method == HttpMethod.Post) { posts++; rejected() }
                else if (++gets == 1) missing()
                else if (disconnect) throw networkFailure
                else respond("""{"error":{"code":"unavailable"}}""", HttpStatusCode.ServiceUnavailable, jsonHeaders)
            }
            try {
                f.source.current = plan.source.copy(ledgerSeq = "9")
                f.owner.prepareImportSubmission("batch", plan.request)
                val original = assertNotNull(f.readSubmission()).requestBody
                val failure = assertFails { f.owner.resumeImportSubmission("batch") { _, _, _, _, _ -> calculations++; error("Unverified old receipt") } }
                if (disconnect) { assertIs<IOException>(failure); assertEquals(networkFailure.message, failure.message) } else assertEquals(503, assertIs<ApiFailure>(failure).status)
                assertEquals(1, posts); assertEquals(2, gets); assertEquals(0, calculations)
                val saved = assertNotNull(f.readSubmission()); assertEquals("batch", saved.idempotencyKey)
                assertEquals(original, saved.requestBody); assertTrue(saved.baseRejected)
            } finally { f.close() }
        }
    }

    @Test fun aLookupConflictCannotAuthorizeRecalculationEvenWhenACalculatorIsAvailable() = runTest {
        val plan = LocalCashImportRebaseFixture(); var posts = 0; var calculations = 0
        val f = openFixture(plan.source, plan.files, plan.review) { http ->
            if (http.method == HttpMethod.Post) posts++
            rejected()
        }
        try {
            f.owner.prepareImportSubmission("batch", plan.request)
            assertFailsWith<ApiFailure> { f.owner.resumeImportSubmission("batch") { _, _, _, _, _ -> calculations++; error("GET is not a POST rejection") } }
            assertEquals(0, posts); assertEquals(0, calculations); assertEquals(0, f.source.reads)
            assertFalse(assertNotNull(f.readSubmission()).baseRejected)
        } finally { f.close() }
    }

    @Test fun aCalculationDefectPreservesTheOriginalDiagnosticAndRecoveryMaterials() = runTest {
        val plan = LocalCashImportRebaseFixture(); var posts = 0
        val defect = ArithmeticException("synthetic calculation defect")
        val f = openFixture(plan.source, plan.files, plan.review) { http ->
            if (http.method == HttpMethod.Get) missing() else { posts++; rejected() }
        }
        try {
            f.source.current = plan.source.copy(ledgerSeq = "9")
            f.owner.prepareImportSubmission("batch", plan.request)
            assertSame(defect, assertFails { f.owner.resumeImportSubmission("batch") { _, _, _, _, _ -> throw defect } })
            assertEquals(1, posts); assertEquals("batch", f.readSubmission()?.idempotencyKey)
            assertTrue(assertNotNull(f.readSubmission()).baseRejected)
            assertEquals(plan.review.encode(), f.owner.importReview("batch")?.encode())
            assertEquals(plan.files.map { it.encode() }, f.owner.importFiles("batch")?.map { it.encode() })
        } finally { f.close() }
    }

    @Test fun aChangedAccountInvalidatesTheRealConclusionAndRetainsTheReviewForReconfirmation() = runTest {
        val plan = LocalCashImportRebaseFixture(); var posts = 0
        val f = openFixture(plan.source, plan.files, plan.review) { http ->
            if (http.method == HttpMethod.Get) missing() else { posts++; rejected() }
        }
        try {
            f.source.current = plan.source.copy(ledgerSeq = "9", facts = plan.source.facts.copy(accounts =
                plan.source.facts.accounts.map { it.copy(active = false) }))
            f.owner.prepareImportSubmission("batch", plan.request)
            assertFailsWith<CashImportPlanError> { f.owner.resumeImportSubmission("batch", plan.calculate) }
            assertEquals(1, posts); assertNull(f.readSubmission())
            assertEquals(plan.review.encode(), f.owner.importReview("batch")?.encode())
            assertEquals(plan.files.map { it.encode() }, f.owner.importFiles("batch")?.map { it.encode() })
            f.owner.saveImportReview("batch", plan.review)
        } finally { f.close() }
    }

    @Test fun aLegalCalculationWithChangedVisibleAccountRequiresReconfirmationWithoutANewPost() = runTest {
        val plan = LocalCashImportRebaseFixture(); var posts = 0; var calculations = 0
        val f = openFixture(plan.source, plan.files, plan.review) { http ->
            if (http.method == HttpMethod.Get) missing() else { posts++; rejected() }
        }
        try {
            f.source.current = plan.source.copy(ledgerSeq = "9", facts = plan.source.facts.copy(accounts =
                plan.source.facts.accounts.map { it.copy(name = "Changed visible account") }))
            f.owner.prepareImportSubmission("batch", plan.request)
            val failure = assertFailsWith<CashImportPlanError> {
                f.owner.resumeImportSubmission("batch") { files, source, input, previous, ids ->
                    plan.calculate(files, source, input, previous, ids).also { calculations++ }
                }
            }
            assertEquals("plan_changed", failure.code); assertEquals(3, calculations)
            assertEquals(1, posts); assertNull(f.readSubmission())
            assertEquals(plan.review.encode(), f.owner.importReview("batch")?.encode())
            assertEquals(plan.files.map { it.encode() }, f.owner.importFiles("batch")?.map { it.encode() })
            f.owner.saveImportReview("batch", plan.review)
        } finally { f.close() }
    }

    @Test fun failedNewAttemptStorageCannotPostItAndRecoveryDoesNotRepeatTheRejectedOldPost() = runTest {
        val plan = LocalCashImportRebaseFixture(); val posts = mutableListOf<String>()
        val f = openFixture(plan.source, plan.files, plan.review) { http ->
            if (http.method == HttpMethod.Get) missing() else {
                val key = assertNotNull(http.headers["Idempotency-Key"]); posts += key
                if (key == "batch") rejected() else receipt()
            }
        }
        try {
            f.source.current = plan.source.copy(ledgerSeq = "9")
            f.owner.prepareImportSubmission("batch", plan.request)
            f.sql("CREATE TRIGGER reject_new_attempt BEFORE UPDATE ON ledger_cache_import_files WHEN json_extract(NEW.files_json, '$.attempt') IS NOT NULL BEGIN SELECT RAISE(ABORT, 'synthetic new attempt failure'); END")
            val failure = assertFails { f.owner.resumeImportSubmission("batch", plan.calculate) }
            assertTrue(failure.message.orEmpty().contains("synthetic new attempt failure"))
            assertEquals(listOf("batch"), posts); assertEquals("batch", f.readSubmission()?.idempotencyKey)
            assertTrue(assertNotNull(f.readSubmission()).baseRejected)
            assertEquals(plan.review.encode(), f.owner.importReview("batch")?.encode())
            f.sql("DROP TRIGGER reject_new_attempt"); f.reopen()
            assertNull(f.owner.resumeImportSubmission("batch", plan.calculate).recoveryFailure)
            assertEquals(listOf("batch", "batch:attempt:1"), posts)
        } finally { f.close() }
    }

    @Test fun cancellationDuringBackoffReopensTheAlreadySavedNewAttempt() = runTest {
        val plan = LocalCashImportRebaseFixture(); val posts = mutableListOf<String>()
        val f = openFixture(plan.source, plan.files, plan.review) { http ->
            if (http.method == HttpMethod.Get) missing() else {
                val key = assertNotNull(http.headers["Idempotency-Key"]); posts += key
                if (key == "batch") rejected() else receipt()
            }
        }
        try {
            f.source.current = plan.source.copy(ledgerSeq = "9")
            f.owner.prepareImportSubmission("batch", plan.request)
            val operation = async { f.owner.resumeImportSubmission("batch", plan.calculate) }
            runCurrent()
            val published = assertNotNull(f.readSubmission())
            assertEquals("batch:attempt:1", published.idempotencyKey); assertEquals(listOf("batch"), posts)
            operation.cancel(); assertFailsWith<CancellationException> { operation.await() }
            f.reopen(); assertEquals(published.encode(), f.readSubmission()?.encode())
            assertNull(f.owner.resumeImportSubmission("batch").recoveryFailure)
            assertEquals(listOf("batch", "batch:attempt:1"), posts)
        } finally { f.close() }
    }

    @Test fun aLaterLoginDuringRecalculationOrBackoffCannotSendOrReplaceAnotherAttempt() = runTest {
        for (duringCalculation in listOf(true, false)) {
            val plan = LocalCashImportRebaseFixture(); val posts = mutableListOf<String>()
            val f = openFixture(plan.source, plan.files, plan.review) { http ->
                if (http.method == HttpMethod.Get) missing() else { posts += assertNotNull(http.headers["Idempotency-Key"]); rejected() }
            }
            try {
                f.source.current = plan.source.copy(ledgerSeq = "9")
                f.owner.prepareImportSubmission("batch", plan.request)
                val operation = async {
                    assertFailsWith<CancellationException> {
                        f.owner.resumeImportSubmission("batch") { files, source, input, previous, ids ->
                            plan.calculate(files, source, input, previous, ids).also { if (duringCalculation) f.token = "later-token" }
                        }
                    }
                }
                runCurrent()
                if (!duringCalculation) { f.token = "later-token"; advanceTimeBy(250); runCurrent() }
                operation.await()
                assertEquals(listOf("batch"), posts)
                assertEquals(if (duringCalculation) "batch" else "batch:attempt:1", f.readSubmission()?.idempotencyKey)
            } finally { f.close() }
        }
    }

    @Test fun reviewReopensThroughTheSessionOwnerAndOldCredentialsCannotChangeIt() = runTest {
        val f = openFixture { error("Review storage must not use HTTP") }
        try {
            val review = localCashImportConfirmedReviewFixture()
            f.owner.saveImportReview("batch", review)
            f.reopen()
            assertEquals(review.encode(), f.owner.importReview("batch")?.encode())
            assertEquals(assertNotNull(review.conclusion()), f.owner.importReview("batch")?.conclusion())
            f.token = "later-token"
            assertFailsWith<CancellationException> { f.owner.saveImportReview("batch", localCashImportReviewFixture("changed")) }
        } finally { f.close() }
    }

    @Test fun responseLossReopensTheOriginalOperationAndRecoversWithoutAnotherPost() = runTest {
        var committed = false; var posts = 0; var gets = 0
        lateinit var fixture: Fixture
        val request = localCashWriteSetFixture()
        fixture = openFixture { http ->
            assertEquals("Bearer original-token", http.headers[HttpHeaders.Authorization])
            assertEquals("workspace", http.url.parameters["workspace_id"])
            if (http.method == HttpMethod.Get) {
                gets++; assertEquals("batch", http.url.parameters["key"])
                if (committed) receipt() else missing()
            } else {
                posts++; assertEquals("batch", http.headers["Idempotency-Key"])
                val persisted = assertNotNull(fixture.readSubmission())
                assertEquals(persisted.requestBody, (http.body as TextContent).text)
                committed = true
                throw IOException("synthetic response loss")
            }
        }
        try {
            fixture.owner.prepareImportSubmission("batch", request)
            assertEquals(0, gets + posts)
            assertFailsWith<IOException> { fixture.owner.resumeImportSubmission("batch") }
            assertNull(fixture.readSubmission()?.receipt())
            fixture.reopen()
            val result = fixture.owner.resumeImportSubmission("batch")
            assertEquals(localCashWriteSetReceiptFixture(), result.receipt)
            assertNull(result.recoveryFailure)
            assertEquals(1, posts); assertEquals(2, gets)
            assertNull(fixture.readSubmission()); assertEquals("9", fixture.readSnapshot()?.ledgerSeq)
        } finally { fixture.close() }
    }

    @Test fun refreshFailureKeepsCommittedSuccessAndSavedReceiptSkipsAllSubmissionHttpAfterReopen() = runTest {
        var requests = 0
        val f = openFixture { requests++; receipt() }
        val failure = IOException("synthetic snapshot failure")
        try {
            f.owner.prepareImportSubmission("batch", localCashWriteSetFixture())
            f.source.failure = failure
            val result = f.owner.resumeImportSubmission("batch")
            assertEquals(localCashWriteSetReceiptFixture(), result.receipt)
            assertSame(failure, result.recoveryFailure)
            assertEquals(result.receipt, f.readSubmission()?.receipt())
            assertEquals("7", f.readSnapshot()?.ledgerSeq)
            f.reopen(); f.source.failure = null
            assertNull(f.owner.resumeImportSubmission("batch").recoveryFailure)
            assertEquals(1, requests); assertNull(f.readSubmission())
        } finally { f.close() }
    }

    @Test fun receiptStorageFailurePreservesTheKeyAndCanBeVerifiedAgain() = runTest {
        var gets = 0
        val f = openFixture { assertEquals(HttpMethod.Get, it.method); gets++; receipt() }
        try {
            f.owner.prepareImportSubmission("batch", localCashWriteSetFixture())
            f.sql("CREATE TRIGGER reject_receipt BEFORE UPDATE ON ledger_cache_import_files BEGIN SELECT RAISE(ABORT, 'synthetic receipt disk failure'); END")
            val result = f.owner.resumeImportSubmission("batch")
            assertEquals(localCashWriteSetReceiptFixture(), result.receipt)
            assertNotNull(result.recoveryFailure)
            assertEquals("batch", f.readSubmission()?.idempotencyKey)
            assertNull(f.readSubmission()?.receipt())
            f.sql("DROP TRIGGER reject_receipt")
            assertNull(f.owner.resumeImportSubmission("batch").recoveryFailure)
            assertEquals(2, gets); assertNull(f.readSubmission())
        } finally { f.close() }
    }

    @Test fun cleanupFailureRetainsTheReceiptEvenAfterItsCompleteSnapshotWasSaved() = runTest {
        var requests = 0
        val f = openFixture { requests++; receipt() }
        try {
            f.owner.prepareImportSubmission("batch", localCashWriteSetFixture())
            f.sql("CREATE TRIGGER reject_cleanup BEFORE DELETE ON ledger_cache_import_files BEGIN SELECT RAISE(ABORT, 'synthetic cleanup failure'); END")
            val result = f.owner.resumeImportSubmission("batch")
            assertEquals(localCashWriteSetReceiptFixture(), result.receipt)
            assertNotNull(result.recoveryFailure)
            assertEquals("9", f.readSnapshot()?.ledgerSeq)
            assertEquals(result.receipt, f.readSubmission()?.receipt())
            f.sql("DROP TRIGGER reject_cleanup")
            assertNull(f.owner.resumeImportSubmission("batch").recoveryFailure)
            assertEquals(1, requests); assertNull(f.readSubmission())
        } finally { f.close() }
    }

    @Test fun baselineRejectionKeepsTheExactRequestAndDoesNotInventAReplacement() = runTest {
        var posts = 0
        val f = openFixture { http ->
            if (http.method == HttpMethod.Get) missing() else {
                posts++
                respond("""{"error":{"code":"ledger_base_changed"},"commit_state":"not_committed"}""",
                    HttpStatusCode.Conflict, jsonHeaders)
            }
        }
        try {
            val request = localCashWriteSetFixture()
            f.owner.prepareImportSubmission("batch", request)
            val original = assertNotNull(f.readSubmission()).requestBody
            assertEquals("ledger_base_changed", assertFailsWith<ApiFailure> { f.owner.resumeImportSubmission("batch") }.code)
            assertTrue(assertNotNull(f.readSubmission()).baseRejected)
            assertEquals(original, f.readSubmission()?.requestBody)
            assertFails { f.owner.prepareImportSubmission("batch", request.copy(baseLedgerSeq = "8")) }
            f.reopen()
            val repeated = assertFailsWith<ApiFailure> { f.owner.resumeImportSubmission("batch") }
            assertEquals("ledger_base_changed", repeated.code); assertEquals("not_committed", repeated.commitState)
            assertEquals(1, posts); assertEquals(0, f.source.reads)
        } finally { f.close() }
    }

    @Test fun incompleteOrDifferentRejectionsCannotBecomeSavedBaseRejections() = runTest {
        val cases = listOf(Triple(409, "ledger_base_changed", null), Triple(409, "ledger_base_changed", "unknown"),
            Triple(409, "other_conflict", "not_committed"), Triple(422, "ledger_base_changed", "not_committed"))
        for ((status, code, state) in cases) {
            val f = openFixture { http ->
                if (http.method == HttpMethod.Get) missing() else respond(
                    """{"error":{"code":"$code"}${state?.let { ",\"commit_state\":\"$it\"" }.orEmpty()}}""",
                    HttpStatusCode.fromValue(status), jsonHeaders)
            }
            try {
                f.owner.prepareImportSubmission("batch", localCashWriteSetFixture())
                assertFailsWith<ApiFailure> { f.owner.resumeImportSubmission("batch") }
                assertFalse(assertNotNull(f.readSubmission()).baseRejected)
            } finally { f.close() }
        }
        var posts = 0
        val f = openFixture { http ->
            if (http.method == HttpMethod.Post) posts++
            respond("""{"error":{"code":"ledger_base_changed"},"commit_state":"not_committed"}""", HttpStatusCode.Conflict, jsonHeaders)
        }
        try {
            f.owner.prepareImportSubmission("batch", localCashWriteSetFixture())
            assertFailsWith<ApiFailure> { f.owner.resumeImportSubmission("batch") }
            assertEquals(0, posts); assertFalse(assertNotNull(f.readSubmission()).baseRejected)
        } finally { f.close() }
    }

    @Test fun anAuthoritativeReceiptWinsOverASavedEarlierAttemptRejection() = runTest {
        var committed = false; var posts = 0
        val f = openFixture { http ->
            if (http.method == HttpMethod.Get) { if (committed) receipt() else missing() }
            else { posts++; respond("""{"error":{"code":"ledger_base_changed"},"commit_state":"not_committed"}""", HttpStatusCode.Conflict, jsonHeaders) }
        }
        try {
            f.owner.prepareImportSubmission("batch", localCashWriteSetFixture())
            assertFailsWith<ApiFailure> { f.owner.resumeImportSubmission("batch") }
            assertTrue(assertNotNull(f.readSubmission()).baseRejected)
            f.reopen(); committed = true
            assertEquals(localCashWriteSetReceiptFixture(), f.owner.resumeImportSubmission("batch").receipt)
            assertEquals(1, posts); assertNull(f.readSubmission())
        } finally { f.close() }
    }

    @Test fun rejectionStorageFailureRetainsTheOriginalAndBothFailureCauses() = runTest {
        var posts = 0
        val f = openFixture { http ->
            if (http.method == HttpMethod.Get) missing() else {
                posts++; respond("""{"error":{"code":"ledger_base_changed"},"commit_state":"not_committed"}""", HttpStatusCode.Conflict, jsonHeaders)
            }
        }
        try {
            f.owner.prepareImportSubmission("batch", localCashWriteSetFixture())
            val original = assertNotNull(f.readSubmission()).encode()
            f.sql("CREATE TRIGGER reject_marker BEFORE UPDATE ON ledger_cache_import_files BEGIN SELECT RAISE(ABORT, 'synthetic rejection marker failure'); END")
            val failed = assertFailsWith<LocalCashImportRejectionStorageFailure> { f.owner.resumeImportSubmission("batch") }
            assertEquals("ledger_base_changed", failed.rejection.code)
            assertEquals("not_committed", failed.rejection.commitState)
            assertTrue(assertNotNull(failed.cause).message.orEmpty().contains("synthetic rejection marker failure"))
            assertEquals(original, f.readSubmission()?.encode())
            f.sql("DROP TRIGGER reject_marker")
            assertFailsWith<ApiFailure> { f.owner.resumeImportSubmission("batch") }
            f.reopen()
            assertFailsWith<ApiFailure> { f.owner.resumeImportSubmission("batch") }
            assertEquals(2, posts)
        } finally { f.close() }
    }

    @Test fun aLaterLoginDuringTheRejectedPostCannotSaveARejectionIntoTheOldBatch() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val f = openFixture { http ->
            if (http.method == HttpMethod.Get) missing() else {
                entered.complete(Unit); release.await()
                respond("""{"error":{"code":"ledger_base_changed"},"commit_state":"not_committed"}""", HttpStatusCode.Conflict, jsonHeaders)
            }
        }
        try {
            f.owner.prepareImportSubmission("batch", localCashWriteSetFixture())
            val operation = async { assertFailsWith<CancellationException> { f.owner.resumeImportSubmission("batch") } }
            entered.await(); f.token = "later-token"; release.complete(Unit); operation.await()
            assertFalse(assertNotNull(f.readSubmission()).baseRejected)
        } finally { release.complete(Unit); f.close() }
    }

    @Test fun aLaterLoginDuringLookupCannotSendAnOldPostOrDeleteItsBatch() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var posts = 0
        val f = openFixture { http ->
            if (http.method == HttpMethod.Post) { posts++; error("No late post") }
            entered.complete(Unit); release.await(); missing()
        }
        try {
            f.owner.prepareImportSubmission("batch", localCashWriteSetFixture())
            val operation = async { assertFailsWith<CancellationException> { f.owner.resumeImportSubmission("batch") } }
            entered.await(); f.token = "later-token"; release.complete(Unit); operation.await()
            assertEquals(0, posts); assertEquals("batch", f.readSubmission()?.idempotencyKey)
        } finally { release.complete(Unit); f.close() }
    }

    @Test fun localTaskRetiresItsOldPlanWhenProductionRebaseRequiresReconfirmation() = runTest {
        val plan = LocalCashImportRebaseFixture(includeRefund = true); var posts = 0
        val f = openFixture(plan.source, plan.files, null) { http ->
            if (http.method == HttpMethod.Get) missing() else { posts++; rejected() }
        }
        try {
            val retired = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val imports = LedgerSessionImports(f.owner, {}, onFailure = { failure ->
                if (failure is CashImportPlanError) { retired.complete(Unit); release.await() }
                throw failure
            })
            val task = LocalCashImportTask("batch", imports) { "actual_plan" }
            task.preview(1, null, plan.input, "UTC"); task.freeze("actual_plan", 2)
            f.source.current = plan.source.copy(ledgerSeq = "9", facts = plan.source.facts.copy(accounts =
                plan.source.facts.accounts.map { it.copy(name = "Changed visible account") }))
            val recovering = async { assertFailsWith<CashImportPlanError> { task.resume() } }
            try {
                retired.await(); assertNull(f.readSubmission())
                val refreeze = runCatching { task.freeze("actual_plan", 2) }
                release.complete(Unit)
                assertEquals("plan_changed", recovering.await().code)
                assertIs<CashImportPlanError>(refreeze.exceptionOrNull())
            } finally { release.complete(Unit); recovering.cancel() }
            assertEquals(1, posts); assertNull(f.readSubmission())
            assertNotNull(f.owner.importReview("batch")?.conclusion())
            assertEquals("plan_not_found", assertFailsWith<CashImportPlanError> { task.plan("actual_plan") }.code)
            assertFailsWith<CashImportPlanError> { task.freeze("actual_plan", 2) }
            assertNull(f.readSubmission()); assertEquals(1, posts)
            val context = task.context()
            assertEquals(3L, context.sessionRevision); assertNull(context.lastPlanId)
        } finally { f.close() }
    }

    @Test fun localTaskCannotFreezeOldPlanAfterActualSqlReviewUpdateHasLostItsAcknowledgement() = runTest {
        val plan = LocalCashImportRebaseFixture(includeRefund = true)
        val f = openFixture(plan.source, plan.files, null) { error("preview must not use HTTP") }
        try {
            val row = (plan.original.first.getValue("operations") as List<*>).first() as Map<*, *>
            val rowRef = (row["row_refs"] as List<*>).first() as String
            val changed = kotlinx.serialization.json.JsonObject(plan.input + ("row_choices" to
                kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("row_ref", kotlinx.serialization.json.JsonPrimitive(rowRef))
                        put("action", kotlinx.serialization.json.JsonPrimitive("defer"))
                    })
                }))
            var failAfterWrite = false; var sequence = 0
            val failure = IOException("synthetic post-UPDATE publication failure")
            val imports = LedgerSessionImports(f.owner, {
                if (failAfterWrite && f.owner.importReview("batch")?.inputJson() == changed) throw failure
            })
            val task = LocalCashImportTask("batch", imports) { "actual_plan_${++sequence}" }
            task.preview(1, null, plan.input, "UTC")
            failAfterWrite = true
            assertSame(failure, assertFailsWith<IOException> { task.preview(2, "actual_plan_1", changed, "UTC") })
            failAfterWrite = false
            assertEquals(changed, f.owner.importReview("batch")?.inputJson())
            assertEquals("plan_not_found", assertFailsWith<CashImportPlanError> { task.freeze("actual_plan_1", 2) }.code)
            assertNull(f.readSubmission()); assertNull(task.context().lastPlanId)
            f.reopen(); assertEquals(changed, f.owner.importReview("batch")?.inputJson())
        } finally { f.close() }
    }

    @Test fun localTaskCancellationAfterRetirementStillMakesTheOldPlanUnconfirmable() = runTest {
        val plan = LocalCashImportRebaseFixture(includeRefund = true); var posts = 0
        val retired = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val f = openFixture(plan.source, plan.files, null) { http ->
            if (http.method == HttpMethod.Get) missing() else { posts++; rejected() }
        }
        try {
            val imports = LedgerSessionImports(f.owner, {}, onFailure = { failure ->
                if (failure is CashImportPlanError) { retired.complete(Unit); release.await() }
                throw failure
            })
            val task = LocalCashImportTask("batch", imports) { "actual_plan" }
            task.preview(1, null, plan.input, "UTC"); task.freeze("actual_plan", 2)
            f.source.current = plan.source.copy(ledgerSeq = "9", facts = plan.source.facts.copy(accounts =
                plan.source.facts.accounts.map { it.copy(name = "Changed visible account") }))
            val recovering = async { task.resume() }
            try {
                retired.await(); assertNull(f.readSubmission())
                recovering.cancel(); recovering.join()
            } finally { release.complete(Unit); recovering.cancel() }
            assertEquals("plan_not_found", assertFailsWith<CashImportPlanError> { task.plan("actual_plan") }.code)
            assertFailsWith<CashImportPlanError> { task.freeze("actual_plan", 2) }
            assertNull(f.readSubmission()); assertEquals(1, posts)
            assertEquals(3L, task.context().sessionRevision)
            assertNotNull(f.owner.importReview("batch")?.conclusion())
        } finally { release.complete(Unit); f.close() }
    }

    @Test fun localTaskRecoveryRechecksOriginalSessionAfterWaitingForItsFinalPublicationLock() = runTest {
        val plan = LocalCashImportRebaseFixture(includeRefund = true)
        val lookup = CompletableDeferred<Unit>(); val receiptRelease = CompletableDeferred<Unit>()
        val readEntered = CompletableDeferred<Unit>(); val readRelease = CompletableDeferred<Unit>()
        val resultReady = CompletableDeferred<Unit>()
        val f = openFixture(plan.source, plan.files, null) {
            lookup.complete(Unit); receiptRelease.await(); receipt()
        }
        try {
            val imports = LedgerSessionImports(f.owner, {
                if (currentCoroutineContext()[kotlinx.coroutines.CoroutineName]?.name == "locked-reader") {
                    readEntered.complete(Unit); readRelease.await()
                }
            }, recover = { action -> action().also { resultReady.complete(Unit) } })
            val task = LocalCashImportTask("batch", imports) { "actual_plan" }
            task.preview(1, null, plan.input, "UTC"); task.freeze("actual_plan", 2)
            f.source.current = plan.source.copy(ledgerSeq = "9")
            val recovering = async { runCatching { task.resume() } }
            try {
                lookup.await()
                val reading = async(kotlinx.coroutines.CoroutineName("locked-reader")) { runCatching { task.plan("actual_plan") } }
                try {
                    readEntered.await(); receiptRelease.complete(Unit); resultReady.await(); runCurrent()
                    f.token = "later-token"; readRelease.complete(Unit)
                    assertIs<CancellationException>(reading.await().exceptionOrNull())
                    assertIs<CancellationException>(recovering.await().exceptionOrNull())
                    assertNull(f.readSubmission())
                } finally { readRelease.complete(Unit); reading.cancel() }
            } finally { receiptRelease.complete(Unit); recovering.cancel() }
        } finally { receiptRelease.complete(Unit); readRelease.complete(Unit); f.close() }
    }

    @Test fun localTaskFileUpdateLosingItsAcknowledgementCannotLeaveTheOldPlanReadable() = runTest {
        val plan = LocalCashImportRebaseFixture(includeRefund = true)
        val f = openFixture(plan.source, plan.files, null) { error("local CSV must not use HTTP") }
        try {
            var failAfterWrite = false
            val failure = IOException("synthetic post-file-UPDATE publication failure")
            val imports = LedgerSessionImports(f.owner, {
                if (failAfterWrite && f.owner.importFiles("batch")?.any { it.fileRef == "added" } == true) throw failure
            })
            val task = LocalCashImportTask("batch", imports) { "actual_plan" }
            task.preview(1, null, plan.input, "UTC")
            failAfterWrite = true
            assertSame(failure, assertFailsWith<IOException> {
                task.append(localCashImportStatementInput("added") { localCashImportCsvBytes() })
            })
            failAfterWrite = false
            assertEquals(listOf("stable", "added"), f.owner.importFiles("batch")?.map { it.fileRef })
            assertEquals("plan_not_found", assertFailsWith<CashImportPlanError> { task.plan("actual_plan") }.code)
            assertFailsWith<CashImportPlanError> { task.freeze("actual_plan", 2) }
            assertNull(f.readSubmission()); assertNull(f.owner.importReview("batch")?.conclusion())
        } finally { f.close() }
    }

    @Test fun localTaskExactFileRetryPreservesTheCurrentPlanAndRevision() = runTest {
        val plan = LocalCashImportRebaseFixture()
        val f = openFixture(plan.source, plan.files) { error("local CSV must not use HTTP") }
        try {
            val imports = LedgerSessionImports(f.owner, {})
            val task = LocalCashImportTask("batch", imports) { "actual_plan" }
            val first = task.append(localCashImportStatementInput("added") { localCashImportCsvBytes() })
            val view = task.preview(assertNotNull(first.sessionRevision), null, kotlinx.serialization.json.JsonObject(emptyMap()), "UTC")
            val revision = view["session_revision"].toString().toLong()
            val retry = task.append(localCashImportStatementInput("added") { localCashImportCsvBytes() })
            assertEquals(revision, retry.sessionRevision); assertEquals("actual_plan", retry.lastPlanId)
            assertEquals(view, task.plan("actual_plan", "UTC"))
            val same = task.replace("added", revision, localCashImportStatementInput("added") { localCashImportCsvBytes() })
            assertEquals(revision, same.sessionRevision); assertEquals("actual_plan", same.lastPlanId)
        } finally { f.close() }
    }

    @Test fun successIsDeliveredWithADurableOriginalReceiptBeforeRefreshAndBatchCleanup() = runTest {
        val plan = LocalCashImportRebaseFixture(includeRefund = true); var gets = 0
        val f = openFixture(plan.source, plan.files, null) { gets++; receipt() }
        val recovery = com.finance.tracker.core.ScopedImportRecoveryStore(
            com.finance.tracker.core.ImportRecoveryScope("https://recovery.example.test", "synthetic@example.test", "workspace"),
            com.finance.tracker.core.InMemoryImportRecoveryStorage())
        try {
            val task = LocalCashImportTask("batch", LedgerSessionImports(f.owner, {})) { "actual_plan" }
            task.preview(1, null, plan.input, "UTC"); task.freeze("actual_plan", 2)
            f.source.current = plan.source.copy(ledgerSeq = "9")
            var deliveries = 0
            val result = task.resume(beforeCompletion = { confirmed ->
                deliveries++
                assertEquals(confirmed, f.readSubmission()?.receipt())
                assertEquals(plan.files.map { it.encode() }, f.owner.importFiles("batch")?.map { it.encode() })
                assertNotNull(f.owner.importReview("batch")?.conclusion()); assertEquals(0, f.source.reads)
                recovery.save(com.finance.tracker.core.ImportRecoveryPointer.Receipt(confirmed.receiptId))
            })
            assertTrue(result.receipt.committed); assertNull(result.recoveryFailure)
            assertEquals(1, deliveries); assertEquals(1, gets); assertNull(f.readSubmission())
            assertNull(f.owner.importFiles("batch"))
            assertEquals(com.finance.tracker.core.ImportRecoveryPointer.Receipt(result.receipt.receiptId), recovery.load())
        } finally { f.close() }
    }

    @Test fun successfulDeliveryMayRepeatAfterRefreshFailureWithoutAnotherHttpRequest() = runTest {
        val plan = LocalCashImportRebaseFixture(includeRefund = true); var requests = 0
        val f = openFixture(plan.source, plan.files, null) { requests++; receipt() }
        val recovery = com.finance.tracker.core.ScopedImportRecoveryStore(
            com.finance.tracker.core.ImportRecoveryScope("https://recovery.example.test", "synthetic@example.test", "workspace"),
            com.finance.tracker.core.InMemoryImportRecoveryStorage())
        try {
            val task = LocalCashImportTask("batch", LedgerSessionImports(f.owner, {})) { "actual_plan" }
            task.preview(1, null, plan.input, "UTC"); task.freeze("actual_plan", 2)
            f.source.current = plan.source.copy(ledgerSeq = "9")
            val failure = IOException("synthetic refresh failure after delivery")
            f.source.failure = failure
            val deliveries = mutableListOf<String>()
            val deliver: suspend (com.finance.tracker.business.contracts.CashMutationReceiptDto) -> Unit = { confirmed ->
                deliveries += confirmed.receiptId
                recovery.save(com.finance.tracker.core.ImportRecoveryPointer.Receipt(confirmed.receiptId))
            }
            val first = task.resume(beforeCompletion = deliver)
            assertTrue(first.receipt.committed); assertSame(failure, first.recoveryFailure)
            val pointer = com.finance.tracker.core.ImportRecoveryPointer.Receipt(first.receipt.receiptId)
            assertEquals(pointer, recovery.load()); assertNotNull(f.readSubmission()); assertNotNull(f.owner.importFiles("batch"))
            f.source.failure = null
            val completed = task.resume(beforeCompletion = deliver)
            assertEquals(first.receipt, completed.receipt); assertNull(completed.recoveryFailure)
            assertEquals(listOf(first.receipt.receiptId, first.receipt.receiptId), deliveries)
            assertEquals(pointer, recovery.load()); assertEquals(1, requests)
            assertNull(f.readSubmission()); assertNull(f.owner.importFiles("batch"))
        } finally { f.close() }
    }

    @Test fun failedSuccessDeliveryRetainsAuthoritativeSuccessAndReopensWithoutAnotherHttpRequest() = runTest {
        for (failure in listOf(IOException("synthetic delivery store unavailable"), ArithmeticException("synthetic delivery defect"))) {
            val plan = LocalCashImportRebaseFixture(includeRefund = true); var requests = 0
            val f = openFixture(plan.source, plan.files, null) { requests++; receipt() }
            try {
                val task = LocalCashImportTask("batch", LedgerSessionImports(f.owner, {})) { "actual_plan" }
                task.preview(1, null, plan.input, "UTC"); task.freeze("actual_plan", 2)
                f.source.current = plan.source.copy(ledgerSeq = "9")
                val result = task.resume(beforeCompletion = { throw failure })
                assertTrue(result.receipt.committed); assertSame(failure, result.recoveryFailure)
                assertEquals(result.receipt, f.readSubmission()?.receipt()); assertEquals(0, f.source.reads)
                assertNotNull(f.owner.importFiles("batch")); assertNotNull(f.owner.importReview("batch"))
                f.reopen()
                val resumed = LocalCashImportTask("batch", LedgerSessionImports(f.owner, {})) { "unused" }
                var delivered: com.finance.tracker.business.contracts.CashMutationReceiptDto? = null
                val completed = resumed.resume(beforeCompletion = { delivered = it })
                assertEquals(result.receipt, completed.receipt); assertEquals(result.receipt, delivered)
                assertNull(completed.recoveryFailure); assertNull(f.readSubmission()); assertEquals(1, requests)
            } finally { f.close() }
        }
    }

    @Test fun cancellationDuringSuccessDeliveryRetainsTheOriginalReceiptForExplicitRecovery() = runTest {
        val plan = LocalCashImportRebaseFixture(includeRefund = true); var requests = 0
        val f = openFixture(plan.source, plan.files, null) { requests++; receipt() }
        try {
            val task = LocalCashImportTask("batch", LedgerSessionImports(f.owner, {})) { "actual_plan" }
            task.preview(1, null, plan.input, "UTC"); task.freeze("actual_plan", 2)
            f.source.current = plan.source.copy(ledgerSeq = "9")
            val delivering = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val waiting = async { task.resume(beforeCompletion = { delivering.complete(Unit); release.await() }) }
            try {
                delivering.await(); waiting.cancel(); waiting.join()
                assertTrue(waiting.isCancelled)
            } finally { release.complete(Unit); waiting.cancel() }
            assertNotNull(f.readSubmission()?.receipt()); assertNotNull(f.owner.importFiles("batch")); assertEquals(0, f.source.reads)
            f.reopen()
            var deliveries = 0
            val completed = LocalCashImportTask("batch", LedgerSessionImports(f.owner, {})) { "unused" }
                .resume(beforeCompletion = { deliveries++ })
            assertTrue(completed.receipt.committed); assertNull(completed.recoveryFailure)
            assertEquals(1, deliveries); assertEquals(1, requests); assertNull(f.readSubmission())
        } finally { f.close() }
    }

    @Test fun aSessionChangedByTheDeliveryCallbackCannotRefreshOrCleanTheOldBatch() = runTest {
        val plan = LocalCashImportRebaseFixture(includeRefund = true)
        val f = openFixture(plan.source, plan.files, null) { receipt() }
        try {
            val task = LocalCashImportTask("batch", LedgerSessionImports(f.owner, {})) { "actual_plan" }
            task.preview(1, null, plan.input, "UTC"); task.freeze("actual_plan", 2)
            f.source.current = plan.source.copy(ledgerSeq = "9")
            assertFailsWith<CancellationException> {
                task.resume(beforeCompletion = { f.token = "later-token" })
            }
            assertNotNull(f.readSubmission()?.receipt()); assertEquals(0, f.source.reads)
            f.token = "original-token"; f.reopen()
            assertNotNull(f.owner.importFiles("batch")); assertNotNull(f.owner.importReview("batch"))
        } finally { f.close() }
    }

    private val jsonHeaders get() = headersOf(HttpHeaders.ContentType, "application/json")
    private fun MockRequestHandleScope.receipt() = respond(LocalCashImportSubmission.freezeReceipt(localCashWriteSetReceiptFixture()), headers = jsonHeaders)
    private fun MockRequestHandleScope.missing() = respond("""{"error":{"code":"receipt_not_found"}}""", HttpStatusCode.NotFound, jsonHeaders)
    private fun MockRequestHandleScope.rejected() = respond("""{"error":{"code":"ledger_base_changed"},"commit_state":"not_committed"}""", HttpStatusCode.Conflict, jsonHeaders)

    private suspend fun openFixture(initial: LedgerSnapshotDto = localCashLedgerOwnerSnapshot().copy(ledgerSeq = "7"),
        files: List<LocalCashImportFile> = listOf(localCashImportFileFixture()), review: LocalCashImportReview? = null,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): Fixture =
        Fixture(handler, currentCoroutineContext()[ContinuationInterceptor] as CoroutineDispatcher).also { it.start(initial, files, review) }

    private inner class Fixture(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData, testDispatcher: CoroutineDispatcher) {
        val file = File.createTempFile("import-recovery-", ".db")
        private val origin = "https://recovery.example.test"
        private val session = SessionDto(UserDto("synthetic@example.test"), "workspace", listOf(WorkspaceDto("workspace", "Synthetic", Role.EDITOR)))
        val scope = LedgerCacheScope(origin, ledgerOfflinePrincipal(session.user.email), "workspace")
        var token = "original-token"
        val api = FinanceApiClient({ origin }, object : TokenStore {
            override suspend fun get() = token
            override suspend fun set(value: String) { token = value }
            override suspend fun clear() = error("No implicit token clearing")
        }, MockEngine(MockEngineConfig().apply { dispatcher = testDispatcher; addHandler(handler) }))
        val source = Source()
        private val directory = LedgerSessionDirectory(object : LedgerSessionLocatorStorage {
            var value: String? = null
            override suspend fun read(key: String) = value
            override suspend fun compareAndSet(key: String, expected: String?, replacement: String): Boolean {
                if (value != expected) return false
                value = replacement; return true
            }
        })
        private var ticket: LedgerSessionTicket? = null
        lateinit var owner: LedgerSessionCacheOwner

        suspend fun start(initial: LedgerSnapshotDto, files: List<LocalCashImportFile>, review: LocalCashImportReview?) {
            val cache = storage()
            try {
                cache.replace(scope, initial)
                cache.saveOfflineSession(scope, "original-token", session)
                cache.imports.save("batch", files)
                if (review != null) cache.imports.saveReview("batch", review)
            } finally { cache.close() }
            ticket = directory.begin(origin).also { directory.register(it, scope); directory.publish(it, scope) }
            newOwner()
        }
        private fun newOwner() {
            owner = LedgerSessionCacheOwner(api, directory, checkNotNull(ticket), scope,
                ApiCredentialSnapshot("original-token", origin), source) { existing, registration ->
                assertTrue(existing); registration.beforeOpen(); storage()
            }
        }
        suspend fun reopen() { owner.close(); newOwner() }
        private suspend fun storage(): ScopedSqlLedgerFactCache {
            val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
            return ScopedSqlLedgerFactCache.open(scope, driver, closeDriver = { driver.close() }, eraseStorage = { check(file.delete()) })
        }
        suspend fun readSubmission(): LocalCashImportSubmission? = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use {
            SqlLedgerImportBatchStore.open(it).readSubmission(scope, "batch")
        }
        suspend fun readSnapshot(): LedgerSnapshotDto? = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { SqlLedgerSnapshotStore.open(it).read(scope) }
        suspend fun sql(text: String) { JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { it.execute(null, text, 0).await() } }
        suspend fun close() { if (this::owner.isInitialized) owner.close(); api.close(); file.delete() }
    }

    private class Source : LedgerSnapshotSource {
        var current = localCashLedgerOwnerSnapshot().copy(ledgerSeq = "9")
        var failure: Throwable? = null
        var reads = 0
        override suspend fun version(workspaceId: String): LedgerVersionDto { reads++; return LedgerVersionDto(current.ledgerSeq) }
        override suspend fun snapshot(workspaceId: String): LedgerSnapshotDto { reads++; failure?.let { throw it }; return current }
    }
}
