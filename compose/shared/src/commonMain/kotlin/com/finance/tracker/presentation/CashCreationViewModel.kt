package com.finance.tracker.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.finance.tracker.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CashCreationState(val draft: CashCreationDraft? = null, val baseline: CashCreationDraft? = null,
    val busy: Boolean = false, val errorCode: String? = null, val unknown: Boolean = false, val savedRevision: Int = 0) {
    val dirty: Boolean get() = draft?.copy(mode = CashCreationMode.ORDINARY) != baseline?.copy(mode = CashCreationMode.ORDINARY)
}
class CashCreationViewModel(private val repository: CashCreationRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(CashCreationState())
    val state = mutableState.asStateFlow()
    fun open(accounts: List<Account>, occurredAt: String) {
        if (state.value.draft != null || state.value.busy) return
        val active = accounts.filter { it.active }
        val from = active.firstOrNull(); val to = active.firstOrNull { it.id != from?.id }
        val draft = CashCreationDraft(CashOrdinaryInput(mapOf("amount" to "", "currency" to from?.currencies?.firstOrNull().orEmpty(),
            "occurred_at" to occurredAt, "account_id" to from?.id?.toString().orEmpty(), "record_type" to "consumption",
            "record_subtype" to "", "category_id" to "", "counterparty" to "", "counterparty_account" to "",
            "counterparty_account_attrs" to "", "note" to "")),
            CashTransferInput(from?.id?.toString().orEmpty(), to?.id?.toString().orEmpty(), from?.currencies?.firstOrNull().orEmpty(),
                to?.currencies?.firstOrNull().orEmpty(), occurredAt = occurredAt))
        mutableState.value = state.value.copy(draft = draft, baseline = draft, errorCode = null, unknown = false)
    }
    fun update(draft: CashCreationDraft) {
        if (state.value.busy || state.value.unknown || state.value.draft == null) return
        mutableState.value = state.value.copy(draft = draft, errorCode = null)
    }
    fun close(): Boolean {
        if (state.value.busy) return false
        mutableState.value = CashCreationState(savedRevision = state.value.savedRevision)
        return true
    }
    fun save(accounts: List<Account>, timeZoneId: String) {
        val draft = state.value.draft ?: return
        if (state.value.busy || state.value.unknown) return
        val input = try {
            if (draft.mode == CashCreationMode.ORDINARY) cashOrdinaryCreation(draft.ordinary, accounts, timeZoneId)
            else cashTransferCreation(draft.transfer, accounts, timeZoneId)
        } catch (failure: DomainFailure) { mutableState.value = state.value.copy(errorCode = failure.code); return }
        mutableState.value = state.value.copy(busy = true, errorCode = null)
        viewModelScope.launch {
            try {
                if (draft.mode == CashCreationMode.ORDINARY) repository.createRecord(input) else repository.createTransfer(input)
                mutableState.value = CashCreationState(savedRevision = state.value.savedRevision + 1)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                val known = failure as? DomainFailure
                val unknown = known == null || known.status == 0 || known.status >= 500 || known.status in 200..299 || known.commitState == "unknown"
                mutableState.value = state.value.copy(busy = false, unknown = unknown, errorCode = known?.code ?: "request_failed")
            }
        }
    }
}
