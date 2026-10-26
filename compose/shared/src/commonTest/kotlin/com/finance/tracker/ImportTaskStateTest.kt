package com.finance.tracker

import com.finance.tracker.domain.*
import com.finance.tracker.presentation.*
import kotlin.test.*

class ImportTaskStateTest {
    @Test fun importAccountTypeMatchesTheExistingNameRule() {
        assertEquals("loan", cashImportAccountTypeForName("花呗账户"))
        assertEquals("loan", cashImportAccountTypeForName("招商银行信用卡"))
        assertEquals("cash", cashImportAccountTypeForName("微信零钱"))
    }

    @Test fun repeatedMemberApplyPreservesEarlierPatchesAndExplicitNullWhileOtherDecisionsClearThem() {
        val fields = detail().copy(members = listOf(detail().members.single().copy(
            plannedAction = "update_posted", rowRevision = 3,
            editableFields = listOf("amount", "account_id"), allowedActions = listOf("apply", "keep_existing", "defer"))))
        val first = prepared().loadedDetail("ip_one", fields).editRow(ImportRecordChoice("f:r1", ImportRowDecision.APPLY,
            StructuredObject(mapOf("amount" to StructuredText("-12.5000")))))
        val next = first.loadedPlan(plan("ip_two", 5), first.choices).loadedDetail("ip_two", fields)
            .editRow(ImportRecordChoice("f:r1", ImportRowDecision.APPLY, StructuredObject(mapOf("account_id" to StructuredNull))))
        assertEquals(mapOf("amount" to StructuredText("-12.5000"), "account_id" to StructuredNull), next.choices.rows.single().patch.fields)
        for (decision in listOf(ImportRowDecision.DEFER, ImportRowDecision.KEEP_EXISTING)) {
            val cleared = next.editRow(ImportRecordChoice("f:r1", decision))
            assertTrue(cleared.choices.rows.single().patch.fields.isEmpty())
            val applied = cleared.editRow(ImportRecordChoice("f:r1", ImportRowDecision.APPLY, StructuredObject(mapOf("amount" to StructuredText("-3")))))
            assertEquals(mapOf("amount" to StructuredText("-3")), applied.choices.rows.single().patch.fields)
        }
    }

    @Test fun newMemberCannotKeepExistingEvenIfAnOldServerOffersTheAction() {
        val fields = detail().copy(members = listOf(detail().members.single().copy(
            allowedActions = listOf("apply", "keep_existing", "defer"))))
        val state = prepared().loadedDetail("ip_one", fields)
        assertFailsWith<DomainFailure> { state.editRow(ImportRecordChoice("f:r1", ImportRowDecision.KEEP_EXISTING)) }
        assertTrue(state.choices.rows.isEmpty())
    }

    @Test fun invalidPreviewInputClearsOnlyOptionalDecisionsAndAllowsReviewAgain() {
        val before = prepared().loadedDetail("ip_one", detail())
            .editRow(ImportRecordChoice("f:r1", ImportRowDecision.DEFER))
            .copy(needsContextRefresh = true)
        val after = before.rejectInvalidPreviewInput("plan_input_invalid")
        assertTrue(after.choices.rows.isEmpty())
        assertEquals(before.choices.accountSelections, after.choices.accountSelections)
        assertTrue(after.previewDirty)
        assertFalse(after.needsContextRefresh)
        assertTrue(after.canEdit)
    }

    @Test fun wholeEntryEditUsesOnlyItsExplicitTargetAndPreservesSourceDecisions() {
        val entry = ImportEntryFields(StructuredObject(mapOf("note" to StructuredText("original"))), listOf("note", "category_id"), "ie_whole")
        val original = prepared().loadedDetail("ip_one", detail().copy(entry = entry))
            .editRow(ImportRecordChoice("f:r1", ImportRowDecision.DEFER))
        val edited = original.editEntry(ImportEntryChoice("ie_whole", StructuredObject(mapOf("note" to StructuredText("")))))
            .editEntry(ImportEntryChoice("ie_whole", StructuredObject(mapOf("category_id" to StructuredNull))))
        assertEquals(original.choices.rows, edited.choices.rows)
        assertEquals(StructuredText(""), edited.choices.entries.single().patch.fields["note"])
        assertEquals(StructuredNull, edited.choices.entries.single().patch.fields["category_id"])
        assertTrue(edited.previewDirty)
        assertFailsWith<DomainFailure> { original.editEntry(ImportEntryChoice("invented", StructuredObject(emptyMap()))) }
        assertFailsWith<DomainFailure> { original.editEntry(ImportEntryChoice("ie_whole", StructuredObject(mapOf("amount" to StructuredText("1"))))) }
    }

    @Test fun defaultAllUsesItsOwnPageWhileExplicitPendingNeverReusesAllCursor() {
        val initial = prepared().loadedPlan(plan().copy(itemCounts = mapOf("pending" to 2L)), ImportChoiceSet())
        assertEquals("all", initial.filter)
        assertEquals(plan().items, initial.items)
        assertTrue(initial.itemsInitialized)
        val pending = initial.changeFilter("pending")
        assertTrue(pending.items.isEmpty())
        assertNull(pending.nextCursor)
        assertFalse(pending.itemsInitialized)
    }

    @Test fun explicitAllSurvivesRepreviewWhileZeroPendingAutomaticallyShowsAll() {
        val pendingPlan = plan().copy(itemCounts = mapOf("pending" to 2L))
        val initial = prepared().loadedPlan(pendingPlan, ImportChoiceSet())
        val all = initial.changeFilter("all").loadedPlan(pendingPlan.copy(planId = "ip_two", sessionRevision = 5), ImportChoiceSet())
        assertEquals("all", all.filter)
        assertEquals(pendingPlan.items, all.items)
        val cleared = initial.loadedPlan(pendingPlan.copy(itemCounts = mapOf("pending" to 0L)), ImportChoiceSet())
        assertEquals("all", cleared.filter)
        assertTrue(cleared.itemsInitialized)
        val pendingAgain = cleared.loadedPlan(pendingPlan, ImportChoiceSet())
        assertEquals("all", pendingAgain.filter)
    }

    @Test fun authoritativeSameSessionReceiptDropsOldPlanButOrdinaryReceiptCannotBypassBinding() {
        val session = "a".repeat(43)
        val first = "ip_$session.${"b".repeat(16)}"
        val other = "ip_$session.${"c".repeat(16)}"
        val oldFile = ImportPlanFile("old", "synthetic.csv", "failed", null, emptyList(), "row_unreadable", true)
        val prepared = prepared().let { it.copy(context = it.context!!.copy(sessionId = session),
            plan = it.plan!!.copy(planId = first, sessionId = session, files = listOf(oldFile)), resultFiles = listOf(oldFile)) }
        val pending = prepared.beginConfirmation("original").failed(DomainFailure("request_failed", 0, FailureCategory.RECOVERABLE))
        val receipt = receipt().copy(planId = other)
        assertNull(pending.received(receipt).receipt)
        val completed = pending.resolved(ImportResolveResult("committed", receipt))
        assertEquals(receipt, completed.receipt)
        assertTrue(completed.resultFiles.isEmpty())
        assertNull(completed.plan)
        for (invalid in listOf(receipt.copy(planId = "ip_${"z".repeat(43)}.${"c".repeat(16)}"), receipt.copy(planId = first, operationKind = "account_create"))) {
            val rejected = pending.resolved(ImportResolveResult("committed", invalid))
            assertNull(rejected.receipt)
            assertEquals(ImportSubmissionState.UNKNOWN, rejected.submission)
        }
    }

    @Test fun restoresFullChoicesAndNeverReplacesExplicitClearWithSuggestion() {
        val clear = ImportAccountSelection("src", ImportAccountTarget(null, null))
        val choices = ImportChoiceSet(listOf(clear), listOf(ImportRecordChoice("f:r1", ImportRowDecision.DEFER)),
            listOf(ImportPairChoice("stale", ImportPairDecision.IGNORE)))
        val state = ImportTaskUiState().loadedContext(context(choices))
        assertEquals(choices, state.choices)
        assertNull(state.choices.accountSelections.single().target.accountId)
    }

    @Test fun unchangedSuggestionsFillOnlyMissingSelections() {
        val state = ImportTaskUiState().loadedContext(context(null))
        assertEquals("a1", state.choices.accountSelections.single().target.accountId)
        assertEquals(7L, state.choices.accountSelections.single().target.revision)
    }

    @Test fun confirmsOnceWithFrozenPlanRevisionAndKey() {
        val prepared = prepared()
        val sending = prepared.beginConfirmation("original-key")
        assertEquals(ImportFrozenCommand("ip_one", 4, "original-key"), sending.frozenCommand)
        assertEquals(ImportSubmissionState.SAVING, sending.submission)
        assertFalse(sending.canConfirm)
        assertEquals(sending, sending.beginConfirmation("another-key"))
        assertFalse(sending.canEdit)
    }

    @Test fun unknownFailureKeepsFrozenCommandAndBlocksEditing() {
        val sending = prepared().beginConfirmation("original")
        val unknown = sending.failed(DomainFailure("network_unavailable", 0, FailureCategory.RECOVERABLE))
        assertEquals(ImportSubmissionState.UNKNOWN, unknown.submission)
        assertEquals(sending.frozenCommand, unknown.frozenCommand)
        assertFalse(unknown.canEdit)
        assertFalse(unknown.canConfirm)
    }

    @Test fun resolvePendingRetryAndNotCommittedStayDistinct() {
        val unknown = prepared().beginConfirmation("original").failed(DomainFailure("unknown_error", 0, FailureCategory.UNKNOWN))
        val pending = unknown.resolved(ImportResolveResult("pending", null))
        assertEquals(ImportSubmissionState.UNKNOWN, pending.submission)
        assertEquals(unknown.frozenCommand, pending.frozenCommand)
        val retry = pending.resolved(ImportResolveResult("retry_original", null))
        assertEquals(ImportSubmissionState.RETRY_ORIGINAL, retry.submission)
        assertEquals(unknown.frozenCommand, retry.frozenCommand)
        val absent = pending.resolved(ImportResolveResult("not_committed", null))
        assertNull(absent.frozenCommand)
        assertNull(absent.plan)
        assertTrue(absent.needsContextRefresh)
        assertFalse(absent.canConfirm)
    }

    @Test fun committedResolutionRequiresRealCommittedReceipt() {
        val unknown = prepared().beginConfirmation("original").failed(DomainFailure("unknown_error", 0, FailureCategory.UNKNOWN))
        assertEquals(ImportSubmissionState.UNKNOWN, unknown.resolved(ImportResolveResult("committed", null)).submission)
        val done = unknown.resolved(ImportResolveResult("committed", receipt()))
        assertEquals(ImportTaskPhase.RESULT, done.phase)
        assertEquals("receipt1", done.receipt?.receiptId)
        assertNull(done.frozenCommand)
        assertFalse(done.canEdit)
    }

    @Test fun newPlanKeepsOldAndNewGroupChoicesButClearsOldDetailAndPages() {
        val choices = ImportChoiceSet(relations = listOf(ImportPairChoice("old", ImportPairDecision.ACCEPT), ImportPairChoice("fresh", ImportPairDecision.IGNORE)))
        val state = prepared().copy(choices = choices, processingItems = listOf(processing()))
        val latest = state.loadedPlan(plan("ip_two", 5), choices)
        assertEquals(choices, latest.choices)
        assertTrue(latest.details.isEmpty())
        assertTrue(latest.processingItems.isEmpty())
        assertEquals("ip_two", latest.context?.lastPlanId)
        assertEquals(5L, latest.context?.revision)
    }

    @Test fun latePagesAndDetailsNeverCrossPlanOrFilter() {
        val state = prepared().beginRequest()
        val request = assertNotNull(state.activeRequest)
        assertEquals(state, state.acceptItemPage(request, "other-plan", "all", ImportItemPage(emptyList(), null), true))
        assertEquals(state, state.acceptItemPage(request, "ip_one", "pending", ImportItemPage(emptyList(), null), true))
        assertEquals(state, state.acceptDetail(request, "other-plan", detail()))
        val filtered = state.finishRequest(request).changeFilter("pending")
        assertFalse(filtered.itemsInitialized)
        assertNull(filtered.nextCursor)
        assertTrue(filtered.items.isEmpty())
    }

    @Test fun allowedEditsBecomeDirtyUntilNewPreviewAndCannotUseMissingFields() {
        val state = prepared().loadedDetail("ip_one", detail())
        val changed = state.editRow(ImportRecordChoice("f:r1", ImportRowDecision.APPLY, StructuredObject(mapOf("note" to StructuredText("新备注")))))
        assertTrue(changed.previewDirty)
        assertFalse(changed.canConfirm)
        assertFailsWith<DomainFailure> { state.editRow(ImportRecordChoice("f:r1", ImportRowDecision.APPLY, StructuredObject(mapOf("amount" to StructuredText("9"))))) }
        assertEquals(state.choices, state.beginConfirmation("original").editRow(ImportRecordChoice("f:r1", ImportRowDecision.DEFER)).choices)
    }

    @Test fun oldPlanResponseCannotUndoANewerFileRevision() {
        val newer = prepared().copy(context = context(ImportChoiceSet()).copy(revision = 6, lastPlanId = null), plan = null).beginRequest()
        assertEquals(newer, newer.acceptPlan(assertNotNull(newer.activeRequest), plan(revision = 4), ImportChoiceSet()))
    }

    @Test fun notCommittedResolutionRequiresContextRefreshBeforeAnyMoreEdits() {
        val pending = prepared().beginConfirmation("original").failed(DomainFailure("unknown_error", 0, FailureCategory.UNKNOWN))
        val absent = pending.resolved(ImportResolveResult("not_committed", null))
        assertFalse(absent.canEdit)
        assertEquals(absent, absent.selectAccount("src", ImportAccountTarget("a2", 1)))
    }

    @Test fun knownRollbackRetriesTheSameFrozenRequestAndCommittedResultClearsPreviewRows() {
        val sending = prepared().loadedDetail("ip_one", detail()).beginConfirmation("original")
        val rolledBack = sending.failed(DomainFailure("commit_failed", 503, FailureCategory.RECOVERABLE, commitState = "not_committed"))
        assertEquals(ImportSubmissionState.RETRY_ORIGINAL, rolledBack.submission)
        assertEquals(sending.frozenCommand, rolledBack.retryOriginal().frozenCommand)
        val done = rolledBack.received(receipt())
        assertTrue(done.details.isEmpty())
        assertTrue(done.items.isEmpty())
    }

    @Test fun committedResultRejectsLatePreviewPagesDetailsAndProcessing() {
        val sending = prepared().beginConfirmation("original")
        val request = assertNotNull(sending.activeRequest)
        val done = sending.acceptReceipt(request, receipt())
        assertNull(done.plan)
        assertEquals(done, done.acceptItemPage(request, "ip_one", "all", ImportItemPage(plan().items, null), false))
        assertEquals(done, done.acceptDetail(request, "ip_one", detail()))
        assertEquals(done, done.acceptProcessingPage(request, "ip_one", ImportProcessingPage(listOf(processing()), null), false))
    }

    @Test fun oldContextRevisionAndOtherSessionCannotOverwriteCurrentPlan() {
        val state = prepared().beginRequest()
        val request = assertNotNull(state.activeRequest)
        assertEquals(state, state.acceptContext(request, context(ImportChoiceSet())))
        assertEquals(state, state.acceptContext(request, context(ImportChoiceSet()).copy(sessionId = "other")))
        val restarted = ImportTaskUiState().beginRequest()
        assertEquals(restarted, restarted.acceptContext(request, context(null)))
    }

    @Test fun oldResolveCannotClearNewFrozenSubmissionAndWrongReceiptCannotCompleteIt() {
        val first = prepared().beginConfirmation("first").failed(DomainFailure("unknown_error", 0, FailureCategory.UNKNOWN)).beginResolution()
        val oldRequest = assertNotNull(first.activeRequest)
        val absent = first.acceptResolution(oldRequest, ImportResolveResult("not_committed", null)).finishRequest(oldRequest)
        val second = absent.loadedContext(context(ImportChoiceSet())).loadedPlan(plan("ip_two", 5), ImportChoiceSet()).beginConfirmation("second")
        assertEquals(second, second.acceptResolution(oldRequest, ImportResolveResult("not_committed", null)))
        assertEquals(second, second.confirmationFailed(oldRequest, DomainFailure("unknown_error", 0, FailureCategory.UNKNOWN)))
        assertEquals(second, second.acceptReceipt(oldRequest, receipt()))
        assertEquals(second, second.finishRequest(oldRequest))
        val wrong = second.acceptReceipt(assertNotNull(second.activeRequest), receipt().copy(planId = "other-plan"))
        assertNull(wrong.receipt)
        assertEquals(second.frozenCommand, wrong.frozenCommand)
    }

    private fun ImportTaskUiState.loadedContext(value: ImportContext): ImportTaskUiState {
        val requested = beginRequest(); val token = assertNotNull(requested.activeRequest)
        return requested.acceptContext(token, value).finishRequest(token)
    }
    private fun ImportTaskUiState.loadedPlan(value: ImportPlan, choices: ImportChoiceSet): ImportTaskUiState {
        val requested = beginRequest(); val token = assertNotNull(requested.activeRequest)
        return requested.acceptPlan(token, value, choices).finishRequest(token)
    }
    private fun ImportTaskUiState.loadedDetail(planId: String, detail: ImportItemDetail): ImportTaskUiState {
        val requested = beginRequest(); val token = assertNotNull(requested.activeRequest)
        return requested.acceptDetail(token, planId, detail).finishRequest(token)
    }
    private fun ImportTaskUiState.failed(failure: DomainFailure): ImportTaskUiState {
        val token = assertNotNull(activeRequest)
        return confirmationFailed(token, failure).finishRequest(token)
    }
    private fun ImportTaskUiState.resolved(value: ImportResolveResult): ImportTaskUiState {
        val requested = beginResolution(); val token = assertNotNull(requested.activeRequest)
        return requested.acceptResolution(token, value).finishRequest(token)
    }
    private fun ImportTaskUiState.received(value: ImportReceipt): ImportTaskUiState {
        val requested = if (activeRequest != null) this else beginResolution()
        val token = assertNotNull(requested.activeRequest)
        return requested.acceptReceipt(token, value).finishRequest(token)
    }

    private fun prepared(): ImportTaskUiState {
        val context = context(ImportChoiceSet())
        return ImportTaskUiState().loadedContext(context).loadedPlan(plan(), ImportChoiceSet())
    }
    private fun context(choices: ImportChoiceSet?) = ImportContext("s1", 3, "2099-01-01", "open", emptyList(), listOf(
        ImportSourceAccount("src", "wechat", "零钱", listOf("CNY"), 1, ImportAccountTarget("a1", 7), "confirmed_mapping", null)), choices, null, true, null)
    private fun plan(id: String = "ip_one", revision: Long = 4) = ImportPlan(id, "s1", revision, "2099-01-01", emptyMap(), emptyMap(), true,
        listOf(ImportProjectedItem("item1", "pending", "draft", ImportDisplay(null, null, null, null, null, emptyList()), false,
            listOf(ImportMemberRef("f:r1", null)), emptyList(), true)), emptyList(), "next", emptyList())
    private fun detail() = ImportItemDetail("item1", listOf(ImportMemberDetail("f:r1", null, "create_posted", StructuredObject(emptyMap()),
        listOf("note"), listOf("apply", "defer"), null)), emptyList(), emptyList(), emptyList(), emptyList())
    private fun processing() = ImportProcessingDetail("f:r2", null, "f", "skip", null, null, "row_unreadable", "未能读取", null, null, null)
    private fun receipt() = ImportReceipt("receipt1", "ip_one", "import", true, "2026-01-01", "partial", emptyMap(), emptyMap(),
        1, 1, 9, null, null, null, null)
}
