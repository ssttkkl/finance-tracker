package com.finance.tracker

import com.finance.tracker.business.contracts.SessionDto
import com.finance.tracker.business.contracts.UserDto
import com.finance.tracker.business.contracts.WorkspaceDto
import com.finance.tracker.business.contracts.Role
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.contracts.LedgerVersionDto
import com.finance.tracker.business.contracts.CashMutationReceiptDto
import com.finance.tracker.core.TokenStore
import com.finance.tracker.data.*
import com.finance.tracker.domain.LedgerCacheScope
import com.finance.tracker.domain.BoundedFileSource
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.*
import kotlin.test.*

class LedgerSessionImportsTest {
    @Test fun onlineAndOfflineRestorationsExposeTheSameProductionFileFundingAndPreviewFlow() = runTest {
        for (offline in listOf(false, true)) {
            val f = Fixture(offline)
            try {
                assertNull(f.repository.captureImports())
                f.repository.restoreSession()
                val imports = assertNotNull(f.repository.captureImports())
                val requests = f.paths.toList()
                imports.saveImportFiles("batch", f.plan.files)
                assertEquals(listOf("batch"), imports.importFileBatches())
                assertEquals(f.plan.files.map { it.encode() }, imports.importFiles("batch")!!.map { it.encode() })
                assertEquals(1, imports.importFunding("batch").first.size)
                val calculated = imports.calculateImport("batch", f.plan.input)
                assertEquals(f.plan.request, calculated.request)
                assertEquals(f.plan.review.conclusion(), calculated.review.conclusion())
                imports.saveImportReview("batch", calculated.review)
                assertEquals(calculated.review.conclusion(), imports.importReview("batch")?.conclusion())
                assertEquals(requests, f.paths)
                imports.removeImportFiles("batch")
                assertNull(imports.importFiles("batch"))
            } finally { f.close() }
        }
    }

    @Test fun noActiveWorkspaceDoesNotCreateAnImporterOrAnEmptyCache() = runTest {
        val f = Fixture()
        f.online = f.session.copy(activeWorkspaceId = null, workspaces = emptyList())
        try {
            f.repository.restoreSession()
            assertNull(f.repository.captureImports())
            assertEquals(0, f.opens)
        } finally { f.close() }
    }

    @Test fun anImporterIsNotPublishedWhileAuthenticationIsStillRunning() = runTest {
        val f = Fixture(); val entered = CompletableDeferred<Unit>(); val proceed = CompletableDeferred<Unit>()
        f.beforeSession = { entered.complete(Unit); proceed.await() }
        val restoring = async { f.repository.restoreSession() }
        try {
            entered.await(); assertNull(f.repository.captureImports())
            proceed.complete(Unit); restoring.await()
            assertNotNull(f.repository.captureImports())
        } finally { proceed.complete(Unit); restoring.cancelAndJoin(); f.close() }
    }

    @Test fun aCapturedImporterNeverRebindsToALaterRestorationEvenWithTheSameToken() = runTest {
        val f = Fixture()
        try {
            f.repository.restoreSession()
            val original = assertNotNull(f.repository.captureImports())
            original.saveImportFiles("batch", f.plan.files)
            f.repository.restoreSession()
            val replacement = assertNotNull(f.repository.captureImports())
            val reads = f.storage.importReads
            assertFailsWith<CancellationException> { original.importFiles("batch") }
            assertEquals(reads, f.storage.importReads)
            assertNotNull(replacement.importFiles("batch"))
        } finally { f.close() }
    }

    @Test fun changingTheTokenFencesTheCapturedImporterBeforeAnyBatchAccess() = runTest {
        val f = Fixture()
        try {
            f.repository.restoreSession()
            val imports = assertNotNull(f.repository.captureImports())
            f.token = "later-token"
            assertFailsWith<CancellationException> { imports.importFileBatches() }
            assertEquals(0, f.storage.importReads)
        } finally { f.close() }
    }

    @Test fun aFailedFactRefreshBlocksCalculationsButStillAllowsOriginalReceiptRecovery() = runTest {
        val f = Fixture()
        try {
            f.repository.restoreSession()
            val imports = assertNotNull(f.repository.captureImports())
            imports.saveImportFiles("batch", f.plan.files)
            val calculated = imports.calculateImport("batch", f.plan.input)
            imports.saveImportReview("batch", calculated.review)
            imports.prepareImportSubmission("batch", calculated.request)
            f.versionFailure = IOException("synthetic disconnected refresh")
            f.repository.captureRefresh()("9")
            assertNotNull(f.repository.captureImports())
            assertFailsWith<IllegalStateException> { imports.importFunding("batch") }
            assertFailsWith<IllegalStateException> { imports.calculateImport("batch", f.plan.input) }
            assertNotNull(imports.importFiles("batch")); assertNotNull(imports.importReview("batch"))
            f.versionFailure = null
            f.current = f.plan.source.copy(ledgerSeq = "9")
            val result = imports.resumeLocalCashImport("batch")
            assertTrue(result.receipt.committed); assertNull(result.recoveryFailure)
            assertEquals(1, f.paths.count { it == "/api/v1/cash-mutations/receipts/by-key" })
            assertFalse(f.paths.any { it.contains("write-set") })
            assertNull(f.storage.submission)
            // A successful recovery refresh restores calculation eligibility for this same publication.
            imports.saveImportFiles("next", f.plan.files)
            assertEquals(1, imports.importFunding("next").first.size)
        } finally { f.close() }
    }

    @Test fun committedRecoveryFailureStaysSuccessfulAndCanBeRetriedWithoutAnotherWrite() = runTest {
        val f = Fixture()
        try {
            f.repository.restoreSession()
            val imports = assertNotNull(f.repository.captureImports())
            imports.saveImportFiles("batch", f.plan.files)
            imports.prepareImportSubmission("batch", f.plan.request)
            val failure = IOException("synthetic refresh after authoritative receipt")
            f.versionFailure = failure
            val first = imports.resumeLocalCashImport("batch")
            assertTrue(first.receipt.committed)
            assertNotNull(first.recoveryFailure)
            assertNotNull(f.storage.submission?.receipt())
            assertFailsWith<IllegalStateException> { imports.importFunding("batch") }
            f.versionFailure = null
            assertNull(imports.resumeLocalCashImport("batch").recoveryFailure)
            assertEquals(1, f.paths.count { it == "/api/v1/cash-mutations/receipts/by-key" })
            assertFalse(f.paths.any { it.contains("write-set") })
            assertNull(f.storage.submission)
        } finally { f.close() }
    }

    @Test fun aLateFileReadDoesNotBlockSessionReplacementOrPublishIntoTheNewRestoration() = runTest {
        val f = Fixture(); val entered = CompletableDeferred<Unit>(); val proceed = CompletableDeferred<Unit>()
        try {
            f.repository.restoreSession()
            val imports = assertNotNull(f.repository.captureImports())
            val capture = async {
                assertFailsWith<CancellationException> {
                    imports.captureImportFiles("late", listOf(localCashImportStatementInput("a") {
                        entered.complete(Unit); proceed.await(); localCashImportCsvBytes()
                    }))
                }
            }
            try {
                entered.await(); f.repository.restoreSession()
                proceed.complete(Unit); capture.await()
                assertNull(assertNotNull(f.repository.captureImports()).importFiles("late"))
            } finally { proceed.complete(Unit); capture.cancelAndJoin() }
        } finally { f.close() }
    }

    @Test fun aLateFundingResultIsNotPublishedAfterFactsBecomeUnreadable() = runTest {
        val f = Fixture()
        try {
            f.repository.restoreSession()
            val imports = assertNotNull(f.repository.captureImports())
            imports.saveImportFiles("batch", f.plan.files)
            var refresh: Job? = null
            f.storage.afterImportRead = {
                f.storage.afterImportRead = null
                f.versionFailure = IOException("synthetic concurrent refresh")
                refresh = launch { f.repository.captureRefresh()("9") }
                yield()
            }
            assertFailsWith<IllegalStateException> { imports.importFunding("batch") }
            refresh?.join()
        } finally { f.close() }
    }

    @Test fun mutableInputsAreFrozenBeforeTheFirstPublicationCheckSuspends() = runTest {
        val f = Fixture()
        try {
            f.repository.restoreSession()
            val original = assertNotNull(f.repository.captureImports())
            suspend fun <T> paused(action: suspend (LedgerSessionImports) -> T, mutate: () -> Unit): T {
                val entered = CompletableDeferred<Unit>(); val proceed = CompletableDeferred<Unit>()
                val imports = original.guardedBy({
                    if (!entered.isCompleted) { entered.complete(Unit); proceed.await() }
                }, { it() })
                val work = async { action(imports) }
                try {
                    entered.await(); mutate(); proceed.complete(Unit); return work.await()
                } finally { proceed.complete(Unit); work.cancelAndJoin() }
            }
            val files = f.plan.files.toMutableList()
            paused({ it.saveImportFiles("batch", files) }, { files.clear() })
            assertEquals(f.plan.files.map { it.encode() }, original.importFiles("batch")!!.map { it.encode() })
            val values = f.plan.input.toMutableMap()
            val calculated = paused({ it.calculateImport("batch", JsonObject(values)) }, { values.clear() })
            assertEquals(f.plan.request, calculated.request)
            val payload = linkedMapOf<String, JsonElement>("exact" to Json.parseToJsonElement("1.000000000000000001"))
            val request = localCashWriteSetFixture(JsonObject(payload))
            val expected = LocalCashImportSubmission.freeze("batch", request).requestBody
            paused({ it.prepareImportSubmission("batch", request) }, { payload["exact"] = JsonPrimitive("later") })
            assertEquals(expected, f.storage.submission?.requestBody)
            val inputs = mutableListOf(localCashImportStatementInput("a") { localCashImportCsvBytes() })
            val captured = paused({ it.captureImportFiles("captured", inputs) }, { inputs.clear() })
            assertEquals(1, captured.size)
            assertNotNull(original.importFiles("captured"))
        } finally { f.close() }
    }

    @Test fun aNewerFailedRefreshCannotBeUndoneByAnOlderRecoveryCompletion() = runTest {
        val f = Fixture(); val entered = CompletableDeferred<Unit>(); val proceed = CompletableDeferred<Unit>()
        try {
            f.repository.restoreSession()
            val imports = assertNotNull(f.repository.captureImports())
            imports.saveImportFiles("batch", f.plan.files)
            imports.prepareImportSubmission("batch", f.plan.request)
            f.beforeReceipt = { entered.complete(Unit); proceed.await() }
            val recovery = async { imports.resumeLocalCashImport("batch") }
            try {
                entered.await()
                f.versionFailure = IOException("synthetic newer refresh failed")
                f.repository.captureRefresh()("9")
                f.versionFailure = null; f.current = f.plan.source.copy(ledgerSeq = "9"); proceed.complete(Unit)
                assertNull(recovery.await().recoveryFailure)
                imports.saveImportFiles("next", f.plan.files)
                assertFailsWith<IllegalStateException> { imports.importFunding("next") }
                f.repository.captureRefresh()(f.plan.source.ledgerSeq)
                assertEquals(1, imports.importFunding("next").first.size)
            } finally { proceed.complete(Unit); recovery.cancelAndJoin() }
        } finally { f.close() }
    }

    @Test fun aMissingOriginalReceiptPostsOnlyTheFrozenOriginalRequestAndKey() = runTest {
        val f = Fixture(); f.receiptMissing = true
        try {
            f.repository.restoreSession()
            val imports = assertNotNull(f.repository.captureImports())
            imports.saveImportFiles("batch", f.plan.files)
            imports.prepareImportSubmission("batch", f.plan.request)
            val result = imports.resumeLocalCashImport("batch")
            assertTrue(result.receipt.committed); assertNull(result.recoveryFailure)
            assertEquals(1, f.paths.count { it == "/api/v1/cash-import/write-set" })
            assertNull(f.storage.submission)
        } finally { f.close() }
    }

    @Test fun anUnknownSubmissionRetainsItsOriginalRequestAndCanRecoverWithoutASecondPost() = runTest {
        val f = Fixture(); f.receiptMissing = true
        try {
            f.repository.restoreSession()
            val imports = assertNotNull(f.repository.captureImports())
            imports.saveImportFiles("batch", f.plan.files)
            imports.prepareImportSubmission("batch", f.plan.request)
            val original = checkNotNull(f.storage.submission)
            f.postFailure = IOException("synthetic response lost after possible commit")
            val failure = assertFailsWith<IOException> { imports.resumeLocalCashImport("batch") }
            assertEquals(f.postFailure?.message, failure.message)
            assertEquals(original.requestBody, f.storage.submission?.requestBody)
            assertEquals("batch", f.storage.submission?.idempotencyKey)
            assertNotNull(imports.importFiles("batch"))
            assertFailsWith<IllegalStateException> { imports.importFunding("batch") }
            f.receiptMissing = false; f.postFailure = null
            assertTrue(imports.resumeLocalCashImport("batch").receipt.committed)
            assertEquals(1, f.paths.count { it == "/api/v1/cash-import/write-set" })
            assertEquals(2, f.paths.count { it == "/api/v1/cash-mutations/receipts/by-key" })
            assertNull(f.storage.submission)
        } finally { f.close() }
    }

    @Test fun realParseAndReceiptRejectionsCloseAndEraseOnlyTheRejectedIdentity() = runTest {
        for (parse in listOf(false, true)) {
            val f = Fixture(offline = true)
            try {
                f.repository.restoreSession()
                val imports = assertNotNull(f.repository.captureImports())
                imports.saveImportFiles("batch", f.plan.files)
                val rejected = if (parse) {
                    assertFailsWith<ApiFailure> { imports.captureImportFiles("pdf", listOf(pdfInput())) }
                } else {
                    imports.prepareImportSubmission("batch", f.plan.request)
                    f.receiptUnauthorized = true
                    assertFailsWith<ApiFailure> { imports.resumeLocalCashImport("batch") }
                }
                assertEquals(401, rejected.status); assertEquals("original-token", rejected.rejectedToken)
                assertNull(f.repository.captureImports()); assertNull(f.repository.localReader())
                assertTrue(f.storage.files.isEmpty()); assertTrue(f.storage.erased)
                assertEquals(1, f.erasedScopes.size)
                assertTrue(f.directory.scopes(f.origin).isEmpty())
            } finally { f.close() }
        }
    }

    @Test fun aSyntheticLocalUnauthorizedFailureDoesNotEraseRecoveryMaterials() = runTest {
        val f = Fixture(offline = true)
        try {
            f.repository.restoreSession()
            val imports = assertNotNull(f.repository.captureImports())
            imports.saveImportFiles("batch", f.plan.files)
            val failure = ApiFailure("authentication_required", 401)
            assertSame(failure, assertFailsWith<ApiFailure> {
                imports.captureImportFiles("local", listOf(localCashImportStatementInput("a") { throw failure }))
            })
            assertTrue(f.erasedScopes.isEmpty()); assertNotNull(imports.importFiles("batch"))
            assertNotNull(f.repository.localReader()?.page())
        } finally { f.close() }
    }

    @Test fun aLateOldHttpRejectionKeepsTheNewRealRestorationReadable() = runTest {
        val f = Fixture(); val entered = CompletableDeferred<Unit>(); val proceed = CompletableDeferred<Unit>()
        try {
            f.repository.restoreSession()
            val original = assertNotNull(f.repository.captureImports())
            f.beforePdf = { entered.complete(Unit); proceed.await() }
            val rejected = async { assertFailsWith<ApiFailure> { original.captureImportFiles("pdf", listOf(pdfInput())) } }
            try {
                entered.await(); f.token = "later-token"; f.repository.restoreSession()
                val current = assertNotNull(f.repository.captureImports())
                current.saveImportFiles("fresh", f.plan.files)
                proceed.complete(Unit)
                assertEquals("original-token", rejected.await().rejectedToken)
                assertEquals("later-token", f.token)
                assertTrue(f.erasedScopes.isEmpty()); assertFalse(f.storage.erased)
                assertNotNull(assertNotNull(f.repository.captureImports()).importFiles("fresh"))
                assertEquals(1, current.importFunding("fresh").first.size)
            } finally { proceed.complete(Unit); rejected.cancelAndJoin() }
        } finally { f.close() }
    }

    @Test fun aKnownRejectedTokenCannotBeRepublishedByItsAlreadyRunningRestoration() = runTest {
        for (fault in listOf("none", "read", "clear")) {
            val f = Fixture()
            val pdfEntered = CompletableDeferred<Unit>(); val pdfProceed = CompletableDeferred<Unit>()
            val authEntered = CompletableDeferred<Unit>(); val authProceed = CompletableDeferred<Unit>()
            try {
                f.repository.restoreSession()
                val original = assertNotNull(f.repository.captureImports())
                val storeFailure = IOException("synthetic credential $fault failure")
                f.beforePdf = {
                    pdfEntered.complete(Unit); pdfProceed.await()
                    if (fault == "read") f.tokenReadFailure = storeFailure
                    if (fault == "clear") f.tokenClearFailure = storeFailure
                }
                val rejected = async { assertFailsWith<ApiFailure> { original.captureImportFiles("pdf", listOf(pdfInput())) } }
                try {
                    pdfEntered.await()
                    f.beforeSession = { authEntered.complete(Unit); authProceed.await() }
                    val restoring = async { runCatching { f.repository.restoreSession() } }
                    try {
                        authEntered.await(); pdfProceed.complete(Unit)
                        val failure = rejected.await()
                        assertEquals("original-token", failure.rejectedToken)
                        assertEquals(401, failure.status)
                        if (fault == "none") assertNull(f.token)
                        else assertTrue(failure.suppressedExceptions.any {
                            it is ApiFailure && it.code == "token_store_unavailable" && it.cause?.message == storeFailure.message
                        })
                        assertTrue(f.storage.erased)
                        // Clear the injected fault before releasing authentication: the permanent close must fence it.
                        f.tokenReadFailure = null; f.tokenClearFailure = null
                        authProceed.complete(Unit)
                        assertIs<CancellationException>(restoring.await().exceptionOrNull())
                        assertNull(f.repository.captureImports()); assertTrue(f.storage.erased)
                    } finally { authProceed.complete(Unit); restoring.cancelAndJoin() }
                } finally { pdfProceed.complete(Unit); rejected.cancelAndJoin() }
            } finally { authProceed.complete(Unit); f.close() }
        }
    }

    @Test fun aRejectedPendingOwnerReleasesItsFailedCloseBeforeRetryingTokenEviction() = runTest {
        for (fault in listOf(IllegalStateException("synthetic close pending"), CancellationException("synthetic close cancellation"))) {
            val f = Fixture(); f.enforceLease = true
            val pdfEntered = CompletableDeferred<Unit>(); val pdfProceed = CompletableDeferred<Unit>()
            val snapshotEntered = CompletableDeferred<Unit>(); val snapshotProceed = CompletableDeferred<Unit>()
            try {
                f.repository.restoreSession()
                val original = assertNotNull(f.repository.captureImports())
                f.beforePdf = { pdfEntered.complete(Unit); pdfProceed.await() }
                val rejected = async { assertFailsWith<ApiFailure> { original.captureImportFiles("pdf", listOf(pdfInput())) } }
                try {
                    pdfEntered.await()
                    f.current = f.plan.source.copy(ledgerSeq = "9")
                    f.beforeSnapshot = { snapshotEntered.complete(Unit); snapshotProceed.await() }
                    val restoring = async { runCatching { f.repository.restoreSession() } }
                    try {
                        snapshotEntered.await()
                        assertTrue(f.leaseHeld); f.closeFailures = 2; f.closeFault = fault
                        pdfProceed.complete(Unit)
                        val failure = rejected.await()
                        assertEquals(401, failure.status)
                        assertTrue(failure.suppressedExceptions.any { it::class == fault::class && it.message == fault.message })
                        assertNull(f.token)
                        assertTrue(f.busyProbes > 0); assertTrue(f.leaseHeld)
                        assertFalse(f.storage.erased); assertNull(f.repository.captureImports())
                        snapshotProceed.complete(Unit)
                        assertIs<CancellationException>(restoring.await().exceptionOrNull())
                        assertFalse(f.leaseHeld); assertTrue(f.storage.erased)
                        assertNull(f.repository.captureImports())
                        assertTrue(f.directory.scopes(f.origin).isEmpty())
                    } finally { snapshotProceed.complete(Unit); restoring.cancelAndJoin() }
                } finally { pdfProceed.complete(Unit); rejected.cancelAndJoin() }
            } finally { snapshotProceed.complete(Unit); f.close() }
        }
    }

    @Test fun aRejectionDuringAsyncOwnerCreationPreventsTheCandidateFromStarting() = runTest {
        val f = Fixture()
        val pdfEntered = CompletableDeferred<Unit>(); val pdfProceed = CompletableDeferred<Unit>()
        val createEntered = CompletableDeferred<Unit>(); val createProceed = CompletableDeferred<Unit>()
        try {
            f.repository.restoreSession()
            val original = assertNotNull(f.repository.captureImports())
            f.beforePdf = { pdfEntered.complete(Unit); pdfProceed.await() }
            val rejected = async { assertFailsWith<ApiFailure> { original.captureImportFiles("pdf", listOf(pdfInput())) } }
            try {
                pdfEntered.await()
                f.beforeCreate = { createEntered.complete(Unit); createProceed.await() }
                val restoring = async { runCatching { f.repository.restoreSession() } }
                try {
                    createEntered.await(); pdfProceed.complete(Unit)
                    assertEquals(401, rejected.await().status)
                    createProceed.complete(Unit)
                    val failure = assertIs<com.finance.tracker.domain.DomainFailure>(restoring.await().exceptionOrNull())
                    assertEquals("session_superseded", failure.code)
                    assertEquals(1, f.paths.count { it == "/api/v1/auth/session" })
                    assertNull(f.repository.captureImports()); assertTrue(f.storage.erased)
                } finally { createProceed.complete(Unit); restoring.cancelAndJoin() }
            } finally { pdfProceed.complete(Unit); rejected.cancelAndJoin() }
        } finally { createProceed.complete(Unit); f.close() }
    }

    private fun pdfInput(): CashImportUploadInput {
        val bytes = "%PDF-1.4\n%%EOF".encodeToByteArray()
        return CashImportUploadInput("pdf", object : BoundedFileSource {
            override val name = "statement.pdf"
            override val mediaType = "application/pdf"
            override val size = bytes.size.toLong()
            override suspend fun read() = bytes.copyOf()
            override suspend fun readBounded(maxBytes: Int) = bytes.copyOf()
        })
    }

    private class Fixture(val offline: Boolean = false) {
        val origin = "https://session-imports.example.test"
        val session = SessionDto(UserDto("synthetic@example.test"), "workspace",
            listOf(WorkspaceDto("workspace", "Synthetic", Role.EDITOR)))
        val plan = LocalCashImportRebaseFixture(includeRefund = true)
        var current = plan.source
        var online = session
        var token: String? = "original-token"
        var versionFailure: Throwable? = null
        var tokenReadFailure: Throwable? = null
        var tokenClearFailure: Throwable? = null
        var beforeCreate: (suspend () -> Unit)? = null
        var beforeSnapshot: (suspend () -> Unit)? = null
        var enforceLease = false
        var leaseHeld = false
        var closeFailures = 0
        var closeFault: Throwable? = null
        var busyProbes = 0
        var beforeSession: (suspend () -> Unit)? = null
        var opens = 0
        var receiptMissing = false
        var postFailure: Throwable? = null
        var receiptUnauthorized = false
        var beforePdf: (suspend () -> Unit)? = null
        val erasedScopes = mutableListOf<LedgerCacheScope>()
        var beforeReceipt: (suspend () -> Unit)? = null
        val paths = mutableListOf<String>()
        val storage = Storage(plan.source, session)
        val directory = LedgerSessionDirectory(object : LedgerSessionLocatorStorage {
            var value: String? = null
            override suspend fun read(key: String) = value
            override suspend fun compareAndSet(key: String, expected: String?, replacement: String): Boolean {
                if (value != expected) return false
                value = replacement; return true
            }
        })
        val api = FinanceApiClient({ origin }, object : TokenStore {
            override suspend fun get(): String? { tokenReadFailure?.let { throw it }; return token }
            override suspend fun set(value: String) { token = value }
            override suspend fun clear() { tokenClearFailure?.let { throw it }; token = null }
        }, MockEngine { request ->
            paths += request.url.encodedPath
            assertEquals("Bearer $token", request.headers[HttpHeaders.Authorization])
            val body = when (request.url.encodedPath) {
                "/api/v1/auth/session" -> {
                    beforeSession?.invoke()
                    if (offline) throw IOException("synthetic disconnected authentication")
                    Json.encodeToString(online)
                }
                "/api/v1/ledger/version" -> {
                    versionFailure?.let { throw it }
                    Json.encodeToString(LedgerVersionDto(current.ledgerSeq))
                }
                "/api/v1/ledger/snapshot" -> { beforeSnapshot?.invoke(); Json.encodeToString(current) }
                "/api/v1/cash-mutations/receipts/by-key" -> {
                    beforeReceipt?.invoke()
                    if (receiptUnauthorized) return@MockEngine respond("{\"error\":{\"code\":\"authentication_required\"}}", HttpStatusCode.Unauthorized,
                        headersOf(HttpHeaders.ContentType, "application/json"))
                    assertEquals("workspace", request.url.parameters["workspace_id"])
                    assertEquals("batch", request.url.parameters["key"])
                    if (receiptMissing) return@MockEngine respond("{\"error\":{\"code\":\"receipt_not_found\"}}", HttpStatusCode.NotFound,
                        headersOf(HttpHeaders.ContentType, "application/json"))
                    Json.encodeToString(localCashWriteSetReceiptFixture().copy(ledgerSeq = current.ledgerSeq))
                }
                "/api/v1/cash-import/pdf/parse" -> {
                    beforePdf?.invoke()
                    return@MockEngine respond("{\"error\":{\"code\":\"authentication_required\"}}", HttpStatusCode.Unauthorized,
                        headersOf(HttpHeaders.ContentType, "application/json"))
                }
                "/api/v1/cash-import/write-set" -> {
                    assertEquals("workspace", request.url.parameters["workspace_id"])
                    assertEquals("batch", request.headers["Idempotency-Key"])
                    assertEquals(checkNotNull(storage.submission).requestBody,
                        (request.body as io.ktor.http.content.TextContent).text)
                    postFailure?.let { throw it }
                    Json.encodeToString(localCashWriteSetReceiptFixture().copy(ledgerSeq = current.ledgerSeq))
                }
                else -> error("Unexpected HTTP: ${request.url}")
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val erasure: (LedgerCacheScope, LedgerSessionDirectory, LedgerSessionTicket) -> LedgerCacheErasure = { scope, directory, ticket ->
            LedgerCacheErasureOwner(acquire = { Unit }, beforeErasure = ledgerCacheErasureCheck(scope, directory, ticket), erase = {
                storage.erased = true; storage.files.clear(); storage.reviews.clear(); storage.submission = null
                directory.forget(scope); erasedScopes += scope
            }, release = {})
        }
        init { storage.onClear = { scope -> directory.forget(scope); erasedScopes += scope } }
        val operations = mutableListOf<LedgerSessionRestoration>()
        val repository = SessionCacheRepository(KtorSessionRepository(api), origin,
            captureCredentials = { api.captureCredentials() },
            clearRejectedToken = { api.clearTokenIfCurrent(it) },
            directory = { directory },
            createErasure = erasure,
            openCache = { _, _, _ -> openStorage() },
            createRestoration = { directory, ticket, credentials ->
                beforeCreate?.invoke()
                if (offline) {
                    val scope = LedgerCacheScope(origin, ledgerOfflinePrincipal(session.user.email), "workspace")
                    directory.register(ticket, scope); directory.publish(ticket, scope)
                }
                LedgerSessionRestoration(api, credentials, directory, ticket, createErasure = erasure) { _, _, registration ->
                    opens++
                    val cache = openStorage()
                    try { registration.beforeOpen(); cache }
                    catch (failure: Throwable) { cache.close(); throw failure }
                }.also { operations += it }
            })
        private fun openStorage(): LedgerStorage {
            if (!enforceLease) return storage
            if (leaseHeld) { busyProbes++; error("ledger_cache_in_use") }
            leaseHeld = true
            var released = false
            fun release() { if (!released) { released = true; leaseHeld = false } }
            return object : LedgerStorage by storage {
                override suspend fun close() {
                    if (!released && closeFailures > 0) { closeFailures--; throw closeFault ?: error("synthetic close pending") }
                    storage.close(); release()
                }
                override suspend fun clear(scope: LedgerCacheScope) { storage.clear(scope); release() }
            }
        }
        suspend fun close() { closeFailures = 0; operations.forEach { it.close() }; api.close() }
    }

    private class Storage(var facts: LedgerSnapshotDto, var session: SessionDto) : LedgerStorage {
        val files = mutableMapOf<String, List<LocalCashImportFile>>()
        val reviews = mutableMapOf<String, com.finance.tracker.business.domain.LocalCashImportReview>()
        var submission: LocalCashImportSubmission? = null
        var importReads = 0
        var erased = false
        var boundToken = "original-token"
        var onClear: (suspend (LedgerCacheScope) -> Unit)? = null
        var afterImportRead: (suspend () -> Unit)? = null
        override val imports = object : LedgerImportBatches {
            override suspend fun list() = files.keys.toList().also { importReads++ }
            override suspend fun read(batchId: String): List<LocalCashImportFile>? {
                importReads++; val result = files[batchId]; afterImportRead?.invoke(); return result
            }
            override suspend fun save(batchId: String, files: List<LocalCashImportFile>) { this@Storage.files[batchId] = files }
            override suspend fun remove(batchId: String) { files.remove(batchId); reviews.remove(batchId) }
            override suspend fun readReview(batchId: String) = reviews[batchId]
            override suspend fun saveReview(batchId: String, input: com.finance.tracker.business.domain.LocalCashImportReview) { reviews[batchId] = input }
            override suspend fun readSubmission(batchId: String) = submission
            override suspend fun prepareSubmission(batchId: String, input: LocalCashImportSubmission) { submission = input }
            override suspend fun recordReceipt(batchId: String, receipt: CashMutationReceiptDto) { submission = checkNotNull(submission).withReceipt(receipt) }
            override suspend fun completeSubmission(batchId: String) { files.remove(batchId); reviews.remove(batchId); submission = null }
            override suspend fun recordBaseRejection(batchId: String, expected: LocalCashImportSubmission) = error("No rejection in fixture")
            override suspend fun retireRejectedSubmission(batchId: String, expected: LocalCashImportSubmission, newerBase: String, replacement: LocalCashImportSubmission?) = error("No rebase in fixture")
        }
        override suspend fun read(scope: LedgerCacheScope) = facts
        override suspend fun replace(scope: LedgerCacheScope, snapshot: LedgerSnapshotDto) { facts = snapshot }
        override suspend fun readOfflineSession(scope: LedgerCacheScope, token: String) = session.takeIf { !erased && token == boundToken }
        override suspend fun saveOfflineSession(scope: LedgerCacheScope, token: String, session: SessionDto) { this.session = session; boundToken = token; erased = false }
        override suspend fun close() {}
        override suspend fun clear(scope: LedgerCacheScope) {
            erased = true; files.clear(); reviews.clear(); submission = null; onClear?.invoke(scope)
        }
    }
}
