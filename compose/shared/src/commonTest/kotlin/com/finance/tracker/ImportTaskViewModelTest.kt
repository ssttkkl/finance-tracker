package com.finance.tracker

import com.finance.tracker.core.*
import com.finance.tracker.domain.*
import com.finance.tracker.presentation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import androidx.paging.PagingSource
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ImportTaskViewModelTest {
    @Test fun uploadBugStopsQueueAndKeepsOriginalCause() = runTest { task { vm, repo, _ ->
        vm.restore(); advanceUntilIdle()
        val bug = IllegalStateException("mapping bug")
        repo.uploadHandler = { _, _, _ -> throw bug }
        vm.upload((1..6).map { ImportUpload("bug-$it", taskFile()) })
        advanceUntilIdle()
        assertTrue(repo.uploadCalls.size <= 3)
        assertSame(bug, vm.state.value.internalFailure)
        assertTrue(vm.state.value.localFileFailures.isEmpty())
        assertFalse(vm.state.value.canAddFiles)
    } }

    @Test fun uploadRecoveryBugStopsQueueWithoutLosingAcceptedContext() = runTest { task { vm, repo, store ->
        vm.restore(); advanceUntilIdle()
        val bug = IllegalStateException("recovery bug")
        store.beforeSave = { throw bug }
        repo.uploadHandler = { _, _, progress -> progress(repo.current); ImportUploadBatch(repo.current, emptyList(), null) }
        vm.upload((1..6).map { ImportUpload("bug-$it", taskFile()) })
        advanceUntilIdle()
        assertSame(bug, vm.state.value.internalFailure)
        assertEquals(repo.current.sessionId, vm.state.value.context?.sessionId)
        assertTrue(vm.state.value.localFileFailures.isEmpty())
    } }
    @Test fun explicitRestartDiscardsOldEntryTargetBeforeRebuildingTheCurrentPlan() = runTest { task { vm, repo, _ ->
        vm.restore(); advanceUntilIdle(); vm.preview(); advanceUntilIdle()
        vm.loadDetail("one"); advanceUntilIdle()
        vm.editEntry(ImportEntryChoice("ie_whole", StructuredObject(mapOf("note" to StructuredText("old target")))))
        vm.preview(); advanceUntilIdle()
        assertTrue(vm.state.value.choices.entries.isNotEmpty())
        repo.previewFailure = DomainFailure("plan_changed", 409, FailureCategory.RECOVERABLE)
        vm.preview(); advanceUntilIdle()
        assertTrue(vm.state.value.needsContextRefresh)
        repo.previewFailure = null
        vm.restartPreview(); advanceUntilIdle()
        assertTrue(assertNotNull(repo.lastChoices).entries.isEmpty())
        assertTrue(vm.state.value.choices.entries.isEmpty())
        assertTrue(vm.state.value.canConfirm)
    } }

    @Test fun explicitPendingPageFailureBlocksConfirmationUntilItIsLoaded() = runTest { task { vm, repo, _ ->
        repo.pendingCount = 1
        vm.restore(); advanceUntilIdle(); vm.preview(); advanceUntilIdle()
        assertEquals("pending", vm.state.value.filter)
        repo.pageFailure = DomainFailure("request_failed", 0, FailureCategory.RECOVERABLE)
        vm.loadItems("pending"); advanceUntilIdle()
        assertFalse(vm.state.value.itemsInitialized)
        assertFalse(vm.state.value.canConfirm)
        vm.confirm(); advanceUntilIdle()
        assertTrue(repo.confirmations.isEmpty())
        repo.pageFailure = null
        repo.pages.add(ImportItemPage(listOf(taskItem("pending-page")), null))
        vm.loadItems("pending"); advanceUntilIdle()
        assertTrue(vm.state.value.canConfirm)
        assertTrue("pending-page" in vm.state.value.details)
    } }

    @Test fun previewAndRestoreDefaultToPendingUntilTheUserChoosesAll() = runTest { task { vm, repo, store ->
        repo.pendingCount = 1
        vm.restore(); advanceUntilIdle(); vm.preview(); advanceUntilIdle()
        assertEquals("pending", vm.state.value.filter)
        assertNull(vm.state.value.nextCursor)
        assertTrue(repo.pageCalls.isEmpty())
        val restored = CashImportTaskViewModel(repo, store)
        restored.restore(); advanceUntilIdle()
        assertEquals("pending", restored.state.value.filter)
        assertNull(restored.state.value.nextCursor)
        repo.pages.add(ImportItemPage(listOf(taskItem("pending-page")), "pending-cursor"))
        vm.loadItems("pending"); advanceUntilIdle()
        assertEquals(listOf("pending-page"), vm.state.value.items.map { it.itemId })
        assertEquals("pending-cursor", vm.state.value.nextCursor)
        assertEquals(listOf<Pair<String?, String>>(null to "pending"), repo.pageCalls)
        assertTrue("pending-page" in vm.state.value.details)
    } }

    @Test fun importItemsPagingSourceLoadsAbsolutePageWithFilteredPlaceholderCounts() = runTest {
        val repo = TaskRepository()
        val items = (0 until 20).map { taskItem("pending-$it") }
        repo.pages.add(ImportItemPage(items, null, startIndex = 40, totalCount = 100))
        var pageLoaded = false
        val source = CashImportItemsPagingSource(repo, TASK_PLAN, "pending") { pageLoaded = true }
        val result = source.load(PagingSource.LoadParams.Refresh(40, 20, true)) as PagingSource.LoadResult.Page
        assertTrue(pageLoaded)
        assertEquals(40, result.itemsBefore)
        assertEquals(20, result.prevKey)
        assertEquals(60, result.nextKey)
        assertEquals(20, result.data.size)
        assertEquals(40, result.itemsAfter)
        assertEquals(listOf<Triple<Int?, Int, String>>(Triple(40, 20, "pending")), repo.indexedCalls)
    }

    @Test fun definiteRollbackCanCancelOnlyAfterServerAndLocalRecoveryClear() = runTest { task { vm, repo, store ->
        vm.restore(); advanceUntilIdle(); vm.preview(); advanceUntilIdle()
        repo.beforeConfirm = { throw DomainFailure("commit_failed", 503, FailureCategory.RECOVERABLE, commitState = "not_committed") }
        vm.confirm(); advanceUntilIdle()
        assertEquals(ImportSubmissionState.RETRY_ORIGINAL, vm.state.value.submission)
        val taskId = vm.state.value.taskId
        vm.cancel(); advanceUntilIdle()
        assertEquals(1, repo.cancelCalls)
        assertNull(store.pointer)
        assertNull(vm.state.value.frozenCommand)
        assertNotEquals(taskId, vm.state.value.taskId)
        assertEquals(taskId, vm.state.value.cancelledTaskId)
    } }

    @Test fun resolveRetryOriginalAlsoAllowsCancellingRecoveredFrozenRequest() = runTest { task { vm, repo, store ->
        store.pointer = ImportRecoveryPointer.Submission(TASK_SESSION, ImportFrozenCommand(TASK_PLAN, 2, "original"))
        repo.resolutions.add(ImportResolveResult("retry_original", null))
        vm.restore(); advanceUntilIdle()
        vm.cancel(); advanceUntilIdle()
        assertEquals(1, repo.cancelCalls)
        assertNull(store.pointer)
        assertTrue(repo.confirmations.isEmpty())
    } }

    @Test fun lostCancellationAfterDefiniteRollbackRetainsOriginalSubmissionUntilResolved() = runTest { task { vm, repo, store ->
        vm.restore(); advanceUntilIdle(); vm.preview(); advanceUntilIdle()
        repo.beforeConfirm = { throw DomainFailure("commit_failed", 503, FailureCategory.RECOVERABLE, commitState = "not_committed") }
        vm.confirm(); advanceUntilIdle()
        val pointer = store.pointer
        repo.failCancel = true
        vm.cancel(); advanceUntilIdle()
        assertEquals(1, repo.cancelCalls)
        assertEquals(pointer, store.pointer)
        assertEquals(ImportSubmissionState.UNKNOWN, vm.state.value.submission)
        assertNull(vm.state.value.cancelledTaskId)
        repo.resolutions.add(ImportResolveResult("not_committed", null))
        vm.resolve(); advanceUntilIdle()
        assertEquals("cancelled", vm.state.value.context?.state)
        assertNotNull(store.pointer)
        repo.failCancel = false
        vm.cancel(); advanceUntilIdle()
        assertNull(store.pointer)
    } }

    @Test fun concurrentCommitWinningCancellationRetainsOriginalThenPublishesActualReceipt() = runTest { task { vm, repo, store ->
        vm.restore(); advanceUntilIdle(); vm.preview(); advanceUntilIdle()
        repo.beforeConfirm = { throw DomainFailure("commit_failed", 503, FailureCategory.RECOVERABLE, commitState = "not_committed") }
        vm.confirm(); advanceUntilIdle()
        val pointer = store.pointer
        repo.cancelFailure = DomainFailure("session_committed", 409, FailureCategory.RECOVERABLE, receiptId = "receipt1")
        vm.cancel(); advanceUntilIdle()
        assertEquals(1, repo.cancelCalls)
        assertEquals(pointer, store.pointer)
        assertNull(vm.state.value.receipt)
        repo.resolutions.add(ImportResolveResult("committed", taskReceipt()))
        vm.resolve(); advanceUntilIdle()
        assertEquals(ImportRecoveryPointer.Receipt("receipt1"), store.pointer)
        assertEquals("receipt1", vm.state.value.receipt?.receiptId)
        assertNull(vm.state.value.cancelledTaskId)
    } }

    @Test fun localClearFailureAfterCancellingFrozenRequestDoesNotPretendExitSucceeded() = runTest { task { vm, repo, store ->
        vm.restore(); advanceUntilIdle(); vm.preview(); advanceUntilIdle()
        repo.beforeConfirm = { throw DomainFailure("commit_failed", 503, FailureCategory.RECOVERABLE, commitState = "not_committed") }
        vm.confirm(); advanceUntilIdle()
        val pointer = store.pointer
        store.failClear = true
        vm.cancel(); advanceUntilIdle()
        assertEquals("cancelled", repo.current.state)
        assertEquals(pointer, store.pointer)
        assertNull(vm.state.value.cancelledTaskId)
        assertNotNull(vm.state.value.frozenCommand)
        assertFalse(vm.state.value.canEdit)
    } }

    @Test fun oldPlanNotCommittedDoesNotAuthorizeExitUntilOpenReplacementSessionIsCancelled() = runTest { task { vm, repo, store ->
        vm.restore(); advanceUntilIdle(); vm.preview(); advanceUntilIdle()
        repo.beforeConfirm = { throw DomainFailure("request_failed", 0, FailureCategory.RECOVERABLE) }
        vm.confirm(); advanceUntilIdle()
        repo.current = repo.current.copy(lastPlanId = "ip_$TASK_SESSION.${"z".repeat(16)}", revision = 4)
        repo.resolutions.add(ImportResolveResult("not_committed", null))
        vm.resolve(); advanceUntilIdle()
        assertEquals("open", vm.state.value.context?.state)
        assertNull(vm.state.value.cancelledTaskId)
        assertNotNull(store.pointer)
        val sourceTask = vm.state.value.taskId
        vm.cancel(); advanceUntilIdle()
        assertEquals("cancelled", repo.current.state)
        assertEquals(sourceTask, vm.state.value.cancelledTaskId)
        assertNull(store.pointer)
    } }

    @Test fun cancellationDoesNotPublishCompletionWhileLocalClearIsStillInFlight() = runTest { task { vm, _, store ->
        vm.restore(); advanceUntilIdle()
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        store.beforeClear = { started.complete(Unit); release.await() }
        val sourceTask = vm.state.value.taskId
        vm.cancel(); started.await()
        assertTrue(vm.state.value.busy)
        assertNull(vm.state.value.cancelledTaskId)
        assertNotNull(store.pointer)
        release.complete(Unit); advanceUntilIdle()
        assertEquals(sourceTask, vm.state.value.cancelledTaskId)
        assertNull(store.pointer)
    } }

    @Test fun cancellationLosingRaceToCommitPreservesRecoveryUntilActualReceipt() = runTest { task { vm, repo, store ->
        vm.restore(); advanceUntilIdle(); vm.preview(); advanceUntilIdle()
        repo.current = repo.current.copy(state = "committed", revision = null, canPreview = false, lastPlanId = null, receiptId = "receipt1")
        repo.cancelFailure = DomainFailure("session_committed", 409, FailureCategory.RECOVERABLE, receiptId = "receipt1")
        vm.cancel(); advanceUntilIdle()
        assertEquals(ImportRecoveryPointer.Session(TASK_SESSION), store.pointer)
        assertFalse(vm.state.value.canEdit)
        vm.refresh(); advanceUntilIdle()
        assertEquals(ImportTaskPhase.RESULT, vm.state.value.phase)
        assertEquals(ImportRecoveryPointer.Receipt("receipt1"), store.pointer)
        assertTrue(repo.confirmations.isEmpty())
    } }

    @Test fun mismatchedCancellationStatusNeverDiscardsRecovery() = runTest { task { vm, repo, store ->
        vm.restore(); advanceUntilIdle()
        repo.cancelFailure = DomainFailure("session_expired", 503, FailureCategory.RECOVERABLE)
        vm.cancel(); advanceUntilIdle()
        assertNotNull(store.pointer)
        assertNotNull(vm.state.value.context)
        assertFalse(vm.state.value.canEdit)
    } }

    @Test fun lateResultPageCannotRepopulateDetachedTask() = runTest { task { vm, repo, store ->
        vm.restore(); advanceUntilIdle(); vm.preview(); advanceUntilIdle(); vm.confirm(); advanceUntilIdle()
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        repo.beforeResults = { withContext(NonCancellable) { started.complete(Unit); release.await() } }
        repo.resultPages.add(ImportResultPage(listOf(taskResult("1", "101")), null, listOf(ImportProjectionRef("101", "projection")), 10))
        vm.loadResults(ImportResultKind.ROW); started.await()
        vm.detach(); release.complete(Unit); advanceUntilIdle()
        assertTrue(vm.state.value.resultPages.isEmpty())
        assertNull(vm.state.value.receipt)
        assertEquals(ImportRecoveryPointer.Receipt("receipt1"), store.pointer)
    } }

    @Test fun failedLocalClearAfterServerCancellationCannotPretendTaskIsGone() = runTest { task { vm, repo, store ->
        vm.restore(); advanceUntilIdle(); vm.preview(); advanceUntilIdle()
        store.failClear = true
        vm.cancel(); advanceUntilIdle()
        assertEquals("cancelled", repo.current.state)
        assertNotNull(store.pointer)
        assertFalse(vm.state.value.canEdit)
        vm.refresh(); advanceUntilIdle()
        assertEquals("cancelled", vm.state.value.context?.state)
        store.failClear = false
        vm.startNew(); advanceUntilIdle()
        assertNull(store.pointer)
        assertTrue(vm.state.value.canEdit)
    } }

    @Test fun cancelledSessionAlreadyGoneCanClearOnlyItsLocalRecoveryPointer() = runTest { task { _, _, _ ->
        for (code in listOf("session_expired", "session_not_found", "session_closed")) {
            val repo = TaskRepository()
            val store = TaskRecovery()
            val vm = CashImportTaskViewModel(repo, store)
            vm.restore(); advanceUntilIdle()
            repo.cancelFailure = DomainFailure(code, when (code) { "session_not_found" -> 404; "session_closed" -> 409; else -> 410 }, FailureCategory.RECOVERABLE)
            vm.cancel(); advanceUntilIdle()
            assertNull(store.pointer)
            assertEquals(1, repo.cancelCalls)
            assertTrue(vm.state.value.canEdit)
        }
    } }

    @Test fun actualResultsAccumulateOutcomesButOnlyKeepLatestProjectionReferences() = runTest { task { vm, repo, _ ->
        vm.restore(); advanceUntilIdle(); vm.preview(); advanceUntilIdle(); vm.confirm(); advanceUntilIdle()
        repo.resultPages.addAll(listOf(
            ImportResultPage(listOf(taskResult("1", "101")), "9007199254740993", listOf(ImportProjectionRef("101", "old")), 10),
            ImportResultPage(listOf(taskResult("2", "102")), null, listOf(ImportProjectionRef("102", "new")), 11),
            ImportResultPage(emptyList(), null, emptyList(), 12)))
        vm.loadResults(ImportResultKind.ROW); advanceUntilIdle()
        vm.loadResults(ImportResultKind.ROW, append = true); advanceUntilIdle()
        val rows = assertNotNull(vm.state.value.resultPages[ImportResultKind.ROW])
        assertEquals(listOf("1", "2"), rows.items.map { it.resultId })
        assertEquals(listOf(ImportProjectionRef("102", "new")), rows.projectionRefs)
        assertEquals(listOf("0", "9007199254740993"), repo.resultCalls.map { it.third })
        vm.loadResults(ImportResultKind.RELATION); advanceUntilIdle()
        assertTrue(vm.state.value.resultPages[ImportResultKind.ROW]!!.projectionRefs.isEmpty())
        assertEquals(12, vm.state.value.resultPages[ImportResultKind.ROW]!!.projectionVersion)
        assertTrue(vm.state.value.items.isEmpty())
    } }

    @Test fun staleResultVersionCannotReplaceCurrentNavigationReferences() = runTest { task { vm, repo, _ ->
        vm.restore(); advanceUntilIdle(); vm.preview(); advanceUntilIdle(); vm.confirm(); advanceUntilIdle()
        repo.resultPages.addAll(listOf(
            ImportResultPage(listOf(taskResult("1", "101")), null, listOf(ImportProjectionRef("101", "new")), 10),
            ImportResultPage(emptyList(), null, listOf(ImportProjectionRef("101", "old")), 9)))
        vm.loadResults(ImportResultKind.ROW); advanceUntilIdle()
        vm.loadResults(ImportResultKind.ROW); advanceUntilIdle()
        assertEquals(listOf(ImportProjectionRef("101", "new")), vm.state.value.resultPages[ImportResultKind.ROW]!!.projectionRefs)
        assertEquals("unknown_error", vm.state.value.errorCode)
        assertIs<IllegalStateException>(vm.state.value.internalFailure)
    } }

    @Test fun confirmedCancellationClearsRecoveryAndStartsFreshTask() = runTest { task { vm, repo, store ->
        vm.restore(); advanceUntilIdle(); vm.preview(); advanceUntilIdle()
        val before = vm.state.value.taskId
        vm.cancel(); advanceUntilIdle()
        assertEquals(1, repo.cancelCalls)
        assertNull(store.pointer)
        assertNull(vm.state.value.context)
        assertNotEquals(before, vm.state.value.taskId)
        assertTrue(vm.state.value.canEdit)
    } }

    @Test fun unknownSubmissionCannotBeDiscardedByCancelOrStartNew() = runTest { task { vm, repo, store ->
        vm.restore(); advanceUntilIdle(); vm.preview(); advanceUntilIdle()
        repo.beforeConfirm = { throw DomainFailure("commit_state_unknown", 0, FailureCategory.RECOVERABLE) }
        vm.confirm(); advanceUntilIdle()
        val pointer = store.pointer
        vm.cancel(); vm.startNew(); advanceUntilIdle()
        assertEquals(pointer, store.pointer)
        assertEquals(0, repo.cancelCalls)
        assertEquals(ImportSubmissionState.UNKNOWN, vm.state.value.submission)
    } }

    @Test fun definiteResultStartsNewOnlyAfterRecoveryClearSucceeds() = runTest { task { vm, _, store ->
        vm.restore(); advanceUntilIdle(); vm.preview(); advanceUntilIdle(); vm.confirm(); advanceUntilIdle()
        store.failClear = true
        vm.startNew(); advanceUntilIdle()
        assertNotNull(vm.state.value.receipt)
        assertEquals(ImportRecoveryPointer.Receipt("receipt1"), store.pointer)
        store.failClear = false
        vm.startNew(); advanceUntilIdle()
        assertNull(store.pointer)
        assertNull(vm.state.value.receipt)
        assertTrue(vm.state.value.canEdit)
    } }

    @Test fun lostCancellationResponseRequiresRefreshBeforeStartingNew() = runTest { task { vm, repo, store ->
        vm.restore(); advanceUntilIdle(); vm.preview(); advanceUntilIdle()
        repo.failCancel = true
        vm.cancel(); advanceUntilIdle()
        assertFalse(vm.state.value.canEdit)
        assertFalse(vm.state.value.canConfirm)
        vm.startNew(); advanceUntilIdle()
        assertNotNull(store.pointer)
        repo.failCancel = false
        vm.refresh(); advanceUntilIdle()
        assertEquals("cancelled", vm.state.value.context?.state)
        vm.startNew(); advanceUntilIdle()
        assertNull(store.pointer)
        assertTrue(vm.state.value.canEdit)
    } }

    @Test fun lostFileCommandResponseLocksRemoveUntilRefresh() = runTest { task { vm, repo, _ ->
        vm.restore(); advanceUntilIdle()
        vm.preview(); advanceUntilIdle()
        assertTrue(vm.state.value.canConfirm)
        repo.failFileMutation = true
        vm.removeFile("f1")
        advanceUntilIdle()
        assertFalse(vm.state.value.canEdit)
        assertFalse(vm.state.value.canConfirm)
        vm.confirm(); advanceUntilIdle(); assertTrue(repo.confirmations.isEmpty())
        repo.failFileMutation = false
        vm.refresh(); advanceUntilIdle()
        assertTrue(vm.state.value.canEdit)
        assertEquals(repo.current.revision, vm.state.value.context?.revision)
        assertNull(vm.state.value.plan)
    } }

    @Test fun lostPreviewResponseLocksPreviousPlanUntilRefresh() = runTest { task { vm, repo, _ ->
        vm.restore(); advanceUntilIdle(); vm.preview(); advanceUntilIdle()
        repo.failPreview = true
        vm.preview(); advanceUntilIdle()
        assertFalse(vm.state.value.canEdit)
        assertFalse(vm.state.value.canConfirm)
        vm.confirm(); advanceUntilIdle(); assertTrue(repo.confirmations.isEmpty())
        repo.failPreview = false
        vm.refresh(); advanceUntilIdle()
        assertTrue(vm.state.value.canEdit)
        assertNull(vm.state.value.plan)
    } }

    @Test fun interruptedUploadRefreshesLatestContextAndKeepsFailures() = runTest { task { vm, repo, _ ->
        vm.restore(); advanceUntilIdle()
        repo.uploadAction = { progress ->
            progress(repo.current)
            repo.current = repo.current.copy(revision = 4)
            ImportUploadBatch(repo.current.copy(revision = 2), listOf(ImportLocalFileFailure("bad", "import_file_read_failed")),
                ImportUploadInterruption("request_failed", 0, null, listOf("uncertain", "remaining")))
        }
        vm.upload(listOf(ImportUpload("first", taskFile()))); advanceUntilIdle()
        assertTrue(vm.state.value.canEdit)
        assertEquals(listOf("uncertain", "remaining"), vm.state.value.unprocessedFileRefs)
        assertTrue(vm.state.value.localFileFailures.any { it.clientFileRef == "bad" })
        assertEquals(4, vm.state.value.context?.revision)
    } }

    @Test fun concurrentUploadsStartAtMostThreeAndDrainQueue() = runTest { task { vm, repo, _ ->
        vm.restore(); advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        var active = 0
        var maxActive = 0
        val started = mutableListOf<String>()
        repo.uploadHandler = { files, _, onContext ->
            val upload = files.single()
            started += upload.clientFileRef
            active++; maxActive = maxOf(maxActive, active)
            gate.await()
            val base = repo.current
            val updated = base.copy(revision = (base.revision ?: 0) + 1,
                files = base.files + ImportContextFile("ref-${upload.clientFileRef}", upload.clientFileRef,
                    upload.file.name, "alipay", "ready", 1, emptyList(), false))
            repo.current = updated
            onContext(updated)
            active--
            ImportUploadBatch(updated, emptyList(), null)
        }
        vm.upload((1..4).map { ImportUpload("file$it", taskFile()) })
        runCurrent()
        assertEquals(3, maxActive)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(4, started.distinct().size)
        assertEquals(6, vm.state.value.context?.revision)
        assertTrue(vm.state.value.canAddFiles)
    } }

    @Test fun failedUploadDoesNotBlockOtherQueuedFiles() = runTest { task { vm, repo, _ ->
        vm.restore(); advanceUntilIdle()
        repo.uploadHandler = { files, _, onContext ->
            val upload = files.single()
            if (upload.clientFileRef == "bad") throw DomainFailure("request_failed", 0, FailureCategory.RECOVERABLE)
            val base = repo.current
            val updated = base.copy(revision = (base.revision ?: 0) + 1,
                files = base.files + ImportContextFile("ref-${upload.clientFileRef}", upload.clientFileRef,
                    upload.file.name, "alipay", "ready", 1, emptyList(), false))
            repo.current = updated
            onContext(updated)
            ImportUploadBatch(updated, emptyList(), null)
        }
        vm.upload(listOf(ImportUpload("good", taskFile()), ImportUpload("bad", taskFile()), ImportUpload("also-good", taskFile())))
        advanceUntilIdle()
        assertEquals(setOf("good", "also-good"), vm.state.value.context?.files?.map { it.clientFileRef }?.toSet())
        assertEquals(listOf("bad"), vm.state.value.unprocessedFileRefs)
        assertEquals(ImportLocalFileFailure("bad", "request_failed"), vm.state.value.localFileFailures.single())
        assertTrue(vm.state.value.canAddFiles)
    } }

    @Test fun retryingFailedUploadClearsOnlyItsOldFailureBeforeSending() = runTest { task { vm, repo, _ ->
        vm.restore(); advanceUntilIdle()
        repo.uploadHandler = { files, _, _ ->
            ImportUploadBatch(null, listOf(ImportLocalFileFailure(files.single().clientFileRef, "import_file_read_failed")), null)
        }
        vm.upload(listOf(ImportUpload("bad", taskFile()), ImportUpload("other", taskFile())))
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        repo.uploadHandler = { files, _, progress ->
            gate.await()
            val file = files.single()
            val updated = repo.current.copy(revision = 3, files = listOf(
                ImportContextFile("retried", file.clientFileRef, file.file.name, "alipay", "ready", 1, emptyList(), false)))
            repo.current = updated; progress(updated)
            ImportUploadBatch(updated, emptyList(), null)
        }
        vm.upload(listOf(ImportUpload("bad", taskFile()))); runCurrent()
        assertEquals(listOf("other"), vm.state.value.localFileFailures.map { it.clientFileRef })
        assertEquals(1, vm.state.value.activeUploads)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(listOf("bad"), vm.state.value.context?.files?.map { it.clientFileRef })
        assertEquals(listOf("other"), vm.state.value.localFileFailures.map { it.clientFileRef })
    } }

    @Test fun removingLocalFailurePreservesOtherFilesAndDoesNotSendServerRemoval() = runTest { task { vm, repo, _ ->
        vm.restore(); advanceUntilIdle()
        repo.uploadHandler = { files, _, _ ->
            ImportUploadBatch(null, emptyList(), ImportUploadInterruption("request_failed", 0, null, listOf(files.single().clientFileRef)))
        }
        vm.upload(listOf(ImportUpload("bad", taskFile()), ImportUpload("other", taskFile())))
        advanceUntilIdle()
        val before = vm.state.value.context
        vm.removeLocalFileFailure("bad")
        assertEquals(listOf("other"), vm.state.value.localFileFailures.map { it.clientFileRef })
        assertEquals(listOf("other"), vm.state.value.unprocessedFileRefs)
        assertEquals(before, vm.state.value.context)
        assertTrue(repo.events.isEmpty())
    } }

    @Test fun retryingOverCapacityFileKeepsOneFailureRow() = runTest { task { vm, repo, _ ->
        repo.current = repo.current.copy(files = (1..20).map {
            ImportContextFile("f$it", "c$it", "$it.csv", "alipay", "ready", 1, emptyList(), false)
        })
        vm.restore(); advanceUntilIdle()
        repeat(3) { vm.upload(listOf(ImportUpload("extra", taskFile()))) }
        assertEquals(listOf(ImportLocalFileFailure("extra", "import_file_too_many")), vm.state.value.localFileFailures)
        assertTrue(repo.uploadCalls.isEmpty())
    } }

    @Test fun appendSavesAccountSelectionBeforeStartingUploads() = runTest { task { vm, repo, _ ->
        repo.current = repo.current.copy(sourceAccounts = listOf(ImportSourceAccount("src", "wechat", "零钱", listOf("CNY"), 1, null, "none", null)))
        vm.restore(); advanceUntilIdle()
        val target = ImportAccountTarget("2", 1)
        vm.selectAccount("src", target)
        repo.uploadHandler = { files, _, onContext ->
            assertEquals(listOf(ImportAccountSelection("src", target)), repo.lastChoices?.accountSelections)
            val updated = repo.current.copy(revision = (repo.current.revision ?: 0) + 1,
                files = repo.current.files + ImportContextFile("new", files.single().clientFileRef, "new.csv", "wechat", "ready", 1, emptyList(), false))
            repo.current = updated; onContext(updated)
            ImportUploadBatch(updated, emptyList(), null)
        }
        vm.upload(listOf(ImportUpload("new", taskFile()))); advanceUntilIdle()
        assertEquals(listOf(ImportAccountSelection("src", target)), vm.state.value.choices.accountSelections)
        assertEquals(ImportTaskPhase.PREPARE, vm.state.value.phase)
    } }

    @Test fun firstLocalFileFailureAllowsNextFileToCreateSession() = runTest { task { vm, repo, store ->
        store.pointer = null
        vm.restore(); advanceUntilIdle()
        repo.uploadHandler = { files, context, onContext ->
            assertNull(context)
            if (files.single().clientFileRef == "bad") ImportUploadBatch(null, listOf(ImportLocalFileFailure("bad", "import_file_read_failed")), null)
            else {
                val updated = repo.current.copy(files = listOf(ImportContextFile("good", "good", "good.csv", "wechat", "ready", 1, emptyList(), false)))
                repo.current = updated; onContext(updated)
                ImportUploadBatch(updated, emptyList(), null)
            }
        }
        vm.upload(listOf(ImportUpload("bad", taskFile()), ImportUpload("good", taskFile()))); advanceUntilIdle()
        assertEquals(listOf("bad", "good"), repo.uploadCalls.flatMap { it.first })
        assertEquals(listOf("good"), vm.state.value.context?.files?.map { it.clientFileRef })
        assertEquals(listOf(ImportLocalFileFailure("bad", "import_file_read_failed")), vm.state.value.localFileFailures)
        assertNull(vm.state.value.errorCode)
    } }

    @Test fun appendingDuringChoiceSaveWaitsForSavedChoices() = runTest { task { vm, repo, _ ->
        repo.current = repo.current.copy(sourceAccounts = listOf(ImportSourceAccount("src", "wechat", "零钱", listOf("CNY"), 1, null, "none", null)))
        vm.restore(); advanceUntilIdle()
        vm.selectAccount("src", ImportAccountTarget("2", 1))
        val gate = CompletableDeferred<Unit>()
        repo.beforePreview = { gate.await() }
        repo.uploadHandler = { _, _, progress -> progress(repo.current); ImportUploadBatch(repo.current, emptyList(), null) }
        vm.upload(listOf(ImportUpload("first", taskFile()))); runCurrent()
        vm.upload(listOf(ImportUpload("second", taskFile()))); runCurrent()
        assertTrue(repo.uploadCalls.isEmpty())
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(listOf("first", "second"), repo.uploadCalls.flatMap { it.first })
        assertEquals(listOf(ImportAccountSelection("src", ImportAccountTarget("2", 1))), vm.state.value.choices.accountSelections)
    } }

    @Test fun unknownFirstCreationDoesNotAllowAnotherCreation() = runTest { task { vm, repo, store ->
        store.pointer = null
        vm.restore(); advanceUntilIdle()
        repo.uploadHandler = { _, _, _ -> ImportUploadBatch(null, emptyList(), ImportUploadInterruption("request_failed", 0, null, listOf("first"))) }
        vm.upload(listOf(ImportUpload("first", taskFile()))); advanceUntilIdle()
        assertTrue(vm.state.value.uploadCreationUnknown)
        assertFalse(vm.state.value.canAddFiles)
        vm.upload(listOf(ImportUpload("second", taskFile()))); advanceUntilIdle()
        assertEquals(1, repo.uploadCalls.size)
    } }

    @Test fun failedChoiceValidationStopsQueueWithoutRetry() = runTest { task { vm, repo, _ ->
        repo.current = repo.current.copy(sourceAccounts = listOf(ImportSourceAccount("src", "wechat", "零钱", listOf("CNY"), 1, null, "none", null)))
        vm.restore(); advanceUntilIdle()
        vm.selectAccount("src", ImportAccountTarget("2", 1))
        var previews = 0
        repo.beforePreview = { previews++ }
        repo.previewFailure = DomainFailure("plan_input_invalid", 422, FailureCategory.RECOVERABLE)
        vm.upload(listOf(ImportUpload("queued", taskFile()))); advanceUntilIdle()
        assertEquals(1, previews)
        assertTrue(repo.uploadCalls.isEmpty())
        assertEquals("queued", vm.state.value.localFileFailures.single().clientFileRef)
        assertFalse(vm.state.value.busy)
    } }

    @Test fun excessFilesRemainVisibleAsFailures() = runTest { task { vm, repo, _ ->
        repo.current = repo.current.copy(files = (1..19).map { ImportContextFile("ref$it", "old$it", "old$it.csv", "wechat", "ready", 1, emptyList(), false) })
        vm.restore(); advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        repo.uploadHandler = { _, _, _ -> gate.await(); ImportUploadBatch(repo.current, emptyList(), null) }
        vm.upload(listOf(ImportUpload("new1", taskFile()), ImportUpload("new2", taskFile()))); runCurrent()
        assertEquals(listOf(ImportLocalFileFailure("new2", "import_file_too_many")), vm.state.value.localFileFailures)
        assertEquals(listOf("new2"), vm.state.value.unprocessedFileRefs)
        gate.complete(Unit); advanceUntilIdle()
    } }

    @Test fun failedChoiceSaveKeepsQueuedFileFailureAndDoesNotUpload() = runTest { task { vm, repo, _ ->
        vm.restore(); advanceUntilIdle()
        vm.setMergeSameNameAcrossChannels(false)
        repo.current = repo.current.copy(sourceAccounts = listOf(ImportSourceAccount("src", "wechat", "零钱", listOf("CNY"), 1, null, "none", null)))
        vm.refresh(); advanceUntilIdle()
        vm.selectAccount("src", ImportAccountTarget("2", 1))
        repo.previewFailure = DomainFailure("request_failed", 0, FailureCategory.RECOVERABLE)
        vm.upload(listOf(ImportUpload("queued", taskFile()))); advanceUntilIdle()
        assertTrue(repo.uploadCalls.isEmpty())
        assertEquals(listOf(ImportLocalFileFailure("queued", "request_failed")), vm.state.value.localFileFailures)
        assertTrue(vm.state.value.needsContextRefresh)
    } }

    @Test fun refreshRemovesFailureOnlyAfterServerConfirmsFileSucceeded() = runTest { task { vm, repo, _ ->
        vm.restore(); advanceUntilIdle()
        repo.uploadHandler = { _, _, _ ->
            repo.current = repo.current.copy(revision = 4, files = listOf(ImportContextFile("found", "uncertain", "uncertain.csv", "wechat", "ready", 1, emptyList(), false)))
            ImportUploadBatch(null, emptyList(), ImportUploadInterruption("request_failed", 0, null, listOf("uncertain")))
        }
        vm.upload(listOf(ImportUpload("uncertain", taskFile()))); advanceUntilIdle()
        assertTrue(vm.state.value.localFileFailures.isEmpty())
        assertTrue(vm.state.value.unprocessedFileRefs.isEmpty())
        assertEquals("uncertain", vm.state.value.context?.files?.single()?.clientFileRef)
    } }

    @Test fun firstUploadCreatesSessionAloneThenRunsRemainingInParallel() = runTest { task { vm, repo, store ->
        store.pointer = null
        vm.restore(); advanceUntilIdle()
        val firstGate = CompletableDeferred<Unit>()
        val restGate = CompletableDeferred<Unit>()
        var active = 0
        var maxActive = 0
        repo.uploadHandler = { files, context, onContext ->
            active++; maxActive = maxOf(maxActive, active)
            if (context == null) firstGate.await() else restGate.await()
            val base = context ?: ImportContext(TASK_SESSION, 0, "future", "open", emptyList(), emptyList(), ImportChoiceSet(), null, true, null)
            val updated = base.copy(revision = (base.revision ?: 0) + 1,
                files = base.files + ImportContextFile("ref-${files.single().clientFileRef}", files.single().clientFileRef,
                    files.single().file.name, "alipay", "ready", 1, emptyList(), false))
            repo.current = updated
            onContext(updated)
            active--
            ImportUploadBatch(updated, emptyList(), null)
        }
        vm.upload((1..4).map { ImportUpload("file$it", taskFile()) })
        runCurrent()
        assertEquals(1, active)
        firstGate.complete(Unit)
        runCurrent()
        assertEquals(3, maxActive)
        restGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(4, repo.uploadCalls.size)
    } }

    @Test fun uploadWaitsForRecoveredSessionBeforeCreatingNewOne() = runTest { task { vm, repo, store ->
        store.pointer = ImportRecoveryPointer.Session(TASK_SESSION)
        val gate = CompletableDeferred<Unit>()
        repo.contextGate = gate
        repo.uploadHandler = { files, context, onContext ->
            val base = context ?: error("recovered context required")
            val updated = base.copy(revision = (base.revision ?: 0) + 1,
                files = base.files + ImportContextFile("ref-${files.single().clientFileRef}", files.single().clientFileRef,
                    files.single().file.name, "alipay", "ready", 1, emptyList(), false))
            repo.current = updated
            onContext(updated)
            ImportUploadBatch(updated, emptyList(), null)
        }
        vm.restore()
        runCurrent()
        vm.upload(listOf(ImportUpload("file1", taskFile())))
        runCurrent()
        assertTrue(repo.uploadCalls.isEmpty())
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(TASK_SESSION, repo.uploadCalls.single().second?.sessionId)
    } }

    @Test fun fatalUploadMarksQueuedFilesAsUnprocessed() = runTest { task { vm, repo, _ ->
        vm.restore(); advanceUntilIdle()
        repo.uploadHandler = { files, _, _ ->
            ImportUploadBatch(null, emptyList(), ImportUploadInterruption("session_closed", 409, null, listOf(files.single().clientFileRef)))
        }
        vm.upload((1..4).map { ImportUpload("file$it", taskFile()) })
        advanceUntilIdle()
        assertEquals((1..4).map { "file$it" }.toSet(), vm.state.value.unprocessedFileRefs.toSet())
    } }

    @Test fun cancelClearsQueuedUploadsInsteadOfStartingNewSession() = runTest { task { vm, repo, store ->
        store.pointer = ImportRecoveryPointer.Session(TASK_SESSION)
        repo.contextFailure = DomainFailure("request_failed", 0, FailureCategory.RECOVERABLE)
        vm.restore(); advanceUntilIdle()
        assertNull(vm.state.value.context)
        vm.upload(listOf(ImportUpload("file1", taskFile()))); runCurrent()
        assertTrue(repo.uploadCalls.isEmpty())
        vm.cancel(); advanceUntilIdle()
        assertNull(vm.state.value.context)
        assertTrue(repo.uploadCalls.isEmpty())
        assertEquals(0, vm.state.value.activeUploads)
    } }

    @Test fun fileChangeFlushesCompleteChoicesBeforeChangingCurrentRevision() = runTest { task { vm, repo, _ ->
        repo.current = repo.current.copy(sourceAccounts = listOf(ImportSourceAccount("src", "wechat", "synthetic", listOf("CNY"), 1, null, "none", null)))
        vm.restore(); advanceUntilIdle()
        vm.selectAccount("src", ImportAccountTarget(null, null))
        vm.removeFile("f1"); advanceUntilIdle()
        assertEquals(listOf("preview:2", "remove:3"), repo.events)
        assertTrue(repo.lastChoices?.accountSelections.orEmpty().isEmpty())
        assertEquals(repo.lastChoices, vm.state.value.choices)
        assertEquals(4, vm.state.value.context?.revision)
        assertNull(vm.state.value.plan)
    } }

    @Test fun pagesDetailsAndRowChoicesUseCurrentPlanAndFullInputs() = runTest { task { vm, repo, _ ->
        vm.restore(); advanceUntilIdle(); vm.preview(); advanceUntilIdle()
        repo.pages.addAll(listOf(ImportItemPage(listOf(taskItem("one")), "opaque/+"), ImportItemPage(listOf(taskItem("two")), null)))
        vm.loadItems("pending"); advanceUntilIdle()
        vm.loadItems("pending", append = true); advanceUntilIdle()
        assertEquals(listOf("one", "two"), vm.state.value.items.map { it.itemId })
        assertEquals(listOf(null to "pending", "opaque/+" to "pending"), repo.pageCalls)
        vm.loadDetail("one"); advanceUntilIdle()
        vm.editRow(ImportRecordChoice("f:r1", ImportRowDecision.APPLY, StructuredObject(mapOf("note" to StructuredNull))))
        vm.editPair(ImportPairChoice("sg_one", ImportPairDecision.DEFER))
        vm.preview(); advanceUntilIdle()
        assertEquals(StructuredNull, repo.lastChoices?.rows?.single()?.patch?.fields?.get("note"))
        assertEquals(ImportPairDecision.DEFER, repo.lastChoices?.relations?.single()?.action)
        assertTrue(vm.state.value.details.isEmpty())
    } }

    @Test fun anotherPlanCommittedInSameSessionIsAuthoritativeDuringResolution() = runTest { task { _, repo, store ->
        store.pointer = ImportRecoveryPointer.Submission(TASK_SESSION, ImportFrozenCommand(TASK_PLAN, 3, "original"))
        val other = taskReceipt().copy(planId = "ip_$TASK_SESSION.${"c".repeat(16)}")
        repo.resolutions.add(ImportResolveResult("committed", other))
        val vm = CashImportTaskViewModel(repo, store)
        vm.restore(); advanceUntilIdle()
        assertEquals(other, vm.state.value.receipt)
        assertEquals(ImportTaskPhase.RESULT, vm.state.value.phase)
        assertTrue(repo.confirmations.isEmpty())
        assertEquals(ImportRecoveryPointer.Receipt("receipt1"), store.pointer)
    } }

    @Test fun normalRefreshFailureLocksExistingPlanUntilSuccessfulRefresh() = runTest { task { vm, repo, _ ->
        vm.restore(); advanceUntilIdle(); vm.preview(); advanceUntilIdle()
        assertTrue(vm.state.value.canConfirm)
        repo.contextFailure = DomainFailure("request_failed", 0, FailureCategory.RECOVERABLE)
        vm.refresh(); advanceUntilIdle()
        assertFalse(vm.state.value.canEdit)
        assertFalse(vm.state.value.canConfirm)
        vm.confirm(); advanceUntilIdle()
        assertTrue(repo.confirmations.isEmpty())
        repo.contextFailure = null
        vm.refresh(); advanceUntilIdle()
        assertTrue(vm.state.value.canEdit)
        assertNull(vm.state.value.plan)
    } }

    @Test fun recoveryReadFailureKeepsTaskBlockedUntilRefreshSucceeds() = runTest { task { vm, repo, _ ->
        repo.contextFailure = DomainFailure("request_failed", 0, FailureCategory.RECOVERABLE)
        vm.restore(); advanceUntilIdle()
        assertFalse(vm.state.value.canEdit)
        repo.contextFailure = null
        vm.refresh(); advanceUntilIdle()
        assertTrue(vm.state.value.canEdit)
        assertEquals(2, repo.contextReads)
    } }

    @Test fun failedReceiptReadCannotStartAnotherTaskAndCanRetryRead() = runTest { task { _, repo, store ->
        store.pointer = ImportRecoveryPointer.Receipt("receipt1")
        repo.receiptFailure = DomainFailure("request_failed", 0, FailureCategory.RECOVERABLE)
        val vm = CashImportTaskViewModel(repo, store)
        vm.restore(); advanceUntilIdle()
        assertFalse(vm.state.value.canEdit)
        repo.receiptFailure = null
        vm.refresh(); advanceUntilIdle()
        assertEquals(ImportTaskPhase.RESULT, vm.state.value.phase)
    } }

    @Test fun contextSaveAfterResolutionKeepsBusyAndEditingBlocked() = runTest { task { _, repo, store ->
        store.pointer = ImportRecoveryPointer.Submission(TASK_SESSION, ImportFrozenCommand(TASK_PLAN, 3, "original"))
        repo.resolutions.add(ImportResolveResult("not_committed", null))
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        store.beforeSave = { if (it is ImportRecoveryPointer.Session) { started.complete(Unit); release.await() } }
        val vm = CashImportTaskViewModel(repo, store)
        vm.restore(); started.await()
        try { assertFalse(vm.state.value.canEdit); assertTrue(vm.state.value.busy) }
        finally { release.complete(Unit); advanceUntilIdle() }
        assertTrue(vm.state.value.canEdit)
    } }

    @Test fun detachedUploadProgressStopsRemainingFilesEvenIfRepositoryIgnoresCancellation() = runTest { task { vm, repo, _ ->
        vm.restore(); advanceUntilIdle()
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var continued = false
        repo.uploadAction = { progress ->
            withContext(NonCancellable) {
                started.complete(Unit); release.await(); progress(repo.current); continued = true
                ImportUploadBatch(repo.current, emptyList(), null)
            }
        }
        vm.upload(listOf(ImportUpload("file1", object : FileSource {
            override val name = "synthetic.csv"
            override val mediaType: String? = null
            override val size: Long? = 1
            override suspend fun read() = byteArrayOf(1)
        })))
        started.await(); vm.detach(); release.complete(Unit); advanceUntilIdle()
        assertFalse(continued)
        assertNull(vm.state.value.context)
    } }

    @Test fun confirmsOnceAndSavesOriginalCommandBeforeSending() = runTest { task { vm, repo, store ->
        vm.restore(); advanceUntilIdle()
        vm.preview(); advanceUntilIdle()
        repo.beforeConfirm = { command -> assertEquals(ImportRecoveryPointer.Submission(TASK_SESSION, command), store.pointer) }
        vm.confirm(); vm.confirm(); advanceUntilIdle()
        assertEquals(listOf(ImportFrozenCommand(TASK_PLAN, 3, "original")), repo.confirmations)
        assertEquals(ImportTaskPhase.RESULT, vm.state.value.phase)
        assertEquals(ImportRecoveryPointer.Receipt("receipt1"), store.pointer)
        assertTrue(vm.state.value.items.isEmpty())
    } }

    @Test fun failedLocalSaveNeverSendsAndRetriesSameFrozenCommand() = runTest { task { vm, repo, store ->
        vm.restore(); advanceUntilIdle(); vm.preview(); advanceUntilIdle()
        store.failSave = true
        vm.confirm(); advanceUntilIdle()
        assertTrue(repo.confirmations.isEmpty())
        assertEquals(ImportSubmissionState.RETRY_ORIGINAL, vm.state.value.submission)
        assertEquals("import_recovery_unavailable", vm.state.value.errorCode)
        store.failSave = false
        vm.retryOriginal(); advanceUntilIdle()
        assertEquals(listOf(ImportFrozenCommand(TASK_PLAN, 3, "original")), repo.confirmations)
    } }

    @Test fun unknownRestoredSubmissionResolvesBeforeReadingContextOrResending() = runTest { task { _, repo, store ->
        val original = ImportFrozenCommand(TASK_PLAN, 3, "original")
        store.pointer = ImportRecoveryPointer.Submission(TASK_SESSION, original)
        repo.resolutions.addAll(listOf(ImportResolveResult("pending", null), ImportResolveResult("retry_original", null)))
        val vm = CashImportTaskViewModel(repo, store, newKey = { "must-not-replace" })
        vm.restore(); advanceUntilIdle()
        assertEquals(0, repo.contextReads)
        assertTrue(repo.confirmations.isEmpty())
        assertEquals(ImportSubmissionState.UNKNOWN, vm.state.value.submission)
        vm.resolve(); advanceUntilIdle()
        assertEquals(ImportSubmissionState.RETRY_ORIGINAL, vm.state.value.submission)
        vm.retryOriginal(); advanceUntilIdle()
        assertEquals(listOf(original), repo.confirmations)
        assertEquals(listOf(TASK_PLAN to "original", TASK_PLAN to "original"), repo.resolveCalls)
    } }

    @Test fun authoritativeNotCommittedRefreshesContextAndRestoresExactChoices() = runTest { task { _, repo, store ->
        store.pointer = ImportRecoveryPointer.Submission(TASK_SESSION, ImportFrozenCommand(TASK_PLAN, 3, "original"))
        repo.resolutions.add(ImportResolveResult("not_committed", null))
        val choices = ImportChoiceSet(rows = listOf(ImportRecordChoice("f:r1", ImportRowDecision.APPLY, StructuredObject(mapOf("note" to StructuredNull)))))
        repo.current = repo.current.copy(lastChoices = choices)
        val vm = CashImportTaskViewModel(repo, store)
        vm.restore(); advanceUntilIdle()
        assertEquals(1, repo.contextReads)
        assertEquals(choices, vm.state.value.choices)
        assertNull(vm.state.value.frozenCommand)
        assertTrue(vm.state.value.canEdit)
        assertEquals(ImportRecoveryPointer.Session(TASK_SESSION), store.pointer)
        assertTrue(repo.confirmations.isEmpty())
    } }

    @Test fun lateConfirmationAfterDetachCannotPublishOrReplaceRecoveryPointer() = runTest { task { vm, repo, store ->
        vm.restore(); advanceUntilIdle(); vm.preview(); advanceUntilIdle()
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        repo.beforeConfirm = { withContext(NonCancellable) { started.complete(Unit); release.await() } }
        vm.confirm(); started.await()
        val frozen = store.pointer
        vm.detach()
        release.complete(Unit); advanceUntilIdle()
        assertNull(vm.state.value.receipt)
        assertEquals(frozen, store.pointer)
        assertFalse(vm.state.value.canEdit)
    } }

    @Test fun receiptRestoreDoesNotNeedExpiredParsedContext() = runTest { task { _, repo, store ->
        store.pointer = ImportRecoveryPointer.Receipt("receipt1")
        val vm = CashImportTaskViewModel(repo, store)
        vm.restore(); advanceUntilIdle()
        assertEquals(0, repo.contextReads)
        assertEquals(ImportTaskPhase.RESULT, vm.state.value.phase)
        assertEquals("receipt1", vm.state.value.receipt?.receiptId)
        assertTrue(repo.resolveCalls.isEmpty())
    } }

    @Test fun badRecoveryNeverSilentlyStartsFreshImport() = runTest { task { _, repo, store ->
        store.failLoad = true
        val vm = CashImportTaskViewModel(repo, store)
        vm.restore(); advanceUntilIdle()
        assertEquals("import_recovery_invalid", vm.state.value.errorCode)
        assertFalse(vm.state.value.canEdit)
        vm.preview(); vm.confirm(); advanceUntilIdle()
        assertEquals(0, repo.contextReads)
        assertTrue(repo.confirmations.isEmpty())
    } }

    private suspend fun TestScope.task(block: suspend TestScope.(CashImportTaskViewModel, TaskRepository, TaskRecovery) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = TaskRepository(); val store = TaskRecovery()
            block(CashImportTaskViewModel(repo, store, newKey = { "original" }), repo, store)
        } finally { Dispatchers.resetMain() }
    }
}

private val TASK_SESSION = "a".repeat(43)
private val TASK_PLAN = "ip_$TASK_SESSION.${"b".repeat(16)}"
private fun taskReceipt() = ImportReceipt("receipt1", TASK_PLAN, "import", true, "2026-10-23T00:00:00Z", "complete", emptyMap(), emptyMap(), 0, 0, 1, null, null, null, null)
private fun taskFile() = object : FileSource {
    override val name = "synthetic.csv"
    override val mediaType: String? = null
    override val size: Long? = 1
    override suspend fun read() = byteArrayOf(1)
}
private fun taskItem(id: String) = ImportProjectedItem(id, "pending", "draft", ImportDisplay(null, null, null, null, null, emptyList()), false, emptyList(), emptyList(), true)
private fun taskResult(id: String, transactionId: String) = ImportResultItem(id, "row", "f:r$id", "created_posted", null, null, null, transactionId, null, null)

private class TaskRecovery : ImportRecoveryStore {
    var pointer: ImportRecoveryPointer? = ImportRecoveryPointer.Session(TASK_SESSION)
    var failLoad = false; var failSave = false
    var failClear = false
    var beforeClear: suspend () -> Unit = {}
    var beforeSave: suspend (ImportRecoveryPointer) -> Unit = {}
    override suspend fun load(): ImportRecoveryPointer? {
        if (failLoad) throw DomainFailure("import_recovery_invalid", 0, FailureCategory.RECOVERABLE)
        return pointer
    }
    override suspend fun save(value: ImportRecoveryPointer) {
        beforeSave(value)
        if (failSave) throw DomainFailure("import_recovery_unavailable", 0, FailureCategory.RECOVERABLE)
        pointer = value
    }
    override suspend fun clear() {
        beforeClear()
        if (failClear) throw DomainFailure("import_recovery_unavailable", 0, FailureCategory.RECOVERABLE)
        pointer = null
    }
}

internal class TaskRepository : CashImportLifecycleRepository {
    var current = ImportContext(TASK_SESSION, 2, "future", "open", emptyList(), emptyList(), ImportChoiceSet(), null, true, null)
    var contextReads = 0
    var pageFailure: DomainFailure? = null
    var pendingCount = 0L
    var latestPlan: ImportPlan? = null
    var previewFailure: DomainFailure? = null
    var failPreview = false
    var failFileMutation = false
    var failCancel = false
    var cancelFailure: DomainFailure? = null
    var cancelCalls = 0
    val resultPages = ArrayDeque<ImportResultPage>()
    val resultCalls = mutableListOf<Triple<String, ImportResultKind, String>>()
    var beforeResults: suspend () -> Unit = {}
    var contextFailure: DomainFailure? = null
    var receiptFailure: DomainFailure? = null
    var uploadAction: (suspend (suspend (ImportContext) -> Unit) -> ImportUploadBatch)? = null
    var uploadHandler: (suspend (List<ImportUpload>, ImportContext?, suspend (ImportContext) -> Unit) -> ImportUploadBatch)? = null
    var contextGate: CompletableDeferred<Unit>? = null
    val uploadCalls = mutableListOf<Pair<List<String>, ImportContext?>>()
    val events = mutableListOf<String>()
    var lastChoices: ImportChoiceSet? = null
    var beforePreview: suspend () -> Unit = {}
    val pages = ArrayDeque<ImportItemPage>()
    val pageCalls = mutableListOf<Pair<String?, String>>()
    val indexedCalls = mutableListOf<Triple<Int?, Int, String>>()
    val confirmations = mutableListOf<ImportFrozenCommand>()
    val resolveCalls = mutableListOf<Pair<String, String>>()
    val resolutions = ArrayDeque<ImportResolveResult>()
    var beforeConfirm: suspend (ImportFrozenCommand) -> Unit = {}
    override suspend fun context(sessionId: String): ImportContext { contextReads++; contextGate?.await(); contextFailure?.let { throw it }; return current }
    override suspend fun preview(sessionId: String, revision: Long, previousPlanId: String?, choices: ImportChoiceSet): ImportPlan {
        beforePreview()
        previewFailure?.let { throw it }
        events += "preview:$revision"; lastChoices = choices
        current = current.copy(revision = revision + 1, lastPlanId = TASK_PLAN, lastChoices = choices)
        if (failPreview) throw DomainFailure("request_failed", 0, FailureCategory.RECOVERABLE)
        return ImportPlan(TASK_PLAN, TASK_SESSION, revision + 1, "future", emptyMap(), mapOf("pending" to pendingCount), true, emptyList(), emptyList(), "all-cursor", emptyList()).also { latestPlan = it }
    }
    override suspend fun confirm(planId: String, revision: Long, idempotencyKey: String): ImportReceipt {
        val command = ImportFrozenCommand(planId, revision, idempotencyKey)
        confirmations += command; beforeConfirm(command); return taskReceipt()
    }
    override suspend fun resolve(planId: String, idempotencyKey: String): ImportResolveResult {
        resolveCalls += planId to idempotencyKey; return resolutions.removeFirst()
    }
    override suspend fun receipt(receiptId: String): ImportReceipt { receiptFailure?.let { throw it }; return taskReceipt() }
    override suspend fun upload(files: List<ImportUpload>, context: ImportContext?, replacingFileRef: String?, onContext: suspend (ImportContext) -> Unit): ImportUploadBatch {
        uploadCalls += files.map { it.clientFileRef } to context
        return uploadHandler?.invoke(files, context, onContext) ?: uploadAction?.invoke(onContext) ?: error("unused")
    }
    override suspend fun removeFile(sessionId: String, fileRef: String, revision: Long): ImportContext {
        return mutateFile("remove", revision)
    }
    private fun mutateFile(kind: String, revision: Long): ImportContext {
        events += "$kind:$revision"
        current = current.copy(revision = revision + 1, lastPlanId = null)
        if (failFileMutation) throw DomainFailure("request_failed", 0, FailureCategory.RECOVERABLE)
        return current
    }
    override suspend fun cancel(sessionId: String): ImportContext {
        cancelCalls++
        cancelFailure?.let { throw it }
        current = current.copy(state = "cancelled", canPreview = false, files = emptyList(), lastPlanId = null)
        if (failCancel) throw DomainFailure("request_failed", 0, FailureCategory.RECOVERABLE)
        return current
    }
    override suspend fun plan(planId: String): ImportPlan = assertNotNull(latestPlan)
    override suspend fun items(planId: String, cursor: String?, limit: Int, confirmationStatus: String, startIndex: Int?): ImportItemPage {
        assertEquals(TASK_PLAN, planId); pageCalls += cursor to confirmationStatus; indexedCalls += Triple(startIndex, limit, confirmationStatus)
        pageFailure?.let { throw it }
        return pages.removeFirst().let { page -> page.copy(startIndex = startIndex ?: page.startIndex ?: 0, totalCount = page.totalCount ?: page.items.size) }
    }
    override suspend fun item(planId: String, itemId: String) = ImportItemDetail(itemId,
        listOf(ImportMemberDetail("f:r1", null, "create_draft", StructuredObject(emptyMap()), listOf("note"), listOf("apply", "defer"), null)),
        emptyList(), emptyList(), listOf(ImportPairOption("sg_one", "refund_offset", "defer", null, emptyList(), emptyList(), listOf("accept", "defer", "ignore"), null)), emptyList(),
        ImportEntryFields(StructuredObject(mapOf("note" to StructuredText("original"))), listOf("note"), "ie_whole"))
    override suspend fun processing(planId: String, cursor: String?, limit: Int): ImportProcessingPage = error("unused")
    override suspend fun receiptByKey(key: String): ImportReceipt = error("unused")
    override suspend fun results(receiptId: String, kind: ImportResultKind, after: String, limit: Int): ImportResultPage {
        resultCalls += Triple(receiptId, kind, after)
        beforeResults()
        return resultPages.removeFirst()
    }
    override suspend fun accounts(): List<ImportAccountListItem> = error("unused")
    override suspend fun account(accountId: String): ImportAccount = error("unused")
    override suspend fun createAccount(name: String, type: String, currencies: List<String>, idempotencyKey: String): ImportAccount = error("unused")
    override suspend fun updateAccount(accountId: String, revision: Long, patch: StructuredObject, idempotencyKey: String): ImportAccount = error("unused")
    override suspend fun deleteAccount(accountId: String, revision: Long, idempotencyKey: String): ImportAccount = error("unused")
}
