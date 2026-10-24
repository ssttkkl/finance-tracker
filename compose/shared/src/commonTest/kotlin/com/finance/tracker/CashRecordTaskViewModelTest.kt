package com.finance.tracker

import com.finance.tracker.core.*
import com.finance.tracker.domain.*
import com.finance.tracker.presentation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CashRecordTaskViewModelTest {
    @Test fun cancelledEditCannotSubmitItsPreviousImpactPlan() = runTest { task { vm, repo, _ ->
        vm.open("11"); advanceUntilIdle()
        vm.prepare(CashMaintenanceRequest("edit_entry", "11", 3)); advanceUntilIdle()
        assertNotNull(vm.state.value.plan)
        assertTrue(vm.cancelEdit())
        assertNull(vm.state.value.plan); assertNull(vm.state.value.input)
        assertTrue(vm.state.value.details.isEmpty()); assertTrue(vm.state.value.affectedRecords.isEmpty())
        vm.confirmPlan(); advanceUntilIdle(); assertTrue(repo.commits.isEmpty())
    } }
    @Test fun suspendedPreviewCannotBeReportedAsDiscarded() = runTest { task { vm, repo, _ ->
        vm.open("11"); advanceUntilIdle(); repo.delayPreview = CompletableDeferred()
        vm.prepare(CashMaintenanceRequest("edit_entry", "11", 3)); runCurrent()
        assertTrue(vm.state.value.busy); assertFalse(vm.cancelEdit()); assertFalse(vm.close())
        repo.delayPreview!!.complete(Unit); advanceUntilIdle()
        assertNotNull(vm.state.value.plan); assertTrue(repo.commits.isEmpty())
    } }
    @Test fun unknownSentEditCannotDiscardItsOriginalCommand() = runTest { task { vm, repo, store ->
        vm.open("11"); advanceUntilIdle(); repo.emptyEffects = true
        repo.commitFailure = DomainFailure("network_unavailable", 0, FailureCategory.RECOVERABLE)
        vm.prepare(CashMaintenanceRequest("edit_entry", "11", 3)); advanceUntilIdle()
        val original = assertNotNull(vm.state.value.frozenCommand)
        assertFalse(vm.cancelEdit()); assertFalse(vm.close())
        assertEquals(original, vm.state.value.frozenCommand); assertEquals(original, store.pointer!!.command)
    } }
    @Test fun detailLoadsPendingIssuesOnOtherConfirmedGroupMembers() = runTest { task { vm, repo, _ ->
        repo.grouped = true
        vm.open("11"); advanceUntilIdle()
        assertEquals(listOf("12"), vm.state.value.pendingMembers.map { it.transactionId })
        assertEquals("source_update_pending", vm.state.value.pendingMembers.single().issues.single().code)
        assertEquals(listOf("11", "12"), repo.reads)
    } }
    @Test fun memberIssueUsesItsOwnRevisionAndReceiptReadTarget() = runTest { task { vm, repo, store ->
        repo.grouped = true; repo.emptyEffects = true
        repo.commitReceipt = detailReceipt().copy(retainedTransactionId = null)
        vm.open("11"); advanceUntilIdle()
        vm.resolveMemberIssue("12", "31", "keep_existing", null); advanceUntilIdle()
        val request = repo.previews.single()
        assertEquals("12", request.transactionId); assertEquals(3L, request.expectedRevision)
        assertEquals(listOf(CashIssueResolution("31", 4, "keep_existing")), request.issueResolutions)
        assertEquals("12", vm.state.value.transactionId); assertEquals("12", vm.state.value.record!!.transactionId)
        assertEquals(1, repo.commits.size); assertNull(store.pointer)
    } }
    @Test fun unreadableGroupDoesNotHidePendingMembersOrLoseSavedReceipt() = runTest { task { vm, repo, store ->
        repo.grouped = true; repo.failGroupRead = true
        vm.open("11"); advanceUntilIdle()
        assertNull(vm.state.value.record); assertEquals("request_failed", vm.state.value.errorCode)
        repo.failGroupRead = false; vm.open("11"); advanceUntilIdle()
        repo.failGroupRead = true; repo.emptyEffects = true
        vm.resolveMemberIssue("12", "31", "keep_existing", null); advanceUntilIdle()
        assertNotNull(vm.state.value.receipt); assertNull(vm.state.value.record)
        assertEquals("unavailable", vm.state.value.currentRecordStatus); assertNull(vm.state.value.errorCode)
        assertNull(store.pointer); assertEquals(1, repo.commits.size)
    } }
    @Test fun sameGroupIdWithChangedVersionOrMemberRevisionAsksForRetry() = runTest {
        for (changeVersion in listOf(true, false)) task { vm, repo, _ ->
            repo.grouped = true
            if (changeVersion) repo.groupVersion = 8 else repo.memberRevision = 4
            vm.open("11"); advanceUntilIdle()
            assertNull(vm.state.value.record); assertEquals("record_changed", vm.state.value.errorCode)
            assertTrue(repo.previews.isEmpty()); assertTrue(repo.commits.isEmpty())
        }
    }
    @Test fun repeatedReadFailureAfterKnownSaveRetainsReceiptAndNeverWritesAgain() = runTest { task { vm, repo, store ->
        repo.grouped = true; repo.emptyEffects = true
        vm.open("11"); advanceUntilIdle(); repo.failGroupRead = true
        vm.resolveMemberIssue("12", "31", "keep_existing", null); advanceUntilIdle()
        val saved = assertNotNull(vm.state.value.receipt)
        repeat(2) { vm.open("11"); advanceUntilIdle(); assertEquals(saved, vm.state.value.receipt) }
        assertEquals("unavailable", vm.state.value.currentRecordStatus); assertNull(vm.state.value.errorCode)
        assertEquals(1, repo.previews.size); assertEquals(1, repo.commits.size); assertNull(store.pointer)
        repo.failGroupRead = false; vm.open("11"); advanceUntilIdle()
        assertEquals(saved, vm.state.value.receipt); assertEquals("available", vm.state.value.currentRecordStatus)
    } }
    @Test fun lateReadCannotReopenClosedDetail() = runTest { task { vm, repo, _ ->
        repo.delayRead = CompletableDeferred(); repo.ignoreReadCancellation = true; vm.open("11"); runCurrent(); vm.close()
        repo.delayRead!!.complete(Unit); advanceUntilIdle()
        assertNull(vm.state.value.record); assertNull(vm.state.value.transactionId)
    } }
    @Test fun ordinaryDraftSaveKeepsDraftAndExplicitClear() = runTest { task { vm, repo, store ->
        vm.open("11"); advanceUntilIdle()
        val patch = StructuredObject(mapOf("amount" to StructuredText("0.00"), "note" to StructuredText("")))
        vm.saveDraft(patch); advanceUntilIdle()
        assertEquals("draft", vm.state.value.record!!.state); assertEquals(patch, repo.draftPatch)
        assertEquals(1, vm.state.value.savedRevision); assertNull(store.pointer); assertEquals("save_draft", repo.previews.single().intent)
    } }
    @Test fun rejectsFieldsOutsidePermissionBeforeNetwork() = runTest { task { vm, repo, _ ->
        vm.open("11"); advanceUntilIdle()
        vm.saveDraft(StructuredObject(mapOf("source_type" to StructuredText("fabricated")))); advanceUntilIdle()
        assertNull(repo.draftPatch); assertEquals("field_not_editable", vm.state.value.errorCode)
    } }
    @Test fun previewFailureKeepsRevisionAndNeverCommits() = runTest { task { vm, repo, _ ->
        vm.open("11"); advanceUntilIdle(); repo.previewFailure = DomainFailure("record_changed", 409, FailureCategory.RECOVERABLE)
        vm.prepare(CashMaintenanceRequest("finalize_draft", "11", 3)); advanceUntilIdle()
        assertEquals(3L, vm.state.value.record!!.rowRevision); assertTrue(repo.commits.isEmpty())
        assertEquals("record_changed", vm.state.value.errorCode)
    } }
    @Test fun unknownCommitUsesOriginalAndResolvesWithoutSecondWrite() = runTest { task { vm, repo, store ->
        vm.open("11"); advanceUntilIdle(); vm.prepare(CashMaintenanceRequest("finalize_draft", "11", 3)); advanceUntilIdle()
        repo.commitFailure = DomainFailure("network_unavailable", 0, FailureCategory.RECOVERABLE)
        vm.confirmPlan(); advanceUntilIdle(); val original = assertNotNull(vm.state.value.frozenCommand)
        assertEquals(original, store.pointer!!.command)
        vm.prepare(CashMaintenanceRequest("finalize_draft", "11", 3)); advanceUntilIdle(); assertEquals(1, repo.previews.size)
        repo.resolution = ImportResolveResult("committed", detailReceipt()); vm.resolve(); advanceUntilIdle()
        assertEquals(listOf(original), repo.commits); assertNull(vm.state.value.frozenCommand)
        assertNull(store.pointer); assertEquals(1, vm.state.value.savedRevision)
    } }
    @Test fun restoreResolvesBeforeOpeningAnotherRecord() = runTest { task { vm, repo, store ->
        val original = ImportFrozenCommand("mp_one", 2, "original")
        store.pointer = CashMaintenanceRecoveryPointer("11", original)
        vm.restore(); advanceUntilIdle(); vm.open("12"); advanceUntilIdle()
        assertEquals(original, vm.state.value.frozenCommand); assertEquals("11", vm.state.value.transactionId)
        assertTrue(repo.reads.isEmpty())
    } }
    @Test fun storageFailurePreventsWriteAndReceiptSurvivesUnavailableRead() = runTest { task { vm, repo, store ->
        vm.open("11"); advanceUntilIdle(); store.failSave = true
        vm.saveDraft(StructuredObject(mapOf("amount" to StructuredText("1")))); advanceUntilIdle(); assertTrue(repo.commits.isEmpty())
        store.failSave = false; repo.currentUnavailable = true
        vm.saveDraft(StructuredObject(mapOf("amount" to StructuredText("1")))); advanceUntilIdle()
        assertEquals(1, vm.state.value.savedRevision); assertEquals("unavailable", vm.state.value.currentRecordStatus)
        assertTrue(vm.state.value.receipt!!.committed)
    } }
    @Test fun restoredPointerLoadCannotBeCancelledIntoPermanentBusy() = runTest { task { vm, repo, store ->
        store.delayLoad = CompletableDeferred(); vm.restore(); runCurrent(); vm.open("11")
        store.delayLoad!!.complete(Unit); advanceUntilIdle(); assertFalse(vm.state.value.busy)
        vm.open("11"); advanceUntilIdle(); assertEquals("11", vm.state.value.record!!.transactionId)
    } }
    @Test fun failedNewPreviewCannotCommitPreviouslyValidPlan() = runTest { task { vm, repo, _ ->
        vm.open("11"); advanceUntilIdle(); vm.prepare(CashMaintenanceRequest("finalize_draft", "11", 3)); advanceUntilIdle()
        repo.previewFailure = DomainFailure("record_changed", 409, FailureCategory.RECOVERABLE)
        vm.prepare(CashMaintenanceRequest("finalize_draft", "11", 3, StructuredObject(mapOf("note" to StructuredText("new"))))); advanceUntilIdle()
        vm.confirmPlan(); advanceUntilIdle(); assertTrue(repo.commits.isEmpty())
    } }
    @Test fun laterPageImpactIsLoadedBeforeConfirmation() = runTest { task { vm, repo, _ ->
        vm.open("11"); advanceUntilIdle()
        repo.paged = true
        vm.prepare(CashMaintenanceRequest("finalize_draft", "11", 3)); advanceUntilIdle()
        assertEquals(listOf<String?>("second"), repo.itemPages)
        assertEquals(listOf("later"), vm.state.value.details.map { it.itemId })
        assertEquals(setOf("12"), vm.state.value.affectedRecords.keys)
        assertTrue(repo.commits.isEmpty())
    } }
    @Test fun failedDetailReadCannotCommitNewOrPreviousPlan() = runTest { task { vm, repo, _ ->
        vm.open("11"); advanceUntilIdle()
        vm.prepare(CashMaintenanceRequest("finalize_draft", "11", 3)); advanceUntilIdle()
        repo.paged = true; repo.detailFailure = true
        vm.prepare(CashMaintenanceRequest("finalize_draft", "11", 3)); advanceUntilIdle()
        vm.confirmPlan(); advanceUntilIdle()
        assertTrue(vm.state.value.previewDirty); assertTrue(repo.commits.isEmpty())
    } }
    @Test fun relationInspectionNeverCommitsEvenWhenNoExtraEffects() = runTest { task { vm, repo, _ ->
        vm.open("11"); advanceUntilIdle(); repo.emptyEffects = true
        vm.beginRelations(); advanceUntilIdle(); vm.confirmPlan(); advanceUntilIdle()
        assertTrue(vm.state.value.selectionPlan); assertTrue(repo.commits.isEmpty())
        assertEquals("maintain_relations", repo.previews.single().intent)
    } }
    @Test fun newlyDiscoveredPublicConflictReturnsToChoicesWithoutSavingDefer() = runTest { task { vm, repo, _ ->
        vm.open("11"); advanceUntilIdle(); repo.paged = true; repo.emergingConflict = true
        vm.beginRelations(); advanceUntilIdle()
        vm.saveRelationChoices(listOf(CashRelationDecision("group", "accept"))); advanceUntilIdle()
        assertTrue(vm.state.value.selectionPlan); assertTrue(repo.commits.isEmpty())
        assertEquals("accept", vm.state.value.input!!.relationChoices.single().action)
        assertEquals(setOf("note"), vm.state.value.details.single().relationChoices.single().commonFieldConflicts.keys)
        vm.confirmPlan(); advanceUntilIdle(); assertTrue(repo.commits.isEmpty())
        vm.saveRelationChoices(listOf(CashRelationDecision("group", "accept", commonFields = StructuredObject(mapOf("note" to StructuredText("乙备注")))))); advanceUntilIdle()
        assertEquals(1, repo.commits.size)
    } }
    @Test fun incompletePlanRequestsRetryWithoutLegacyConfirmationPath() = runTest { task { vm, repo, _ ->
        vm.open("11"); advanceUntilIdle(); repo.missingEffects = true
        vm.prepare(CashMaintenanceRequest("finalize_draft", "11", 3)); advanceUntilIdle()
        assertNull(vm.state.value.plan); assertEquals("plan_changed", vm.state.value.errorCode)
        vm.confirmPlan(); advanceUntilIdle(); assertTrue(repo.commits.isEmpty())
    } }
    @Test fun definiteCommitRejectionClearsOldCommandAndAllowsReadRetry() = runTest {
        for (failure in listOf(
            DomainFailure("record_changed", 409, FailureCategory.RECOVERABLE),
            DomainFailure("plan_expired", 410, FailureCategory.RECOVERABLE),
            DomainFailure("commit_failed", 503, FailureCategory.RECOVERABLE, commitState = "not_committed"),
        )) task { vm, repo, store ->
            vm.open("11"); advanceUntilIdle()
            repo.commitFailure = failure
            vm.saveDraft(StructuredObject(mapOf("note" to StructuredText("本次修改")))); advanceUntilIdle()
            assertNull(store.pointer); assertFalse(vm.state.value.unresolved); assertNull(vm.state.value.plan)
            assertEquals(failure.code, vm.state.value.errorCode); assertEquals(0, vm.state.value.savedRevision)
            vm.open("11"); advanceUntilIdle()
            assertEquals(3L, vm.state.value.record!!.rowRevision); assertEquals(1, repo.commits.size)
        }
    }
    @Test fun consumedOrAmbiguousErrorKeepsOriginalInsteadOfGuessingNotSaved() = runTest {
        for (failure in listOf(
            DomainFailure("edit_context_committed", 409, FailureCategory.RECOVERABLE),
            DomainFailure("idempotency_conflict", 409, FailureCategory.RECOVERABLE),
            DomainFailure("record_changed", 503, FailureCategory.RECOVERABLE, commitState = "unknown"),
        )) task { vm, repo, store ->
            vm.open("11"); advanceUntilIdle(); repo.commitFailure = failure
            vm.saveDraft(StructuredObject(mapOf("note" to StructuredText("本次修改")))); advanceUntilIdle()
            assertNotNull(store.pointer); assertTrue(vm.state.value.unresolved); assertEquals(1, repo.commits.size)
        }
    }
    @Test fun failedOrPartialReceiptShowsErrorAndReleasesTheFinishedCommand() = runTest {
        for (status in listOf("failed", "partial")) task { vm, repo, store ->
            vm.open("11"); advanceUntilIdle(); repo.commitReceipt = detailReceipt().copy(status = status)
            vm.saveDraft(StructuredObject(mapOf("note" to StructuredText("本次修改")))); advanceUntilIdle()
            assertEquals("request_failed", vm.state.value.errorCode)
            assertEquals(status, vm.state.value.receipt!!.status); assertFalse(vm.state.value.unresolved)
            assertNull(store.pointer); assertEquals(1, repo.commits.size)
        }
    }
    @Test fun retryingFailedReceiptReadDoesNotPresentItAsSuccessfulSave() = runTest {
        for (status in listOf("failed", "partial")) task { vm, repo, _ ->
            vm.open("11"); advanceUntilIdle(); repo.commitReceipt = detailReceipt().copy(status = status)
            vm.saveDraft(StructuredObject(mapOf("note" to StructuredText("本次修改")))); advanceUntilIdle()
            repo.currentUnavailable = true
            vm.open("11"); advanceUntilIdle()
            assertEquals(status, vm.state.value.receipt!!.status)
            assertEquals("request_failed", vm.state.value.errorCode)
            assertEquals(1, repo.commits.size); assertEquals(1, repo.previews.size)
        }
    }
    private suspend fun TestScope.task(block: suspend TestScope.(CashRecordTaskViewModel, DetailRepo, DetailStore) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { block(CashRecordTaskViewModel(DetailRepo().also { repo = it }, DetailStore().also { store = it }) { "original" }, repo, store) }
        finally { Dispatchers.resetMain() }
    }
    private lateinit var repo: DetailRepo
    private lateinit var store: DetailStore
}
private fun detailRecord() = CashCurrentRecord("import", null, "11", "draft", 3, "pending", StructuredObject(emptyMap()), listOf("amount", "note"), emptyList(), emptyList(), null)
private fun detailReceipt() = ImportReceipt("receipt1", "mp_one", "finalize_draft", true, "2026-10-24T00:00:00Z", "completed", emptyMap(), emptyMap(), null, null, null, null, null, "11", null, null, null)
private class DetailStore : CashMaintenanceRecoveryStore {
    var delayLoad: CompletableDeferred<Unit>? = null
    var pointer: CashMaintenanceRecoveryPointer? = null; var failSave = false
    override suspend fun load(): CashMaintenanceRecoveryPointer? { delayLoad?.await(); return pointer }
    override suspend fun save(value: CashMaintenanceRecoveryPointer) { if (failSave) error("unavailable"); pointer = value }
    override suspend fun clear() { pointer = null }
}
private class DetailRepo : CashMaintenanceRepository {
    var delayPreview: CompletableDeferred<Unit>? = null
    var grouped = false; var failGroupRead = false; var groupVersion = 7L; var memberRevision = 3L
    override suspend fun currentGroup(projectionId: String): CashCurrentGroup {
        if (failGroupRead) throw DomainFailure("request_failed", 503, FailureCategory.RECOVERABLE)
        return CashCurrentGroup(groupVersion, listOf(CashCurrentMember("11", 3, "confirmed"), CashCurrentMember("12", memberRevision, "pending"), CashCurrentMember("13", 3, "confirmed")))
    }
    override suspend fun sources(projectionId: String): CashSourceEvidence = error("unused")
    var missingEffects = false; var emergingConflict = false; var latestChoices = emptyList<CashRelationDecision>(); var emptyEffects = false; var paged = false; var detailFailure = false; val itemPages = mutableListOf<String?>()
    var delayRead: CompletableDeferred<Unit>? = null; var ignoreReadCancellation = false; val reads = mutableListOf<String>()
    var draftPatch: StructuredObject? = null; val previews = mutableListOf<CashMaintenanceRequest>()
    val commits = mutableListOf<ImportFrozenCommand>(); var previewFailure: DomainFailure? = null
    var commitFailure: DomainFailure? = null; var currentUnavailable = false; var commitReceipt = detailReceipt()
    var resolution = ImportResolveResult("pending", null)
    override suspend fun record(transactionId: String): CashCurrentRecord { reads += transactionId; if (ignoreReadCancellation) withContext(NonCancellable) { delayRead?.await() } else delayRead?.await(); if (currentUnavailable) throw DomainFailure("request_failed", 503, FailureCategory.RECOVERABLE); return detailRecord().copy(transactionId = transactionId,
        state = if (grouped) "posted" else "draft",
        confirmationStatus = if (grouped && transactionId == "11") "confirmed" else "pending",
        projectionRef = if (grouped) CashCurrentProjectionRef(transactionId, "group-one", 1, 7) else null,
        issues = if (grouped && transactionId == "12") listOf(CashRecordIssue("31", 4, "source_update_pending", "来源有更新", null, "posting", "open_record", listOf("keep_existing"), StructuredObject(emptyMap()), null, emptyList())) else emptyList()) }
    override suspend fun saveDraft(transactionId: String, revision: Long, patch: StructuredObject, key: String): CashDraftSaveResult {
        draftPatch = patch; return CashDraftSaveResult(detailReceipt().copy(operationKind = "save_draft"), if (currentUnavailable) "unavailable" else "available", detailRecord().takeUnless { currentUnavailable })
    }
    override suspend fun preview(input: CashMaintenanceRequest): CashMaintenancePlan {
        delayPreview?.await()
        previews += input; latestChoices = input.relationChoices; previewFailure?.let { throw it }; if (input.intent == "save_draft") draftPatch = input.patch
        return CashMaintenancePlan("mp_one", "context", 2, "2099-01-01T00:00:00Z", emptyMap(), emptyMap(), true, emptyList(), if (paged) "second" else null, emptyList(), additionalEffects = if (missingEffects) null else if (emergingConflict) (if (latestChoices.any { it.action == "accept" && it.commonFields == null }) listOf(CashAdditionalEffect("choice_changed", "group")) else emptyList()) else if (input.intent == "save_draft" || emptyEffects) emptyList() else listOf(CashAdditionalEffect("new_attention", "11")))
    }
    override suspend fun plan(planId: String) = error("unused")
    override suspend fun currentPlan(contextId: String) = error("unused")
    override suspend fun items(planId: String, cursor: String?, confirmationStatus: String): ImportItemPage { itemPages += cursor; return ImportItemPage(listOf(ImportProjectedItem("later", "pending", "draft", ImportDisplay(null, null, null, null, null, emptyList()), false, listOf(ImportMemberRef(null, "12")), emptyList(), true)), null) }
    override suspend fun item(planId: String, itemId: String): ImportItemDetail { if (detailFailure) error("unavailable"); return ImportItemDetail(itemId, listOf(ImportMemberDetail(null, "12", "duplicate", StructuredObject(emptyMap()), emptyList(), emptyList(), 3)), emptyList(), emptyList(), if (emergingConflict) listOf(ImportPairOption("group", "payment_mirror", null, if (latestChoices.any { it.action == "accept" && it.commonFields != null }) "accept" else "defer", null, emptyList(), listOf(ImportMemberRef(null, "11"), ImportMemberRef(null, "12")), listOf("accept", "defer", "ignore"), null, commonFieldConflicts = if (latestChoices.isNotEmpty()) mapOf("note" to listOf(StructuredText("甲备注"), StructuredText("乙备注"))) else emptyMap())) else emptyList(), emptyList()) }
    override suspend fun commit(command: ImportFrozenCommand): ImportReceipt { commits += command; commitFailure?.let { throw it }; return commitReceipt }
    override suspend fun resolve(command: ImportFrozenCommand) = resolution
    override suspend fun receiptByKey(key: String) = detailReceipt()
}
