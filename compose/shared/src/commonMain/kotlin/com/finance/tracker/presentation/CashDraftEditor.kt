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
        CashRecordFieldInputs(values, record.editableFields, accounts, categories, options, sizeClass, busy, "cash.draft", ::change)
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
