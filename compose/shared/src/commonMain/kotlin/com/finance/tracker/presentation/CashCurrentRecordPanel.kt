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

internal fun StructuredValue?.cashText(): String? = when (this) {
    is StructuredText -> value
    is StructuredNumber -> value
    else -> null
}

@Composable
internal fun CashCurrentRecordPanel(
    state: CashRecordTaskState,
    projection: CashProjection?,
    sizeClass: WindowSizeClass,
    evidence: CashSourceEvidence?,
    sourceError: String?,
    onLoadSources: () -> Unit,
    onRetry: () -> Unit,
    onResolve: () -> Unit,
    onRetryOriginal: () -> Unit,
    onClose: () -> Unit,
    onResolveIssue: ((String, String, String?, StructuredObject) -> Unit)? = null,
    onSaveDraft: ((StructuredObject, Boolean) -> Unit)? = null,
    onSaveEntry: ((CashMaintenanceRequest) -> Unit)? = null,
    onCancelEntry: () -> Boolean = { false },
    onBeginRelations: ((StructuredObject) -> Unit)? = null,
    onSaveRelationChoices: (List<CashRelationDecision>) -> Unit = {},
    onConfirmPlan: () -> Unit = {},
    onCancelPlan: () -> Unit = {},
    accounts: List<Account> = emptyList(),
    categories: List<CashCategory> = emptyList(),
    options: LedgerOptions = LedgerOptions(),
    onEdit: (() -> Unit)? = null,
    onProcessIssue: ((CashRecordIssue) -> Unit)? = null,
    onProcessDraft: (() -> Unit)? = null,
    onProcessRelations: (() -> Unit)? = null,
    onResolveMemberIssue: ((String, String, String, String?) -> Unit)? = null,
    onOpenMember: (String) -> Unit = {},
) {
    var sourcesExpanded by remember(state.transactionId, state.record?.rowRevision, state.record?.projectionRef?.projectionVersion, state.savedRevision) { mutableStateOf(false) }
    var expandedIssue by remember(state.transactionId, state.savedRevision) { mutableStateOf<String?>(null) }
    var draftExpanded by remember(state.transactionId, state.savedRevision) { mutableStateOf(false) }
    var draftDirty by remember(state.transactionId, state.record?.rowRevision, state.savedRevision) { mutableStateOf(false) }
    var draftPatch by remember(state.transactionId, state.record?.rowRevision, state.savedRevision) { mutableStateOf<StructuredObject?>(StructuredObject(emptyMap())) }
    var entryEditing by remember(state.transactionId, state.savedRevision) { mutableStateOf(false) }
    var entryDirty by remember(state.transactionId, state.savedRevision) { mutableStateOf(false) }
    var discardAction by remember(state.transactionId) { mutableStateOf<(() -> Unit)?>(null) }
    val record = state.record
    val requestClose = {
        if (!state.unresolved && !(state.busy && record != null)) {
            if (draftDirty || entryDirty) discardAction = onClose else onClose()
        }
    }
    val cancelEntry = { if (onCancelEntry()) { entryEditing = false; entryDirty = false } }
    val requestCancelEntry = {
        if (!state.busy && !state.unresolved) {
            if (entryDirty) discardAction = cancelEntry else cancelEntry()
        }
    }
    CashRecordModalPanel(sizeClass, localizedText(if (entryEditing) "cash_edit_entry" else "copy_8cb72a44a5"), compactFullScreen = true,
        semanticStateKey = "current-record-${state.transactionId}", onDismiss = requestClose, onBack = if (entryEditing) requestCancelEntry else requestClose,
        footer = if (record != null && cashEntryEditableFields(record).isNotEmpty() && onSaveEntry != null && !entryEditing && !state.unresolved) ({
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = { entryEditing = true; onEdit?.invoke() }, enabled = !state.busy && state.plan == null, modifier = Modifier.testTag("cash.current.edit")) { Text(localizedText("copy_a7f814c0a4")) }
            }
        }) else null,
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp).testTag("cash.current.detail"),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.busy && record == null) CircularProgressIndicator(Modifier.testTag("cash.current.loading"))
            if (state.unresolved) {
                Text(localizedText("cash_save_unknown"), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onResolve, enabled = !state.busy, modifier = Modifier.testTag("cash.current.resolve")) { Text(localizedText("cash_check_result")) }
                    if (state.retryOriginalAllowed) TextButton(onClick = onRetryOriginal, enabled = !state.busy, modifier = Modifier.testTag("cash.current.retry-original")) { Text(localizedText("cash_continue_save")) }
                }
            }
            state.errorCode?.let {
                StateMessage(ledgerErrorText(it), isError = true, modifier = Modifier.testTag("cash.current.error")) {
                    if (!state.unresolved) TextButton(onClick = onRetry, enabled = !state.busy) { Text(localizedText("copy_e2d53a6d3a")) }
                }
            }
            if (state.receipt != null && state.currentRecordStatus != "available" && state.errorCode == null) {
                Text(localizedText("cash_saved_read_unavailable"), Modifier.testTag("cash.current.saved-unavailable"))
                TextButton(onClick = onRetry, enabled = !state.busy) { Text(localizedText("copy_e2d53a6d3a")) }
            }
            if (record != null) {
                val common = record.entry?.fields?.fields ?: record.fields.fields
                val editingFields = if (entryEditing) cashEntryEditableFields(record).toSet() +
                    (if (cashTransferEntryMembers(record, state.transferMembers) != null) cashTransferFinancialFields else emptyList()) else emptySet()
                if ("amount" !in editingFields) Text(projection?.let(::cashProjectionAmountLabel) ?: listOfNotNull(record.fields.fields["amount"].cashText() ?: "—", record.fields.fields["currency"].cashText()).joinToString(" "),
                    style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("cash.current.amount"))
                if ("counterparty" !in editingFields) common["counterparty"].cashText()?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                if ("note" !in editingFields) common["note"].cashText()?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                val occurredAt = record.fields.fields["occurred_at"].cashText()
                if ("occurred_at" !in editingFields) Text(localDateTimeDisplayLabel(occurredAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                projection?.let {
                    val summary = buildList {
                        if ("account_id" !in editingFields) add(cashProjectionAccountLabel(it))
                        if ("category_id" !in editingFields) add(cashCategoryDisplayPath(it.category))
                    }
                    if (summary.isNotEmpty()) Text(summary.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                }
                if (entryEditing && onSaveEntry != null) {
                    CashEntryEditor(record, state.savedRevision, accounts, categories, options, sizeClass,
                        state.busy || state.unresolved || state.plan != null, onInput = { entryDirty = it }, onCancel = requestCancelEntry, onSave = onSaveEntry,
                        transferMembers = state.transferMembers)
                    if (state.plan != null && !state.unresolved) CashMaintenanceConfirmation(state, accounts, categories, options, onConfirmPlan, onCancelPlan)
                } else {
                    if (record.state == "draft") {
                        val problems = cashDraftEditingIssues(record)
                        CashPendingCard("draft", problems.joinToString("\n") { it.message }.ifEmpty { localizedText("cash_draft_problem") }, localizedText("cash_fill_information"),
                            if (onSaveDraft != null && record.editableFields.isNotEmpty()) ({ draftExpanded = true }) else onProcessDraft, state.busy || state.unresolved)
                    }
                    record.issues.filterNot { record.state == "draft" && it.nextAction == "edit" }.forEach { issue ->
                        val process = if (issue.allowedResolutions.isNotEmpty() && onResolveIssue != null) ({ expandedIssue = if (expandedIssue == issue.issueId) null else issue.issueId })
                            else onProcessIssue?.let { { it(issue) } }
                        CashPendingCard(issue.issueId, issue.message, localizedText("cash_process_issue"), process, state.busy || state.unresolved)
                        if (expandedIssue == issue.issueId && onResolveIssue != null) CashIssueTreatment(record, issue, state.busy || state.unresolved || state.plan != null || draftPatch == null, accounts, categories, options) { resolution, target ->
                            draftPatch?.let { onResolveIssue(issue.issueId, resolution, target, it) }
                        }
                    }
                    state.pendingMembers.forEach { owner ->
                        owner.issues.forEach { issue ->
                            val process = if (issue.allowedResolutions.isNotEmpty() && onResolveMemberIssue != null) ({ expandedIssue = if (expandedIssue == issue.issueId) null else issue.issueId }) else null
                            CashPendingCard("member-${issue.issueId}", listOfNotNull(owner.fields.fields["counterparty"].cashText()?.takeIf { it.isNotBlank() }, issue.message).joinToString(" · "),
                                localizedText("cash_process_issue"), process, state.busy || state.unresolved)
                            if (expandedIssue == issue.issueId && onResolveMemberIssue != null) CashIssueTreatment(owner, issue,
                                state.busy || state.unresolved || state.plan != null, accounts, categories, options) { resolution, target ->
                                onResolveMemberIssue(owner.transactionId, issue.issueId, resolution, target)
                            }
                        }
                    }
                    if (record.relations.none { it.active && it.state == "draft" }) {
                        state.pendingMembers.firstOrNull { owner -> owner.relations.any { it.active && it.state == "draft" } }?.let { owner ->
                            CashPendingCard("member-relations", localizedText("cash_relation_problem"), localizedText("cash_review_relations"),
                                if (onBeginRelations != null) ({ onOpenMember(owner.transactionId) }) else null, state.busy || state.unresolved || draftDirty)
                        }
                    }
                    if (draftExpanded && onSaveDraft != null && record.state == "draft") CashDraftEditor(record, state.savedRevision, accounts, categories, options, sizeClass,
                        state.busy || state.unresolved || state.plan != null,
                        onInput = { dirty, patch -> draftDirty = dirty; draftPatch = patch }, onSave = onSaveDraft)
                    if (record.relations.any { it.active && it.state == "draft" }) {
                        CashPendingCard("relations", localizedText("cash_relation_problem"), localizedText("cash_review_relations"),
                            onBeginRelations?.let { begin -> { draftPatch?.let(begin) } } ?: onProcessRelations,
                            state.busy || state.unresolved || state.plan != null || draftPatch == null)
                    } else if (record.relations.isNotEmpty() && onBeginRelations != null && record.recordOrigin !in setOf("manual", "manual_transfer")) {
                        TextButton(onClick = { draftPatch?.let(onBeginRelations) }, enabled = !state.busy && !state.unresolved && state.plan == null && draftPatch != null,
                            modifier = Modifier.testTag("cash.current.manage-relations")) { Text(localizedText("cash_manage_relations")) }
                    }
                    if (state.selectionPlan) CashRelationTreatment(state, accounts, categories, options, onCancelPlan, onSaveRelationChoices)
                    else if (state.plan != null && !state.unresolved) CashMaintenanceConfirmation(state, accounts, categories, options, onConfirmPlan, onCancelPlan)
                    HorizontalDivider()
                    TextButton(onClick = {
                        sourcesExpanded = !sourcesExpanded
                        if (sourcesExpanded && evidence == null) onLoadSources()
                    }, modifier = Modifier.testTag("cash.current.sources-toggle")) { Text(localizedText(if (sourcesExpanded) "cash_hide_sources" else "cash_show_sources")) }
                    if (sourcesExpanded) {
                        if (sourceError != null) StateMessage(if (sourceError == "record_changed") ledgerErrorText(sourceError) else localizedText("cash_sources_unavailable"), isError = true) {
                            TextButton(onClick = onLoadSources, modifier = Modifier.testTag("cash.current.sources-retry")) { Text(localizedText("copy_e2d53a6d3a")) }
                        }
                        val members = evidence?.members.orEmpty()
                        if (evidence == null && sourceError == null) CircularProgressIndicator()
                        else if (members.isEmpty()) CashReadonlyFields(record.fields)
                        else members.forEach { member ->
                            key(member.transactionId) {
                                Text(member.counterparty.ifBlank { localizedText("display_not_provided") }, style = MaterialTheme.typography.titleSmall)
                                Text(listOfNotNull(member.amount ?: "—", member.currency).joinToString(" "), style = MaterialTheme.typography.bodyMedium)
                                Text(localDateTimeDisplayLabel(member.occurredAt), style = MaterialTheme.typography.bodySmall)
                                member.accountName?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                if (member.components.size > 1) member.components.forEach { component ->
                                    Text(listOfNotNull(component.accountName, component.amount ?: "—", component.currency).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                                }
                                member.note.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                                member.counterpartyAccount?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                Text(localizedText("cash_statement_content"), style = MaterialTheme.typography.titleSmall)
                                Column(Modifier.fillMaxWidth().testTag("cash.current.source-body-${member.transactionId}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val lines = cashStatementLines(member.sourcePayload)
                                    if (lines.isEmpty()) Text(localizedText("cash_statement_empty"))
                                    else lines.forEach { (label, value) -> Text(if (label.isEmpty()) value else "$label：$value", style = MaterialTheme.typography.bodyMedium) }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
    if (discardAction != null) AlertDialog(onDismissRequest = { discardAction = null },
        title = { Text(localizedText("ledger_record_discard_title")) },
        confirmButton = { TextButton(onClick = { val action = discardAction; discardAction = null; action?.invoke() }) { Text(localizedText("ledger_record_discard_confirm")) } },
        dismissButton = { TextButton(onClick = { discardAction = null }) { Text(localizedText("ledger_record_discard_continue")) } })
}

@Composable
private fun CashPendingCard(id: String, message: String, action: String, onAction: (() -> Unit)?, busy: Boolean) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().testTag("cash.current.pending-$id")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            if (onAction != null) TextButton(onClick = onAction, enabled = !busy, modifier = Modifier.testTag("cash.current.process-$id")) { Text(action) }
        }
    }
}

@Composable
internal fun CashReadonlyFields(fields: StructuredObject) {
    val labels = listOf("occurred_at" to "copy_51f85a78ca", "amount" to "copy_34943c40c9", "currency" to "copy_a81ab5e100",
        "counterparty" to "copy_4b5a03c119", "counterparty_account" to "copy_27a7edc87c", "note" to "copy_e0361480e3")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEach { (field, label) ->
            val value = fields.fields[field].cashText()
            if (!value.isNullOrBlank()) {
                Text(localizedText(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (field == "occurred_at") localDateTimeDisplayLabel(value) else value, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
