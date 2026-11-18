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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.io.IOException
import kotlinx.serialization.json.*
import kotlin.test.*

class LedgerSessionImportsTest {
    @Test fun aLocalFileReadFailureDoesNotCloseTheOnlineWriteQualification() = runTest {
        val f = Fixture()
        try {
            f.repository.restoreSession()
            val imports = assertNotNull(f.repository.captureImports())
            imports.saveImportFiles("batch", f.plan.files)
            val calculation = imports.calculateImport("batch", f.plan.input)
            val lost = IOException("synthetic local file unavailable")
            val failure = assertFailsWith<IOException> {
                imports.parseImportFile(localCashImportStatementInput("unreadable") { throw lost })
            }
            assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it === lost })
            assertFalse(f.operations.last().offline.value)
            imports.prepareImportSubmission("batch", calculation.request)
            assertEquals(calculation.request, assertNotNull(imports.importSubmission("batch")).request())
        } finally { f.close() }
    }

    @Test fun aParseOnlyTransportFailureClosesTheOriginalWriteQualification() = runTest {
        val f = Fixture()
        try {
            f.repository.restoreSession()
            val imports = assertNotNull(f.repository.captureImports())
            imports.saveImportFiles("batch", f.plan.files)
            val calculation = imports.calculateImport("batch", f.plan.input)
            f.beforePdf = { throw IOException("synthetic PDF transport unavailable") }
            assertFailsWith<LocalCashImportParseTransportFailure> { imports.parseImportFile(pdfInput()) }
            assertTrue(f.operations.last().offline.value)
            val before = f.paths.toList()
            assertEquals("network_unavailable", assertFailsWith<com.finance.tracker.domain.DomainFailure> {
                imports.prepareImportSubmission("batch", calculation.request)
            }.code)
            assertNull(imports.importSubmission("batch")); assertNotNull(imports.importFiles("batch"))
            assertEquals(before, f.paths)
        } finally { f.close() }
    }

    @Test fun anOnlineImportAccountTransportFailureBlocksNewConfirmationUntilVerifiedAgain() = runTest {
        val f = Fixture()
        try {
            f.repository.restoreSession()
            val imports = assertNotNull(f.repository.captureImports())
            imports.saveImportFiles("batch", f.plan.files)
            val calculation = imports.calculateImport("batch", f.plan.input)
            f.accountFailure = IOException("synthetic account transport failure")
            assertFailsWith<IOException> { imports.createAccount("new", "cash", listOf("CNY"), "key") }
            val before = f.paths.toList()
            val failure = assertFailsWith<com.finance.tracker.domain.DomainFailure> { imports.prepareImportSubmission("batch", calculation.request) }
            assertEquals("network_unavailable", failure.code); assertNull(failure.commitState)
            assertNull(imports.importSubmission("batch")); assertEquals(before, f.paths)
            assertTrue(imports.accounts().isNotEmpty())
            f.accountFailure = null
            f.repository.session()
            assertFailsWith<CancellationException> { imports.prepareImportSubmission("batch", calculation.request) }
            val verified = assertNotNull(f.repository.captureImports())
            verified.prepareImportSubmission("batch", calculation.request)
            assertEquals(calculation.request, assertNotNull(verified.importSubmission("batch")).request())
        } finally { f.close() }
    }

    @Test fun offlineImportKeepsPreparationAndReviewButCannotPersistANewSubmission() = runTest {
        val f = Fixture(offline = true)
        try {
            f.repository.restoreSession()
            val imports = assertNotNull(f.repository.captureImports())
            imports.saveImportFiles("batch", f.plan.files)
            val calculation = imports.calculateImport("batch", f.plan.input)
            val before = f.paths.toList()
            val failure = assertFailsWith<com.finance.tracker.domain.DomainFailure> {
                imports.prepareImportSubmission("batch", calculation.request)
            }
            assertEquals("network_unavailable", failure.code)
            assertNull(failure.commitState)
            assertNull(imports.importSubmission("batch"))
            assertEquals(f.plan.files.map { it.encode() }, assertNotNull(imports.importFiles("batch")).map { it.encode() })
            assertEquals(before, f.paths)
            assertTrue(imports.accounts().isNotEmpty())
        } finally { f.close() }
    }

    @Test fun offlineAccountMutationsDoNotSendRequestsOrDiscardPreparation() = runTest {
        val f = Fixture(offline = true)
        try {
            f.repository.restoreSession()
            val imports = assertNotNull(f.repository.captureImports())
            imports.saveImportFiles("batch", f.plan.files)
            val account = imports.selectionAccount(imports.accounts().first().id)
            val before = f.paths.toList()
            val writes: List<suspend () -> Any?> = listOf(
                { imports.createAccount("new", "cash", listOf("CNY"), "new-key") },
                { imports.updateAccount(account.accountId, account.accountRevision, buildJsonObject { put("name", "changed") }, "edit-key") },
                { imports.deleteAccount(account.accountId, account.accountRevision, "delete-key") },
                { imports.ensureAccountCurrencies(listOf(ImportCurrencyRequirementDto("currency-key", account.accountId, account.accountRevision, listOf("USD")))) },
            )
            for (write in writes) {
                val failure = assertFailsWith<com.finance.tracker.domain.DomainFailure> { write() }
                assertEquals("network_unavailable", failure.code)
                assertNull(failure.commitState)
            }
            assertEquals(before, f.paths)
            assertNotNull(imports.importFiles("batch"))
        } finally { f.close() }
    }

    @Test fun offlineReopeningRetainsAnOnlineFrozenOrUnknownSubmissionWithoutClaimingItWasNotCommitted() = runTest {
        for (sent in listOf(false, true)) {
            val f = Fixture()
            try {
                f.repository.restoreSession()
                val original = assertNotNull(f.repository.captureImports())
                original.saveImportFiles("batch", f.plan.files)
                original.prepareImportSubmission("batch", f.plan.request)
                if (sent) {
                    f.receiptMissing = true; f.postFailure = IOException("synthetic response lost")
                    assertFailsWith<IOException> { original.resumeLocalCashImport("batch") }
                }
                val frozen = assertNotNull(f.storage.submission).encode()
                f.offline = true; f.repository.restoreSession()
                val reopened = assertNotNull(f.repository.captureImports())
                val before = f.paths.toList()
                val failure = assertFailsWith<com.finance.tracker.domain.DomainFailure> { reopened.resumeLocalCashImport("batch") }
                assertEquals("network_unavailable", failure.code); assertNull(failure.commitState)
                assertEquals(frozen, assertNotNull(reopened.importSubmission("batch")).encode())
                assertEquals(f.plan.files.map { it.encode() }, assertNotNull(reopened.importFiles("batch")).map { it.encode() })
                assertEquals(before, f.paths)
                assertTrue(reopened.accounts().isNotEmpty())
            } finally { f.close() }
        }
    }

    @Test fun offlineReopeningCanFinishAKnownSuccessfulOriginalWithoutSendingAnotherWrite() = runTest {
        val f = Fixture()
        try {
            f.repository.restoreSession()
            val original = assertNotNull(f.repository.captureImports())
            original.saveImportFiles("batch", f.plan.files)
            original.prepareImportSubmission("batch", f.plan.request)
            f.versionFailure = IOException("synthetic refresh interrupted after authoritative receipt")
            val first = original.resumeLocalCashImport("batch")
            assertTrue(first.receipt.committed); assertNotNull(first.recoveryFailure)
            assertNotNull(f.storage.submission?.receipt())
            f.versionFailure = null; f.offline = true; f.repository.restoreSession()
            val reopened = assertNotNull(f.repository.captureImports())
            val before = f.paths.toList()
            val result = reopened.resumeLocalCashImport("batch")
            assertEquals(first.receipt, result.receipt); assertNull(result.recoveryFailure)
            assertEquals(before.count { it == "/api/v1/cash-import/write-set" }, f.paths.count { it == "/api/v1/cash-import/write-set" })
            assertEquals(before.count { it == "/api/v1/cash-mutations/receipts/by-key" }, f.paths.count { it == "/api/v1/cash-mutations/receipts/by-key" })
            assertNull(reopened.importSubmission("batch")); assertNull(reopened.importFiles("batch"))
        } finally { f.close() }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun reopenedAccountPreparationUsesLocalFactsWithoutASelectedRuntimeSnapshot() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = Fixture(offline = true)
        var viewModel: com.finance.tracker.presentation.ImportAccountViewModel? = null
        try {
            f.repository.restoreSession()
            val lifecycle = LocalCashImportLifecycleRepository(f.repository::captureImports)
            val expected = f.plan.source.facts.accounts.first { it.type in setOf("cash", "loan", "lend") }
            val selected = lifecycle.selectionAccount(expected.id)
            assertTrue(selected.currencies.isNotEmpty())
            val recovery = com.finance.tracker.core.ScopedImportAccountRecovery(
                com.finance.tracker.core.ImportRecoveryScope("https://example.test", "synthetic", "workspace"),
                RecoveryMemoryStorage(),
            )
            val vm = com.finance.tracker.presentation.ImportAccountViewModel(lifecycle, recovery).also { viewModel = it }
            vm.restore(); advanceUntilIdle()
            assertTrue(vm.state.value.ready)
            assertTrue(vm.state.value.currentAccounts.isEmpty())
            f.accountFailure = IOException("synthetic disconnected account request")
            val before = f.paths.toList()
            var ready: Map<String, com.finance.tracker.domain.ImportAccount>? = null
            vm.ensureCurrencies(mapOf(selected.accountId to selected.currencies)) { ready = it }
            advanceUntilIdle()
            assertNull(vm.state.value.internalFailure)
            assertNull(vm.state.value.errorCode)
            assertEquals(selected, assertNotNull(ready)[selected.accountId])
            assertFalse(vm.state.value.needsExitDecision)
            assertEquals(before, f.paths)
        } finally { viewModel?.detach(); f.close(); Dispatchers.resetMain() }
    }

    @Test fun applicationLifecycleSelectsAnExistingAccountWithoutNetwork() = runTest {
        val f = Fixture(offline = true)
        try {
            f.repository.restoreSession()
            val lifecycle = LocalCashImportLifecycleRepository(f.repository::captureImports)
            val expected = f.plan.source.facts.accounts.first { it.type in setOf("cash", "loan", "lend") }
            val before = f.paths.toList()
            val selected = lifecycle.selectionAccount(expected.id)
            assertEquals(expected.id, selected.accountId)
            assertEquals(expected.revision.toLong(), selected.revision)
            assertEquals(expected.name, selected.name)
            assertEquals(expected.active, selected.active)
            assertFalse(selected.deleted)
            assertEquals(false, selected.capabilities["can_delete"])
            assertEquals(before, f.paths)
        } finally { f.close() }
    }

    @Test fun logoutPinsTheOriginalTokenBeforePreparingItsCacheCleanup() = runTest {
        val f = Fixture()
        try {
            f.repository.restoreSession()
            var authorization: String? = null
            f.expectedHttpToken = "original-token"
            f.beforeLocatorWrite = {
                f.beforeLocatorWrite = null
                f.api.writeToken("new-token")
            }
            f.onLogoutRequest = { authorization = it.headers[HttpHeaders.Authorization] }
            f.repository.logout()
            assertEquals("Bearer original-token", authorization)
            assertEquals("new-token", f.token)
        } finally { f.close() }
    }

    @Test fun failedAuthenticationDoesNotRevokeThePreviouslyStoredTokenOrEraseItsFiles() = runTest {
        for (registering in listOf(false, true)) {
            val f = Fixture()
            try {
                f.repository.restoreSession()
                assertNotNull(f.repository.captureImports()).saveImportFiles("retained", f.plan.files)
                f.authenticationStatus = HttpStatusCode.Unauthorized
                val failure = assertFailsWith<ApiFailure> {
                    if (registering) f.repository.register(f.session.user.email, "wrong")
                    else f.repository.login(f.session.user.email, "wrong")
                }
                assertEquals("original-token", f.token)
                assertNull(failure.rejectedToken)
                assertTrue(f.erasedScopes.isEmpty())
                assertNotNull(f.storage.files["retained"])
            } finally { f.close() }
        }
    }

    @Test fun anOlderLogoutCannotCloseOrEraseANewLoginPublishedDuringItsHttpRequest() = runTest {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        try {
            f.repository.restoreSession()
            f.beforeLogout = { entered.complete(Unit); release.await() }
            val signingOut = async { runCatching { f.repository.logout() } }
            entered.await()
            f.authenticationToken = "new-token"
            val session = f.repository.login(f.session.user.email, "synthetic")
            val imports = assertNotNull(f.repository.captureImportsFor(session))
            imports.saveImportFiles("new-batch", f.plan.files)
            release.complete(Unit)
            val failure = signingOut.await().exceptionOrNull()
            assertEquals("sign_out_incomplete", assertIs<com.finance.tracker.domain.DomainFailure>(failure).code)
            assertEquals("new-token", f.token)
            assertNotNull(imports.importFiles("new-batch"))
            assertTrue(imports.accounts().isNotEmpty())
            assertTrue(f.erasedScopes.isEmpty())
        } finally { release.complete(Unit); f.close() }
    }

    @Test fun applicationLifecycleReopensAnAssociatedBatchWhoseFirstReadSavedNoFiles() = runTest {
        val f = Fixture()
        try {
            f.repository.restoreSession()
            val original = LocalCashImportLifecycleRepository(f.repository::captureImports, nextBatchId = { "empty-batch" })
            val file = localCashImportStatementInput("failed") { throw IOException("synthetic read failure") }
            var association: com.finance.tracker.domain.ImportContext? = null
            original.upload(listOf(com.finance.tracker.domain.ImportUpload(file.clientFileRef, file.file))) { association = it }
            val batch = assertNotNull(association).sessionId
            assertNull(f.storage.files[batch])
            val reopened = LocalCashImportLifecycleRepository(f.repository::captureImports)
            val context = reopened.resumeBatch(batch) { error("no submission can have committed") }
            assertEquals(batch, context.sessionId)
            assertEquals(com.finance.tracker.domain.ImportContextOrigin.LOCAL, context.origin)
            assertFalse(context.canPreview)
            assertTrue(context.files.isEmpty())
            assertNull(context.revision); assertNull(context.lastPlanId); assertNull(context.expiresAt)
            assertEquals("cancelled", reopened.cancel(batch).state)
        } finally { f.close() }
    }

    @Test fun applicationLifecycleAssociatesRecoveryBeforeTheFirstFileCanBePersisted() = runTest {
        val f = Fixture()
        try {
            f.repository.restoreSession()
            val lifecycle = LocalCashImportLifecycleRepository(f.repository::captureImports, nextBatchId = { "first-batch" })
            val file = localCashImportStatementInput("first") { localCashImportCsvBytes() }
            var associated: com.finance.tracker.domain.ImportContext? = null
            val writeAcknowledgementLost = IOException("synthetic saved-file acknowledgement lost")
            f.storage.afterFileSave = {
                assertEquals("first-batch", associated?.sessionId)
                throw writeAcknowledgementLost
            }
            val failure = assertFailsWith<com.finance.tracker.domain.DomainFailure> {
                lifecycle.upload(listOf(com.finance.tracker.domain.ImportUpload(file.clientFileRef, file.file))) { associated = it }
            }
            assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it === writeAcknowledgementLost })
            val context = assertNotNull(associated)
            assertEquals(com.finance.tracker.domain.ImportContextOrigin.LOCAL, context.origin)
            assertFalse(context.canPreview)
            f.storage.afterFileSave = null
            val reopened = LocalCashImportLifecycleRepository(f.repository::captureImports)
            assertEquals("first", reopened.context(context.sessionId).files.single().clientFileRef)
        } finally { f.close() }
    }

    @Test fun applicationLifecyclePinsAnUnusedRepositoryToTheExactSessionPublication() = runTest {
        val f = Fixture()
        try {
            val first = f.repository.restoreSession()
            val factory = LocalCashImportLifecycleRepository(f.repository::captureImports,
                captureSessionImports = f.repository::captureImportsFor)
            val unused = factory.forSession(first)
            val next = f.repository.restoreSession()
            assertEquals(first, next)
            assertNotSame(first, next)
            val before = f.paths.toList()
            assertFailsWith<CancellationException> { unused.accounts() }
            assertEquals(before, f.paths)
            assertTrue(factory.forSession(next).accounts().isNotEmpty())
        } finally { f.close() }
    }

    @Test fun applicationLifecycleIsReadyImmediatelyAfterLoginAndWorkspaceSelection() = runTest {
        val f = Fixture()
        try {
            val loggedIn = f.repository.login(f.session.user.email, "synthetic")
            assertNotNull(f.repository.captureImports(), "login must publish the original local owner before returning")
            val original = LocalCashImportLifecycleRepository(f.repository::captureImports).forSession(loggedIn)
            assertTrue(original.accounts().isNotEmpty())
            val selected = f.repository.selectWorkspace("workspace")
            assertNotNull(f.repository.captureImports(), "workspace selection must publish its local owner")
            assertFailsWith<CancellationException> { original.accounts() }
            val current = LocalCashImportLifecycleRepository(f.repository::captureImports).forSession(selected)
            assertTrue(current.accounts().isNotEmpty())
        } finally { f.close() }
    }

    @Test fun applicationLifecycleCannotCancelAPermanentlyCompletedBatchAfterReopen() = runTest {
        val f = Fixture()
        try {
            f.repository.restoreSession()
            val imports = assertNotNull(f.repository.captureImports())
            imports.saveImportFiles("batch", f.plan.files)
            val lifecycle = LocalCashImportLifecycleRepository(f.repository::captureImports, nextPlanId = { "local-plan" })
            val context = lifecycle.context("batch")
            val choices = assertNotNull(CashImportSessionDto("batch", state = "open", files = emptyList(), sourceAccounts = emptyList(),
                lastPreviewInput = f.plan.input, canPreview = true).toDomain().lastChoices)
            val plan = lifecycle.preview("batch", assertNotNull(context.revision), null, choices)
            f.receiptMissing = true; f.current = f.plan.source.copy(ledgerSeq = "9")
            val receipt = lifecycle.confirm(plan.planId, plan.sessionRevision, "batch")
            assertNull(imports.importFiles("batch"))
            val reopened = LocalCashImportLifecycleRepository(f.repository::captureImports)
            val failure = assertFailsWith<com.finance.tracker.domain.DomainFailure> { reopened.cancel("batch") }
            assertEquals("session_committed", failure.code)
            assertEquals("committed", reopened.context("batch").state)
            assertEquals(receipt, reopened.receipt(receipt.receiptId))
        } finally { f.close() }
    }

    @Test fun applicationLifecycleChecksTheExpectedIdentityBeforeItsFirstAction() = runTest {
        val f = Fixture()
        try {
            f.repository.restoreSession()
            val factory = LocalCashImportLifecycleRepository(f.repository::captureImports)
            val expected = f.session.toDomain()
            val before = f.paths.toList()
            val wrongUser = factory.forSession(expected.copy(user = com.finance.tracker.domain.User("another@example.test")))
            val wrongWorkspace = factory.forSession(expected.copy(activeWorkspaceId = "another-workspace"))
            assertFailsWith<CancellationException> { wrongUser.createAccount("Wrong", "cash", listOf("CNY"), "wrong-user-key") }
            assertFailsWith<CancellationException> { wrongWorkspace.accounts() }
            assertEquals(before, f.paths)
            val original = factory.forSession(expected)
            assertTrue(original.accounts().isNotEmpty())
            f.token = "later-token"; f.repository.restoreSession()
            val laterPaths = f.paths.toList()
            assertFailsWith<CancellationException> { original.account("10") }
            assertEquals(laterPaths, f.paths)
            assertTrue(factory.forSession(expected).accounts().isNotEmpty())
        } finally { f.close() }
    }

    @Test fun applicationLifecycleCannotRebindAnOldTaskToTheNextLogin() = runTest {
        val f = Fixture()
        try {
            f.repository.restoreSession()
            val lifecycle = LocalCashImportLifecycleRepository(f.repository::captureImports)
            assertTrue(lifecycle.accounts().isNotEmpty())
            f.token = "later-token"
            f.repository.restoreSession()
            val before = f.paths.toList()
            assertFailsWith<CancellationException> { lifecycle.createAccount("Later", "cash", listOf("CNY"), "key") }
            val file = localCashImportStatementInput("first") { localCashImportCsvBytes() }
            assertFailsWith<CancellationException> { lifecycle.upload(listOf(com.finance.tracker.domain.ImportUpload(file.clientFileRef, file.file))) }
            assertEquals(before, f.paths)
            assertNull(f.storage.submission)
            assertTrue(LocalCashImportLifecycleRepository(f.repository::captureImports).accounts().isNotEmpty())
        } finally { f.close() }
    }

    @Test fun applicationLifecycleKeepsPdfTransportFailureSeparateFromLocalFileReadFailure() = runTest {
        val f = Fixture()
        try {
            f.repository.restoreSession()
            val lifecycle = LocalCashImportLifecycleRepository(f.repository::captureImports, nextBatchId = { "batch" })
            val badFile = localCashImportStatementInput("bad") { throw IOException("synthetic file read failed") }
            val rejected = lifecycle.upload(listOf(com.finance.tracker.domain.ImportUpload(badFile.clientFileRef, badFile.file)))
            assertEquals("import_file_read_failed", rejected.localFailures.single().code)
            assertNull(rejected.interruption)
            val pdf = object : BoundedFileSource {
                override val name = "synthetic.pdf"
                override val mediaType = "application/pdf"
                override val size = 14L
                override val lastModifiedAtMillis: Long? = null
                override suspend fun read() = "%PDF-synthetic".encodeToByteArray()
                override suspend fun readBounded(maxBytes: Int) = read()
            }
            f.beforePdf = { throw IOException("synthetic parse transport failed") }
            val goodFile = localCashImportStatementInput("later") { localCashImportCsvBytes() }
            val interrupted = lifecycle.upload(listOf(com.finance.tracker.domain.ImportUpload("pdf", pdf),
                com.finance.tracker.domain.ImportUpload(goodFile.clientFileRef, goodFile.file)))
            assertTrue(interrupted.localFailures.isEmpty())
            assertEquals("network_unavailable", interrupted.interruption?.code)
            assertEquals(listOf("pdf", "later"), interrupted.interruption?.unprocessedFileRefs)
            assertNull(assertNotNull(f.repository.captureImports()).importFiles("batch"))
        } finally { f.close() }
    }

    @Test fun applicationLifecycleAwaitsEachSavedContextBeforeReadingTheNextFile() = runTest {
        val f = Fixture(); val entered = CompletableDeferred<Unit>(); val proceed = CompletableDeferred<Unit>()
        var firstReads = 0; var secondReads = 0
        try {
            f.repository.restoreSession(); val imports = assertNotNull(f.repository.captureImports())
            val lifecycle = LocalCashImportLifecycleRepository(f.repository::captureImports, nextBatchId = { "batch" })
            val first = localCashImportStatementInput("first") { firstReads++; localCashImportCsvBytes() }
            val second = localCashImportStatementInput("second") { secondReads++; localCashImportCsvBytes() }
            val before = f.paths.toList()
            val pending = async { lifecycle.upload(listOf(
                com.finance.tracker.domain.ImportUpload("first", first.file), com.finance.tracker.domain.ImportUpload("second", second.file))) {
                if (it.files.map { file -> file.clientFileRef } == listOf("first")) { entered.complete(Unit); proceed.await() }
            } }
            try {
                entered.await(); assertEquals(1, firstReads); assertEquals(0, secondReads)
                assertEquals(listOf("first"), assertNotNull(imports.importFiles("batch")).map { it.fileRef })
                proceed.complete(Unit)
                val result = pending.await()
                assertEquals(listOf("first", "second"), result.context!!.files.map { it.clientFileRef })
                assertTrue(result.localFailures.isEmpty()); assertNull(result.interruption)
                assertEquals(1, firstReads); assertEquals(1, secondReads)
                assertEquals(listOf("first", "second"), assertNotNull(imports.importFiles("batch")).map { it.fileRef })
                assertEquals(before, f.paths); assertNull(imports.importSubmission("batch"))
            } finally { proceed.complete(Unit); pending.cancelAndJoin() }
        } finally { proceed.complete(Unit); f.close() }
    }

    @Test fun applicationLifecyclePropagatesProgressSaveFailureAndKeepsOnlyThePreparedFile() = runTest {
        val f = Fixture(); var secondReads = 0
        try {
            f.repository.restoreSession(); val imports = assertNotNull(f.repository.captureImports())
            val lifecycle = LocalCashImportLifecycleRepository(f.repository::captureImports, nextBatchId = { "batch" })
            val first = localCashImportStatementInput("first") { localCashImportCsvBytes() }
            val second = localCashImportStatementInput("second") { secondReads++; localCashImportCsvBytes() }
            val fault = IllegalStateException("synthetic progress save failure"); val before = f.paths.toList()
            assertSame(fault, assertFailsWith<IllegalStateException> { lifecycle.upload(listOf(
                com.finance.tracker.domain.ImportUpload("first", first.file), com.finance.tracker.domain.ImportUpload("second", second.file))) {
                if (it.files.isNotEmpty()) throw fault
            } })
            assertEquals(0, secondReads); assertEquals(before, f.paths)
            assertEquals(listOf("first"), assertNotNull(imports.importFiles("batch")).map { it.fileRef })
            assertNull(imports.importSubmission("batch"))
        } finally { f.close() }
    }

    @Test fun applicationLifecycleContinuesAfterLocalInputFailuresAndNeverSubmitsTheBatch() = runTest {
        for ((kind, code) in listOf("io" to "import_file_read_failed", "oversize" to "import_file_too_large",
            "unbounded" to "import_file_size_unavailable", "settings" to "import_payload_invalid", "unsupported" to "import_file_unsupported_type")) {
            val f = Fixture(); var badReads = 0; var goodReads = 0
            try {
                f.repository.restoreSession(); val imports = assertNotNull(f.repository.captureImports())
                val lifecycle = LocalCashImportLifecycleRepository(f.repository::captureImports, nextBatchId = { "batch" })
                val bad = object : BoundedFileSource {
                    override val name = if (kind == "unsupported") "bad.exe" else "bad.csv"
                    override val mediaType: String? = null
                    override val size: Long? = if (kind == "oversize") 100L * 1024 * 1024 + 1 else null
                    override suspend fun read(): ByteArray = error("unbounded read forbidden")
                    override suspend fun readBounded(maxBytes: Int): ByteArray { badReads++; throw IOException("private local path") }
                }
                val unbounded = object : com.finance.tracker.domain.FileSource {
                    override val name = "bad.csv"
                    override val mediaType: String? = null
                    override val size: Long? = null
                    override suspend fun read(): ByteArray { badReads++; error("unbounded read forbidden") }
                }
                val good = localCashImportStatementInput("good") { goodReads++; localCashImportCsvBytes() }
                val before = f.paths.toList()
                val result = lifecycle.upload(listOf(com.finance.tracker.domain.ImportUpload("bad",
                    if (kind == "unbounded") unbounded else bad, sourceHint = if (kind == "settings") "bad/source" else null),
                    com.finance.tracker.domain.ImportUpload("good", good.file)))
                assertEquals(listOf(com.finance.tracker.domain.ImportLocalFileFailure("bad", code)), result.localFailures)
                assertFalse("private" in result.localFailures.toString()); assertNull(result.interruption)
                assertEquals(listOf("good"), result.context!!.files.map { it.clientFileRef })
                assertEquals(listOf("good"), assertNotNull(imports.importFiles("batch")).map { it.fileRef })
                assertEquals(if (kind == "io") 1 else 0, badReads); assertEquals(1, goodReads)
                assertEquals(before, f.paths); assertNull(imports.importSubmission("batch"))
            } finally { f.close() }
        }
        val f = Fixture()
        try {
            f.repository.restoreSession(); val imports = assertNotNull(f.repository.captureImports())
            val lifecycle = LocalCashImportLifecycleRepository(f.repository::captureImports, nextBatchId = { "batch" })
            val oversized = object : BoundedFileSource {
                override val name = "large.csv"
                override val mediaType: String? = null
                override val size = 100L * 1024 * 1024 + 1
                override suspend fun read(): ByteArray = error("oversized input must not read")
                override suspend fun readBounded(maxBytes: Int): ByteArray = error("oversized input must not read")
            }
            val before = f.paths.toList()
            val result = lifecycle.upload(listOf(com.finance.tracker.domain.ImportUpload("a", oversized), com.finance.tracker.domain.ImportUpload("b", oversized)))
            assertEquals(listOf("a", "b"), result.localFailures.map { it.clientFileRef })
            assertTrue(result.localFailures.all { it.code == "import_file_too_large" })
            assertNull(result.interruption); assertTrue(result.context!!.files.isEmpty())
            assertNull(imports.importFiles("batch")); assertNull(imports.importSubmission("batch")); assertEquals(before, f.paths)
        } finally { f.close() }
    }

    @Test fun applicationLifecycleRejectsInvalidCollectionsBeforeReadingAndPropagatesReadCancellation() = runTest {
        val f = Fixture(); var reads = 0; var callbacks = 0
        try {
            f.repository.restoreSession(); val imports = assertNotNull(f.repository.captureImports())
            val lifecycle = LocalCashImportLifecycleRepository(f.repository::captureImports, nextBatchId = { "batch" })
            fun input(ref: String): com.finance.tracker.domain.ImportUpload {
                val file = localCashImportStatementInput(ref) { reads++; localCashImportCsvBytes() }
                return com.finance.tracker.domain.ImportUpload(ref, file.file)
            }
            val before = f.paths.toList()
            for (files in listOf(emptyList(), List(21) { input("f$it") }, listOf(input("same"), input("same")), listOf(input("bad?")))) {
                assertEquals("import_payload_invalid", assertFailsWith<com.finance.tracker.domain.DomainFailure> {
                    lifecycle.upload(files) { callbacks++ }
                }.code)
            }
            assertEquals(0, reads); assertEquals(0, callbacks); assertNull(imports.importFiles("batch"))
            val fault = CancellationException("synthetic read cancellation")
            val cancelled = localCashImportStatementInput("cancelled") { throw fault }
            val failure = assertFailsWith<CancellationException> { lifecycle.upload(listOf(
                com.finance.tracker.domain.ImportUpload("cancelled", cancelled.file), input("later"))) }
            assertEquals(fault.message, failure.message)
            assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it === fault })
            assertEquals(0, reads); assertNull(imports.importFiles("batch")); assertNull(imports.importSubmission("batch"))
            assertEquals(before, f.paths)
        } finally { f.close() }
    }

    @Test fun applicationLifecycleKeepsTheLatestPreparedContextWhenTheNextFileLosesTransport() = runTest {
        val f = Fixture(); var firstReads = 0; var laterReads = 0
        try {
            f.repository.restoreSession()
            val lifecycle = LocalCashImportLifecycleRepository(f.repository::captureImports, nextBatchId = { "batch" })
            val first = localCashImportStatementInput("first") { firstReads++; localCashImportCsvBytes() }
            val later = localCashImportStatementInput("later") { laterReads++; localCashImportCsvBytes() }
            val pdf = pdfInput()
            val contexts = mutableListOf<com.finance.tracker.domain.ImportContext>()
            f.beforePdf = { throw IOException("synthetic parse transport failed after preparing the first file") }
            val before = f.paths.toList()
            val result = lifecycle.upload(listOf(
                com.finance.tracker.domain.ImportUpload("first", first.file),
                com.finance.tracker.domain.ImportUpload("pdf", pdf.file),
                com.finance.tracker.domain.ImportUpload("later", later.file)), onContext = contexts::add)
            assertEquals(1, firstReads); assertEquals(0, laterReads)
            assertEquals(2, contexts.size); assertTrue(contexts.first().files.isEmpty())
            assertEquals(contexts.last(), result.context)
            assertEquals(listOf("first"), result.context!!.files.map { it.clientFileRef })
            assertEquals(listOf("ready"), result.context!!.files.map { it.status })
            assertTrue(result.localFailures.isEmpty())
            val interruption = assertNotNull(result.interruption)
            assertEquals("network_unavailable", interruption.code); assertEquals(0, interruption.status)
            assertNull(interruption.receiptId); assertEquals(listOf("pdf", "later"), interruption.unprocessedFileRefs)
            val imports = assertNotNull(f.repository.captureImports())
            assertEquals(listOf("first"), assertNotNull(imports.importFiles("batch")).map { it.fileRef })
            assertNull(imports.importSubmission("batch"))
            assertEquals(listOf("/api/v1/cash-import/pdf/parse"), f.paths.drop(before.size))
        } finally { f.close() }
    }

    @Test fun applicationLifecycleRejectsMalformedRemoteIdentityBeforeRefreshingOrPublishingTheReceipt() = runTest {
        val f = Fixture()
        try {
            f.repository.restoreSession()
            val lifecycle = LocalCashImportLifecycleRepository(f.repository::captureImports)
            val raw = Json.encodeToJsonElement(localCashWriteSetReceiptFixture().copy(receiptId = "account-receipt", ledgerSeq = "9")).jsonObject
            f.accountReceiptBody = JsonObject(raw + ("write_set_result" to JsonObject(raw.getValue("write_set_result").jsonObject +
                ("unknown" to JsonPrimitive("not allowed"))))).toString()
            val before = f.paths.toList(); val facts = f.storage.facts
            assertFails { lifecycle.receipt("account-receipt") }
            assertFails { lifecycle.receiptByKey("account-key") }
            assertEquals(listOf("/api/v1/cash-mutations/receipts/account-receipt", "/api/v1/cash-mutations/receipts/by-key"), f.paths.drop(before.size))
            assertEquals(facts, f.storage.facts); assertNotNull(f.repository.localReader())
            assertEquals("original-token", f.token); assertFalse(f.storage.erased)
            assertNull(f.storage.submission)
        } finally { f.close() }
    }

    @Test fun applicationLifecycleAcceptsTheOwnersRealDisplayReceiptWithLocalFileFailure() = runTest {
        val f = Fixture()
        try {
            f.repository.restoreSession(); val imports = assertNotNull(f.repository.captureImports())
            val original = f.plan.files.single().originalInput()
            val failed = LocalCashImportFile.captureFailure(CashImportUploadInput("failed", original.file), byteArrayOf(), "statement_unreadable")
            f.receiptOverride = localCashWriteSetReceiptFixture().copy(summary = mapOf("created_posted" to 2L))
            imports.saveImportFiles("batch", f.plan.files + failed)
            val lifecycle = LocalCashImportLifecycleRepository(f.repository::captureImports, nextPlanId = { "local-plan" })
            val context = lifecycle.context("batch")
            val choices = assertNotNull(CashImportSessionDto("batch", state = "open", files = emptyList(), sourceAccounts = emptyList(),
                lastPreviewInput = f.plan.input, canPreview = true).toDomain().lastChoices)
            val plan = lifecycle.preview("batch", assertNotNull(context.revision), null, choices)
            f.receiptMissing = true; f.current = f.plan.source.copy(ledgerSeq = "9")
            var delivered: com.finance.tracker.domain.ImportReceipt? = null
            val receipt = lifecycle.confirm(com.finance.tracker.domain.ImportFrozenCommand(plan.planId, plan.sessionRevision, "batch")) { delivered = it }
            assertEquals("partial", receipt.status)
            assertEquals(receipt, delivered)
            val archived = assertNotNull(imports.importResult(receipt.receiptId))
            assertNotEquals(archived.authoritativeReceipt().status, archived.displayReceipt().status)
            assertNull(f.storage.submission)
        } finally { f.close() }
    }

    @Test fun applicationLifecycleReadsTheRealLocalPlanAndAllItsViewsWithoutSessionHttp() = runTest {
        val f = Fixture(offline = true)
        try {
            f.repository.restoreSession()
            val imports = assertNotNull(f.repository.captureImports())
            imports.saveImportFiles("batch", f.plan.files)
            val lifecycle = LocalCashImportLifecycleRepository(f.repository::captureImports, nextPlanId = { "local-plan" })
            val context = lifecycle.context("batch")
            val choices = assertNotNull(CashImportSessionDto("batch", state = "open", files = emptyList(), sourceAccounts = emptyList(),
                lastPreviewInput = f.plan.input, canPreview = true).toDomain().lastChoices)
            val before = f.paths.toList()
            val plan = lifecycle.preview("batch", assertNotNull(context.revision), null, choices)
            assertEquals("local-plan", plan.planId)
            assertEquals("batch", plan.sessionId)
            assertNull(plan.expiresAt)
            assertTrue(plan.canCommit)
            assertEquals(plan, lifecycle.plan(plan.planId))
            val page = lifecycle.items(plan.planId, limit = 1, startIndex = 0)
            assertEquals(1, page.items.size)
            assertEquals(plan.items.first(), page.items.first())
            assertEquals(plan.items.first().itemId, lifecycle.item(plan.planId, plan.items.first().itemId).itemId)
            assertEquals(0, lifecycle.processing(plan.planId).items.size)
            assertEquals(before, f.paths)
        } finally { f.close() }
    }

    @Test fun applicationLifecycleFreezesPostsAndArchivesBeforeDeliveryAndCleanup() = runTest {
        val f = Fixture()
        try {
            f.repository.restoreSession(); val imports = assertNotNull(f.repository.captureImports())
            imports.saveImportFiles("batch", f.plan.files)
            val lifecycle = LocalCashImportLifecycleRepository(f.repository::captureImports, nextPlanId = { "local-plan" })
            val context = lifecycle.context("batch")
            val choices = assertNotNull(CashImportSessionDto("batch", state = "open", files = emptyList(), sourceAccounts = emptyList(),
                lastPreviewInput = f.plan.input, canPreview = true).toDomain().lastChoices)
            val plan = lifecycle.preview("batch", assertNotNull(context.revision), null, choices)
            f.receiptMissing = true; f.current = f.plan.source.copy(ledgerSeq = "9")
            var deliveries = 0
            val command = com.finance.tracker.domain.ImportFrozenCommand(plan.planId, plan.sessionRevision, "batch")
            val receipt = lifecycle.confirm(command) { value ->
                deliveries++
                assertNotNull(imports.importResult(value.receiptId))
                assertNotNull(imports.importFiles("batch"))
            }
            assertEquals(1, deliveries)
            assertEquals(1, f.paths.count { it == "/api/v1/cash-import/write-set" })
            assertNull(imports.importFiles("batch")); assertNull(f.storage.submission)
            assertEquals(receipt, lifecycle.receipt(receipt.receiptId))
            assertEquals(receipt, lifecycle.receiptByKey("batch"))
            assertEquals(receipt, lifecycle.confirm(command) { deliveries++ })
            assertEquals(2, deliveries)
            assertEquals(1, f.paths.count { it == "/api/v1/cash-import/write-set" })
            assertTrue(lifecycle.results(receipt.receiptId, com.finance.tracker.domain.ImportResultKind.ROW).items.isNotEmpty())
        } finally { f.close() }
    }

    @Test fun applicationLifecycleReopenRecoversTheOriginalUnknownRequestWithoutAnotherPost() = runTest {
        val f = Fixture()
        try {
            f.repository.restoreSession(); val imports = assertNotNull(f.repository.captureImports())
            imports.saveImportFiles("batch", f.plan.files)
            val lifecycle = LocalCashImportLifecycleRepository(f.repository::captureImports, nextPlanId = { "local-plan" })
            val context = lifecycle.context("batch")
            val choices = assertNotNull(CashImportSessionDto("batch", state = "open", files = emptyList(), sourceAccounts = emptyList(),
                lastPreviewInput = f.plan.input, canPreview = true).toDomain().lastChoices)
            val plan = lifecycle.preview("batch", assertNotNull(context.revision), null, choices)
            f.receiptMissing = true; f.postFailure = IOException("synthetic response lost")
            assertFailsWith<com.finance.tracker.domain.DomainFailure> { lifecycle.confirm(plan.planId, plan.sessionRevision, "batch") }
            val frozen = assertNotNull(f.storage.submission).encode()
            f.receiptMissing = false; f.postFailure = null; f.current = f.plan.source.copy(ledgerSeq = "9")
            f.repository.session()
            assertEquals(frozen, assertNotNull(f.storage.submission).encode())
            val reopened = LocalCashImportLifecycleRepository(f.repository::captureImports)
            var deliveries = 0
            val recovered = reopened.resumeBatch("batch") { assertTrue(it.committed); deliveries++; assertNotNull(f.storage.submission) }
            assertEquals("committed", recovered.state)
            assertEquals(com.finance.tracker.domain.ImportContextOrigin.LOCAL, recovered.origin)
            assertEquals(1, deliveries)
            assertTrue(frozen.contains("batch"))
            assertEquals(1, f.paths.count { it == "/api/v1/cash-import/write-set" })
            assertNull(f.storage.submission)
            assertFailsWith<com.finance.tracker.domain.DomainFailure> { reopened.plan("local-plan") }
        } finally { f.close() }
    }

    @Test fun applicationLifecycleDeliversKnownSuccessWhenArchivingFailsWithoutRepeatingThePost() = runTest {
        val f = Fixture()
        try {
            f.repository.restoreSession(); val imports = assertNotNull(f.repository.captureImports())
            imports.saveImportFiles("batch", f.plan.files)
            val lifecycle = LocalCashImportLifecycleRepository(f.repository::captureImports, nextPlanId = { "local-plan" })
            val context = lifecycle.context("batch")
            val choices = assertNotNull(CashImportSessionDto("batch", state = "open", files = emptyList(), sourceAccounts = emptyList(),
                lastPreviewInput = f.plan.input, canPreview = true).toDomain().lastChoices)
            val plan = lifecycle.preview("batch", assertNotNull(context.revision), null, choices)
            f.receiptMissing = true; f.current = f.plan.source.copy(ledgerSeq = "9")
            val unavailable = IOException("synthetic archive failure")
            f.storage.beforeArchiveFailure = unavailable
            var deliveries = 0
            val failure = assertFailsWith<com.finance.tracker.domain.ImportCompletionFailure> {
                lifecycle.confirm(com.finance.tracker.domain.ImportFrozenCommand(plan.planId, plan.sessionRevision, "batch")) { deliveries++ }
            }
            assertTrue(failure.knownReceipt.committed)
            assertEquals("batch", failure.batchId)
            assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it === unavailable })
            assertEquals(0, deliveries)
            assertNotNull(f.storage.submission?.receipt()); assertNotNull(imports.importFiles("batch"))
            f.storage.beforeArchiveFailure = null
            lifecycle.resumeBatch("batch") { deliveries++ }
            assertEquals(1, deliveries)
            assertEquals(1, f.paths.count { it == "/api/v1/cash-import/write-set" })
            assertNull(f.storage.submission)
        } finally { f.close() }
    }

    @Test fun applicationImportLifecycleUsesLocalCsvAndNeverCreatesAServerSession() = runTest {
        val f = Fixture(offline = true)
        try {
            f.repository.restoreSession()
            val lifecycle: com.finance.tracker.domain.CashImportLifecycleRepository = LocalCashImportLifecycleRepository(f.repository::captureImports)
            val file = localCashImportStatementInput("first") { localCashImportCsvBytes() }
            val before = f.paths.toList()
            val uploaded = lifecycle.upload(listOf(com.finance.tracker.domain.ImportUpload(file.clientFileRef, file.file)))
            val context = assertNotNull(uploaded.context)
            assertEquals(com.finance.tracker.domain.ImportContextOrigin.LOCAL, context.origin)
            assertEquals("ready", context.files.single().status)
            assertTrue(context.canPreview)
            assertEquals(before, f.paths)
        } finally { f.close() }
    }

    @Test fun importerAccountReadKeepsTheOriginalWorkspaceAndRealCapabilities() = runTest {
        val f = Fixture()
        try {
            f.repository.restoreSession()
            val imports = assertNotNull(f.repository.captureImports())
            val account = imports.account("10")
            assertEquals("10", account.accountId)
            assertTrue(account.capabilities.getValue("can_delete"))
        } finally { f.close() }
    }

    @Test fun importerAccountCatalogStaysOfflineAndKeepsInactiveCashLoanAndLendAccounts() = runTest {
        val f = Fixture(offline = true)
        try {
            val base = f.storage.facts.facts.accounts.first()
            f.storage.facts = f.storage.facts.copy(facts = f.storage.facts.facts.copy(accounts = listOf(
                base.copy(id = "13", type = "lend"), base.copy(id = "12", type = "loan"),
                base.copy(id = "11", type = "investment"), base.copy(id = "10", type = "cash", active = false))))
            f.repository.restoreSession(); val imports = assertNotNull(f.repository.captureImports())
            val paths = f.paths.toList()
            val accounts = imports.accounts()
            assertEquals(listOf("10", "12", "13"), accounts.map { it.id })
            assertFalse(accounts.first().active)
            assertEquals(listOf("CNY"), accounts.first().currencies)
            assertEquals(paths, f.paths)
        } finally { f.close() }
    }

    @Test fun importerAccountCommandsAndReceiptsKeepOriginalCredentialsAndRefreshThatCache() = runTest {
        val f = Fixture()
        try {
            f.repository.restoreSession(); val imports = assertNotNull(f.repository.captureImports())
            f.current = f.plan.source.copy(ledgerSeq = "9")
            f.expectedHttpToken = "original-token"; f.apiOrigin = "https://later.example.test"
            assertTrue(imports.account("10").capabilities.getValue("can_delete"))
            assertEquals(true, imports.createAccount("Original", "cash", listOf("CNY"), "account-key").committed)
            assertEquals("9", f.storage.facts.ledgerSeq)
            assertEquals(true, imports.updateAccount("10", 2, buildJsonObject { put("active", false) }, "account-key").committed)
            assertEquals(true, imports.deleteAccount("10", 2, "account-key").committed)
            assertEquals("updated", imports.ensureAccountCurrencies(listOf(ImportCurrencyRequirementDto("account-key", "10", 2, listOf("CNY")))).single().status)
            assertEquals("account_update", imports.accountReceipt("account-receipt").operationKind)
            assertEquals("account-receipt", imports.accountReceiptByKey("account-key").receiptId)
            assertEquals("original-token", f.token); assertFalse(f.storage.erased)
            assertTrue(f.paths.count { it == "/api/v1/ledger/version" } >= 7)
            f.token = "later-token"
            val paths = f.paths.toList()
            assertFailsWith<CancellationException> { imports.account("10") }
            assertEquals(paths, f.paths)
        } finally { f.close() }
    }

    @Test fun importerAccountSuccessSurvivesRefreshFailureAndOriginalReceiptReopensOnlyFreshFacts() = runTest {
        val f = Fixture()
        try {
            f.repository.restoreSession(); val imports = assertNotNull(f.repository.captureImports())
            val fault = IOException("synthetic original cache unavailable")
            f.versionFailure = fault
            assertEquals(true, imports.createAccount("Original", "cash", listOf("CNY"), "account-key").committed)
            assertNull(f.repository.localReader())
            assertEquals("ledger_cache_refresh_required", assertFailsWith<IllegalStateException> { imports.accounts() }.message)
            assertNotNull(imports.account("10")); assertTrue(f.diagnosticTypes.isNotEmpty())
            f.versionFailure = null
            // The authoritative receipt requires 9: merely reading version 7 cannot reopen facts.
            assertTrue(imports.accountReceipt("account-receipt").committed)
            assertFailsWith<IllegalStateException> { imports.accounts() }
            f.current = f.plan.source.copy(ledgerSeq = "9")
            assertTrue(imports.accountReceiptByKey("account-key").committed)
            assertNotNull(f.repository.localReader()); assertNotNull(imports.accounts())
            assertEquals("9", f.storage.facts.ledgerSeq)
        } finally { f.close() }
    }

    @Test fun importerAccountRejectionsUseOriginalCleanupAndNeverClearALaterTokenOn403() = runTest {
        for (status in listOf(401, 403)) {
            val f = Fixture()
            try {
                f.repository.restoreSession(); val imports = assertNotNull(f.repository.captureImports())
                f.expectedHttpToken = "original-token"; f.accountStatus = status
                f.beforeAccount = { f.token = "later-token" }
                val failure = assertFailsWith<ApiFailure> { imports.account("10") }
                assertEquals(status, failure.status)
                assertEquals(status == 401, f.storage.erased)
                assertEquals("later-token", f.token)
                if (status == 403) {
                    f.token = "original-token"
                    assertNotNull(imports.accounts())
                }
            } finally { f.close() }
        }
    }

    @Test fun importerAccountResponseAfterRestorationCannotRefreshOrPublishToTheNewOwner() = runTest {
        val f = Fixture()
        try {
            f.repository.restoreSession(); val imports = assertNotNull(f.repository.captureImports())
            var afterRestoration = 0
            f.beforeAccount = {
                f.beforeAccount = null
                f.repository.restoreSession()
                afterRestoration = f.paths.size
            }
            assertFailsWith<CancellationException> { imports.createAccount("Original", "cash", listOf("CNY"), "account-key") }
            assertEquals(afterRestoration, f.paths.size)
            val next = assertNotNull(f.repository.captureImports())
            assertNotNull(next.accounts()); assertFalse(f.storage.erased)
            assertFailsWith<CancellationException> { imports.accounts() }
        } finally { f.close() }
    }

    @Test fun anOlderAccountRefreshCannotReopenFactsAfterANewerRefreshFails() = runTest {
        val f = Fixture(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        try {
            f.repository.restoreSession(); val imports = assertNotNull(f.repository.captureImports())
            f.current = f.plan.source.copy(ledgerSeq = "9")
            f.beforeSnapshot = { f.beforeSnapshot = null; entered.complete(Unit); release.await() }
            val writing = async { imports.createAccount("Original", "cash", listOf("CNY"), "account-key") }
            try {
                entered.await()
                f.versionFailure = IOException("synthetic newer refresh failed")
                val later = async { f.repository.captureRefresh()(null) }
                yield(); release.complete(Unit)
                assertEquals(true, writing.await().committed); later.await()
                assertNull(f.repository.localReader())
                assertFailsWith<IllegalStateException> { imports.accounts() }
                assertTrue(f.diagnosticTypes.isNotEmpty())
            } finally { release.complete(Unit); writing.cancelAndJoin() }
        } finally { release.complete(Unit); f.close() }
    }

    @Test fun importerAccountTransportFailureAndCancellationDoNotRetryOrErase() = runTest {
        for (fault in listOf(IOException("synthetic response lost"), CancellationException("caller cancelled"))) {
            val f = Fixture()
            try {
                f.repository.restoreSession(); val imports = assertNotNull(f.repository.captureImports())
                f.accountFailure = fault
                val before = f.paths.size
                val failure = assertFails { imports.createAccount("Original", "cash", listOf("CNY"), "account-key") }
                assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it === fault })
                assertEquals(listOf("/api/v1/accounts"), f.paths.drop(before))
                assertFalse(f.storage.erased); assertEquals("original-token", f.token)
            } finally { f.close() }
        }
    }

    @Test fun partialCurrencySuccessAndFailureSurviveAFailedRefreshWithoutResendingSuccess() = runTest {
        val f = Fixture()
        try {
            f.repository.restoreSession(); val imports = assertNotNull(f.repository.captureImports())
            val account = Json.encodeToString(imports.account("10"))
            f.currencyResults = """{"results":[{"account_id":"10","status":"updated","account":$account},
                {"account_id":"11","status":"failed","error":{"code":"account_changed","message":"Synthetic conflict","status":409,"retryable":false}}]}"""
            f.versionFailure = IOException("synthetic post-currency refresh failed")
            val result = imports.ensureAccountCurrencies(listOf(
                ImportCurrencyRequirementDto("account-10", "10", 2, listOf("CNY")),
                ImportCurrencyRequirementDto("account-11", "11", 3, listOf("USD"))))
            assertEquals(listOf("10", "11"), result.map { it.accountId })
            assertEquals(listOf("updated", "failed"), result.map { it.status })
            assertEquals(true, result.first().account?.committed)
            assertNull(result.first().error); assertNull(result.last().account)
            assertEquals("account_changed", result.last().error?.code); assertEquals(409, result.last().error?.status)
            assertFailsWith<IllegalStateException> { imports.accounts() }
            assertNull(f.repository.localReader()); assertTrue(f.diagnosticTypes.isNotEmpty())
            f.versionFailure = null; f.current = f.plan.source.copy(ledgerSeq = "9")
            assertTrue(imports.accountReceipt("account-receipt").committed)
            assertNotNull(imports.accounts()); assertNotNull(f.repository.localReader())
            assertEquals(1, f.paths.count { it.endsWith("currency-requirements") })
        } finally { f.close() }
    }

    @Test fun importerAccountInputsFreezeBeforeTheirPublicationGuardSuspends() = runTest {
        for (operation in listOf("create", "update", "currencies")) {
            val f = Fixture(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            try {
                f.repository.restoreSession(); val original = assertNotNull(f.repository.captureImports())
                val imports = original.guardedBy({ if (!entered.isCompleted) { entered.complete(Unit); release.await() } }, { it() })
                val currencies = mutableListOf("CNY")
                val patch = mutableMapOf<String, JsonElement>("name" to JsonPrimitive("Original"))
                val requirements = mutableListOf(ImportCurrencyRequirementDto("account-key", "10", 2, currencies))
                f.beforeAccount = { request ->
                    val body = (request.body as io.ktor.http.content.TextContent).text
                    assertFalse("Later" in body); assertFalse("USD" in body)
                    if (operation == "currencies") assertEquals(1, Json.parseToJsonElement(body).jsonObject.getValue("items").jsonArray.size)
                }
                val pending = async {
                    when (operation) {
                        "create" -> imports.createAccount("Original", "cash", currencies, "account-key")
                        "update" -> imports.updateAccount("10", 2, JsonObject(patch), "account-key")
                        else -> imports.ensureAccountCurrencies(requirements)
                    }
                }
                try {
                    entered.await(); currencies += "USD"; patch["name"] = JsonPrimitive("Later"); requirements.clear(); release.complete(Unit)
                    pending.await()
                } finally { release.complete(Unit); pending.cancelAndJoin() }
            } finally { release.complete(Unit); f.close() }
        }
    }

    @Test fun filePreparationCombinesKnownFailuresFilesAndSavedChoicesOnTheOriginalOwner() = runTest {
        val f = Fixture()
        try {
            f.repository.restoreSession(); val imports = assertNotNull(f.repository.captureImports())
            imports.saveImportFiles("batch", f.plan.files); imports.saveImportReview("batch", f.plan.review)
            val failed = imports.parseImportFile(localCashImportStatementInput("bad") { ByteArray(0) })
            imports.savePreparedImportFile("batch", failed)
            val view = imports.importPreparation("batch")
            assertEquals(listOf("ready", "failed"), view.files.map { it.status })
            assertEquals(f.plan.review.inputJson(), view.lastPreviewInput)
            assertEquals("10", view.sourceAccounts.single().selection?.accountId)
            assertNull(view.sessionRevision); assertNull(view.expiresAt); assertNull(view.lastPlanId)
            val parsed = imports.parseImportFile(localCashImportStatementInput("bad") { localCashImportCsvBytes() })
            imports.savePreparedImportFile("batch", parsed, failed)
            assertEquals(listOf("ready", "ready"), imports.importPreparation("batch").files.map { it.status })
            imports.prepareImportSubmission("batch", f.plan.request)
            assertFailsWith<IllegalStateException> { imports.savePreparedImportFile("batch", parsed) }
            assertFailsWith<IllegalStateException> { imports.importPreparation("batch") }
            assertEquals(f.plan.request, f.storage.submission?.request())
        } finally { f.close() }
    }

    @Test fun staleFactsBlockAccountPreparationButNotParsingOrSavingAFile() = runTest {
        val f = Fixture()
        try {
            f.repository.restoreSession(); val imports = assertNotNull(f.repository.captureImports())
            f.versionFailure = IOException("synthetic disconnected refresh")
            f.repository.captureRefresh()("9")
            val file = imports.parseImportFile(localCashImportStatementInput("first") { localCashImportCsvBytes() })
            imports.savePreparedImportFile("first_batch", file)
            assertEquals(listOf("first"), imports.importFiles("first_batch")?.map { it.fileRef })
            assertFailsWith<IllegalStateException> { imports.importPreparation("first_batch") }
            f.versionFailure = null; f.current = f.plan.source.copy(ledgerSeq = "9")
            f.repository.captureRefresh()("9")
            assertEquals("ready", imports.importPreparation("first_batch").files.single().status)
        } finally { f.close() }
    }

    @Test fun authenticationRejectedDuringFilePreparationStillClosesTheOriginalSession() = runTest {
        val f = Fixture()
        try {
            f.repository.restoreSession(); val imports = assertNotNull(f.repository.captureImports())
            imports.saveImportFiles("batch", f.plan.files)
            assertEquals(401, assertFailsWith<ApiFailure> { imports.parseImportFile(pdfInput()) }.status)
            assertNull(f.repository.captureImports()); assertNull(f.token); assertTrue(f.storage.erased)
        } finally { f.close() }
    }

    @Test fun theCapturedImporterAppendsReplacesAndRemovesFilesWithoutExtraHttpOrRebinding() = runTest {
        val f = Fixture(); var reads = 0
        try {
            f.repository.restoreSession()
            val imports = assertNotNull(f.repository.captureImports())
            imports.saveImportFiles("batch", f.plan.files)
            val original = f.plan.files.first()
            val requests = f.paths.toList()
            val added = imports.appendImportFiles("batch", listOf(localCashImportStatementInput("added") { reads++; localCashImportCsvBytes() })).single()
            val replacement = imports.replaceImportFile("batch", original,
                localCashImportStatementInput(original.fileRef) { reads++; localCashImportCsvBytes() })
            imports.removeImportFile("batch", added)
            assertEquals(listOf(replacement.encode()) + f.plan.files.drop(1).map { it.encode() }, imports.importFiles("batch")?.map { it.encode() })
            assertEquals(2, reads); assertEquals(requests, f.paths)
            f.token = "later-token"
            assertFailsWith<CancellationException> {
                imports.appendImportFiles("batch", listOf(localCashImportStatementInput("later") { reads++; localCashImportCsvBytes() }))
            }
            assertEquals(2, reads)
        } finally { f.close() }
    }

    @Test fun aFailedAppendDoesNotSaveTheEarlierSuccessfulFileInThatCommand() = runTest {
        val f = Fixture()
        try {
            f.repository.restoreSession(); val imports = assertNotNull(f.repository.captureImports())
            imports.saveImportFiles("batch", f.plan.files)
            val failure = assertFailsWith<com.finance.tracker.business.domain.StatementDocumentFailure> { imports.appendImportFiles("batch", listOf(
                localCashImportStatementInput("good") { localCashImportCsvBytes() },
                localCashImportStatementInput("bad") { ByteArray(0) })) }
            assertEquals("statement_input_invalid", failure.code)
            assertEquals(f.plan.files.map { it.encode() }, imports.importFiles("batch")?.map { it.encode() })
        } finally { f.close() }
    }

    @Test fun aLateAppendOrReplacementCannotWriteIntoTheNextSession() = runTest {
        for (replace in listOf(false, true)) {
            val f = Fixture(); val entered = CompletableDeferred<Unit>(); val proceed = CompletableDeferred<Unit>()
            try {
                f.repository.restoreSession(); val imports = assertNotNull(f.repository.captureImports())
                imports.saveImportFiles("batch", f.plan.files)
                val original = f.plan.files.first()
                val input = localCashImportStatementInput(if (replace) original.fileRef else "added") {
                    entered.complete(Unit); proceed.await(); localCashImportCsvBytes()
                }
                val writing = async { assertFailsWith<CancellationException> {
                    if (replace) imports.replaceImportFile("batch", original, input)
                    else imports.appendImportFiles("batch", listOf(input))
                } }
                try {
                    entered.await(); f.repository.restoreSession(); proceed.complete(Unit); writing.await()
                    assertEquals(f.plan.files.map { it.encode() }, assertNotNull(f.repository.captureImports()).importFiles("batch")?.map { it.encode() })
                } finally { proceed.complete(Unit); writing.cancelAndJoin() }
            } finally { proceed.complete(Unit); f.close() }
        }
    }

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
            var imports = assertNotNull(f.repository.captureImports())
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
            f.repository.session(); imports = assertNotNull(f.repository.captureImports())
            val result = imports.resumeLocalCashImport("batch")
            assertTrue(result.receipt.committed); assertNull(result.recoveryFailure)
            assertEquals(1, f.paths.count { it == "/api/v1/cash-mutations/receipts/by-key" })
            assertFalse(f.paths.any { it.contains("write-set") })
            assertNull(f.storage.submission)
            // A successful recovery refresh restores calculation eligibility after actual session verification.
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
            var imports = assertNotNull(f.repository.captureImports())
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
            f.repository.session(); imports = assertNotNull(f.repository.captureImports())
            assertEquals(original.encode(), assertNotNull(imports.importSubmission("batch")).encode())
            assertTrue(imports.resumeLocalCashImport("batch").receipt.committed)
            assertEquals(1, f.paths.count { it == "/api/v1/cash-import/write-set" })
            assertEquals(2, f.paths.count { it == "/api/v1/cash-mutations/receipts/by-key" })
            assertNull(f.storage.submission)
        } finally { f.close() }
    }

    @Test fun realParseAndReceiptRejectionsCloseAndEraseOnlyTheRejectedIdentity() = runTest {
        for (parse in listOf(false, true)) {
            val f = Fixture(offline = parse)
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

    @Test fun localTaskCombinesActualPreparationPreviewPagesDetailsAndStableChoices() = runTest {
        val f = Fixture(offline = true)
        try {
            f.repository.restoreSession(); val imports = assertNotNull(f.repository.captureImports())
            imports.saveImportFiles("batch", f.plan.files)
            var sequence = 0
            val task = LocalCashImportTask("batch", imports) { "actual_plan_${++sequence}" }
            val paths = f.paths.toList()
            val context = task.context()
            assertEquals(1L, context.sessionRevision); assertNull(context.expiresAt); assertNull(context.lastPlanId)
            val plan = task.preview(1, null, f.plan.input, "UTC")
            assertEquals("actual_plan_1", plan.getValue("plan_id").jsonPrimitive.content)
            assertEquals(2L, plan.getValue("session_revision").jsonPrimitive.long)
            assertEquals(JsonNull, plan["expires_at"])
            val typed = apiJson.decodeFromJsonElement<CashImportPlanDto>(plan).toDomain()
            assertEquals("actual_plan_1", typed.planId); assertEquals("batch", typed.sessionId)
            assertEquals(2L, typed.sessionRevision); assertNull(typed.expiresAt)
            assertEquals(1, typed.items.size)
            assertFalse("operations" in plan); assertFalse("relation_groups" in plan)
            assertEquals(typed, apiJson.decodeFromJsonElement<CashImportPlanDto>(task.plan("actual_plan_1", "UTC")).toDomain())
            val dated = JsonObject(plan + ("expires_at" to JsonPrimitive("2099-01-01T00:00:00Z")))
            assertEquals("2099-01-01T00:00:00Z", apiJson.decodeFromJsonElement<CashImportPlanDto>(dated).toDomain().expiresAt)
            val page = apiJson.decodeFromJsonElement<CashPlanItemPageDto>(task.items("actual_plan_1"))
            assertTrue(page.items.isNotEmpty())
            for (item in page.items) {
                val rawDetail = task.detail("actual_plan_1", item.previewItemId)
                val detail = apiJson.decodeFromJsonElement<CashPlanItemDetailDto>(rawDetail)
                assertEquals(item.previewItemId, detail.previewItemId); assertTrue(detail.relationChoices.isNotEmpty())
                assertEquals(2, rawDetail.getValue("members").jsonArray.size)
                assertEquals(1, rawDetail.getValue("relation_choices").jsonArray.size)
                for (member in rawDetail.getValue("members").jsonArray) {
                    for (component in member.jsonObject.getValue("fields").jsonObject.getValue("components").jsonArray) {
                        assertTrue(component.jsonObject["component_id"] == null || component.jsonObject["component_id"] == JsonNull || component.jsonObject["component_id"]?.jsonPrimitive?.content == "")
                    }
                }
            }
            assertEquals(emptyList(), apiJson.decodeFromJsonElement<CashPlanProcessingPageDto>(task.processing("actual_plan_1")).items)
            assertEquals(f.plan.input, imports.importReview("batch")?.inputJson())
            assertEquals("actual_plan_1", task.context().lastPlanId)
            val relation = task.detail("actual_plan_1", page.items.single().previewItemId).getValue("relation_choices").jsonArray.single().jsonObject
            val defer = JsonObject(f.plan.input + ("relation_choices" to buildJsonArray { add(buildJsonObject {
                put("suggestion_id", relation.getValue("suggestion_id")); put("action", "defer")
            }) }))
            val next = task.preview(2, "actual_plan_1", defer, "UTC")
            assertEquals(3L, next.getValue("session_revision").jsonPrimitive.long)
            assertEquals(2, next.getValue("items").jsonArray.size)
            assertTrue(next.getValue("items").jsonArray.all { it.jsonObject.getValue("confirmation_status").jsonPrimitive.content == "pending" })
            assertEquals("plan_not_found", assertFailsWith<com.finance.tracker.business.domain.CashImportPlanError> { task.plan("actual_plan_1") }.code)
            assertEquals(paths, f.paths)
        } finally { f.close() }
    }

    @Test fun localTaskPublicEntryChoicesKeepTheOldReviewOnInvalidInputAndRestoreExplicitClears() = runTest {
        val f = Fixture(offline = true); var sequence = 0
        try {
            f.repository.restoreSession(); val imports = assertNotNull(f.repository.captureImports())
            imports.saveImportFiles("batch", f.plan.files)
            val task = LocalCashImportTask("batch", imports) { "entry_plan_${++sequence}" }
            val first = task.preview(1, null, f.plan.input, "UTC")
            val item = first.getValue("items").jsonArray.single().jsonObject
            val detail = task.detail("entry_plan_1", item.getValue("preview_item_id").jsonPrimitive.content)
            val reference = detail.getValue("entry").jsonObject.getValue("edit_ref").jsonPrimitive.content
            assertTrue(reference.startsWith("ie_"))
            fun input(ref: String, patch: JsonObject) = JsonObject(f.plan.input + ("entry_choices" to buildJsonArray { add(buildJsonObject {
                put("edit_ref", ref); put("patch", patch)
            }) }))
            val context = task.context(); val review = assertNotNull(imports.importReview("batch")).encode()
            val facts = f.storage.facts; val files = f.storage.files.toMap(); val paths = f.paths.toList()
            var saves = 0; f.storage.afterReviewSave = { saves++ }
            for ((ref, patch, code) in listOf(
                Triple("invented", buildJsonObject { put("note", "unsafe") }, "plan_changed"),
                Triple(reference, buildJsonObject { put("category_id", "missing-category") }, "category_unavailable"),
                Triple(reference, buildJsonObject { put("category_id", "other-workspace-category") }, "category_unavailable"))) {
                assertEquals(code, assertFailsWith<com.finance.tracker.business.domain.CashImportPlanError> {
                    task.preview(2, "entry_plan_1", input(ref, patch), "UTC")
                }.code)
                assertEquals(0, saves); assertEquals(1, sequence)
                assertEquals(context, task.context()); assertEquals(first, task.plan("entry_plan_1", "UTC"))
                assertEquals(review, assertNotNull(imports.importReview("batch")).encode())
                assertEquals(facts, f.storage.facts); assertEquals(files, f.storage.files); assertEquals(paths, f.paths)
                assertNull(f.storage.submission)
            }
            val patch = buildJsonObject { put("note", ""); put("category_id", JsonNull); put("counterparty_account_attrs", JsonArray(emptyList())) }
            val selectedInput = input(reference, patch)
            val partialInput = JsonObject(selectedInput.filterKeys { it != "row_choices" } + ("unused_legacy_field" to JsonPrimitive(true)))
            val selected = task.preview(2, "entry_plan_1", partialInput, "UTC")
            assertEquals(1, saves); assertEquals(2, sequence)
            assertEquals(JsonPrimitive(""), selected.getValue("items").jsonArray.single().jsonObject.getValue("display").jsonObject["note"])
            val stored = assertNotNull(imports.importReview("batch"))
            assertEquals(selectedInput, stored.inputJson())
            assertEquals(selectedInput, com.finance.tracker.business.domain.LocalCashImportReview.decode(stored.encode()).inputJson())
            val restored = LocalCashImportTask("batch", imports) { "restored_entry_plan" }
            val again = restored.preview(1, null, selectedInput, "UTC")
            assertEquals(JsonPrimitive(""), again.getValue("items").jsonArray.single().jsonObject.getValue("display").jsonObject["note"])
            assertEquals(selectedInput, assertNotNull(imports.importReview("batch")).inputJson())
            assertEquals(facts, f.storage.facts); assertEquals(files, f.storage.files); assertEquals(paths, f.paths)
            assertNull(f.storage.submission)
        } finally { f.close() }
    }

    @Test fun localTaskIndependentParsingDoesNotHoldTheTaskLockAndBothAppendsSurvive() = runTest {
        val f = Fixture(); val entered = CompletableDeferred<Unit>(); val proceed = CompletableDeferred<Unit>()
        try {
            f.repository.restoreSession(); val imports = assertNotNull(f.repository.captureImports())
            imports.saveImportFiles("batch", f.plan.files)
            val task = LocalCashImportTask("batch", imports) { "actual_plan" }
            task.preview(1, null, f.plan.input, "UTC")
            val slow = async { task.append(localCashImportStatementInput("slow") { entered.complete(Unit); proceed.await(); localCashImportCsvBytes() }) }
            try {
                entered.await()
                val fast = task.append(localCashImportStatementInput("fast") { localCashImportCsvBytes() })
                assertEquals(listOf("stable", "fast"), fast.files.map { it.fileRef }); assertNull(fast.lastPlanId)
                proceed.complete(Unit)
                val final = slow.await()
                assertEquals(listOf("stable", "fast", "slow"), final.files.map { it.fileRef })
                assertEquals(4L, final.sessionRevision)
                assertEquals("plan_not_found", assertFailsWith<com.finance.tracker.business.domain.CashImportPlanError> { task.plan("actual_plan") }.code)
            } finally { proceed.complete(Unit); slow.cancelAndJoin() }
        } finally { proceed.complete(Unit); f.close() }
    }

    @Test fun localTaskReplacementCannotRestoreAFileRemovedDuringParsing() = runTest {
        val f = Fixture(); val entered = CompletableDeferred<Unit>(); val proceed = CompletableDeferred<Unit>()
        try {
            f.repository.restoreSession(); val imports = assertNotNull(f.repository.captureImports())
            imports.saveImportFiles("batch", f.plan.files)
            val task = LocalCashImportTask("batch", imports) { "actual_plan" }
            val replacing = async { assertFailsWith<com.finance.tracker.business.domain.CashImportPlanError> {
                task.replace("stable", 1, localCashImportStatementInput("stable") { entered.complete(Unit); proceed.await(); localCashImportCsvBytes() })
            } }
            try {
                entered.await(); val removed = task.remove("stable", 1)
                assertEquals(2L, removed.sessionRevision); assertTrue(removed.files.isEmpty())
                proceed.complete(Unit); assertEquals("plan_changed", replacing.await().code)
                assertTrue(assertNotNull(imports.importFiles("batch")).isEmpty())
            } finally { proceed.complete(Unit); replacing.cancelAndJoin() }
        } finally { proceed.complete(Unit); f.close() }
    }

    @Test fun localTaskRejectsStaleRevisionPreviousPlanAndInvalidZoneBeforePublishingReview() = runTest {
        val f = Fixture()
        try {
            f.repository.restoreSession(); val imports = assertNotNull(f.repository.captureImports())
            imports.saveImportFiles("batch", f.plan.files)
            val task = LocalCashImportTask("batch", imports) { "actual_plan" }
            for ((revision, previous, zone, code) in listOf(
                listOf(2L, null, "UTC", "plan_changed"), listOf(1L, "missing", "UTC", "plan_changed"),
                listOf(1L, null, "Not/AZone", "plan_input_invalid"))) {
                assertEquals(code, assertFailsWith<com.finance.tracker.business.domain.CashImportPlanError> {
                    task.preview(revision as Long, previous as String?, f.plan.input, zone as String)
                }.code)
                assertNull(imports.importReview("batch")); assertEquals(1L, task.context().sessionRevision)
            }
        } finally { f.close() }
    }

    @Test fun localTaskFreezesTheReviewedRequestBeforeAnyNetworkAndResumesTheOriginalOperation() = runTest {
        val f = Fixture()
        try {
            f.repository.restoreSession(); val imports = assertNotNull(f.repository.captureImports())
            imports.saveImportFiles("batch", f.plan.files)
            val task = LocalCashImportTask("batch", imports) { "actual_plan" }
            val plan = task.preview(1, null, f.plan.input, "UTC")
            assertTrue(plan.getValue("can_commit").jsonPrimitive.boolean)
            val paths = f.paths.toList()
            task.freeze("actual_plan", 2)
            assertEquals(paths, f.paths)
            assertEquals(f.plan.request, assertNotNull(f.storage.submission).request())
            assertEquals("batch", f.storage.submission?.idempotencyKey)
            val storedCommand = Json.parseToJsonElement(checkNotNull(f.storage.submission).encode()).jsonObject
            val resultInput = assertNotNull(storedCommand["resultInput"]) as JsonPrimitive
            assertTrue(resultInput.isString, "the original local outcomes must survive the first POST and process restart")
            val inputBody = Json.parseToJsonElement(resultInput.content).jsonObject
            assertEquals(1, inputBody.getValue("version").jsonPrimitive.int)
            assertEquals(plan.getValue("file_results").jsonArray.size, inputBody.getValue("files").jsonArray.size)
            assertNotNull(f.storage.reviews["batch"]?.conclusion())
            val restored = LocalCashImportTask("batch", imports) { "other_plan" }
            assertEquals("plan_changed", assertFailsWith<com.finance.tracker.business.domain.CashImportPlanError> {
                restored.append(localCashImportStatementInput("later") { error("must not read a frozen task") })
            }.code)
            f.current = f.plan.source.copy(ledgerSeq = "9")
            val result = restored.resume()
            assertTrue(result.receipt.committed); assertNull(result.recoveryFailure)
            assertNull(f.storage.submission); assertNull(imports.importFiles("batch"))
            val archived = assertNotNull(imports.importResult(result.receipt.receiptId))
            assertEquals(resultInput.content, archived.input.encode())
            assertEquals(result.receipt, archived.displayReceipt())
            val serverRows = Json.parseToJsonElement(archived.encode()).jsonObject["serverItems"]?.jsonArray
            assertEquals("created", serverRows?.single()?.jsonObject?.get("outcome")?.jsonPrimitive?.content,
                "actual permanent row outcomes must survive the original batch cleanup")
        } finally { f.close() }
    }

    @Test fun permanentResultReadFailureRetainsKnownSuccessAndRecoveryDoesNotResubmit() = runTest {
        val f = Fixture()
        try {
            f.repository.restoreSession(); val imports = assertNotNull(f.repository.captureImports())
            imports.saveImportFiles("batch", f.plan.files)
            val task = LocalCashImportTask("batch", imports) { "actual_plan" }
            task.preview(1, null, f.plan.input, "UTC"); task.freeze("actual_plan", 2)
            f.current = f.plan.source.copy(ledgerSeq = "9")
            val lost = IOException("synthetic permanent result read disconnected")
            f.resultFailure = lost
            var deliveries = 0
            val first = task.resume { deliveries++ }
            assertTrue(first.receipt.committed); assertTrue(generateSequence(first.recoveryFailure) { it.cause }.any { it === lost })
            assertNotNull(f.storage.submission?.receipt()); assertNotNull(imports.importFiles("batch"))
            assertNull(imports.importResult("receipt")); assertEquals(0, deliveries)
            f.resultFailure = null
            val second = LocalCashImportTask("batch", imports) { "unused" }.resume { deliveries++ }
            assertNull(second.recoveryFailure); assertEquals(1, deliveries)
            assertEquals(localCashImportServerResultItemsFixture(), imports.importResult("receipt")?.serverItems())
            assertNull(imports.importFiles("batch")); assertNull(f.storage.submission)
            assertEquals(1, f.paths.count { it.endsWith("/by-key") }); assertEquals(0, f.paths.count { it.endsWith("/write-set") })
            assertEquals(2, f.paths.count { it.endsWith("/receipt/rows") })
        } finally { f.close() }
    }

    @Test fun resultArchiveIsReusedWhenRefreshFailsAndLaterRecoveryIsOfflineForResultReads() = runTest {
        val f = Fixture()
        try {
            f.repository.restoreSession(); val imports = assertNotNull(f.repository.captureImports())
            imports.saveImportFiles("batch", f.plan.files)
            val task = LocalCashImportTask("batch", imports) { "actual_plan" }
            task.preview(1, null, f.plan.input, "UTC"); task.freeze("actual_plan", 2)
            f.current = f.plan.source.copy(ledgerSeq = "9")
            f.beforeSnapshot = { throw IOException("synthetic snapshot failed after archive") }
            val first = task.resume()
            assertTrue(first.receipt.committed); assertNotNull(first.recoveryFailure)
            val archived = assertNotNull(imports.importResult("receipt")); assertNotNull(archived.serverItems())
            f.beforeSnapshot = null; f.resultFailure = IOException("must not re-read a complete archive")
            val second = LocalCashImportTask("batch", imports) { "unused" }.resume()
            assertNull(second.recoveryFailure); assertEquals(archived.encode(), imports.importResult("receipt")?.encode())
            assertEquals(1, f.paths.count { it.endsWith("/receipt/rows") })
        } finally { f.close() }
    }

    @Test fun unauthorizedPermanentResultReadErasesOnlyTheRejectedOriginalScope() = runTest {
        val f = Fixture()
        try {
            f.repository.restoreSession(); val imports = assertNotNull(f.repository.captureImports())
            imports.saveImportFiles("batch", f.plan.files)
            val task = LocalCashImportTask("batch", imports) { "actual_plan" }
            task.preview(1, null, f.plan.input, "UTC"); task.freeze("actual_plan", 2)
            f.resultStatus = 401
            f.beforeResults = { assertNotNull(f.storage.submission?.receipt()); f.token = "later-token" }
            val failure = assertFailsWith<ApiFailure> { task.resume() }
            assertEquals(401, failure.status); assertEquals("original-token", failure.rejectedToken)
            assertEquals("later-token", f.token); assertTrue(f.storage.erased)
        } finally { f.close() }
    }

    @Test fun localTaskResponseLossRetainsOriginalRequestAndRecoveryNeverCreatesANewKey() = runTest {
        val f = Fixture()
        try {
            f.repository.restoreSession(); var imports = assertNotNull(f.repository.captureImports())
            imports.saveImportFiles("batch", f.plan.files)
            val task = LocalCashImportTask("batch", imports) { "actual_plan" }
            task.preview(1, null, f.plan.input, "UTC"); task.freeze("actual_plan", 2)
            val original = assertNotNull(f.storage.submission).encode()
            f.receiptMissing = true; f.postFailure = IOException("synthetic response lost")
            assertFailsWith<Exception> { task.resume() }
            assertEquals(original, f.storage.submission?.encode()); assertNotNull(imports.importFiles("batch"))
            assertEquals("actual_plan", task.plan("actual_plan").getValue("plan_id").jsonPrimitive.content)
            assertEquals("plan_changed", assertFailsWith<com.finance.tracker.business.domain.CashImportPlanError> { task.cancel() }.code)
            f.receiptMissing = false; f.postFailure = null; f.current = f.plan.source.copy(ledgerSeq = "9")
            f.repository.session(); imports = assertNotNull(f.repository.captureImports())
            assertEquals(original, assertNotNull(imports.importSubmission("batch")).encode())
            assertTrue(LocalCashImportTask("batch", imports) { "unused" }.resume().receipt.committed)
            assertEquals(1, f.paths.count { it == "/api/v1/cash-import/write-set" })
            assertNull(f.storage.submission)
        } finally { f.close() }
    }

    @Test fun localTaskCachedPlanReadsStillRejectTheSupersededOriginalSession() = runTest {
        val f = Fixture()
        try {
            f.repository.restoreSession(); val imports = assertNotNull(f.repository.captureImports())
            imports.saveImportFiles("batch", f.plan.files)
            val task = LocalCashImportTask("batch", imports) { "actual_plan" }
            val view = task.preview(1, null, f.plan.input, "UTC")
            val item = view.getValue("items").jsonArray.first().jsonObject.getValue("preview_item_id").jsonPrimitive.content
            f.token = "later-token"
            assertFailsWith<CancellationException> { task.plan("actual_plan") }
            assertFailsWith<CancellationException> { task.items("actual_plan") }
            assertFailsWith<CancellationException> { task.detail("actual_plan", item) }
            assertFailsWith<CancellationException> { task.freeze("actual_plan", 2) }
            assertNull(f.storage.submission)
        } finally { f.close() }
    }

    @Test fun localTaskCancellationFencesAnAlreadyParsingAppend() = runTest {
        val f = Fixture(); val entered = CompletableDeferred<Unit>(); val proceed = CompletableDeferred<Unit>()
        try {
            f.repository.restoreSession(); val imports = assertNotNull(f.repository.captureImports())
            imports.saveImportFiles("batch", f.plan.files)
            val task = LocalCashImportTask("batch", imports) { "actual_plan" }
            val append = async { assertFailsWith<com.finance.tracker.business.domain.CashImportPlanError> {
                task.append(localCashImportStatementInput("later") { entered.complete(Unit); proceed.await(); localCashImportCsvBytes() })
            } }
            try {
                entered.await(); val cancelled = task.cancel()
                assertEquals("cancelled", cancelled.state); assertFalse(cancelled.canPreview)
                proceed.complete(Unit); assertEquals("plan_changed", append.await().code)
                assertNull(imports.importFiles("batch"))
            } finally { proceed.complete(Unit); append.cancelAndJoin() }
        } finally { proceed.complete(Unit); f.close() }
    }

    @Test fun localTaskCalculationFailureBeforeReviewSaveKeepsThePreviousPlanAndReview() = runTest {
        val f = Fixture(); var sequence = 0
        try {
            f.repository.restoreSession(); val imports = assertNotNull(f.repository.captureImports())
            imports.saveImportFiles("batch", f.plan.files)
            val task = LocalCashImportTask("batch", imports) { "actual_plan_${++sequence}" }
            val plan = task.preview(1, null, f.plan.input, "UTC")
            val context = task.context(); val review = assertNotNull(imports.importReview("batch")).encode()
            val facts = f.storage.facts; val files = f.storage.files.toMap(); val paths = f.paths.toList()
            var saves = 0; f.storage.afterReviewSave = { saves++ }
            val invalid = JsonObject(f.plan.input + ("row_choices" to buildJsonArray {
                add(buildJsonObject { put("row_ref", "file:r1"); put("action", "invalid") })
            }))
            assertEquals("plan_input_invalid", assertFailsWith<com.finance.tracker.business.domain.CashImportPlanError> {
                task.preview(2, "actual_plan_1", invalid, "UTC")
            }.code)
            assertEquals(0, saves); assertEquals(1, sequence)
            assertEquals(context, task.context()); assertEquals(plan, task.plan("actual_plan_1", "UTC"))
            assertEquals(review, assertNotNull(imports.importReview("batch")).encode())
            assertEquals(facts, f.storage.facts); assertEquals(files, f.storage.files); assertEquals(paths, f.paths)
            assertNull(f.storage.submission)
        } finally { f.close() }
    }

    @Test fun localTaskRejectsManualOverridesWithoutReplacingTheDraftReviewAndCanContinue() = runTest {
        val f = Fixture(); var sequence = 0
        try {
            f.repository.restoreSession(); val imports = assertNotNull(f.repository.captureImports())
            imports.saveImportFiles("batch", f.plan.files)
            val task = LocalCashImportTask("batch", imports) { "actual_plan_${++sequence}" }
            val input = JsonObject(f.plan.input + ("account_selections" to JsonArray(emptyList())))
            val plan = task.preview(1, null, input, "UTC")
            assertEquals(JsonPrimitive(2), plan.getValue("item_counts").jsonObject["pending"])
            assertTrue(plan.getValue("items").jsonArray.all { it.jsonObject["statistics_eligible"] == JsonPrimitive(false) })
            val row = plan.getValue("items").jsonArray.first().jsonObject.getValue("member_refs").jsonArray.first().jsonObject.getValue("row_ref")
            val invalid = JsonObject(input + ("row_choices" to buildJsonArray { add(buildJsonObject {
                put("row_ref", row); put("action", "apply"); put("patch", buildJsonObject { put("manual_overrides", buildJsonObject { put("note", true) }) })
            }) }))
            val context = task.context(); val review = assertNotNull(imports.importReview("batch")).encode()
            val facts = f.storage.facts; val files = f.storage.files.toMap(); val paths = f.paths.toList()
            var saves = 0; f.storage.afterReviewSave = { saves++ }
            assertEquals("plan_input_invalid", assertFailsWith<com.finance.tracker.business.domain.CashImportPlanError> {
                task.preview(2, "actual_plan_1", invalid, "UTC")
            }.code)
            assertEquals(0, saves); assertEquals(1, sequence)
            assertEquals(context, task.context()); assertEquals(plan, task.plan("actual_plan_1", "UTC"))
            assertEquals(review, assertNotNull(imports.importReview("batch")).encode())
            assertEquals(facts, f.storage.facts); assertEquals(files, f.storage.files); assertEquals(paths, f.paths)
            assertNull(f.storage.submission)
            val continued = task.preview(2, "actual_plan_1", input, "UTC")
            assertEquals(JsonPrimitive("actual_plan_2"), continued["plan_id"])
            assertEquals(JsonPrimitive(3), continued["session_revision"])
            assertEquals(JsonPrimitive(2), continued.getValue("summary").jsonObject["create_draft"])
            assertEquals(JsonPrimitive(2), continued.getValue("item_counts").jsonObject["pending"])
            assertEquals(1, saves); assertEquals(paths, f.paths); assertNull(f.storage.submission)
        } finally { f.close() }
    }

    @Test fun localTaskPlanKeepsActualFileOrderStatusAndUnknownCounts() = runTest {
        val f = Fixture()
        try {
            f.repository.restoreSession(); val imports = assertNotNull(f.repository.captureImports())
            val bytes = "synthetic rejected file".encodeToByteArray()
            val input = localCashImportStatementInput("failed") { bytes.copyOf() }
            val failed = LocalCashImportFile.captureFailure(input, bytes, "statement_unreadable")
            imports.saveImportFiles("batch", f.plan.files + failed)
            val task = LocalCashImportTask("batch", imports) { "actual_plan" }
            val paths = f.paths.toList(); val context = task.context()
            val plan = task.preview(1, null, f.plan.input, "UTC")
            val shown = plan.getValue("file_results").jsonArray.map { it.jsonObject }
            assertEquals(listOf("stable", "failed"), context.files.map { it.fileRef })
            assertEquals(listOf("ready", "failed"), context.files.map { it.status })
            assertEquals(listOf(2, null), context.files.map { it.recognizedRowCount })
            assertEquals(context.files.size, shown.size)
            for (index in shown.indices) {
                assertEquals(JsonPrimitive(context.files[index].fileRef), shown[index]["file_ref"])
                assertEquals(JsonPrimitive(context.files[index].status), shown[index]["status"])
                assertEquals(context.files[index].recognizedRowCount?.let(::JsonPrimitive) ?: JsonNull, shown[index]["recognized_row_count"])
            }
            assertEquals(JsonNull, shown[1]["recognized_row_count"])
            assertEquals(paths, f.paths); assertNull(f.storage.submission)
        } finally { f.close() }
    }

    @Test fun localTaskCannotConfirmAnOldPlanWhenSavingTheNextReviewFailsAfterTheWrite() = runTest {
        val f = Fixture(); var sequence = 0
        try {
            f.repository.restoreSession(); val imports = assertNotNull(f.repository.captureImports())
            imports.saveImportFiles("batch", f.plan.files)
            val task = LocalCashImportTask("batch", imports) { "actual_plan_${++sequence}" }
            task.preview(1, null, f.plan.input, "UTC")
            val row = (f.plan.original.first.getValue("operations") as List<*>).first() as Map<*, *>
            val rowRef = (row["row_refs"] as List<*>).first() as String
            val changed = JsonObject(f.plan.input + ("row_choices" to buildJsonArray {
                add(buildJsonObject { put("row_ref", rowRef); put("action", "defer") })
            }))
            val failure = IOException("synthetic save acknowledgement lost")
            f.storage.afterReviewSave = { throw failure }
            assertSame(failure, assertFailsWith<IOException> { task.preview(2, "actual_plan_1", changed, "UTC") })
            f.storage.afterReviewSave = null
            assertEquals(changed, imports.importReview("batch")?.inputJson())
            assertEquals("plan_not_found", assertFailsWith<com.finance.tracker.business.domain.CashImportPlanError> {
                task.freeze("actual_plan_1", 2)
            }.code)
            assertNull(f.storage.submission)
            assertNull(task.context().lastPlanId)
        } finally { f.close() }
    }

    @Test fun localTaskCanCancelBeforeTheFirstFileHasBeenSaved() = runTest {
        val f = Fixture(); val entered = CompletableDeferred<Unit>(); val proceed = CompletableDeferred<Unit>()
        try {
            f.repository.restoreSession(); val imports = assertNotNull(f.repository.captureImports())
            val task = LocalCashImportTask("new_batch", imports) { "unused" }
            val append = async { runCatching {
                task.append(localCashImportStatementInput("first") { entered.complete(Unit); proceed.await(); localCashImportCsvBytes() })
            } }
            try {
                entered.await(); val cancelled = runCatching { task.cancel() }
                proceed.complete(Unit); val late = append.await()
                val context = cancelled.getOrThrow()
                assertEquals("cancelled", context.state); assertTrue(context.files.isEmpty())
                assertNull(context.expiresAt); assertFalse(context.canPreview)
                assertEquals("plan_changed", assertIs<com.finance.tracker.business.domain.CashImportPlanError>(late.exceptionOrNull()).code)
                assertNull(imports.importFiles("new_batch"))
            } finally { proceed.complete(Unit); append.cancelAndJoin() }
        } finally { proceed.complete(Unit); f.close() }
    }

    @Test fun localTaskCancellationNeedsNoReadableFactsOrAccountPreparation() = runTest {
        val f = Fixture()
        try {
            f.repository.restoreSession(); val imports = assertNotNull(f.repository.captureImports())
            imports.saveImportFiles("batch", f.plan.files)
            val task = LocalCashImportTask("batch", imports) { "unused" }
            f.versionFailure = IOException("synthetic stale refresh")
            f.repository.captureRefresh()("9")
            val paths = f.paths.toList()
            val cancelled = task.cancel()
            assertEquals("cancelled", cancelled.state); assertEquals(listOf("stable"), cancelled.files.map { it.fileRef })
            assertFalse(cancelled.canPreview); assertNull(imports.importFiles("batch"))
            assertEquals(paths, f.paths)
        } finally { f.close() }
    }

    @Test fun localTaskCachedPagesAndDetailsDoNotReloadOrDecodeTheWholeOriginalBatch() = runTest {
        val f = Fixture()
        try {
            f.repository.restoreSession(); val imports = assertNotNull(f.repository.captureImports())
            imports.saveImportFiles("batch", f.plan.files)
            val task = LocalCashImportTask("batch", imports) { "actual_plan" }
            val view = task.preview(1, null, f.plan.input, "UTC")
            val item = view.getValue("items").jsonArray.first().jsonObject.getValue("preview_item_id").jsonPrimitive.content
            val before = f.storage.importReads
            repeat(3) {
                task.plan("actual_plan"); task.items("actual_plan"); task.detail("actual_plan", item); task.processing("actual_plan")
            }
            assertEquals(before, f.storage.importReads)
            f.token = "later-token"
            assertFailsWith<CancellationException> { task.items("actual_plan") }
        } finally { f.close() }
    }

    @Test fun originalSessionTaskDeliversTheSuccessfulReceiptBeforeReleasingItsRecoveryMaterials() = runTest {
        val f = Fixture()
        try {
            f.repository.restoreSession(); val imports = assertNotNull(f.repository.captureImports())
            imports.saveImportFiles("batch", f.plan.files)
            val task = LocalCashImportTask("batch", imports) { "actual_plan" }
            task.preview(1, null, f.plan.input, "UTC"); task.freeze("actual_plan", 2)
            f.current = f.plan.source.copy(ledgerSeq = "9")
            var delivered: CashMutationReceiptDto? = null
            val result = task.resume(beforeCompletion = { confirmed ->
                assertEquals(confirmed, f.storage.submission?.receipt()); assertNotNull(imports.importFiles("batch"))
                assertNotNull(imports.importReview("batch")?.conclusion()); delivered = confirmed
            })
            assertEquals(result.receipt, delivered); assertNull(result.recoveryFailure)
            assertNull(f.storage.submission); assertNull(imports.importFiles("batch"))
        } finally { f.close() }
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

    private class Fixture(var offline: Boolean = false) {
        val origin = "https://session-imports.example.test"
        var apiOrigin = origin
        var expectedHttpToken: String? = null
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
        var beforeLogout: (suspend () -> Unit)? = null
        var onLogoutRequest: ((io.ktor.client.request.HttpRequestData) -> Unit)? = null
        var beforeLocatorWrite: (suspend () -> Unit)? = null
        var authenticationStatus: HttpStatusCode? = null
        var authenticationToken = "authenticated-token"
        var opens = 0
        var receiptMissing = false
        var receiptOverride: CashMutationReceiptDto? = null
        var postFailure: Throwable? = null
        var receiptUnauthorized = false
        var beforePdf: (suspend () -> Unit)? = null
        var beforeAccount: (suspend (io.ktor.client.request.HttpRequestData) -> Unit)? = null
        var accountStatus: Int? = null
        var currencyResults: String? = null
        var accountFailure: Throwable? = null
        val diagnosticTypes = mutableListOf<String?>()
        private val accountJson = """{"account_id":"10","name":"Original","type":"cash","active":false,"currencies":["CNY"],"account_revision":2,"deleted":false,"capabilities":{"can_update":true,"can_delete":true},"receipt_id":"account-receipt","committed":true}"""
        var accountReceiptBody: String? = null
        private val accountReceiptJson = """{"receipt_id":"account-receipt","operation_kind":"account_update","committed":true,"created_at":"2026-11-16T00:00:00Z","ledger_seq":"9"}"""
        val erasedScopes = mutableListOf<LedgerCacheScope>()
        var beforeReceipt: (suspend () -> Unit)? = null
        var resultFailure: Throwable? = null
        var resultStatus: Int? = null
        var resultBody: String? = null
        var beforeResults: (suspend () -> Unit)? = null
        val paths = mutableListOf<String>()
        val storage = Storage(plan.source, session)
        val directory = LedgerSessionDirectory(object : LedgerSessionLocatorStorage {
            var value: String? = null
            override suspend fun read(key: String) = value
            override suspend fun compareAndSet(key: String, expected: String?, replacement: String): Boolean {
                beforeLocatorWrite?.invoke()
                if (value != expected) return false
                value = replacement; return true
            }
        })
        val api = FinanceApiClient({ apiOrigin }, object : TokenStore {
            override suspend fun get(): String? { tokenReadFailure?.let { throw it }; return token }
            override suspend fun set(value: String) { token = value }
            override suspend fun clear() { tokenClearFailure?.let { throw it }; token = null }
        }, MockEngine { request ->
            paths += request.url.encodedPath
            assertEquals("Bearer ${expectedHttpToken ?: token}", request.headers[HttpHeaders.Authorization])
            val body = when (request.url.encodedPath) {
                "/api/v1/auth/login", "/api/v1/auth/register" -> {
                    authenticationStatus?.let { return@MockEngine respond("{\"error\":{\"code\":\"invalid_credentials\"}}", it,
                        headersOf(HttpHeaders.ContentType, "application/json")) }
                    Json.encodeToString(com.finance.tracker.business.contracts.AuthResponseDto(authenticationToken, online.user,
                        online.activeWorkspaceId, online.workspaces))
                }
                "/api/v1/auth/logout" -> { onLogoutRequest?.invoke(request); beforeLogout?.invoke(); "{\"ok\":true}" }
                "/api/v1/auth/workspaces/workspace/select" -> Json.encodeToString(online)
                "/api/v1/accounts/by-id/10", "/api/v1/accounts", "/api/v1/cash-import/accounts/currency-requirements",
                "/api/v1/cash-mutations/receipts/account-receipt" -> {
                    assertEquals(listOf("workspace"), request.url.parameters.getAll("workspace_id"))
                    assertEquals("session-imports.example.test", request.url.host)
                    beforeAccount?.invoke(request)
                    accountFailure?.let { throw it }
                    accountStatus?.let { return@MockEngine respond("{\"error\":{\"code\":\"permission_denied\"}}",
                        HttpStatusCode.fromValue(it), headersOf(HttpHeaders.ContentType, "application/json")) }
                    when {
                        request.url.encodedPath.contains("/receipts/") -> accountReceiptBody ?: accountReceiptJson
                        request.url.encodedPath.endsWith("currency-requirements") -> currencyResults ?: """{"results":[{"account_id":"10","status":"updated","account":$accountJson}]}"""
                        else -> accountJson
                    }
                }
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
                    if (request.url.parameters["key"] == "account-key") return@MockEngine respond(accountReceiptBody ?: accountReceiptJson,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    assertEquals("batch", request.url.parameters["key"])
                    if (receiptMissing) return@MockEngine respond("{\"error\":{\"code\":\"receipt_not_found\"}}", HttpStatusCode.NotFound,
                        headersOf(HttpHeaders.ContentType, "application/json"))
                    Json.encodeToString((receiptOverride ?: localCashWriteSetReceiptFixture()).copy(ledgerSeq = current.ledgerSeq))
                }
                "/api/v1/cash-mutations/receipts/receipt/rows" -> {
                    assertEquals(listOf("workspace"), request.url.parameters.getAll("workspace_id"))
                    beforeResults?.invoke()
                    resultFailure?.let { throw it }
                    resultStatus?.let { return@MockEngine respond("broken response", HttpStatusCode.fromValue(it), headersOf(HttpHeaders.ContentType, "application/json")) }
                    resultBody ?: """{"items":[{"result_id":"1","result_kind":"row","item_ref":"row:0","transaction_id":"9007199254740993","outcome":"created"}],"next_after":null,"projection_refs":[],"projection_version":0}"""
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
                    Json.encodeToString((receiptOverride ?: localCashWriteSetReceiptFixture()).copy(ledgerSeq = current.ledgerSeq))
                }
                else -> error("Unexpected HTTP: ${request.url}")
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val erasure: (LedgerCacheScope, LedgerSessionDirectory, LedgerSessionTicket) -> LedgerCacheErasure = { scope, directory, ticket ->
            LedgerCacheErasureOwner(acquire = { Unit }, beforeErasure = ledgerCacheErasureCheck(scope, directory, ticket), erase = {
                storage.erased = true; storage.files.clear(); storage.reviews.clear(); storage.results.clear(); storage.submission = null
                directory.forget(scope); erasedScopes += scope
            }, release = {})
        }
        init { storage.onClear = { scope -> directory.forget(scope); erasedScopes += scope } }
        val operations = mutableListOf<LedgerSessionRestoration>()
        private val remoteSessionRepository = KtorSessionRepository(api)
        val repository = SessionCacheRepository(remoteSessionRepository, origin,
            authenticate = { email, password, registering, owner, ticket ->
                if (registering) api.registerWithCredentials(email, password, owner, ticket)
                else api.loginWithCredentials(email, password, owner, ticket)
            },
            bindSession = remoteSessionRepository::boundTo,
            diagnosticLogger = object : com.finance.tracker.core.DiagnosticLogger {
                override fun record(feature: com.finance.tracker.core.DiagnosticFeature, action: com.finance.tracker.core.DiagnosticAction,
                    errorCode: String?, status: Int?, exceptionType: String?) { diagnosticTypes += exceptionType }
            },
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
        val results = mutableMapOf<String, LocalCashImportResult>()
        var submission: LocalCashImportSubmission? = null
        var importReads = 0
        var erased = false
        var boundToken = "original-token"
        var onClear: (suspend (LedgerCacheScope) -> Unit)? = null
        var afterImportRead: (suspend () -> Unit)? = null
        var afterReviewSave: (suspend () -> Unit)? = null
        var beforeArchiveFailure: Throwable? = null
        var afterFileSave: (suspend () -> Unit)? = null
        override val imports = object : LedgerImportBatches {
            override suspend fun list() = files.keys.toList().also { importReads++ }
            override suspend fun read(batchId: String): List<LocalCashImportFile>? {
                importReads++; val result = files[batchId]; afterImportRead?.invoke(); return result
            }
            override suspend fun save(batchId: String, files: List<LocalCashImportFile>) {
                this@Storage.files[batchId] = files
                afterFileSave?.invoke()
            }
            override suspend fun append(batchId: String, files: List<LocalCashImportFile>) {
                this@Storage.files[batchId] = checkNotNull(this@Storage.files[batchId]) + files
            }
            override suspend fun replaceFile(batchId: String, expected: LocalCashImportFile, replacement: LocalCashImportFile) {
                files[batchId] = checkNotNull(files[batchId]).map { if (it.fileRef == expected.fileRef) replacement else it }
            }
            override suspend fun removeFile(batchId: String, expected: LocalCashImportFile) {
                files[batchId] = checkNotNull(files[batchId]).filter { it.fileRef != expected.fileRef }
            }
            override suspend fun remove(batchId: String) { files.remove(batchId); reviews.remove(batchId) }
            override suspend fun readReview(batchId: String) = reviews[batchId]
            override suspend fun saveReview(batchId: String, input: com.finance.tracker.business.domain.LocalCashImportReview) { reviews[batchId] = input; afterReviewSave?.invoke() }
            override suspend fun readSubmission(batchId: String) = submission
            override suspend fun prepareSubmission(batchId: String, input: LocalCashImportSubmission) { submission = input }
            override suspend fun recordReceipt(batchId: String, receipt: CashMutationReceiptDto) { submission = checkNotNull(submission).withReceipt(receipt) }
            override suspend fun archiveResult(batchId: String, serverItems: List<com.finance.tracker.business.contracts.CashMutationResultDto>?): LocalCashImportResult? {
                beforeArchiveFailure?.let { throw it }
                val result = LocalCashImportResult.from(batchId, checkNotNull(submission), serverItems) ?: return null
                val previous = results[result.authoritativeReceipt().receiptId]
                check(previous == null || previous.encode() == result.encode())
                results[result.authoritativeReceipt().receiptId] = result
                return result
            }
            override suspend fun readResult(receiptId: String) = results[receiptId]
            override suspend fun readResultByBatchId(batchId: String) = results.values.singleOrNull { it.batchId == batchId }
            override suspend fun readResultByKey(key: String) = results.values.singleOrNull { it.submissionKey == key }
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
            erased = true; files.clear(); reviews.clear(); results.clear(); submission = null; onClear?.invoke(scope)
        }
    }
}
