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
        assertEquals("request_failed", vm.state.value.errorCode)
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

    @Test fun lostFileCommandResponseLocksBothRemoveAndScopeUntilRefresh() = runTest { task { vm, repo, _ ->
        vm.restore(); advanceUntilIdle()
        for (scopeChange in listOf(false, true)) {
            vm.preview(); advanceUntilIdle()
            assertTrue(vm.state.value.canConfirm)
            repo.failFileMutation = true
            if (scopeChange) vm.setSourceScope("f1", "other") else vm.removeFile("f1")
            advanceUntilIdle()
            assertFalse(vm.state.value.canEdit)
            assertFalse(vm.state.value.canConfirm)
            vm.confirm(); advanceUntilIdle(); assertTrue(repo.confirmations.isEmpty())
            repo.failFileMutation = false
            vm.refresh(); advanceUntilIdle()
            assertTrue(vm.state.value.canEdit)
            assertEquals(repo.current.revision, vm.state.value.context?.revision)
            assertNull(vm.state.value.plan)
        }
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

    @Test fun interruptedUploadRequiresLatestContextBeforeFurtherEdits() = runTest { task { vm, repo, _ ->
        vm.restore(); advanceUntilIdle()
        repo.uploadAction = { progress ->
            progress(repo.current)
            repo.current = repo.current.copy(revision = 4)
            ImportUploadBatch(repo.current.copy(revision = 2), listOf(ImportLocalFileFailure("bad", "import_file_read_failed")),
                ImportUploadInterruption("request_failed", 0, null, listOf("uncertain", "remaining")))
        }
        vm.upload(listOf(ImportUpload("first", taskFile()))); advanceUntilIdle()
        assertFalse(vm.state.value.canEdit)
        assertEquals(listOf("uncertain", "remaining"), vm.state.value.unprocessedFileRefs)
        assertEquals("bad", vm.state.value.localFileFailures.single().clientFileRef)
        vm.refresh(); advanceUntilIdle()
        assertTrue(vm.state.value.canEdit)
        assertEquals(4, vm.state.value.context?.revision)
    } }

    @Test fun fileChangeFlushesCompleteChoicesBeforeChangingCurrentRevision() = runTest { task { vm, repo, _ ->
        repo.current = repo.current.copy(sourceAccounts = listOf(ImportSourceAccount("src", "wechat", "synthetic", listOf("CNY"), 1, null, "none", null)))
        vm.restore(); advanceUntilIdle()
        vm.selectAccount("src", ImportAccountTarget(null, null))
        vm.removeFile("f1"); advanceUntilIdle()
        assertEquals(listOf("preview:2", "remove:3"), repo.events)
        assertNull(repo.lastChoices?.accountSelections?.single()?.target?.accountId)
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
    val events = mutableListOf<String>()
    var lastChoices: ImportChoiceSet? = null
    val pages = ArrayDeque<ImportItemPage>()
    val pageCalls = mutableListOf<Pair<String?, String>>()
    val indexedCalls = mutableListOf<Triple<Int?, Int, String>>()
    val confirmations = mutableListOf<ImportFrozenCommand>()
    val resolveCalls = mutableListOf<Pair<String, String>>()
    val resolutions = ArrayDeque<ImportResolveResult>()
    var beforeConfirm: suspend (ImportFrozenCommand) -> Unit = {}
    override suspend fun context(sessionId: String): ImportContext { contextReads++; contextFailure?.let { throw it }; return current }
    override suspend fun preview(sessionId: String, revision: Long, previousPlanId: String?, choices: ImportChoiceSet): ImportPlan {
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
    override suspend fun upload(files: List<ImportUpload>, context: ImportContext?, replacingFileRef: String?, onContext: suspend (ImportContext) -> Unit): ImportUploadBatch = uploadAction?.invoke(onContext) ?: error("unused")
    override suspend fun setSourceScope(sessionId: String, fileRef: String, revision: Long, label: String?): ImportContext = mutateFile("scope", revision)
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
        emptyList(), emptyList(), listOf(ImportPairOption("sg_one", "refund_offset", null, "defer", null, emptyList(), emptyList(), listOf("accept", "defer", "ignore"), null)), emptyList(),
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
