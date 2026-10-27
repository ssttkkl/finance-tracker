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
    var associationsExpanded by remember(state.transactionId, state.record?.projectionRef?.projectionVersion, state.savedRevision) { mutableStateOf(true) }
    var relationActionsExpanded by remember(state.transactionId, state.savedRevision) { mutableStateOf(false) }
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
                Button(onClick = { entryEditing = true; onEdit?.invoke() }, enabled = !state.busy && state.plan == null, modifier = Modifier.testTag("cash.current.edit")) { Text(localizedText("copy_a7f814c0a4")) }
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
                    if (record.relations.any { it.active && it.state == "draft" }) {
                        CashPendingCard("relations", localizedText("cash_relation_problem"), localizedText("cash_review_relations"),
                            onBeginRelations?.let { begin -> {
                                relationActionsExpanded = true
                                if (!state.selectionPlan) draftPatch?.let(begin)
                            } } ?: onProcessRelations,
                            state.busy || state.unresolved || state.plan != null && !state.selectionPlan || draftPatch == null)
                    } else if (record.relations.isNotEmpty() && onBeginRelations != null && record.recordOrigin !in setOf("manual", "manual_transfer")) {
                        TextButton(onClick = { relationActionsExpanded = true; draftPatch?.let(onBeginRelations) }, enabled = !state.busy && !state.unresolved && state.plan == null && draftPatch != null,
                            modifier = Modifier.testTag("cash.current.manage-relations")) { Text(localizedText("cash_manage_relations")) }
                    }
                    val relationChoices = state.details.flatMap { it.relationChoices }.distinctBy { it.suggestionId }
                    val sourceMembers = evidence?.members.orEmpty()
                    val hasActualRelations = sourceMembers.size > 1 || (projection?.memberCount ?: 1) > 1
                    val hasAssociation = hasActualRelations || record.relations.any { it.active && it.state != "ignored" } || relationChoices.isNotEmpty()
                    if (hasAssociation) {
                        CashAssociationDisclosure(associationsExpanded, onToggle = { associationsExpanded = !associationsExpanded }) {
                            if (hasActualRelations) {
                                if (evidence == null && sourceError != null) StateMessage(
                                    if (sourceError == "record_changed") ledgerErrorText(sourceError) else localizedText("cash_sources_unavailable"),
                                    isError = true,
                                ) {
                                    TextButton(onClick = onLoadSources, modifier = Modifier.testTag("cash.current.associations-retry")) {
                                        Text(localizedText("copy_e2d53a6d3a"))
                                    }
                                }
                                else if (evidence == null) CircularProgressIndicator(Modifier.testTag("cash.current.associations-loading"))
                                else {
                                    evidence.projection.acceptedRelationSummary.forEach { relation ->
                                        Text(cashRelationTypeLabel(relation.kind, options), style = MaterialTheme.typography.titleSmall)
                                    }
                                    sourceMembers.firstOrNull { it.transactionId == state.transactionId }?.let { member ->
                                        Text(localizedText("ledger_detail_current_record"), style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        CashCurrentAssociatedMember(member)
                                    }
                                    val others = sourceMembers.filterNot { it.transactionId == state.transactionId }
                                    if (others.isNotEmpty()) {
                                        Text(localizedText("ledger_detail_other_records"), style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        others.forEach { CashCurrentAssociatedMember(it) }
                                    }
                                }
                            }
                            relationChoices.filter { it.candidates.isNotEmpty() || it.action != "accept" }.forEach { group ->
                                Text(group.kind?.let { cashRelationTypeLabel(it, options) } ?: localizedText("cash_import_related_entries"),
                                    style = MaterialTheme.typography.titleSmall)
                                group.code?.let { code -> cashRelationErrorText(code)?.let { Text(it, color = MaterialTheme.colorScheme.error) } }
                                if (group.candidates.isEmpty()) {
                                    cashRelationMemberRecords(group.members, state.affectedRecords)?.forEach { CashCurrentRelationMemberSummary(it, accounts) }
                                } else {
                                    group.candidates.forEach { candidate ->
                                        Column(Modifier.fillMaxWidth().testTag("cash.current.candidate-${candidate.candidateId}"),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(localizedText("cash_relation_candidate_label"), style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.primary)
                                            candidate.code?.let { code -> cashRelationErrorText(code)?.let { Text(it, color = MaterialTheme.colorScheme.error) } }
                                            val members = cashRelationMemberRecords(candidate.members, state.affectedRecords)
                                            if (members == null) Text(ledgerErrorText("record_changed"), color = MaterialTheme.colorScheme.error)
                                            else members.forEach { CashCurrentRelationMemberSummary(it, accounts) }
                                        }
                                    }
                                }
                            }
                            if (record.relations.any { it.active && it.state == "draft" } && state.busy && relationChoices.isEmpty()) {
                                CircularProgressIndicator(Modifier.testTag("cash.current.relation-candidates-loading"))
                            }
                            if (state.selectionPlan && relationActionsExpanded) {
                                CashRelationTreatment(state, accounts, categories, options, onCancelPlan, onSaveRelationChoices)
                            } else if (state.plan != null && !state.unresolved && !state.selectionPlan) {
                                CashMaintenanceConfirmation(state, accounts, categories, options, onConfirmPlan, onCancelPlan)
                            }
                        }
                    } else if (state.selectionPlan && relationActionsExpanded) {
                        CashRelationTreatment(state, accounts, categories, options, onCancelPlan, onSaveRelationChoices)
                    } else if (state.plan != null && !state.unresolved && !state.selectionPlan) {
                        CashMaintenanceConfirmation(state, accounts, categories, options, onConfirmPlan, onCancelPlan)
                    }
                    HorizontalDivider()
                    CashStatementDisclosure(
                        expanded = sourcesExpanded,
                        onToggle = {
                        sourcesExpanded = !sourcesExpanded
                        if (sourcesExpanded && evidence == null) onLoadSources()
                        },
                        testTag = "cash.current.sources-toggle",
                    ) {
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
private fun CashAssociationDisclosure(expanded: Boolean, onToggle: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("cash.current.associations")) {
        TextButton(onClick = onToggle, modifier = Modifier.fillMaxWidth().testTag("cash.current.associations-toggle")) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(localizedText("copy_f46e36d281"), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Text(localizedText(if (expanded) "cash_import_collapse" else "cash_import_process"))
            }
        }
        if (expanded) Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

@Composable
private fun CashCurrentAssociatedMember(member: CashSourceMember) {
    Column(Modifier.fillMaxWidth().testTag("cash.current.associated-${member.transactionId}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(member.counterparty.ifBlank { localizedText("display_not_provided") }, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(listOfNotNull(member.amount ?: "—", member.currency).joinToString(" "), style = MaterialTheme.typography.titleSmall)
        }
        member.accountName?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(localDateTimeDisplayLabel(member.occurredAt), style = MaterialTheme.typography.bodySmall)
        member.note.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun CashCurrentRelationMemberSummary(record: CashCurrentRecord, accounts: List<Account>) {
    val fields = record.fields.fields
    val accountName = fields["account_name"].cashText()?.takeIf { it.isNotBlank() }
        ?: accounts.firstOrNull { it.id.toString() == fields["account_id"].cashText() }?.name
        ?: localizedText("copy_d041bb6737")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(fields["counterparty"].cashText().orEmpty().ifBlank { "—" }, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(cashAmountWithCurrencyLabel(fields["amount"].cashText(), fields["currency"].cashText()), style = MaterialTheme.typography.titleSmall)
        }
        Text(accountName, style = MaterialTheme.typography.bodySmall)
        Text(localDateTimeDisplayLabel(fields["occurred_at"].cashText()), style = MaterialTheme.typography.bodySmall)
        fields["note"].cashText()?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
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
