package com.finance.tracker.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.finance.tracker.domain.*

internal fun importRelationMembers(refs: List<ImportMemberRef>, members: List<ImportMemberDetail>): List<ImportMemberDetail>? {
    if (refs.isEmpty() || refs.any { it.rowRef == null && it.transactionId == null }) return null
    val byReference = members.associateBy { ImportMemberRef(it.rowRef, it.transactionId) }
    return refs.map { byReference[it] ?: return null }
}

@Composable
internal fun CashImportRelationEditor(group: ImportPairOption, members: List<ImportMemberDetail>, accounts: List<Account>,
    categories: List<CashCategory>, options: LedgerOptions, busy: Boolean, onDirty: (Boolean) -> Unit, onSave: (ImportPairChoice) -> Unit) {
    val initial = ImportPairChoice(group.suggestionId,
        ImportPairDecision.entries.firstOrNull { it.name.lowercase() == group.action } ?: ImportPairDecision.DEFER,
        group.candidateId, group.commonFields, group.inheritCommonFields)
    var selected by remember(group) { mutableStateOf(initial) }
    LaunchedEffect(selected != initial) { onDirty(selected != initial) }
    val accepting = selected.action == ImportPairDecision.ACCEPT
    val selectedCandidate = group.candidates.firstOrNull { it.candidateId == selected.candidateId }
    val selectedMembers = importRelationMembers(if (group.candidates.isEmpty()) group.members else selectedCandidate?.members.orEmpty(), members)
    val conflictsResolved = group.commonFieldConflicts.all { (field, values) ->
        selected.commonFields?.fields?.get(field)?.let { it in values } == true
    }
    val valid = selected.action.name.lowercase() in group.allowedActions &&
        (!accepting || selectedMembers != null && conflictsResolved)
    Column(Modifier.fillMaxWidth().testTag("cash.import.relation-${group.suggestionId}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        group.kind?.let { Text(cashRelationTypeLabel(it, options), style = MaterialTheme.typography.titleMedium) }
        if (group.candidates.isEmpty()) {
            val actual = importRelationMembers(group.members, members)
            if (actual == null) Text(localizedText("cash_import_member_missing"), color = MaterialTheme.colorScheme.error)
            else actual.forEach { CashImportMemberSummary(it, accounts) }
        }
        listOf(ImportPairDecision.ACCEPT to "cash_relation_accept", ImportPairDecision.DEFER to "cash_relation_defer", ImportPairDecision.IGNORE to "cash_relation_ignore").forEach { (action, label) ->
            if (action.name.lowercase() in group.allowedActions) Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected.action == action, onClick = {
                    selected = ImportPairChoice(group.suggestionId, action,
                        if (action == ImportPairDecision.ACCEPT) group.candidateId ?: group.candidates.singleOrNull()?.candidateId else null,
                        if (action == ImportPairDecision.ACCEPT) group.commonFields else null)
                }, enabled = !busy, modifier = Modifier.testTag("cash.import.relation-action-${group.suggestionId}-${action.name.lowercase()}"))
                Text(localizedText(label))
            }
        }
        if (accepting) {
            group.candidates.forEachIndexed { index, candidate ->
                if (index > 0) HorizontalDivider()
                val actual = importRelationMembers(candidate.members, members)
                Row(Modifier.fillMaxWidth().testTag("cash.import.candidate-${candidate.candidateId}"), verticalAlignment = Alignment.Top) {
                    if (group.candidates.size > 1) RadioButton(selected.candidateId == candidate.candidateId, onClick = {
                        selected = selected.copy(candidateId = candidate.candidateId)
                    }, enabled = !busy && actual != null)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (actual == null) Text(localizedText("cash_import_member_missing"), color = MaterialTheme.colorScheme.error)
                        else actual.forEach { CashImportMemberSummary(it, accounts) }
                    }
                }
            }
            group.commonFieldConflicts.forEach { (field, values) ->
                cashFieldLabel(field)?.let { Text(localizedText(it), style = MaterialTheme.typography.titleSmall) }
                values.distinct().forEachIndexed { index, value ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected.commonFields?.fields?.get(field) == value, onClick = {
                            selected = selected.copy(commonFields = StructuredObject(selected.commonFields?.fields.orEmpty() + (field to value)))
                        }, enabled = !busy, modifier = Modifier.testTag("cash.import.common-value-${group.suggestionId}-$field-$index"))
                        Text(if (value == StructuredNull || value == StructuredText("") || value == StructuredArray(emptyList())) localizedText("cash_clear_value")
                            else cashBusinessFieldValue(field, value, accounts, categories, options))
                    }
                }
            }
        }
        Button(onClick = { onSave(selected) }, enabled = !busy && valid, modifier = Modifier.testTag("cash.import.relation-save-${group.suggestionId}")) {
            Text(localizedText("copy_fadf24dbc5"))
        }
    }
}
