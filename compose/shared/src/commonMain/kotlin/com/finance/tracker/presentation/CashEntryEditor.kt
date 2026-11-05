package com.finance.tracker.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
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
    var noteDecision by remember(initial, savedRevision) { mutableStateOf(false) }
    var confirmedEmptyNote by remember(initial, savedRevision) { mutableStateOf(false) }
    var restoreNote by remember(initial, savedRevision) { mutableStateOf<Boolean?>(null) }
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
    fun save(nextChanges: Map<String, String>, restore: Boolean = false) {
        val request = try {
            if (pair != null) cashTransferEntryRequest(record, transferMembers, nextChanges, outgoing, incoming, zone)
            else cashEntryRequest(record, nextChanges, zone, parts)
        } catch (failure: DomainFailure) { error = failure.code; return }
        onSave(if (restore) request.copy(restoreNoteToken = record.entry?.originalNoteToken) else request)
    }
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
                    if (field == "note") confirmedEmptyNote = false
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
        val sideSheet = sizeClass != WindowSizeClass.COMPACT
        if (sideSheet) Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().height(if (sideSheet) 72.dp else 56.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = if (sideSheet) Arrangement.Start else Arrangement.End) {
            if (actions != null) actions() else {
                if (!sideSheet) {
                TextButton(onClick = onCancel, enabled = !busy, modifier = Modifier.testTag("cash.entry.cancel")) { Text(localizedText("copy_4d0b4688c7")) }
                }
                Button(onClick = {
                    if (changes["note"] == "" && initial["note"].orEmpty().isNotEmpty() && !confirmedEmptyNote) { restoreNote = null; noteDecision = true }
                    else save(changes)
                }, enabled = !busy && dirty, modifier = Modifier.testTag("cash.entry.save")) { Text(localizedText("copy_fadf24dbc5")) }
                if (sideSheet) {
                TextButton(onClick = onCancel, enabled = !busy, modifier = Modifier.testTag("cash.entry.cancel")) { Text(localizedText("copy_4d0b4688c7")) }
                }
            }
        }
        if (sideSheet) Spacer(Modifier.height(24.dp))
    }
    if (noteDecision) {
        PlatformControlAccessibility("cash.entry.restore-note", ControlAccessibilityState.Radio(restoreNote == true))
        PlatformControlAccessibility("cash.entry.keep-note-empty", ControlAccessibilityState.Radio(restoreNote == false))
        PlatformControlAccessibility("cash.entry.note-confirm", ControlAccessibilityState.Button(restoreNote != null))
        AlertDialog(
        onDismissRequest = { noteDecision = false },
        title = { Text(localizedText("cash_entry_clear_note_title")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                Text(localizedText("cash_entry_clear_note_body"))
                Text(localizedText("cash_entry_original_note"), style = MaterialTheme.typography.labelLarge)
                Text(record.entry?.originalNote?.takeIf { it.isNotEmpty() } ?: localizedText("cash_entry_original_note_empty"))
                Row(Modifier.fillMaxWidth().testTag("cash.entry.restore-note").selectable(
                    selected = restoreNote == true, role = Role.RadioButton, onClick = { restoreNote = true }),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    RadioButton(selected = restoreNote == true, onClick = null)
                    Text(localizedText("cash_entry_restore_note"))
                }
                Row(Modifier.fillMaxWidth().testTag("cash.entry.keep-note-empty").selectable(
                    selected = restoreNote == false, role = Role.RadioButton, onClick = { restoreNote = false }),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    RadioButton(selected = restoreNote == false, onClick = null)
                    Text(localizedText("cash_entry_keep_note_empty"))
                }
            }
        },
        confirmButton = { TextButton(onClick = {
            noteDecision = false
            if (restoreNote == true) {
                val original = record.entry?.originalNote.orEmpty()
                values = values + ("note" to original)
                save(changes + ("note" to original), restore = true)
            } else { confirmedEmptyNote = true; save(changes) }
        }, enabled = restoreNote != null, modifier = Modifier.testTag("cash.entry.note-confirm")) { Text(localizedText("copy_fadf24dbc5")) } },
        dismissButton = { TextButton(onClick = {
            noteDecision = false
        }) { Text(localizedText("copy_4d0b4688c7")) } },
        modifier = Modifier.testTag("cash.entry.note-decision"),
    )
    }
}
