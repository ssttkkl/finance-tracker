package com.finance.tracker.presentation

import com.finance.tracker.domain.*
import kotlin.random.Random

enum class ImportTaskPhase { PREPARE, REVIEW, RESULT }
enum class ImportSubmissionState { IDLE, SAVING, UNKNOWN, CHECKING, RETRY_ORIGINAL, COMPLETE }
data class ImportTaskRequest(val taskId: Long, val sequence: Long)

data class ImportTaskUiState(
    val taskId: Long = Random.nextLong(),
    val requestSequence: Long = 0,
    val activeRequest: ImportTaskRequest? = null,
    val resultFiles: List<ImportPlanFile> = emptyList(),
    val cancelledTaskId: Long? = null,
    val phase: ImportTaskPhase = ImportTaskPhase.PREPARE,
    val context: ImportContext? = null,
    val plan: ImportPlan? = null,
    val choices: ImportChoiceSet = ImportChoiceSet(),
    val items: List<ImportProjectedItem> = emptyList(),
    val nextCursor: String? = null,
    val itemsInitialized: Boolean = false,
    val filter: String = "all",
    val requestedFilter: String? = null,
    val details: Map<String, ImportItemDetail> = emptyMap(),
    val processingItems: List<ImportProcessingDetail> = emptyList(),
    val processingNextCursor: String? = null,
    val processingInitialized: Boolean = false,
    val receipt: ImportReceipt? = null,
    val resultPages: Map<ImportResultKind, ImportResultPage> = emptyMap(),
    val receiptToFetch: String? = null,
    val frozenCommand: ImportFrozenCommand? = null,
    val submission: ImportSubmissionState = ImportSubmissionState.IDLE,
    val previewDirty: Boolean = false,
    val needsContextRefresh: Boolean = false,
    val recoveryReady: Boolean = true,
    val localFileFailures: List<ImportLocalFileFailure> = emptyList(),
    val unprocessedFileRefs: List<String> = emptyList(),
    val busy: Boolean = false,
    val errorCode: String? = null,
) {
    val canEdit: Boolean get() = recoveryReady && !busy && submission == ImportSubmissionState.IDLE && !needsContextRefresh && frozenCommand == null && receipt == null && receiptToFetch == null && (context == null || context.state == "open")
    val canConfirm: Boolean get() = canEdit && phase == ImportTaskPhase.REVIEW && itemsInitialized && !previewDirty && !needsContextRefresh &&
        plan?.canCommit == true && context?.revision == plan.sessionRevision

    private fun requestStarted(): ImportTaskUiState = copy(requestSequence = requestSequence + 1,
        activeRequest = ImportTaskRequest(taskId, requestSequence + 1), busy = true)

    fun beginCancellation(): ImportTaskUiState = if (!recoveryReady || busy || receipt != null || receiptToFetch != null ||
        frozenCommand != null && submission != ImportSubmissionState.RETRY_ORIGINAL) this
        else requestStarted().copy(needsContextRefresh = true)

    fun beginRequest(): ImportTaskUiState = if (busy || frozenCommand != null) this else requestStarted()
    fun finishRequest(request: ImportTaskRequest): ImportTaskUiState = if (activeRequest != request) this else copy(activeRequest = null, busy = false)

    fun acceptContext(request: ImportTaskRequest, value: ImportContext): ImportTaskUiState {
        if (activeRequest != request || frozenCommand != null || receipt != null || phase == ImportTaskPhase.RESULT ||
            context != null && (context.sessionId != value.sessionId || (value.revision ?: 0) < (context.revision ?: 0)) && value.state != "committed") return this
        if (context != null && context.sessionId != value.sessionId) return this
        val restored = value.lastChoices ?: ImportChoiceSet(accountSelections = value.sourceAccounts.mapNotNull { group ->
            val target = group.selection ?: group.suggestion?.takeIf { it.accountId != null && it.revision != null }
            target?.let { ImportAccountSelection(group.sourceAccountRef, it) }
        })
        return copy(phase = ImportTaskPhase.PREPARE, context = value, choices = restored, plan = null,
            items = emptyList(), nextCursor = null, itemsInitialized = false, details = emptyMap(),
            processingItems = emptyList(), processingNextCursor = null, processingInitialized = false,
            receiptToFetch = value.receiptId, previewDirty = false, needsContextRefresh = false, errorCode = null)
    }

    fun acceptPlan(request: ImportTaskRequest, value: ImportPlan, submitted: ImportChoiceSet): ImportTaskUiState {
        if (activeRequest != request || frozenCommand != null || receipt != null || phase == ImportTaskPhase.RESULT || context?.sessionId != value.sessionId || value.sessionRevision < (context.revision ?: 0)) return this
        val selected = if ((value.itemCounts["pending"] ?: 0L) == 0L) "all" else requestedFilter ?: "pending"
        return copy(phase = ImportTaskPhase.REVIEW, plan = value, choices = submitted,
            context = context.copy(revision = value.sessionRevision, lastPlanId = value.planId, lastChoices = submitted),
            items = if (selected == "all") value.items else emptyList(),
            nextCursor = value.nextCursor.takeIf { selected == "all" }, filter = selected, itemsInitialized = selected == "all",
            details = emptyMap(), processingItems = emptyList(), processingNextCursor = null, processingInitialized = false,
            previewDirty = false, needsContextRefresh = false, errorCode = null)
    }

    fun selectAccount(sourceRef: String, target: ImportAccountTarget): ImportTaskUiState {
        if (!canEdit) return this
        if (context?.sourceAccounts?.none { it.sourceAccountRef == sourceRef } != false ||
            target.accountId == null && target.revision != null || target.accountId != null && (target.revision ?: 0) <= 0) invalidTaskChoice()
        return copy(choices = choices.copy(accountSelections = choices.accountSelections.filterNot { it.sourceAccountRef == sourceRef } +
            ImportAccountSelection(sourceRef, target)), previewDirty = true, errorCode = null)
    }

    fun editRow(choice: ImportRecordChoice): ImportTaskUiState {
        if (!canEdit) return this
        val member = details.values.flatMap { it.members }.firstOrNull { it.rowRef == choice.rowRef } ?: invalidTaskChoice()
        if (choice.action.name.lowercase() !in member.allowedActions || choice.patch.fields.keys.any { it !in member.editableFields }) invalidTaskChoice()
        return copy(choices = choices.copy(rows = choices.rows.filterNot { it.rowRef == choice.rowRef } + choice), previewDirty = true, errorCode = null)
    }

    fun editEntry(choice: ImportEntryChoice): ImportTaskUiState {
        if (!canEdit) return this
        val entry = details.values.mapNotNull { it.entry }.firstOrNull { it.editRef == choice.editRef } ?: invalidTaskChoice()
        if (choice.patch.fields.keys.any { it !in entry.editableFields || it !in cashPublicEntryFields }) invalidTaskChoice()
        val previous = choices.entries.firstOrNull { it.editRef == choice.editRef }?.patch?.fields.orEmpty()
        val updated = choice.copy(patch = StructuredObject(previous + choice.patch.fields))
        return copy(choices = choices.copy(entries = choices.entries.filterNot { it.editRef == choice.editRef } + updated),
            previewDirty = true, errorCode = null)
    }

    fun editPair(choice: ImportPairChoice): ImportTaskUiState {
        if (!canEdit) return this
        val group = details.values.flatMap { it.relationChoices }.firstOrNull { it.suggestionId == choice.suggestionId } ?: invalidTaskChoice()
        if (choice.action.name.lowercase() !in group.allowedActions || choice.candidateId != null && group.candidates.none { it.candidateId == choice.candidateId }) invalidTaskChoice()
        // 保留旧失效决定供服务端阻止复活；当前合法新决定由服务端按组重验。
        return copy(choices = choices.copy(relations = choices.relations.filterNot { it.suggestionId == choice.suggestionId } + choice), previewDirty = true, errorCode = null)
    }

    fun changeFilter(value: String): ImportTaskUiState {
        if (!canEdit || plan == null) return this
        if (value !in setOf("all", "confirmed", "pending")) invalidTaskChoice()
        if (filter == value) return copy(requestedFilter = value)
        return copy(filter = value, requestedFilter = value, items = emptyList(), nextCursor = null, itemsInitialized = false, errorCode = null)
    }

    fun acceptItemPage(request: ImportTaskRequest, planId: String, requestedFilter: String, page: ImportItemPage, append: Boolean): ImportTaskUiState {
        if (activeRequest != request || receipt != null || phase == ImportTaskPhase.RESULT || plan?.planId != planId || filter != requestedFilter || frozenCommand != null) return this
        return copy(items = ((if (append) items else emptyList()) + page.items).distinctBy { it.itemId },
            nextCursor = page.nextCursor, itemsInitialized = true, errorCode = null)
    }

    fun acceptDetail(request: ImportTaskRequest, planId: String, detail: ImportItemDetail): ImportTaskUiState {
        if (activeRequest != request || receipt != null || phase == ImportTaskPhase.RESULT || plan?.planId != planId || frozenCommand != null) return this
        return copy(details = details + (detail.itemId to detail), errorCode = null)
    }

    fun acceptProcessingPage(request: ImportTaskRequest, planId: String, page: ImportProcessingPage, append: Boolean): ImportTaskUiState {
        if (activeRequest != request || receipt != null || phase == ImportTaskPhase.RESULT || plan?.planId != planId || frozenCommand != null) return this
        return copy(processingItems = (if (append) processingItems else emptyList()) + page.items,
            processingNextCursor = page.nextCursor, processingInitialized = true, errorCode = null)
    }

    fun beginConfirmation(key: String): ImportTaskUiState {
        if (!canConfirm) return this
        if (!key.matches(Regex("[A-Za-z0-9_:.~-]{1,255}"))) invalidTaskChoice()
        val current = plan ?: return this
        return requestStarted().copy(frozenCommand = ImportFrozenCommand(current.planId, current.sessionRevision, key),
            submission = ImportSubmissionState.SAVING, busy = true, errorCode = null)
    }

    fun confirmationFailed(request: ImportTaskRequest, failure: DomainFailure): ImportTaskUiState {
        if (activeRequest != request || frozenCommand == null || receipt != null) return this
        return copy(submission = if (failure.commitState == "not_committed") ImportSubmissionState.RETRY_ORIGINAL else ImportSubmissionState.UNKNOWN,
            busy = false, errorCode = failure.code, receiptToFetch = failure.receiptId.takeIf { failure.code == "session_committed" })
    }

    fun beginResolution(): ImportTaskUiState {
        if (frozenCommand == null || busy || receipt != null) return this
        return requestStarted().copy(submission = ImportSubmissionState.CHECKING, busy = true, errorCode = null)
    }

    fun retryOriginal(): ImportTaskUiState {
        if (submission != ImportSubmissionState.RETRY_ORIGINAL || frozenCommand == null || busy) return this
        return requestStarted().copy(submission = ImportSubmissionState.SAVING, busy = true, errorCode = null)
    }

    fun acceptResolution(request: ImportTaskRequest, value: ImportResolveResult): ImportTaskUiState {
        if (activeRequest != request || frozenCommand == null || receipt != null) return this
        return when (value.status) {
            "committed" -> value.receipt?.takeIf { it.committed && it.operationKind == "import" && sameImportSession(frozenCommand.planId, it.planId) }?.let {
                copy(receiptToFetch = it.receiptId, plan = plan.takeIf { old -> old?.planId == it.planId },
                    resultFiles = resultFiles.takeIf { _ -> frozenCommand.planId == it.planId }.orEmpty()).acceptReceipt(request, it)
            }
                ?: copy(submission = ImportSubmissionState.UNKNOWN, busy = false, errorCode = "request_failed")
            "pending" -> copy(submission = ImportSubmissionState.UNKNOWN, busy = false, errorCode = null)
            "retry_original" -> copy(submission = ImportSubmissionState.RETRY_ORIGINAL, busy = false, errorCode = null)
            "not_committed" -> copy(phase = ImportTaskPhase.PREPARE, submission = ImportSubmissionState.IDLE,
                frozenCommand = null, plan = null, items = emptyList(), nextCursor = null, itemsInitialized = false,
                details = emptyMap(), processingItems = emptyList(), processingNextCursor = null, processingInitialized = false,
                previewDirty = true, needsContextRefresh = true, busy = false, receiptToFetch = null, errorCode = null)
            else -> copy(submission = ImportSubmissionState.UNKNOWN, busy = false, errorCode = "request_failed")
        }
    }

    fun acceptReceipt(request: ImportTaskRequest, value: ImportReceipt): ImportTaskUiState {
        if (activeRequest != request || receipt != null) return this
        if ((frozenCommand == null || frozenCommand.planId != value.planId) && receiptToFetch != value.receiptId) {
            return copy(submission = ImportSubmissionState.UNKNOWN, busy = false, errorCode = "request_failed")
        }
        if (!value.committed) return copy(submission = ImportSubmissionState.UNKNOWN, busy = false, errorCode = "request_failed")
        return copy(phase = ImportTaskPhase.RESULT, resultFiles = plan?.files ?: resultFiles, plan = null, receipt = value, receiptToFetch = null, frozenCommand = null,
            submission = ImportSubmissionState.COMPLETE, busy = false, errorCode = null,
            items = emptyList(), nextCursor = null, itemsInitialized = false, details = emptyMap(),
            processingItems = emptyList(), processingNextCursor = null, processingInitialized = false)
    }
}

private fun invalidTaskChoice(): Nothing = throw DomainFailure("import_input_invalid", 0, FailureCategory.RECOVERABLE)

private fun sameImportSession(expected: String, actual: String?): Boolean {
    if (expected == actual) return true
    val pattern = Regex("ip_([A-Za-z0-9_-]{43})\\.([A-Za-z0-9_-]{16})")
    val first = pattern.matchEntire(expected)?.groupValues?.get(1) ?: return false
    return actual?.let { pattern.matchEntire(it)?.groupValues?.get(1) } == first
}
