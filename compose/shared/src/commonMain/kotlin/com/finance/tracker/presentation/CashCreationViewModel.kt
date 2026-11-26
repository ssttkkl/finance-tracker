package com.finance.tracker.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.finance.tracker.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

data class CashCreationState(val draft: CashCreationDraft? = null, val baseline: CashCreationDraft? = null,
    val busy: Boolean = false, val errorCode: String? = null, val unknown: Boolean = false, val savedRevision: Int = 0,
    val canResumeSave: Boolean = false, val visible: Boolean = false,
    val fieldErrors: List<CashFormError> = emptyList()) {
    val dirty: Boolean get() = draft?.copy(mode = CashCreationMode.ORDINARY) != baseline?.copy(mode = CashCreationMode.ORDINARY)
}
class CashCreationViewModel(private val repository: CashCreationRepository, private val workspaceId: String? = null,
    private val principalEmail: String? = null) : ViewModel() {
    private data class Command(val mode: CashCreationMode, val input: StructuredObject, val key: String, val accountIds: List<String>)
    private var original: Command? = null
    private val mutableState = MutableStateFlow(CashCreationState())
    val state = mutableState.asStateFlow()
    fun open(accounts: List<Account>, occurredAt: String) {
        if (state.value.busy) return
        if (state.value.draft != null) {
            mutableState.value = state.value.copy(visible = true)
            return
        }
        val active = accounts.filter { it.active }
        val from = active.firstOrNull(); val to = active.firstOrNull { it.id != from?.id }
        val draft = CashCreationDraft(CashOrdinaryInput(mapOf("amount" to "", "currency" to from?.currencies?.firstOrNull().orEmpty(),
            "occurred_at" to occurredAt, "account_id" to from?.id?.toString().orEmpty(), "record_type" to "consumption",
            "record_subtype" to "", "category_id" to "", "counterparty" to "", "counterparty_account" to "",
            "counterparty_account_attrs" to "", "note" to "")),
            CashTransferInput(from?.id?.toString().orEmpty(), to?.id?.toString().orEmpty(), from?.currencies?.firstOrNull().orEmpty(),
                to?.currencies?.firstOrNull().orEmpty(), occurredAt = occurredAt))
        mutableState.value = state.value.copy(draft = draft, baseline = draft, errorCode = null, unknown = false, visible = true,
            fieldErrors = emptyList())
    }
    fun update(draft: CashCreationDraft) {
        if (state.value.busy || state.value.unknown || state.value.draft == null) return
        mutableState.value = state.value.copy(draft = draft, errorCode = null)
    }
    fun selectCreatedAccount(account: Account) {
        val draft = state.value.draft ?: return
        val id = account.id.toString()
        val currency = account.currencies.orEmpty().firstOrNull().orEmpty()
        val transfer = draft.transfer
        val nextTransfer = when {
            transfer.fromAccountId.isEmpty() -> transfer.copy(fromAccountId = id, fromCurrency = currency)
            transfer.toAccountId.isEmpty() && transfer.fromAccountId != id -> transfer.copy(toAccountId = id, toCurrency = currency)
            else -> transfer
        }
        update(draft.copy(ordinary = CashOrdinaryInput(draft.ordinary.values + mapOf("account_id" to id, "currency" to currency)),
            transfer = nextTransfer))
    }

    fun close(): Boolean {
        if (state.value.busy) return false
        if (state.value.canResumeSave) {
            mutableState.value = state.value.copy(visible = false)
            return true
        }
        original = null
        mutableState.value = CashCreationState(savedRevision = state.value.savedRevision)
        return true
    }
    fun save(accounts: List<Account>, timeZoneId: String) {
        val draft = state.value.draft ?: return
        if (state.value.busy || state.value.unknown && !state.value.canResumeSave) return
        val keyed = workspaceId != null && principalEmail != null && repository.supportsIdempotentSave
        val transfer = draft.mode == CashCreationMode.TRANSFER
        val split = draft.ordinary.values["cash_granularity"] == "aggregate"
        if (original == null) {
            val preflight = if (transfer) validateCashTransferCreation(draft.transfer, accounts, timeZoneId)
                else validateCashOrdinaryCreation(draft.ordinary, accounts, timeZoneId)
            if (preflight.isNotEmpty()) {
                mutableState.value = state.value.copy(fieldErrors = preflight, errorCode = null)
                return
            }
        }
        val command = original ?: try {
            val input = if (draft.mode == CashCreationMode.ORDINARY) cashOrdinaryCreation(draft.ordinary, accounts, timeZoneId)
                else cashTransferCreation(draft.transfer, accounts, timeZoneId)
            val accountIds = if (draft.mode == CashCreationMode.ORDINARY) listOf(draft.ordinary.values.getValue("account_id"))
                else listOf(draft.transfer.fromAccountId, draft.transfer.toAccountId)
            Command(draft.mode, input, Random.nextBytes(24).toHexString(), accountIds)
        } catch (failure: DomainFailure) {
            val mapped = cashFieldErrorKeyFor(failure.code, transfer, split)
            mutableState.value = state.value.copy(
                fieldErrors = mapped?.let { listOf(CashFormError(it.field, it.messageKey)) }.orEmpty(),
                errorCode = failure.code.takeIf { mapped == null },
            )
            return
        }
        val continuing = original != null
        if (keyed) original = command
        mutableState.value = state.value.copy(busy = true, errorCode = null, fieldErrors = emptyList())
        viewModelScope.launch {
            try {
                if (keyed && continuing) repository.resume(command.mode, command.input, command.key, command.accountIds,
                    requireNotNull(workspaceId), requireNotNull(principalEmail))
                else if (keyed) repository.save(command.mode, command.input, command.key, command.accountIds,
                    requireNotNull(workspaceId), requireNotNull(principalEmail))
                else if (command.mode == CashCreationMode.ORDINARY) repository.createRecord(command.input)
                else repository.createTransfer(command.input)
                original = null
                mutableState.value = CashCreationState(savedRevision = state.value.savedRevision + 1)
            } catch (cancelled: CancellationException) {
                mutableState.value = state.value.copy(busy = false, unknown = keyed, canResumeSave = keyed)
                throw cancelled
            }
            catch (failure: Exception) {
                val known = failure as? DomainFailure
                val unknown = known == null || known.commitState != "not_committed" && (continuing ||
                    known.commitState == "unknown" || known.status == 0 || known.status == 408 || known.status >= 500 || known.status in 200..299)
                if (!unknown) original = null
                val mapped = known?.let { cashFieldErrorKeyFor(it.code, transfer, split) }
                mutableState.value = state.value.copy(busy = false, unknown = unknown, canResumeSave = keyed && unknown,
                    fieldErrors = mapped?.let { listOf(CashFormError(it.field, it.messageKey)) }.orEmpty(),
                    errorCode = (known?.code ?: "request_failed").takeIf { mapped == null })
            }
        }
    }
}
