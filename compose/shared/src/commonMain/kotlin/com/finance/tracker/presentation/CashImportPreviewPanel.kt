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
internal fun CashImportPreviewPanel(item: ImportProjectedItem, detail: ImportItemDetail?, planId: String, busy: Boolean,
    sizeClass: WindowSizeClass, accounts: List<Account>, categories: List<CashCategory>, options: LedgerOptions,
    errorCode: String?, onRetry: () -> Unit, onClose: () -> Unit, onEntry: (ImportEntryChoice) -> Unit,
    onRow: (ImportRecordChoice) -> Unit, onRelation: (ImportPairChoice) -> Unit, onDirty: (Boolean) -> Unit = {}) {
    var expanded by remember(planId, item.itemId) { mutableStateOf<String?>(null) }
    var editingMember by remember(planId, item.itemId) { mutableStateOf<String?>(null) }
    var entryEditing by remember(planId, item.itemId) { mutableStateOf(false) }
    var dirty by remember(planId, item.itemId) { mutableStateOf(false) }
    var discardAction by remember(planId, item.itemId) { mutableStateOf<(() -> Unit)?>(null) }
    fun request(action: () -> Unit) { if (!busy) { if (dirty) discardAction = action else action() } }
    LaunchedEffect(dirty) { onDirty(dirty) }
    val close = { request(onClose) }
    val editingFields = detail?.entry?.editableFields.orEmpty().takeIf { entryEditing }.orEmpty()
    CashRecordModalPanel(sizeClass, localizedText(if (entryEditing) "cash_edit_entry" else "copy_8cb72a44a5"), true,
        semanticStateKey = planId, onDismiss = close, onBack = close,
        footer = if (!entryEditing && detail?.entry?.editRef != null && detail.entry.editableFields.isNotEmpty()) { {
            Button(onClick = { request { entryEditing = true; expanded = null } }, enabled = !busy, modifier = Modifier.testTag("cash.import.edit-entry")) {
                Text(localizedText("cash_edit_entry"))
            }
        } } else null) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp).testTag("cash.import.detail"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CashRecordSummary(cashAmountWithCurrencyLabel(item.display.amount, item.display.currency), item.display.amount?.startsWith("-") == true,
                item.display.counterparty?.takeIf { "counterparty" !in editingFields }, localDateTimeDisplayLabel(item.display.occurredAt),
                buildList {
                    add("copy_c3d92b20c8" to item.display.accounts.joinToString("、").ifEmpty { localizedText("display_not_provided") })
                    if ("category_id" !in editingFields) add("copy_a42e73f0a7" to cashCategoryDisplayPath(categories.firstOrNull { it.id == item.display.categoryId }))
                    item.display.note?.takeIf { it.isNotBlank() && "note" !in editingFields }?.let { add("copy_e0361480e3" to it) }
                })
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            errorCode?.let { Text(ledgerErrorText(it), color = MaterialTheme.colorScheme.error) }
            if (detail == null) {
                if (!busy) TextButton(onClick = onRetry) { Text(localizedText("copy_e2d53a6d3a")) }
            } else if (entryEditing) {
                val entry = detail.entry
                if (entry?.editRef != null) CashImportFieldEditor(entry.fields, entry.editableFields.filter { it in cashPublicEntryFields },
                    accounts, categories, options, sizeClass, busy, "cash.import.entry", onDirty = { dirty = it },
                    onCancel = { request { dirty = false; entryEditing = false } },
                    onSave = { patch -> dirty = false; onEntry(ImportEntryChoice(entry.editRef, patch)) })
            } else {
                val issues = detail.issues.distinctBy { it.code to it.fieldPath }
                if (issues.isNotEmpty()) {
                    CashImportWarningCard(issues.joinToString("；") { it.message }, expanded == "issues", !busy,
                        onClick = { request { expanded = if (expanded == "issues") null else "issues" } })
                    if (expanded == "issues") {
                        detail.members.filter { it.rowRef != null && "apply" in it.allowedActions && it.editableFields.any { field -> field in cashDraftScalarFields && field !in cashPublicEntryFields } }
                            .forEach { member ->
                                key(member.rowRef) {
                                    CashImportMemberSummary(member, accounts)
                                    TextButton(onClick = { request { editingMember = member.rowRef } }, enabled = !busy,
                                        modifier = Modifier.testTag("cash.import.edit-member-${member.rowRef}")) { Text(localizedText("cash_import_process")) }
                                    if (editingMember == member.rowRef) CashImportFieldEditor(member.fields,
                                        member.editableFields.filter { it !in cashPublicEntryFields && it != "source_payload" },
                                        accounts, categories, options, sizeClass, busy, "cash.import.row-${member.rowRef}", onDirty = { dirty = it },
                                        onCancel = { request { dirty = false; editingMember = null } },
                                        onSave = { patch -> dirty = false; editingMember = null; onRow(ImportRecordChoice(member.rowRef!!, ImportRowDecision.APPLY, patch)) })
                                }
                            }
                    }
                }
                detail.fieldDiffs.filter { it.current != it.incoming }.groupBy { ImportMemberRef(it.rowRef, it.transactionId) }.forEach { (reference, differences) ->
                    val member = detail.members.firstOrNull { it.rowRef == reference.rowRef && it.transactionId == reference.transactionId }
                    val key = "source:${reference.rowRef}:${reference.transactionId}"
                    CashImportWarningCard(localizedText("cash_import_source_changed"), expanded == key, !busy,
                        onClick = { request { expanded = if (expanded == key) null else key } })
                    if (expanded == key) {
                        differences.forEach { difference ->
                            cashFieldLabel(difference.field)?.let { Text(localizedText(it), style = MaterialTheme.typography.titleSmall) }
                            if (difference.field in setOf("source_payload", "components")) {
                                Text(localizedText("cash_current_value"), style = MaterialTheme.typography.labelSmall)
                                CashSourcePatchValue(difference.field, difference.current, accounts)
                                Text(localizedText("cash_statement_value"), style = MaterialTheme.typography.labelSmall)
                                CashSourcePatchValue(difference.field, difference.incoming, accounts)
                            } else {
                                Text(localizedText("cash_import_current_value", cashBusinessFieldValue(difference.field, difference.current, accounts, categories, options)))
                                Text(localizedText("cash_import_incoming_value", cashBusinessFieldValue(difference.field, difference.incoming, accounts, categories, options)))
                            }
                        }
                        if (member?.rowRef != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(ImportRowDecision.APPLY to "cash_import_apply_source", ImportRowDecision.KEEP_EXISTING to "cash_import_keep_source", ImportRowDecision.DEFER to "cash_relation_defer").forEach { (action, label) ->
                                    if (action.name.lowercase() in member.allowedActions &&
                                        (action != ImportRowDecision.KEEP_EXISTING || member.rowRevision != null)) {
                                        TextButton(onClick = { onRow(ImportRecordChoice(member.rowRef, action)) }, enabled = !busy) { Text(localizedText(label)) }
                                    }
                                }
                            }
                        }
                    }
                }
                detail.relationChoices.distinctBy { it.suggestionId }.forEach { group ->
                    val key = "relation:${group.suggestionId}"
                    val pending = group.action == "defer" || group.code != null || group.commonFieldConflicts.isNotEmpty()
                    if (pending) CashImportWarningCard(localizedText("cash_import_review_relation"), expanded == key, !busy,
                        onClick = { request { expanded = if (expanded == key) null else key } })
                    else TextButton(onClick = { request { expanded = if (expanded == key) null else key } }, enabled = !busy) { Text(localizedText("cash_import_related_entries")) }
                    if (expanded == key) CashImportRelationEditor(group, detail.relationMembers, accounts, categories, options, busy, onDirty = { dirty = it }, onSave = { dirty = false; onRelation(it) })
                }
                TextButton(onClick = { request { expanded = if (expanded == "sources") null else "sources" } }, enabled = !busy, modifier = Modifier.testTag("cash.import.sources")) {
                    Text(localizedText("cash_statement_content"))
                }
                if (expanded == "sources") detail.members.forEach { member ->
                    CashImportMemberSummary(member, accounts)
                    val lines = cashStatementLines(member.fields.fields["source_payload"])
                    if (lines.isEmpty()) Text(localizedText("cash_statement_empty"))
                    else lines.forEach { (label, value) -> Text(if (label.isEmpty()) value else "$label：$value", style = MaterialTheme.typography.bodyMedium) }
                    HorizontalDivider()
                }
            }
        }
    }
    if (discardAction != null) AlertDialog(onDismissRequest = { discardAction = null }, title = { Text(localizedText("cash_import_discard_edit")) },
        confirmButton = { TextButton(onClick = { val action = discardAction; discardAction = null; dirty = false; action?.invoke() }) { Text(localizedText("cash_import_discard")) } },
        dismissButton = { TextButton(onClick = { discardAction = null }) { Text(localizedText("cash_import_continue_edit")) } })
}

@Composable
private fun CashImportWarningCard(message: String, expanded: Boolean, enabled: Boolean, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(message, Modifier.weight(1f), color = LocalFinanceColorRoles.current.warning)
            Text(localizedText(if (expanded) "cash_import_collapse" else "cash_import_process"), color = LocalFinanceColorRoles.current.warning)
        }
    }
}

@Composable
internal fun CashImportMemberSummary(member: ImportMemberDetail, accounts: List<Account>) {
    val fields = member.fields.fields
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(fields["counterparty"].cashText().orEmpty().ifBlank { "—" }, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(cashAmountWithCurrencyLabel(fields["amount"].cashText(), fields["currency"].cashText()), style = MaterialTheme.typography.titleSmall)
        }
        Text(cashImportMemberAccountLabel(fields, accounts), style = MaterialTheme.typography.bodySmall)
        Text(localDateTimeDisplayLabel(fields["occurred_at"].cashText()), style = MaterialTheme.typography.bodySmall)
        fields["note"].cashText()?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun CashImportFieldEditor(fields: StructuredObject, editable: List<String>, accounts: List<Account>, categories: List<CashCategory>,
    options: LedgerOptions, sizeClass: WindowSizeClass, busy: Boolean, prefix: String, onDirty: (Boolean) -> Unit,
    onCancel: () -> Unit, onSave: (StructuredObject) -> Unit) {
    val initial = remember(fields, editable) { cashRecordInitialValues(fields, editable) }
    var values by remember(initial) { mutableStateOf(initial) }
    val initialParts = remember(fields) { cashRecordInitialAllocations(fields) }
    var allocation by remember(initialParts) { mutableStateOf(CashDraftAllocationInput(initialParts)) }
    val split = values["cash_granularity"] == "aggregate"
    val parts = allocation.patchParts(split, values, initialParts)
    val changes = values.filter { (field, value) -> initial[field] != value }.toMutableMap().apply {
        if (split && initial["account_id"].orEmpty().isNotEmpty()) put("account_id", "")
        else if (split) remove("account_id")
    }
    val dirty = changes.isNotEmpty() || parts != null
    var error by remember(initial) { mutableStateOf<String?>(null) }
    val zone = getLocalTimeZoneId()
    LaunchedEffect(dirty) { onDirty(dirty) }
    Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().testTag(prefix), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CashRecordFieldInputs(values, editable, accounts, categories, options, sizeClass, busy, prefix, bookingContent = if ("components" in editable) ({
            CashDraftAllocationEditor(split, if (split) allocation.parts else parts ?: initialParts,
                accounts, busy, singleNeedsRepair = !allocation.singleChosen && !cashDraftSingleAllocationMatches(initial, initialParts), semanticPrefix = prefix,
                onChange = { allocation = allocation.changeParts(it); error = null }, onUseSingle = { allocation = allocation.chooseSingle(); error = null })
        }) else null) { field, value ->
            if (field == "cash_granularity") allocation = allocation.switchMode()
            values = values + (field to value); error = null
        }
        error?.let { Text(when (it) {
            "invalid_allocation" -> localizedText("cash_import_invalid_allocation")
            "account_changed" -> cashImportTaskErrorText(it)
            else -> ledgerErrorText(it)
        }, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onCancel, enabled = !busy) { Text(localizedText("copy_4d0b4688c7")) }
            Button(onClick = {
                val patch = try { cashRecordFieldPatch(editable, changes, zone, parts).also {
                    validateCashImportFinancialPatch(fields, it, accounts)
                } }
                catch (failure: DomainFailure) { error = failure.code; return@Button }
                onSave(patch)
            }, enabled = !busy && dirty, modifier = Modifier.testTag("$prefix.save")) { Text(localizedText("copy_fadf24dbc5")) }
        }
    }
}

@Composable
private fun cashImportMemberAccountLabel(fields: Map<String, StructuredValue>, accounts: List<Account>): String {
    val ids = fields["account_id"].cashText()?.let(::listOf) ?: (fields["components"] as? StructuredArray)?.values.orEmpty()
        .mapNotNull { (it as? StructuredObject)?.fields?.get("account_id").cashText() }
    return ids.distinct().map { id -> accounts.firstOrNull { it.id.toString() == id }?.name ?: localizedText("display_not_provided") }
        .joinToString("、").ifEmpty { localizedText("display_not_provided") }
}
