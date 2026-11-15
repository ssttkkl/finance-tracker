package com.finance.tracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.contracts.LedgerVersionDto
import com.finance.tracker.business.contracts.SessionDto
import com.finance.tracker.business.contracts.UserDto
import com.finance.tracker.business.contracts.WorkspaceDto
import com.finance.tracker.business.contracts.Role
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.*

class LocalCashImportRecoveryTest {
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
            assertEquals(original, f.readSubmission()?.requestBody)
            assertFails { f.owner.prepareImportSubmission("batch", request.copy(baseLedgerSeq = "8")) }
            assertEquals(1, posts); assertEquals(0, f.source.reads)
        } finally { f.close() }
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

    private val jsonHeaders get() = headersOf(HttpHeaders.ContentType, "application/json")
    private fun MockRequestHandleScope.receipt() = respond(LocalCashImportSubmission.freezeReceipt(localCashWriteSetReceiptFixture()), headers = jsonHeaders)
    private fun MockRequestHandleScope.missing() = respond("""{"error":{"code":"receipt_not_found"}}""", HttpStatusCode.NotFound, jsonHeaders)

    private suspend fun openFixture(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): Fixture =
        Fixture(handler).also { it.start() }

    private inner class Fixture(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) {
        val file = File.createTempFile("import-recovery-", ".db")
        private val origin = "https://recovery.example.test"
        private val session = SessionDto(UserDto("synthetic@example.test"), "workspace", listOf(WorkspaceDto("workspace", "Synthetic", Role.EDITOR)))
        val scope = LedgerCacheScope(origin, ledgerOfflinePrincipal(session.user.email), "workspace")
        var token = "original-token"
        val api = FinanceApiClient({ origin }, object : TokenStore {
            override suspend fun get() = token
            override suspend fun set(value: String) { token = value }
            override suspend fun clear() = error("No implicit token clearing")
        }, MockEngine(handler))
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

        suspend fun start() {
            val cache = storage()
            try {
                cache.replace(scope, localCashLedgerOwnerSnapshot().copy(ledgerSeq = "7"))
                cache.saveOfflineSession(scope, "original-token", session)
                cache.imports.save("batch", listOf(localCashImportFileFixture()))
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
        var failure: Throwable? = null
        var reads = 0
        override suspend fun version(workspaceId: String): LedgerVersionDto { reads++; return LedgerVersionDto("9") }
        override suspend fun snapshot(workspaceId: String): LedgerSnapshotDto { reads++; failure?.let { throw it }; return localCashLedgerOwnerSnapshot().copy(ledgerSeq = "9") }
    }
}
