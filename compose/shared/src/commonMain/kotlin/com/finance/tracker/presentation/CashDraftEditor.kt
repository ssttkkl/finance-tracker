package com.finance.tracker.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.finance.tracker.core.*
import com.finance.tracker.domain.*

@Composable
internal fun CashDraftEditor(record: CashCurrentRecord, savedRevision: Int, accounts: List<Account>, categories: List<CashCategory>, options: LedgerOptions,
    sizeClass: WindowSizeClass, busy: Boolean, onInput: (Boolean, StructuredObject?) -> Unit, onSave: (StructuredObject, Boolean) -> Unit) {
    val initial = remember(record.transactionId, record.rowRevision, savedRevision) { cashDraftInitialValues(record) }
    var values by remember(record.transactionId, record.rowRevision, savedRevision) { mutableStateOf(initial) }
    val initialAllocations = remember(record.transactionId, record.rowRevision, savedRevision) { cashDraftInitialAllocations(record) }
    var allocationInput by remember(record.transactionId, record.rowRevision, savedRevision) { mutableStateOf(CashDraftAllocationInput(initialAllocations)) }
    var error by remember(record.transactionId, record.rowRevision, savedRevision) { mutableStateOf<String?>(null) }
    val split = values["cash_granularity"] == "aggregate"
    val changes = values.filter { (field, value) -> initial[field] != value }.toMutableMap().apply {
        if (split && initial["account_id"].orEmpty().isNotEmpty()) put("account_id", "")
        else if (split) remove("account_id")
    }
    val allocationChanges = allocationInput.patchParts(split, values, initialAllocations)
    val effectiveAllocations = if (split) allocationInput.parts else allocationChanges ?: initialAllocations
    val dirty = changes.isNotEmpty() || allocationChanges != null
    val unavailable = localizedText("copy_d041bb6737")
    fun optionsWithCurrent(value: String, options: List<Pair<String, String>>) =
        if (value.isEmpty() || options.any { it.first == value }) options else options + (value to unavailable)
    val zone = getLocalTimeZoneId()
    val currentPatch = remember(record, changes, allocationChanges, zone) { runCatching { cashDraftPatch(record, changes, zone, allocationChanges) }.getOrNull() }
    LaunchedEffect(dirty, currentPatch) { onInput(dirty, currentPatch) }
    fun change(field: String, value: String) {
        if (field == "cash_granularity") allocationInput = allocationInput.switchMode()
        values = values + (field to value); error = null
    }
    fun save(finalize: Boolean) {
        val patch = try { cashDraftPatch(record, changes, zone, allocationChanges) } catch (failure: DomainFailure) { error = failure.code; return }
        onSave(patch, finalize)
    }
    Column(Modifier.fillMaxWidth().testTag("cash.draft.editor"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val selectedAccount = accounts.firstOrNull { it.id.toString() == values["account_id"] }
        val selectedType = options.recordTypes.firstOrNull { it.value == values["record_type"] }
        for (field in cashDraftScalarFields.filter { it in values }) {
            if (field == "account_id" && split) continue
            val label = cashFieldLabel(field)?.let { localizedText(it) } ?: continue
            val value = values[field].orEmpty()
            when (field) {
                "cash_granularity" -> ChoicePicker(label, value, listOf("atomic" to localizedText("cash_allocation_single"), "aggregate" to localizedText("cash_allocation_split")),
                    { change(field, it) }, enabled = !busy, semanticId = "cash.draft.cash_granularity")
                "occurred_at" -> DateTimePickerInput(value, { change(field, it) }, label, sizeClass,
                    enabled = !busy, allowEmpty = true, modifier = Modifier.testTag("cash.draft.occurred_at"))
                "account_id" -> ChoicePicker(label, value, optionsWithCurrent(value, listOf("" to localizedText("display_not_provided")) + accounts.filter { it.active || it.id.toString() == value }.map { it.id.toString() to it.name }),
                    { change(field, it) }, enabled = !busy, semanticId = "cash.draft.account_id")
                "currency" -> ChoicePicker(label, value, (listOf("") + selectedAccount?.currencies.orEmpty().ifEmpty { accounts.flatMap { it.currencies.orEmpty() } } + value).distinct()
                    .map { it to it.ifEmpty { localizedText("display_not_provided") } }, { change(field, it) }, enabled = !busy, semanticId = "cash.draft.currency")
                "category_id" -> ChoicePicker(label, value, optionsWithCurrent(value, listOf("" to localizedText("copy_f11956caf6")) + categories.map { it.id to cashCategoryDisplayPath(it) }),
                    { change(field, it) }, enabled = !busy, semanticId = "cash.draft.category_id")
                "record_type" -> ChoicePicker(label, value, optionsWithCurrent(value, listOf("" to localizedText("display_not_provided")) + options.recordTypes.map { it.value to cashRecordTypeLabel(it.value, options) }),
                    { next -> change(field, next); if (next != value && "record_subtype" in values) change("record_subtype", "") }, enabled = !busy, semanticId = "cash.draft.record_type")
                "record_subtype" -> ChoicePicker(label, value, optionsWithCurrent(value, listOf("" to localizedText("display_not_provided")) + selectedType?.subtypes.orEmpty().map { it.value to it.label }),
                    { change(field, it) }, enabled = !busy, semanticId = "cash.draft.record_subtype")
                "counterparty_account_attrs" -> {
                    val choices = mutableListOf("" to localizedText("display_not_provided"))
                    for (attribute in listOf("full", "tail", "masked")) choices += attribute to localizedText(cashAccountAttributeLabelKey(attribute)!!)
                    if (value.isNotEmpty() && choices.none { it.first == value }) {
                        val labels = mutableListOf<String>()
                        value.split(',').mapNotNull(::cashAccountAttributeLabelKey).forEach { labels += localizedText(it) }
                        choices += value to labels.joinToString("、").ifEmpty { localizedText("display_not_provided") }
                    }
                    ChoicePicker(label, value, choices, { change(field, it) }, enabled = !busy, semanticId = "cash.draft.counterparty_account_attrs")
                }
                else -> OutlinedTextField(value, { change(field, it) }, label = { Text(label) }, enabled = !busy,
                    singleLine = field != "note", isError = field == "amount" && value.isNotEmpty() && !isExactDecimalString(value),
                    modifier = Modifier.fillMaxWidth().testTag("cash.draft.$field"))
            }
        }
        if ("components" in record.editableFields) CashDraftAllocationEditor(split, effectiveAllocations, accounts, busy,
            singleNeedsRepair = !allocationInput.singleChosen && !cashDraftSingleAllocationMatches(initial, initialAllocations),
            onChange = { allocationInput = allocationInput.changeParts(it); error = null },
            onUseSingle = { allocationInput = allocationInput.chooseSingle(); error = null })
        error?.let { Text(when (it) { "invalid_datetime" -> localizedText("cash_invalid_local_time"); "invalid_amount" -> localizedText("copy_d9ea7affdf"); "invalid_currency" -> localizedText("cash_invalid_currency"); else -> ledgerErrorText(it) }, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("cash.draft.error")) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { save(false) }, enabled = !busy && dirty, modifier = Modifier.testTag("cash.draft.save")) { Text(localizedText("cash_save_pending")) }
            Button(onClick = { save(true) }, enabled = !busy, modifier = Modifier.testTag("cash.draft.finalize")) { Text(localizedText("cash_finalize_record")) }
        }
    }
}
