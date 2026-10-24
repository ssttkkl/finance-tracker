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
internal fun CashEntryEditor(record: CashCurrentRecord, savedRevision: Int, accounts: List<Account>, categories: List<CashCategory>,
    options: LedgerOptions, sizeClass: WindowSizeClass, busy: Boolean, onInput: (Boolean) -> Unit,
    onCancel: () -> Unit, onSave: (CashMaintenanceRequest) -> Unit, transferMembers: List<CashCurrentRecord> = emptyList(), modifier: Modifier = Modifier,
    summary: @Composable () -> Unit = {}, confirmation: @Composable () -> Unit = {}, actions: (@Composable () -> Unit)? = null) {
    val initial = remember(record.transactionId, record.rowRevision, record.entry?.revision, savedRevision) { cashEntryInitialValues(record) }
    var values by remember(initial, savedRevision) { mutableStateOf(initial) }
    val initialAllocations = remember(record.transactionId, record.rowRevision, savedRevision) { cashDraftInitialAllocations(record) }
    var allocationInput by remember(initialAllocations, savedRevision) { mutableStateOf(CashDraftAllocationInput(initialAllocations)) }
    var error by remember(initial, savedRevision) { mutableStateOf<String?>(null) }
    val editable = cashEntryEditableFields(record)
    val pair = cashTransferEntryMembers(record, transferMembers)
    val initialOutgoing = remember(pair) { pair?.outgoing?.let(::cashTransferEntryValues).orEmpty() }
    val initialIncoming = remember(pair) { pair?.incoming?.let(::cashTransferEntryValues).orEmpty() }
    var outgoing by remember(initialOutgoing, savedRevision) { mutableStateOf(initialOutgoing) }
    var incoming by remember(initialIncoming, savedRevision) { mutableStateOf(initialIncoming) }
    val effectiveIncoming = if (pair == null) incoming else cashTransferIncomingValues(outgoing, incoming, initialOutgoing, initialIncoming)
    val split = values["cash_granularity"] == "aggregate"
    val changes = values.filter { (field, value) -> initial[field] != value }.toMutableMap().apply {
        if (split && initial["account_id"].orEmpty().isNotEmpty()) put("account_id", "")
        else if (split) remove("account_id")
    }
    val parts = allocationInput.patchParts(split, values, initialAllocations)
    val dirty = changes.isNotEmpty() || parts != null || pair != null && (outgoing != initialOutgoing || effectiveIncoming != initialIncoming)
    val zone = getLocalTimeZoneId()
    LaunchedEffect(dirty) { onInput(dirty) }
    val formScroll = rememberScrollState()
    val reviewScroll = rememberScrollState()
    Column(modifier.fillMaxSize().testTag("cash.entry.editor")) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(if (actions == null) formScroll else reviewScroll).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (actions != null) {
                Column(Modifier.fillMaxWidth().testTag("cash.entry.review")) { confirmation() }
            } else {
                summary()
                if (pair != null) CashTransferEntryFields(outgoing, incoming, accounts, sizeClass, busy,
                    onOutgoing = { outgoing = it; error = null }, onIncoming = { incoming = it; error = null })
                CashRecordFieldInputs(values, editable, accounts, categories, options, sizeClass, busy, "cash.entry", bookingContent = if ("components" in editable) ({
                    CashDraftAllocationEditor(split, if (split) allocationInput.parts else parts ?: initialAllocations,
                        accounts, busy, singleNeedsRepair = !allocationInput.singleChosen && !cashDraftSingleAllocationMatches(initial, initialAllocations), semanticPrefix = "cash.entry",
                        onChange = { allocationInput = allocationInput.changeParts(it); error = null }, onUseSingle = { allocationInput = allocationInput.chooseSingle(); error = null })
                }) else null) { field, value ->
                    if (field == "cash_granularity") allocationInput = allocationInput.switchMode()
                    values = values + (field to value); error = null
                }
                error?.let { Text(when (it) {
                    "invalid_datetime" -> localizedText("cash_invalid_local_time")
                    "invalid_amount" -> localizedText(if (pair == null) "copy_d9ea7affdf" else "cash_create_invalid_amount")
                    "invalid_currency" -> localizedText("cash_invalid_currency")
                    else -> ledgerErrorText(it)
                }, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("cash.entry.error")) }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
            if (actions != null) actions() else {
                TextButton(onClick = onCancel, enabled = !busy, modifier = Modifier.testTag("cash.entry.cancel")) { Text(localizedText("copy_4d0b4688c7")) }
                Button(onClick = {
                    val request = try {
                        if (pair != null) cashTransferEntryRequest(record, transferMembers, changes, outgoing, incoming, zone)
                        else cashEntryRequest(record, changes, zone, parts)
                    } catch (failure: DomainFailure) { error = failure.code; return@Button }
                    onSave(request)
                }, enabled = !busy && dirty, modifier = Modifier.testTag("cash.entry.save")) { Text(localizedText("copy_fadf24dbc5")) }
            }
        }
    }
}
