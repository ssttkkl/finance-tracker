package com.finance.tracker.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.finance.tracker.core.*
import com.finance.tracker.domain.*

@Composable
internal fun CashCreationPanel(state: CashCreationState, accounts: List<Account>, categories: List<CashCategory>,
    options: LedgerOptions, sizeClass: WindowSizeClass, onChange: (CashCreationDraft) -> Unit, onClose: () -> Unit, onSave: () -> Unit) {
    val draft = state.draft ?: return
    var confirmDiscard by remember { mutableStateOf(false) }
    val requestClose = { if (!state.busy) { if (state.dirty && !state.unknown) confirmDiscard = true else onClose() } }
    val locked = state.busy || state.unknown
    CashRecordModalPanel(sizeClass, localizedText("copy_1f62e3f53f"), compactFullScreen = true,
        semanticStateKey = "cash-creation", onDismiss = requestClose, onBack = requestClose,
        footer = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = requestClose, enabled = !state.busy, modifier = Modifier.testTag("cash.create.cancel")) {
                    Text(localizedText(if (state.unknown) "copy_855fa817d2" else "copy_4d0b4688c7"))
                }
                if (!state.unknown) Button(onClick = onSave, enabled = !state.busy, modifier = Modifier.testTag("cash.create.save")) {
                    Text(localizedText(if (state.busy) "copy_6644f06197" else "copy_fadf24dbc5"))
                }
            }
        }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp).testTag("cash.create.form"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((mode, label) in listOf(CashCreationMode.ORDINARY to "cash_ordinary_booking", CashCreationMode.TRANSFER to "ledger_quick_transfer")) {
                    FilterChip(selected = draft.mode == mode, onClick = { onChange(draft.copy(mode = mode)) }, enabled = !locked,
                        label = { Text(localizedText(label)) }, modifier = Modifier.testTag("cash.create.mode-${mode.name.lowercase()}"))
                }
            }
            if (state.unknown) StateMessage(localizedText("cash_create_unknown"), isError = true, modifier = Modifier.testTag("cash.create.unknown"))
            else state.errorCode?.let { code ->
                StateMessage(when (code) {
                    "invalid_datetime" -> localizedText("cash_invalid_local_time")
                    "invalid_amount" -> localizedText("cash_create_invalid_amount")
                    "invalid_currency" -> localizedText("cash_invalid_currency")
                    "invalid_account" -> localizedText("cash_invalid_account")
                    else -> ledgerErrorText(code)
                }, isError = true, modifier = Modifier.testTag("cash.create.error"))
            }
            if (draft.mode == CashCreationMode.ORDINARY) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((value, label) in listOf("consumption" to "ledger_quick_expense", "income" to "ledger_quick_income")) {
                        FilterChip(selected = draft.ordinary.values["record_type"] == value, onClick = {
                            onChange(draft.copy(ordinary = CashOrdinaryInput(draft.ordinary.values + ("record_type" to value) + ("record_subtype" to ""))))
                        }, enabled = !locked, label = { Text(localizedText(label)) }, modifier = Modifier.testTag("cash.create.direction-$value"))
                    }
                }
                CashRecordFieldInputs(draft.ordinary.values, cashOrdinaryFields.filter { it !in setOf("record_type", "record_subtype") }, accounts, categories, options, sizeClass,
                    locked, "cash.create") { field, value ->
                    val values = draft.ordinary.values + (field to value)
                    val currency = if (field == "account_id") accounts.firstOrNull { it.id.toString() == value }?.currencies?.firstOrNull() else null
                    onChange(draft.copy(ordinary = CashOrdinaryInput(if (currency == null) values else values + ("currency" to currency))))
                }
            } else CashTransferCreationFields(draft.transfer, accounts, sizeClass, locked) { onChange(draft.copy(transfer = it)) }
        }
    }
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false }, title = { Text(localizedText("ledger_record_discard_title")) },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; onClose() }) { Text(localizedText("ledger_record_discard_confirm")) } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(localizedText("ledger_record_discard_continue")) } })
}

@Composable
private fun CashTransferCreationFields(input: CashTransferInput, accounts: List<Account>, sizeClass: WindowSizeClass,
    busy: Boolean, onChange: (CashTransferInput) -> Unit) {
    val choices = listOf("" to localizedText("display_not_provided")) + accounts.filter { it.active }.map { it.id.toString() to it.name }
    ChoicePicker(localizedText("cash_from_account"), input.fromAccountId, choices, { value ->
        onChange(input.copy(fromAccountId = value, fromCurrency = accounts.firstOrNull { it.id.toString() == value }?.currencies?.firstOrNull().orEmpty()))
    }, enabled = !busy, semanticId = "cash.create.from-account")
    CashTransferCurrency(input.fromCurrency, input.fromAccountId, accounts, busy, "cash.create.from-currency") { onChange(input.copy(fromCurrency = it)) }
    OutlinedTextField(input.amount, { onChange(input.copy(amount = it)) }, label = { Text(localizedText("cash_from_amount")) },
        enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("cash.create.from-amount"))
    ChoicePicker(localizedText("cash_to_account"), input.toAccountId, choices, { value ->
        onChange(input.copy(toAccountId = value, toCurrency = accounts.firstOrNull { it.id.toString() == value }?.currencies?.firstOrNull().orEmpty()))
    }, enabled = !busy, semanticId = "cash.create.to-account")
    CashTransferCurrency(input.toCurrency, input.toAccountId, accounts, busy, "cash.create.to-currency") { onChange(input.copy(toCurrency = it)) }
    if (input.fromCurrency != input.toCurrency) OutlinedTextField(input.toAmount, { onChange(input.copy(toAmount = it)) },
        label = { Text(localizedText("cash_to_amount")) }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("cash.create.to-amount"))
    DateTimePickerInput(input.occurredAt, { onChange(input.copy(occurredAt = it)) }, localizedText("copy_51f85a78ca"), sizeClass,
        enabled = !busy, modifier = Modifier.testTag("cash.create.transfer-time"))
    OutlinedTextField(input.note, { onChange(input.copy(note = it)) }, label = { Text(localizedText("copy_e0361480e3")) },
        enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("cash.create.transfer-note"))
}

@Composable
private fun CashTransferCurrency(value: String, accountId: String, accounts: List<Account>, busy: Boolean, id: String, onChange: (String) -> Unit) {
    val currencies = (accounts.firstOrNull { it.id.toString() == accountId }?.currencies.orEmpty() + value).distinct()
    ChoicePicker(localizedText("copy_a81ab5e100"), value, currencies.map { it to it }, onChange, enabled = !busy, semanticId = id)
}
