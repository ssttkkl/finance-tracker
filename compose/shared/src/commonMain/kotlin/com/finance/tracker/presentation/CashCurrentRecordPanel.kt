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
import com.finance.tracker.resources.Res
import com.finance.tracker.resources.ic_edit
import org.jetbrains.compose.resources.painterResource

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
    onSearchAssociations: suspend (CashAssociationSearch) -> CashAssociationPage,
    onAddAssociation: (CashRelationAddition, String?) -> Unit,
    onRelationCommonFields: (StructuredObject) -> Unit,
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
        footer = if (record != null && cashEntryEditableFields(record).isNotEmpty() && onSaveEntry != null && !entryEditing && !state.unresolved && state.plan == null) ({
            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (sizeClass == WindowSizeClass.COMPACT) Arrangement.End else Arrangement.Start) {
                Button(onClick = { entryEditing = true; onEdit?.invoke() }, enabled = !state.busy && state.plan == null, modifier = Modifier.testTag("cash.current.edit")) {
                    Icon(painterResource(Res.drawable.ic_edit), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(localizedText("copy_a7f814c0a4"))
                }
            }
        }) else null,
    ) {
        Column(Modifier.fillMaxSize().then(if (entryEditing) Modifier else Modifier.verticalScroll(rememberScrollState())).padding(top = if (sizeClass == WindowSizeClass.COMPACT) 0.dp else 24.dp, bottom = if (entryEditing) 0.dp else 16.dp).testTag("cash.current.detail"),
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
                val editingFields = if (entryEditing) cashEntryEditableFields(record).toSet() +
                    (if (cashTransferEntryMembers(record, state.transferMembers) != null) cashTransferFinancialFields else emptyList()) else emptySet()
                if (!entryEditing) CashCurrentRecordSummary(record, projection, editingFields, accounts, categories, options)
                if (entryEditing && onSaveEntry != null) {
                    CashEntryEditor(record, state.savedRevision, accounts, categories, options, sizeClass,
                        state.busy || state.unresolved || state.plan != null, onInput = { entryDirty = it }, onCancel = requestCancelEntry, onSave = onSaveEntry,
                        transferMembers = state.transferMembers, modifier = Modifier.weight(1f),
                        summary = { CashCurrentRecordSummary(record, projection, editingFields, accounts, categories, options) },
                        confirmation = { if (state.plan != null && !state.unresolved) CashMaintenanceConfirmation(state, accounts, categories, options, onConfirmPlan, onCancelPlan, showActions = false) },
                        actions = if (state.plan != null && !state.unresolved) ({ CashMaintenanceConfirmationActions(state, onConfirmPlan, onCancelPlan, sideSheet = sizeClass != WindowSizeClass.COMPACT) }) else null)
                } else {
                    if (record.state == "draft") {
                        val problems = cashDraftEditingIssues(record)
                        CashRecordStatusBanner(
                            title = localizedText("cash_import_draft"),
                            message = cashDraftIncompleteMessage(
                                problems.filter { it.code in setOf("incomplete_record", "posting_incomplete") }.map { it.fieldPath },
                                problems.map { it.message },
                            ),
                            modifier = Modifier.testTag("cash.current.pending-draft"),
                            actionLabel = localizedText("cash_fill_information"),
                            onAction = if (onSaveDraft != null && record.editableFields.isNotEmpty()) ({ draftExpanded = true }) else onProcessDraft,
                            enabled = !state.busy && !state.unresolved,
                        )
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
                    CashDetailRelations(state, evidence, sourceError, sizeClass, accounts, categories, options,
                        onLoadSources, onBeginRelations?.let { begin -> { draftPatch?.let(begin) } },
                        onSaveRelationChoices, onAddAssociation, onSearchAssociations, onConfirmPlan, onCancelPlan,
                        onRelationCommonFields, onResolve, onRetryOriginal, onRetry)

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
