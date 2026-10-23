package com.finance.tracker.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.finance.tracker.core.ImportRecoveryPointer
import com.finance.tracker.core.ImportRecoveryStore
import com.finance.tracker.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

class CashImportTaskViewModel(
    private val repository: CashImportLifecycleRepository,
    private val recovery: ImportRecoveryStore,
    private val newKey: () -> String = { Random.nextBytes(24).joinToString("") { it.toUByte().toString(16).padStart(2, '0') } },
) : ViewModel() {
    private val mutableState = MutableStateFlow(ImportTaskUiState(recoveryReady = false))
    val state: StateFlow<ImportTaskUiState> = mutableState.asStateFlow()
    private var job: Job? = null
    private var recoverySessionId: String? = null

    fun detach() {
        job?.cancel()
        job = null
        recoverySessionId = null
        mutableState.value = ImportTaskUiState(recoveryReady = false)
    }

    fun restore() {
        if (state.value.busy || state.value.recoveryReady) return
        execute(state.value.beginRequest()) { request ->
            val pointer = recovery.load()
            if (!current(request)) return@execute
            when (pointer) {
                null -> mutableState.value = state.value.copy(recoveryReady = true)
                is ImportRecoveryPointer.Session -> {
                    recoverySessionId = pointer.sessionId
                    mutableState.value = state.value.copy(recoveryReady = true, needsContextRefresh = true)
                    loadContext(request, pointer.sessionId)
                    val context = state.value.context
                    if (current(request) && state.value.receipt == null && context?.state == "open" && context.lastPlanId != null) {
                        val plan = repository.plan(context.lastPlanId)
                        if (current(request)) mutableState.value = state.value.acceptPlan(request, plan, state.value.choices)
                    }
                }
                is ImportRecoveryPointer.Submission -> {
                    recoverySessionId = pointer.sessionId
                    mutableState.value = state.value.copy(recoveryReady = true, frozenCommand = pointer.command,
                        submission = ImportSubmissionState.CHECKING, needsContextRefresh = true)
                    resolveRequest(request, pointer.command)
                }
                is ImportRecoveryPointer.Receipt -> {
                    mutableState.value = state.value.copy(recoveryReady = true, receiptToFetch = pointer.receiptId)
                    publishReceipt(request, repository.receipt(pointer.receiptId))
                }
            }
        }
    }

    fun refresh() {
        val before = state.value
        if (!before.recoveryReady || before.busy || before.frozenCommand != null || before.receipt != null) return
        before.receiptToFetch?.let { receiptId ->
            execute(before.beginRequest()) { publishReceipt(it, repository.receipt(receiptId)) }
            return
        }
        val sessionId = before.context?.sessionId ?: recoverySessionId ?: return
        execute(before.beginRequest().copy(needsContextRefresh = true)) { loadContext(it, sessionId) }
    }

    fun upload(files: List<ImportUpload>, replacingFileRef: String? = null) {
        if (!state.value.canEdit || files.isEmpty()) return
        execute(state.value.beginRequest()) { request ->
            flushChoices(request)
            if (!current(request)) return@execute
            val batch = repository.upload(files, state.value.context, replacingFileRef) { context ->
                if (!current(request)) throw CancellationException("import_task_detached")
                adoptContext(request, context)
                if (!current(request)) throw CancellationException("import_task_detached")
            }
            if (current(request)) mutableState.value = state.value.copy(
                localFileFailures = state.value.localFileFailures.filterNot { old -> files.any { it.clientFileRef == old.clientFileRef } } + batch.localFailures,
                unprocessedFileRefs = batch.interruption?.unprocessedFileRefs.orEmpty(), errorCode = batch.interruption?.code,
                needsContextRefresh = state.value.needsContextRefresh || batch.interruption != null && state.value.context != null)
        }
    }

    fun setSourceScope(fileRef: String, label: String?) = changeFile { context ->
        repository.setSourceScope(context.sessionId, fileRef, context.revision ?: invalidTask(), label)
    }

    fun removeFile(fileRef: String) = changeFile { context ->
        repository.removeFile(context.sessionId, fileRef, context.revision ?: invalidTask())
    }

    private fun changeFile(action: suspend (ImportContext) -> ImportContext) {
        if (!state.value.canEdit || state.value.context == null) return
        execute(state.value.beginRequest()) { request ->
            flushChoices(request)
            if (current(request)) {
                mutableState.value = state.value.copy(needsContextRefresh = true)
                adoptContext(request, action(state.value.context ?: invalidTask()))
            }
        }
    }

    fun selectAccount(sourceRef: String, target: ImportAccountTarget) = edit { it.selectAccount(sourceRef, target) }
    fun editRow(choice: ImportRecordChoice) = edit { it.editRow(choice) }
    fun editPair(choice: ImportPairChoice) = edit { it.editPair(choice) }

    fun preview() {
        if (!state.value.canEdit || state.value.context?.canPreview != true) return
        execute(state.value.beginRequest()) { buildPlan(it) }
    }

    private suspend fun flushChoices(request: ImportTaskRequest) {
        if (state.value.previewDirty) buildPlan(request)
    }

    private suspend fun buildPlan(request: ImportTaskRequest) {
        val before = state.value
        val context = before.context ?: invalidTask()
        val choices = before.choices
        mutableState.value = before.copy(needsContextRefresh = true)
        val plan = repository.preview(context.sessionId, context.revision ?: invalidTask(), before.plan?.planId ?: context.lastPlanId, choices)
        if (current(request)) mutableState.value = state.value.acceptPlan(request, plan, choices)
    }

    fun loadItems(filter: String = state.value.filter, append: Boolean = false) {
        val before = state.value
        if (!before.canEdit || before.plan == null || append && (before.nextCursor == null || filter != before.filter)) return
        edit { it.changeFilter(filter) }
        if (state.value.filter != filter) return
        val planId = state.value.plan?.planId ?: return
        val cursor = if (append) state.value.nextCursor else null
        execute(state.value.beginRequest()) { request ->
            val page = repository.items(planId, cursor, confirmationStatus = filter)
            if (current(request)) mutableState.value = state.value.acceptItemPage(request, planId, filter, page, append)
        }
    }

    fun loadDetail(itemId: String) {
        val before = state.value
        val planId = before.plan?.planId ?: return
        if (!before.canEdit) return
        execute(before.beginRequest()) { request ->
            val detail = repository.item(planId, itemId)
            if (current(request)) mutableState.value = state.value.acceptDetail(request, planId, detail)
        }
    }

    fun loadProcessing(append: Boolean = false) {
        val before = state.value
        val planId = before.plan?.planId ?: return
        if (!before.canEdit || append && before.processingNextCursor == null) return
        execute(before.beginRequest()) { request ->
            val page = repository.processing(planId, if (append) before.processingNextCursor else null)
            if (current(request)) mutableState.value = state.value.acceptProcessingPage(request, planId, page, append)
        }
    }

    fun confirm() {
        if (!state.value.canConfirm) return
        execute(state.value.beginConfirmation(newKey())) { sendOriginal(it) }
    }

    fun retryOriginal() = execute(state.value.retryOriginal()) { sendOriginal(it) }

    private suspend fun sendOriginal(request: ImportTaskRequest) {
        val command = state.value.frozenCommand ?: return
        val sessionId = state.value.context?.sessionId ?: recoverySessionId ?: invalidTask()
        try { recovery.save(ImportRecoveryPointer.Submission(sessionId, command)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (cause: Exception) {
            if (current(request)) mutableState.value = state.value.confirmationFailed(request,
                DomainFailure(cause.safeFailure().code, 0, FailureCategory.RECOVERABLE, commitState = "not_committed"))
            return
        }
        if (!current(request)) return
        publishReceipt(request, repository.confirm(command.planId, command.revision, command.idempotencyKey))
    }

    fun resolve() {
        val command = state.value.frozenCommand ?: return
        execute(state.value.beginResolution()) { resolveRequest(it, command) }
    }

    fun loadResults(kind: ImportResultKind, append: Boolean = false) {
        val before = state.value
        val receiptId = before.receipt?.receiptId ?: return
        val previous = before.resultPages[kind]
        if (before.busy || append && previous?.nextAfter == null) return
        val after = if (append) previous?.nextAfter ?: return else "0"
        execute(before.beginRequest()) { request ->
            val page = repository.results(receiptId, kind, after)
            if (!current(request) || state.value.receipt?.receiptId != receiptId) return@execute
            val latestVersion = state.value.resultPages.values.maxOfOrNull { it.projectionVersion } ?: 0
            if (page.projectionVersion < latestVersion) throw DomainFailure("request_failed", 0, FailureCategory.RECOVERABLE)
            val old = previous.takeIf { append }
            val refs = (old?.projectionRefs?.takeIf { old.projectionVersion == page.projectionVersion }.orEmpty() + page.projectionRefs)
                .distinctBy { it.transactionId }
            val combined = page.copy(items = (old?.items.orEmpty() + page.items).distinctBy { it.resultId }, projectionRefs = refs)
            val pages = state.value.resultPages.mapValues { (_, value) ->
                if (value.projectionVersion == page.projectionVersion) value else value.copy(projectionRefs = emptyList(), projectionVersion = page.projectionVersion)
            }
            mutableState.value = state.value.copy(resultPages = pages + (kind to combined), errorCode = null)
        }
    }

    fun cancel() {
        val before = state.value
        if (!before.recoveryReady || before.busy || before.frozenCommand != null || before.receipt != null || before.receiptToFetch != null) return
        val sessionId = before.context?.sessionId ?: recoverySessionId
        execute(before.beginRequest().copy(needsContextRefresh = true)) { request ->
            if (sessionId != null) {
                try {
                    val cancelled = repository.cancel(sessionId)
                    if (!current(request)) return@execute
                    if (cancelled.sessionId != sessionId || cancelled.state != "cancelled") invalidTask()
                } catch (failure: DomainFailure) {
                    val gone = failure.code == "session_expired" && failure.status == 410 ||
                        failure.code == "session_not_found" && failure.status == 404 ||
                        failure.code == "session_closed" && failure.status == 409
                    if (!gone) throw failure
                }
            }
            clearTask(request)
        }
    }

    fun startNew() {
        val before = state.value
        if (before.busy || before.frozenCommand != null || before.receiptToFetch != null ||
            before.receipt == null && before.context?.state != "cancelled") return
        execute(before.beginRequest()) { clearTask(it) }
    }

    private suspend fun clearTask(request: ImportTaskRequest) {
        if (!current(request)) return
        recovery.clear()
        if (!current(request)) return
        recoverySessionId = null
        mutableState.value = ImportTaskUiState(recoveryReady = true)
    }

    private suspend fun resolveRequest(request: ImportTaskRequest, command: ImportFrozenCommand) {
        val result = repository.resolve(command.planId, command.idempotencyKey)
        if (!current(request)) return
        mutableState.value = state.value.acceptResolution(request, result).copy(busy = true)
        val receipt = state.value.receipt
        if (receipt != null) recovery.save(ImportRecoveryPointer.Receipt(receipt.receiptId))
        else if (result.status == "not_committed") loadContext(request, recoverySessionId ?: state.value.context?.sessionId ?: invalidTask())
    }

    private suspend fun loadContext(request: ImportTaskRequest, sessionId: String) = adoptContext(request, repository.context(sessionId))

    private suspend fun adoptContext(request: ImportTaskRequest, context: ImportContext) {
        if (!current(request)) return
        val next = state.value.acceptContext(request, context)
        if (next.context != context) invalidTask()
        recoverySessionId = context.sessionId
        mutableState.value = next.copy(busy = true, needsContextRefresh = true)
        if (context.state == "committed" && context.receiptId != null) publishReceipt(request, repository.receipt(context.receiptId))
        else {
            recovery.save(ImportRecoveryPointer.Session(context.sessionId))
            if (current(request)) mutableState.value = state.value.copy(needsContextRefresh = false)
        }
    }

    private suspend fun publishReceipt(request: ImportTaskRequest, receipt: ImportReceipt) {
        if (!current(request)) return
        mutableState.value = state.value.acceptReceipt(request, receipt).copy(busy = true)
        if (state.value.receipt?.receiptId == receipt.receiptId) recovery.save(ImportRecoveryPointer.Receipt(receipt.receiptId))
    }

    private fun edit(action: (ImportTaskUiState) -> ImportTaskUiState) {
        try { mutableState.value = action(state.value) }
        catch (cause: Exception) { mutableState.value = state.value.copy(errorCode = cause.safeFailure().code) }
    }

    private fun current(request: ImportTaskRequest): Boolean = state.value.activeRequest == request

    private fun execute(started: ImportTaskUiState, action: suspend (ImportTaskRequest) -> Unit) {
        if (started === state.value) return
        val request = started.activeRequest ?: return
        mutableState.value = started
        job = viewModelScope.launch {
            try { action(request) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (cause: Exception) {
                if (current(request)) {
                    val failure = cause.safeFailure()
                    mutableState.value = if (state.value.frozenCommand != null) state.value.confirmationFailed(request, failure)
                    else state.value.copy(errorCode = failure.code, needsContextRefresh = state.value.needsContextRefresh || failure.code in setOf("plan_changed", "session_changed"))
                }
            } finally {
                mutableState.value = state.value.finishRequest(request)
            }
        }
    }
}

private fun Throwable.safeFailure(): DomainFailure = this as? DomainFailure ?: DomainFailure("request_failed", 0, FailureCategory.RECOVERABLE)
private fun invalidTask(): Nothing = throw DomainFailure("import_input_invalid", 0, FailureCategory.RECOVERABLE)
