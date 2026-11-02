package com.finance.tracker.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.finance.tracker.core.ImportRecoveryPointer
import com.finance.tracker.core.ImportRecoveryStore
import com.finance.tracker.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
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
    private data class QueuedUpload(val upload: ImportUpload, val replacingFileRef: String?)
    private val uploadQueue = ArrayDeque<QueuedUpload>()
    private val uploadJobs = mutableSetOf<Job>()
    private val inFlightUploads = mutableSetOf<String>()
    private var uploadGeneration = 0

    fun detach() {
        uploadGeneration++
        uploadQueue.clear()
        inFlightUploads.clear()
        uploadJobs.toList().forEach { it.cancel() }
        uploadJobs.clear()
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
                        publishPlan(request, plan, state.value.choices)
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
        if (before.internalFailure != null) return
        if (!before.recoveryReady || before.busy || before.frozenCommand != null || before.receipt != null) return
        before.receiptToFetch?.let { receiptId ->
            execute(before.beginRequest()) { publishReceipt(it, repository.receipt(receiptId)) }
            return
        }
        val sessionId = before.context?.sessionId ?: recoverySessionId ?: return
        execute(before.beginRequest().copy(needsContextRefresh = true)) { loadContext(it, sessionId) }
    }

    fun upload(files: List<ImportUpload>, replacingFileRef: String? = null) {
        if (files.isEmpty()) return
        if (replacingFileRef != null) {
            uploadReplacement(files, replacingFileRef)
            return
        }
        if (!state.value.canAddFiles) return
        val known = (state.value.context?.files?.map { it.clientFileRef }.orEmpty() +
            uploadQueue.map { it.upload.clientFileRef } + inFlightUploads).toSet()
        val unique = files.filterNot { it.clientFileRef in known }
        val accepted = unique.take((20 - known.size).coerceAtLeast(0))
        val rejected = unique.drop(accepted.size)
        if (rejected.isNotEmpty()) mutableState.value = state.value.copy(
            localFileFailures = state.value.localFileFailures.filterNot { old -> rejected.any { it.clientFileRef == old.clientFileRef } } +
                rejected.map { ImportLocalFileFailure(it.clientFileRef, "import_file_too_many") },
            unprocessedFileRefs = (state.value.unprocessedFileRefs + rejected.map { it.clientFileRef }).distinct())
        if (accepted.isEmpty()) return
        val retryRefs = accepted.mapTo(mutableSetOf()) { it.clientFileRef }
        mutableState.value = state.value.copy(
            localFileFailures = state.value.localFileFailures.filterNot { it.clientFileRef in retryRefs },
            unprocessedFileRefs = state.value.unprocessedFileRefs.filterNot { it in retryRefs })
        val generation = uploadGeneration
        uploadQueue.addAll(accepted.map { QueuedUpload(it, null) })
        pumpUploads(generation)
    }

    private fun uploadReplacement(files: List<ImportUpload>, replacingFileRef: String) {
        if (!state.value.canEdit) return
        val retryRefs = files.mapTo(mutableSetOf()) { it.clientFileRef }
        mutableState.value = state.value.copy(
            localFileFailures = state.value.localFileFailures.filterNot { it.clientFileRef in retryRefs },
            unprocessedFileRefs = state.value.unprocessedFileRefs.filterNot { it in retryRefs })
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
                unprocessedFileRefs = batch.interruption?.unprocessedFileRefs.orEmpty(), errorCode = batch.interruption?.code, errorDiagnostic = batch.interruption?.diagnostic,
                needsContextRefresh = state.value.needsContextRefresh || batch.interruption != null && state.value.context != null)
        }
    }

    private fun pumpUploads(generation: Int) {
        if (state.value.internalFailure != null) return
        if (generation != uploadGeneration) return
        val current = state.value
        if (!current.canAddFiles) {
            dropQueuedAsUnprocessed()
            return
        }
        if (current.context == null) {
            if (current.activeRequest != null || recoverySessionId != null) return
            if (uploadJobs.isEmpty() && uploadQueue.isNotEmpty()) startUpload(uploadQueue.removeFirst(), generation, creating = true)
            return
        }
        if (current.previewDirty && current.activeUploads == 0 && current.activeRequest != null) return
        if (current.previewDirty && current.activeUploads == 0) {
            execute(current.beginRequest()) { request ->
                try {
                    flushChoices(request)
                    if (this.current(request) && !state.value.needsContextRefresh && !state.value.previewDirty)
                        mutableState.value = state.value.copy(phase = ImportTaskPhase.PREPARE)
                    else dropQueuedAsUnprocessed()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (cause: Exception) { dropQueuedAsUnprocessed(cause.safeFailure().code); throw cause }
            }
            return
        }
        while (uploadJobs.size < MAX_CONCURRENT_IMPORT_UPLOADS && uploadQueue.isNotEmpty()) {
            startUpload(uploadQueue.removeFirst(), generation, creating = false)
        }
    }

    private fun dropQueuedAsUnprocessed(code: String = state.value.errorCode ?: "request_failed") {
        if (uploadQueue.isEmpty()) return
        mutableState.value = state.value.copy(
            localFileFailures = state.value.localFileFailures + uploadQueue.map { ImportLocalFileFailure(it.upload.clientFileRef, code) },
            unprocessedFileRefs = (state.value.unprocessedFileRefs + uploadQueue.map { it.upload.clientFileRef }).distinct())
        uploadQueue.clear()
    }

    private fun startUpload(item: QueuedUpload, generation: Int, creating: Boolean) {
        val started = state.value.uploadStarted()
        if (started === state.value) {
            mutableState.value = state.value.copy(
                unprocessedFileRefs = (state.value.unprocessedFileRefs + item.upload.clientFileRef).distinct())
            return
        }
        mutableState.value = started
        inFlightUploads += item.upload.clientFileRef
        lateinit var launched: Job
        launched = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                uploadOne(item, generation, creating)
            } finally {
                if (generation == uploadGeneration) {
                    inFlightUploads -= item.upload.clientFileRef
                    uploadJobs.remove(launched)
                    mutableState.value = state.value.uploadFinished()
                    pumpUploads(generation)
                    if (state.value.internalFailure == null && uploadJobs.isEmpty() && uploadQueue.isEmpty() &&
                        (state.value.needsContextRefresh || state.value.receiptToFetch != null)) refresh()
                }
            }
        }
        uploadJobs += launched
        launched.start()
    }

    private suspend fun uploadOne(item: QueuedUpload, generation: Int, creating: Boolean) {
        try {
            val batch = repository.upload(listOf(item.upload), state.value.context, null) { context ->
                if (generation != uploadGeneration) throw CancellationException("import_task_detached")
                applyUploadedContext(context)
            }
            if (generation != uploadGeneration) return
            if (batch.context != null && state.value.context?.sessionId != batch.context.sessionId) applyUploadedContext(batch.context)
            applyUploadOutcome(item, batch, creating)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (cause: Exception) {
            if (generation == uploadGeneration) {
                if (cause is DomainFailure && cause.category == FailureCategory.RECOVERABLE && cause.code != "internal_error") applyUploadFailure(item, cause, creating)
                else stopForInternalFailure(cause)
            }
        }
    }

    private suspend fun applyUploadedContext(context: ImportContext) {
        mutableState.value = state.value.acceptUploadedContext(context)
        if (state.value.context?.sessionId != context.sessionId) return
        recoverySessionId = context.sessionId
        recovery.save(ImportRecoveryPointer.Session(context.sessionId))
    }

    private fun applyUploadOutcome(item: QueuedUpload, batch: ImportUploadBatch, creating: Boolean) {
        val local = batch.localFailures.distinctBy { it.clientFileRef }
        if (local.isNotEmpty()) mutableState.value = state.value.copy(
            localFileFailures = state.value.localFileFailures.filterNot { old -> local.any { it.clientFileRef == old.clientFileRef } } + local)
        val interruption = batch.interruption
        if (interruption == null && (local.isNotEmpty() || !(creating && state.value.context == null))) return
        applyUploadInterruption(item, interruption, creating)
    }

    private fun applyUploadFailure(item: QueuedUpload, failure: DomainFailure, creating: Boolean) =
        applyUploadInterruption(item, ImportUploadInterruption(failure.code, failure.status, failure.receiptId,
            listOf(item.upload.clientFileRef), failure.diagnostic), creating)

    private fun applyUploadInterruption(item: QueuedUpload, interruption: ImportUploadInterruption?, creating: Boolean) {
        val failure = interruption ?: ImportUploadInterruption("request_failed", 0, null, listOf(item.upload.clientFileRef))
        val fatal = (creating && state.value.context == null) || failure.code in FATAL_IMPORT_UPLOAD_CODES
        val failedRefs = (failure.unprocessedFileRefs.ifEmpty { listOf(item.upload.clientFileRef) } +
            if (fatal) uploadQueue.map { it.upload.clientFileRef } else emptyList()).distinct()
        mutableState.value = state.value.copy(
            localFileFailures = state.value.localFileFailures.filterNot { it.clientFileRef in failedRefs } +
                failedRefs.map { ImportLocalFileFailure(it, failure.code) },
            unprocessedFileRefs = (state.value.unprocessedFileRefs + failure.unprocessedFileRefs +
                (if (fatal) uploadQueue.map { it.upload.clientFileRef } else emptyList())).distinct(),
            errorCode = failure.code.takeIf { fatal } ?: state.value.errorCode,
            errorDiagnostic = failure.diagnostic.takeIf { fatal } ?: state.value.errorDiagnostic,
            uploadCreationUnknown = state.value.uploadCreationUnknown || creating && state.value.context == null,
            needsContextRefresh = state.value.needsContextRefresh || state.value.context != null)
        if (fatal) uploadQueue.clear()
    }

    fun removeLocalFileFailure(clientFileRef: String) {
        if (!state.value.canEdit) return
        mutableState.value = state.value.copy(
            localFileFailures = state.value.localFileFailures.filterNot { it.clientFileRef == clientFileRef },
            unprocessedFileRefs = state.value.unprocessedFileRefs.filterNot { it == clientFileRef })
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

    fun showPreparation() { if (state.value.canEdit) mutableState.value = state.value.copy(phase = ImportTaskPhase.PREPARE) }

    fun selectAccount(sourceRef: String, target: ImportAccountTarget) = edit { it.selectAccount(sourceRef, target) }
    fun setMergeSameNameAcrossChannels(enabled: Boolean) = edit { it.setMergeSameNameAcrossChannels(enabled) }
    fun selectReviewFilter(value: String) = edit { it.changeFilter(value) }
    fun editRow(choice: ImportRecordChoice) = edit { it.editRow(choice) }
    fun editEntry(choice: ImportEntryChoice) = edit { it.editEntry(choice) }
    fun editPair(choice: ImportPairChoice) = edit { it.editPair(choice) }

    fun restartPreview() {
        val before = state.value
        val sessionId = before.context?.sessionId ?: recoverySessionId ?: return
        if (!before.recoveryReady || before.busy || before.frozenCommand != null || before.receipt != null) return
        execute(before.beginRequest().copy(needsContextRefresh = true)) { request ->
            loadContext(request, sessionId)
            if (current(request) && state.value.receipt == null && state.value.context?.state == "open" && state.value.context?.canPreview == true) {
                mutableState.value = state.value.copy(choices = state.value.choices.copy(entries = emptyList(), relations = emptyList()))
                buildPlan(request)
            }
        }
    }

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
        val plan = try { repository.preview(context.sessionId, context.revision ?: invalidTask(), before.plan?.planId ?: context.lastPlanId, choices) }
        catch (failure: DomainFailure) {
            if (failure.code !in setOf("plan_input_invalid", "import_payload_invalid")) throw failure
            if (current(request)) mutableState.value = state.value.rejectInvalidPreviewInput(failure.code)
            return
        }
        publishPlan(request, plan, choices)
    }

    private suspend fun publishPlan(request: ImportTaskRequest, plan: ImportPlan, choices: ImportChoiceSet) {
        if (!current(request)) return
        mutableState.value = state.value.acceptPlan(request, plan, choices)
    }

    fun markItemsLoaded(planId: String, filter: String) {
        val before = state.value
        if (before.plan?.planId == planId && before.filter == filter && !before.itemsInitialized)
            mutableState.value = before.copy(itemsInitialized = true)
    }

    fun failPageContract(cause: Throwable) { stopForInternalFailure(cause) }

    private suspend fun loadPageDetails(request: ImportTaskRequest, planId: String) {
        val missing = state.value.items.filter { it.detailAvailable && it.itemId !in state.value.details }.map { it.itemId }
        for (itemId in missing) {
            if (!current(request) || state.value.plan?.planId != planId) return
            val detail = repository.item(planId, itemId)
            if (current(request)) mutableState.value = state.value.acceptDetail(request, planId, detail)
        }
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
            loadPageDetails(request, planId)
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
            if (cause !is DomainFailure || cause.category == FailureCategory.UNKNOWN) {
                if (current(request)) {
                    stopForInternalFailure(cause)
                    mutableState.value = state.value.confirmationFailed(request,
                        DomainFailure("unknown_error", 0, FailureCategory.UNKNOWN, commitState = "not_committed", cause = cause))
                }
                return
            }
            if (current(request)) mutableState.value = state.value.confirmationFailed(request,
                DomainFailure(cause.safeFailure().code, 0, FailureCategory.RECOVERABLE, commitState = "not_committed", cause = cause))
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
            check(page.projectionVersion >= latestVersion) { "import_result_version_regressed" }
            val old = previous.takeIf { append }
            val refs = (old?.projectionRefs?.takeIf { old.projectionVersion == page.projectionVersion }.orEmpty() + page.projectionRefs)
                .distinctBy { it.transactionId }
            val combined = page.copy(items = (old?.items.orEmpty() + page.items).distinctBy { it.resultId }, projectionRefs = refs)
            val pages = state.value.resultPages.mapValues { (_, value) ->
                if (value.projectionVersion == page.projectionVersion) value else value.copy(projectionRefs = emptyList(), projectionVersion = page.projectionVersion)
            }
            mutableState.value = state.value.copy(resultPages = pages + (kind to combined), errorCode = null, errorDiagnostic = null)
        }
    }

    fun cancel() {
        val before = state.value
        val started = before.beginCancellation()
        if (started === before) return
        val sessionId = before.context?.sessionId ?: recoverySessionId
        execute(started) { request ->
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
            clearTask(request, cancelled = true)
        }
    }

    fun startNew() {
        val before = state.value
        if (before.busy || before.frozenCommand != null || before.receiptToFetch != null ||
            before.receipt == null && before.context?.state != "cancelled") return
        execute(before.beginRequest()) { clearTask(it) }
    }

    private suspend fun clearTask(request: ImportTaskRequest, cancelled: Boolean = false) {
        if (!current(request)) return
        uploadGeneration++
        uploadQueue.clear()
        inFlightUploads.clear()
        uploadJobs.toList().forEach { it.cancel() }
        uploadJobs.clear()
        recovery.clear()
        if (!current(request)) return
        recoverySessionId = null
        mutableState.value = ImportTaskUiState(recoveryReady = true, cancelledTaskId = request.taskId.takeIf { cancelled })
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
        val before = state.value
        val next = before.acceptContext(request, context)
        if (next.context != context) {
            if (before.context?.sessionId == context.sessionId &&
                (context.revision ?: 0) <= (before.context.revision ?: 0)) return
            invalidTask()
        }
        recoverySessionId = context.sessionId
        val confirmedRefs = context.files.filter { it.status in setOf("ready", "partial") }.map { it.clientFileRef }.toSet()
        mutableState.value = next.copy(busy = true, needsContextRefresh = true,
            localFileFailures = next.localFileFailures.filterNot { it.clientFileRef in confirmedRefs },
            unprocessedFileRefs = next.unprocessedFileRefs.filterNot { it in confirmedRefs })
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
        catch (cancelled: CancellationException) { throw cancelled }
        catch (cause: Exception) {
            val failure = cause.safeFailure()
            if (failure.category == FailureCategory.UNKNOWN || failure.code == "internal_error") stopForInternalFailure(cause)
            else mutableState.value = state.value.copy(errorCode = failure.code, errorDiagnostic = failure.diagnostic)
        }
    }

    private fun current(request: ImportTaskRequest): Boolean = state.value.activeRequest == request

    private fun stopForInternalFailure(cause: Throwable) {
        uploadQueue.clear()
        mutableState.value = state.value.copy(internalFailure = cause, errorCode = "unknown_error", needsContextRefresh = true)
        com.finance.tracker.core.createDiagnosticLogger().record(com.finance.tracker.core.DiagnosticFeature.CASH_IMPORT,
            com.finance.tracker.core.DiagnosticAction.LOAD, "unknown_error", null, cause::class.simpleName)
    }

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
                    if (failure.category == FailureCategory.UNKNOWN || failure.code == "internal_error") stopForInternalFailure(cause)
                    mutableState.value = if (state.value.frozenCommand != null) state.value.confirmationFailed(request, failure)
                    else state.value.copy(errorCode = failure.code, errorDiagnostic = failure.diagnostic, needsContextRefresh = state.value.needsContextRefresh || failure.code in setOf("plan_changed", "session_changed"))
                }
            } finally {
                mutableState.value = state.value.finishRequest(request)
                if (uploadQueue.isNotEmpty() && state.value.taskId == request.taskId) pumpUploads(uploadGeneration)
            }
        }
    }
}

private const val MAX_CONCURRENT_IMPORT_UPLOADS = 3
private val FATAL_IMPORT_UPLOAD_CODES = setOf("session_closed", "session_committed", "session_expired", "session_not_found", "workspace_forbidden")
private fun Throwable.safeFailure(): DomainFailure = this as? DomainFailure ?: DomainFailure("unknown_error", 0, FailureCategory.UNKNOWN, cause = this)
private fun invalidTask(): Nothing = throw DomainFailure("import_input_invalid", 0, FailureCategory.RECOVERABLE)
