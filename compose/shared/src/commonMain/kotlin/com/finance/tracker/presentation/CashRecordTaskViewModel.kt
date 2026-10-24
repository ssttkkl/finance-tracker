package com.finance.tracker.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.finance.tracker.core.*
import com.finance.tracker.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

data class CashRecordTaskState(
    val transactionId: String? = null, val record: CashCurrentRecord? = null,
    val plan: CashMaintenancePlan? = null, val details: List<ImportItemDetail> = emptyList(),
    val input: CashMaintenanceRequest? = null, val busy: Boolean = false, val recoveryReady: Boolean = false,
    val receipt: ImportReceipt? = null, val frozenCommand: ImportFrozenCommand? = null,
    val previewDirty: Boolean = false, val retryOriginalAllowed: Boolean = false, val errorCode: String? = null, val currentRecordStatus: String = "available",
    val savedRevision: Int = 0,
    val affectedRecords: Map<String, CashCurrentRecord> = emptyMap(),
    val selectionPlan: Boolean = false,
    val pendingMembers: List<CashCurrentRecord> = emptyList(),
    val transferMembers: List<CashCurrentRecord> = emptyList(),
) {
    val unresolved: Boolean get() = frozenCommand != null
}

class CashRecordTaskViewModel(
    private val repository: CashMaintenanceRepository,
    private val recovery: CashMaintenanceRecoveryStore,
    private val newKey: () -> String = { Random.nextBytes(24).joinToString("") { it.toUByte().toString(16).padStart(2, '0') } },
) : ViewModel() {
    private val mutableState = MutableStateFlow(CashRecordTaskState())
    val state = mutableState.asStateFlow()
    private var sequence = 0L
    private var job: Job? = null

    fun restore() {
        if (state.value.busy || state.value.recoveryReady) return
        execute { restorePointer() }
    }
    private suspend fun restorePointer(): Boolean {
        val pointer = recovery.load()
        currentCoroutineContext().ensureActive()
        mutableState.value = state.value.copy(recoveryReady = true)
        if (pointer == null) return false
        mutableState.value = state.value.copy(transactionId = pointer.transactionId, frozenCommand = pointer.command,
            retryOriginalAllowed = false)
        resolveOriginal()
        return true
    }
    fun open(transactionId: String) {
        if (state.value.unresolved || state.value.busy) return
        job?.cancel(); sequence++
        execute {
            if (!state.value.recoveryReady && restorePointer()) return@execute
            val savedReceipt = state.value.receipt.takeIf { state.value.transactionId == transactionId }
            mutableState.value = CashRecordTaskState(transactionId = transactionId, recoveryReady = true, busy = true,
                savedRevision = state.value.savedRevision, receipt = savedReceipt,
                errorCode = "request_failed".takeIf { savedReceipt?.status in setOf("failed", "partial") },
                currentRecordStatus = if (savedReceipt == null) "available" else "unavailable")
            val (record, members) = try { readCurrentView(transactionId) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { if (savedReceipt == null) throw failure else return@execute }
            currentCoroutineContext().ensureActive()
            if (record.transactionId != transactionId) invalid("request_failed")
            mutableState.value = state.value.copy(record = record, pendingMembers = members.filter { it.confirmationStatus == "pending" },
                transferMembers = if (record.recordOrigin == "manual_transfer" && record.entry?.supportsTransferEdit == true) listOf(record) + members else emptyList(),
                currentRecordStatus = "available")
        }
    }
    fun close(): Boolean {
        if (state.value.unresolved || state.value.busy && state.value.record != null) return false
        job?.cancel(); sequence++
        mutableState.value = CashRecordTaskState(recoveryReady = state.value.recoveryReady, savedRevision = state.value.savedRevision)
        return true
    }
    fun dismissPlan() { cancelEdit() }
    fun cancelEdit(): Boolean {
        if (state.value.busy || state.value.unresolved) return false
        cancelUnsentPlan()
        return true
    }

    private fun cancelUnsentPlan() {
        mutableState.value = state.value.copy(plan = null, details = emptyList(), input = null,
            previewDirty = false, selectionPlan = false, affectedRecords = emptyMap())
    }

    fun beginRelations(patch: StructuredObject = StructuredObject(emptyMap())) {
        val record = state.value.record ?: return
        if (record.recordOrigin in setOf("manual", "manual_transfer")) return
        prepare(CashMaintenanceRequest("maintain_relations", record.transactionId, record.rowRevision, patch), forSelection = true)
    }
    fun saveRelationChoices(choices: List<CashRelationDecision>) {
        val record = state.value.record ?: return
        if (!state.value.selectionPlan || state.value.busy || state.value.unresolved) return
        val options = state.value.details.flatMap { it.relationChoices }
        if (!cashRelationChoicesLoaded(choices, options, state.value.affectedRecords)) {
            mutableState.value = state.value.copy(errorCode = "record_changed"); return
        }
        val input = try { cashRelationRequest(record, choices, options, state.value.input?.patch ?: StructuredObject(emptyMap())) }
        catch (failure: DomainFailure) { mutableState.value = state.value.copy(errorCode = failure.code); return }
        prepare(input)
    }

    fun resolveIssue(issueId: String, resolution: String, targetId: String? = null, patch: StructuredObject = StructuredObject(emptyMap())) {
        val record = state.value.record ?: return
        resolveIssueFor(record, issueId, resolution, targetId, patch)
    }
    fun resolveMemberIssue(transactionId: String, issueId: String, resolution: String, targetId: String?) {
        val record = state.value.pendingMembers.singleOrNull { it.transactionId == transactionId } ?: return
        resolveIssueFor(record, issueId, resolution, targetId, StructuredObject(emptyMap()))
    }
    private fun resolveIssueFor(record: CashCurrentRecord, issueId: String, resolution: String, targetId: String?, patch: StructuredObject) {
        if (state.value.busy || state.value.unresolved) return
        val input = try { cashIssueRequest(record, issueId, resolution, targetId, patch) }
        catch (failure: DomainFailure) { mutableState.value = state.value.copy(errorCode = failure.code); return }
        prepare(input)
    }

    fun saveDraft(patch: StructuredObject) {
        val record = state.value.record ?: return
        if (state.value.busy || state.value.unresolved) return
        if (record.state != "draft" || patch.fields.keys.any { it !in record.editableFields }) {
            mutableState.value = state.value.copy(errorCode = "field_not_editable"); return
        }
        prepare(CashMaintenanceRequest("save_draft", record.transactionId, record.rowRevision, patch))
    }

    fun prepare(input: CashMaintenanceRequest, forSelection: Boolean = false) {
        val record = (listOfNotNull(state.value.record) + state.value.pendingMembers).firstOrNull { it.transactionId == input.transactionId } ?: return
        if (state.value.busy || state.value.unresolved) return
        if (input.transactionId != record.transactionId || input.expectedRevision != record.rowRevision) {
            mutableState.value = state.value.copy(errorCode = "record_changed"); return
        }
        mutableState.value = state.value.copy(previewDirty = true)
        execute {
            try {
                val old = state.value.plan
                val request = if (old != null && input.editContextId == null) input.copy(editContextId = old.editContextId,
                    previousPlanId = old.planId, expectedPlanRevision = old.revision) else input
                val plan = repository.preview(request)
                currentCoroutineContext().ensureActive()
                if (plan.additionalEffects == null || request.intent == "maintain_relations" && plan.invalidatedChoices.isNotEmpty()) invalid("plan_changed")
                mutableState.value = state.value.copy(input = request, plan = plan)
                val items = plan.items.toMutableList()
                val cursors = mutableSetOf<String>()
                var cursor = plan.nextCursor
                while (cursor != null) {
                    if (!cursors.add(cursor)) invalid("request_failed")
                    val page = repository.items(plan.planId, cursor)
                    items += page.items
                    cursor = page.nextCursor
                }
                if (items.map { it.itemId }.distinct().size != items.size) invalid("request_failed")
                val details = items.filter { it.detailAvailable }.map { repository.item(plan.planId, it.itemId) }
                val memberDetails = details.flatMap { it.members }
                val references = details.flatMap { it.relationChoices }.flatMap { group -> group.members + group.candidates.flatMap { it.members } }
                val ids = (memberDetails.mapNotNull { it.transactionId } + references.mapNotNull { it.transactionId }).distinct()
                val affected = ids.associateWith { id ->
                    val before = if (id == record.transactionId) record else repository.record(id)
                    if (before.transactionId != id || memberDetails.filter { it.transactionId == id }.any { it.rowRevision != null && before.rowRevision != it.rowRevision }) invalid("record_changed")
                    before
                }
                currentCoroutineContext().ensureActive()
                val needsRelationChoices = request.intent == "maintain_relations" && (plan.invalidatedChoices.isNotEmpty() ||
                    details.flatMap { it.relationChoices }.any { option ->
                        val requested = request.relationChoices.firstOrNull { it.suggestionId == option.suggestionId }
                        requested?.action == "accept" && (option.action in setOf("defer", "skip") ||
                            option.commonFieldConflicts.any { (field, values) -> requested.commonFields?.fields?.get(field) !in values })
                    })
                val selection = forSelection || needsRelationChoices
                mutableState.value = state.value.copy(input = request, plan = plan.copy(items = items, nextCursor = null), details = details, affectedRecords = affected, previewDirty = false, selectionPlan = selection)
                if (!selection && plan.additionalEffects.isEmpty() && plan.canCommit && plan.invalidatedChoices.isEmpty()) sendPlan(plan, record.transactionId)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (input.intent == "maintain_relations" && !state.value.unresolved) cancelUnsentPlan()
                throw error
            }
        }
    }

    fun confirmPlan() {
        val plan = state.value.plan ?: return
        val record = state.value.record ?: return
        if (state.value.busy || state.value.unresolved || state.value.previewDirty || state.value.selectionPlan || !plan.canCommit || plan.invalidatedChoices.isNotEmpty()) return
        execute { sendPlan(plan, state.value.input?.transactionId ?: record.transactionId) }
    }
    private suspend fun sendPlan(plan: CashMaintenancePlan, transactionId: String) {
        val command = ImportFrozenCommand(plan.planId, plan.revision, newKey())
        recovery.save(CashMaintenanceRecoveryPointer(transactionId, command))
        currentCoroutineContext().ensureActive()
        mutableState.value = state.value.copy(frozenCommand = command, retryOriginalAllowed = false)
        sendOriginal(command)
    }
    private suspend fun sendOriginal(command: ImportFrozenCommand) {
        val receipt = try { repository.commit(command) }
        catch (failure: DomainFailure) {
            val rejectedBeforeWrite = failure.commitState != "unknown" && failure.status in setOf(409, 410) &&
                failure.code in setOf("record_changed", "issue_changed", "relation_context_changed", "plan_changed", "plan_expired")
            if (failure.commitState == "not_committed" || rejectedBeforeWrite) {
                recovery.clear()
                mutableState.value = state.value.copy(frozenCommand = null, retryOriginalAllowed = false,
                    plan = null, details = emptyList(), input = null, previewDirty = false, selectionPlan = false,
                    affectedRecords = emptyMap())
            }
            throw failure
        }
        if (receipt.planId != command.planId) invalid("request_failed")
        publishReceipt(receipt)
    }
    fun retryOriginal() {
        val command = state.value.frozenCommand ?: return
        if (state.value.busy || !state.value.retryOriginalAllowed) return
        execute {
            mutableState.value = state.value.copy(retryOriginalAllowed = false)
            sendOriginal(command)
        }
    }

    fun resolve() {
        if (state.value.busy || !state.value.unresolved) return
        execute { resolveOriginal() }
    }
    private suspend fun resolveOriginal() {
        val command = state.value.frozenCommand ?: return
        val result = repository.resolve(command)
        when (result.status) {
            "committed" -> {
                val receipt = result.receipt ?: invalid("request_failed")
                if (receipt.planId != command.planId) invalid("request_failed")
                publishReceipt(receipt)
            }
            "not_committed" -> {
                recovery.clear()
                mutableState.value = state.value.copy(frozenCommand = null, plan = null, details = emptyList(), input = null,
                    errorCode = "commit_not_applied")
            }
            // 原键可重发也不自动写入；当前界面先继续核实，避免重复点击改变命令。
            "pending", "retry_original" -> mutableState.value = state.value.copy(errorCode = "commit_result_unknown", retryOriginalAllowed = result.status == "retry_original")
            else -> invalid("request_failed")
        }
    }
    private suspend fun publishReceipt(receipt: ImportReceipt) {
        if (!receipt.committed) invalid("request_failed")
        val target = receipt.retainedTransactionId ?: state.value.input?.transactionId ?: state.value.transactionId ?: invalid("request_failed")
        val current = try { readCurrentView(target) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
        acceptReceipt(receipt, if (current == null) "unavailable" else "available", current?.first, current?.second.orEmpty())
    }
    private suspend fun readCurrentView(transactionId: String): Pair<CashCurrentRecord, List<CashCurrentRecord>> {
        val record = repository.record(transactionId)
        if (record.transactionId != transactionId) invalid("request_failed")
        val ref = record.projectionRef ?: return record to emptyList()
        val group = repository.currentGroup(ref.projectionId)
        if (group.projectionVersion != ref.projectionVersion) invalid("record_changed")
        val members = group.members
        if (members.none { it.transactionId == transactionId } || members.map { it.transactionId }.distinct().size != members.size) invalid("record_changed")
        if (members.single { it.transactionId == transactionId }.rowRevision != record.rowRevision) invalid("record_changed")
        val completeTransfer = record.recordOrigin == "manual_transfer" && record.entry?.supportsTransferEdit == true
        val related = members.filter { it.transactionId != transactionId && (it.confirmationStatus == "pending" || completeTransfer) }.map {
            val member = repository.record(it.transactionId)
            if (member.transactionId != it.transactionId || member.rowRevision != it.rowRevision ||
                member.projectionRef?.projectionId != ref.projectionId || member.projectionRef.projectionVersion != group.projectionVersion) invalid("record_changed")
            member
        }
        return record to related
    }
    private suspend fun acceptReceipt(receipt: ImportReceipt, status: String, record: CashCurrentRecord?, members: List<CashCurrentRecord>) {
        currentCoroutineContext().ensureActive()
        if (!receipt.committed) invalid("request_failed")
        mutableState.value = state.value.copy(receipt = receipt, currentRecordStatus = status, record = record, pendingMembers = members.filter { it.confirmationStatus == "pending" },
            transferMembers = if (record?.recordOrigin == "manual_transfer" && record.entry?.supportsTransferEdit == true) listOf(record) + members else emptyList(),
            transactionId = receipt.retainedTransactionId ?: record?.transactionId ?: state.value.input?.transactionId ?: state.value.transactionId,
            frozenCommand = null, retryOriginalAllowed = false, plan = null, details = emptyList(), input = null,
            savedRevision = state.value.savedRevision + 1,
            errorCode = "request_failed".takeIf { receipt.status in setOf("partial", "failed") })
        recovery.clear()
    }
    private fun execute(action: suspend () -> Unit) {
        if (state.value.busy) return
        val ticket = ++sequence
        mutableState.value = state.value.copy(busy = true, errorCode = null)
        job = viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (ticket == sequence) mutableState.value = state.value.copy(errorCode =
                (error as? DomainFailure)?.code ?: "request_failed") }
            finally { if (ticket == sequence) mutableState.value = state.value.copy(busy = false) }
        }
    }
    private fun invalid(code: String): Nothing = throw DomainFailure(code, 0, FailureCategory.RECOVERABLE)
}
