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
internal fun CashEntryEditor(record: CashCurrentRecord, savedRevision: Int, accounts: List<Account>, categories: List<CashCategory>,
    options: LedgerOptions, sizeClass: WindowSizeClass, busy: Boolean, onInput: (Boolean) -> Unit,
    onCancel: () -> Unit, onSave: (CashMaintenanceRequest) -> Unit) {
    val initial = remember(record.transactionId, record.rowRevision, record.entry?.revision, savedRevision) { cashEntryInitialValues(record) }
    var values by remember(initial, savedRevision) { mutableStateOf(initial) }
    val initialAllocations = remember(record.transactionId, record.rowRevision, savedRevision) { cashDraftInitialAllocations(record) }
    var allocationInput by remember(initialAllocations, savedRevision) { mutableStateOf(CashDraftAllocationInput(initialAllocations)) }
    var error by remember(initial, savedRevision) { mutableStateOf<String?>(null) }
    val editable = cashEntryEditableFields(record)
    val split = values["cash_granularity"] == "aggregate"
    val changes = values.filter { (field, value) -> initial[field] != value }.toMutableMap().apply {
        if (split && initial["account_id"].orEmpty().isNotEmpty()) put("account_id", "")
        else if (split) remove("account_id")
    }
    val parts = allocationInput.patchParts(split, values, initialAllocations)
    val dirty = changes.isNotEmpty() || parts != null
    val zone = getLocalTimeZoneId()
    LaunchedEffect(dirty) { onInput(dirty) }
    Column(Modifier.fillMaxWidth().testTag("cash.entry.editor"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CashRecordFieldInputs(values, editable, accounts, categories, options, sizeClass, busy, "cash.entry") { field, value ->
            if (field == "cash_granularity") allocationInput = allocationInput.switchMode()
            values = values + (field to value); error = null
        }
        if ("components" in editable) CashDraftAllocationEditor(split, if (split) allocationInput.parts else parts ?: initialAllocations,
            accounts, busy, singleNeedsRepair = !allocationInput.singleChosen && !cashDraftSingleAllocationMatches(initial, initialAllocations), semanticPrefix = "cash.entry",
            onChange = { allocationInput = allocationInput.changeParts(it); error = null }, onUseSingle = { allocationInput = allocationInput.chooseSingle(); error = null })
        error?.let { Text(when (it) {
            "invalid_datetime" -> localizedText("cash_invalid_local_time")
            "invalid_amount" -> localizedText("copy_d9ea7affdf")
            "invalid_currency" -> localizedText("cash_invalid_currency")
            else -> ledgerErrorText(it)
        }, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("cash.entry.error")) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel, enabled = !busy, modifier = Modifier.testTag("cash.entry.cancel")) { Text(localizedText("copy_4d0b4688c7")) }
            Button(onClick = {
                val request = try { cashEntryRequest(record, changes, zone, parts) } catch (failure: DomainFailure) { error = failure.code; return@Button }
                onSave(request)
            }, enabled = !busy && dirty, modifier = Modifier.testTag("cash.entry.save")) { Text(localizedText("copy_fadf24dbc5")) }
        }
    }
}
