package com.finance.tracker.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.finance.tracker.domain.*

@Composable
internal fun CashRelationTreatment(state: CashRecordTaskState, accounts: List<Account>, categories: List<CashCategory>, options: LedgerOptions,
    onCancel: () -> Unit, onSave: (List<CashRelationDecision>) -> Unit) {
    val record = state.record ?: return
    val groups = state.details.flatMap { it.relationChoices }.distinctBy { it.suggestionId }
    var choices by remember(state.plan?.planId) { mutableStateOf(state.input?.relationChoices.orEmpty().associateBy { it.suggestionId }) }
    val busy = state.busy || state.unresolved
    val inheritanceRequired = groups.filter { group -> group.members.any { state.affectedRecords[it.transactionId]?.entry?.manualFields?.isNotEmpty() == true } }.map { it.suggestionId }.toSet()
    val valid = choices.isNotEmpty() && choices.values.none { it.action == "remove" && it.suggestionId in inheritanceRequired && it.inheritCommonFields == null } &&
        cashRelationChoicesLoaded(choices.values.toList(), groups, state.affectedRecords) &&
        runCatching { cashRelationRequest(record, choices.values.toList(), groups, state.input?.patch ?: StructuredObject(emptyMap())) }.isSuccess
    Column(Modifier.fillMaxWidth().testTag("cash.relations.treatment"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (groups.isEmpty()) Text(localizedText("cash_no_relation_choices"))
        groups.forEach { group ->
            Text(group.kind?.let { cashRelationTypeLabel(it, options) } ?: localizedText("cash_relation_other"), style = MaterialTheme.typography.titleMedium)
            if (group.candidates.isEmpty()) cashRelationMemberRecords(group.members, state.affectedRecords)?.forEach { CashRelationMemberSummary(it, accounts) }
            val selected = choices[group.suggestionId]
            group.allowedActions.forEach { action ->
                val key = when (action) { "accept" -> "cash_relation_accept"; "defer" -> "cash_relation_defer"; "ignore" -> "cash_relation_ignore"; "remove" -> "cash_relation_remove"; else -> null }
                if (key != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected?.action == action, onClick = {
                        choices = choices + (group.suggestionId to CashRelationDecision(group.suggestionId, action,
                            candidateId = if (action == "accept") group.candidateId ?: group.candidates.singleOrNull()?.candidateId else null))
                    }, enabled = !busy, modifier = Modifier.testTag("cash.relation.action-${group.suggestionId}-$action"))
                    Text(localizedText(key))
                }
            }
            if (selected?.action == "accept") {
                group.candidates.forEachIndexed { index, candidate ->
                    if (index > 0) HorizontalDivider()
                    val members = cashRelationMemberRecords(candidate.members, state.affectedRecords)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp).testTag("cash.relation.candidate-${group.suggestionId}-${candidate.candidateId}")) {
                        if (group.candidates.size > 1) RadioButton(selected.candidateId == candidate.candidateId,
                            onClick = { choices = choices + (group.suggestionId to selected.copy(candidateId = candidate.candidateId)) }, enabled = !busy && members != null)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (members == null) Text(ledgerErrorText("record_changed"), color = MaterialTheme.colorScheme.error)
                            else members.forEach { member ->
                                Column(Modifier.fillMaxWidth().testTag("cash.relation.member-${candidate.candidateId}-${member.transactionId}")) {
                                    CashRelationMemberSummary(member, accounts)
                                }
                            }
                        }
                    }
                }
                group.commonFieldConflicts.forEach { (field, values) ->
                    cashFieldLabel(field)?.let { Text(localizedText(it), style = MaterialTheme.typography.labelLarge) }
                    values.distinct().forEachIndexed { index, value ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected.commonFields?.fields?.get(field) == value, onClick = {
                                choices = choices + (group.suggestionId to selected.copy(commonFields = StructuredObject(selected.commonFields?.fields.orEmpty() + (field to value))))
                            }, enabled = !busy, modifier = Modifier.testTag("cash.relation.value-${group.suggestionId}-$field-$index"))
                            Text(if (value == StructuredNull || value == StructuredText("") || value == StructuredArray(emptyList())) localizedText("cash_clear_value")
                                else cashBusinessFieldValue(field, value, accounts, categories, options))
                        }
                    }
                }
            }
            if (selected?.action == "remove" && group.suggestionId in inheritanceRequired) {
                Text(localizedText("cash_inherit_question"), style = MaterialTheme.typography.titleSmall)
                listOf(true to "cash_inherit_changes", false to "cash_keep_individual").forEach { (inherit, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected.inheritCommonFields == inherit, onClick = { choices = choices + (group.suggestionId to selected.copy(inheritCommonFields = inherit)) }, enabled = !busy,
                            modifier = Modifier.testTag("cash.relation.inherit-${group.suggestionId}-$inherit"))
                        Text(localizedText(label))
                    }
                }
            }
            HorizontalDivider()
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onCancel, enabled = !busy) { Text(localizedText("copy_4d0b4688c7")) }
            Button(onClick = { onSave(choices.values.toList()) }, enabled = !busy && valid, modifier = Modifier.testTag("cash.relations.save")) { Text(localizedText("copy_fadf24dbc5")) }
        }
    }
}

@Composable
private fun CashRelationMemberSummary(record: CashCurrentRecord, accounts: List<Account>) {
    val fields = record.fields.fields
    val accountName = fields["account_name"].cashText()?.takeIf { it.isNotBlank() }
        ?: accounts.firstOrNull { it.id.toString() == fields["account_id"].cashText() }?.name
        ?: localizedText("copy_d041bb6737")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(fields["counterparty"].cashText().orEmpty().ifBlank { "—" }, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(listOfNotNull(fields["amount"].cashText() ?: "—", fields["currency"].cashText()).joinToString(" "), style = MaterialTheme.typography.titleSmall)
        }
        Text(accountName, style = MaterialTheme.typography.bodySmall)
        Text(localDateTimeDisplayLabel(fields["occurred_at"].cashText()), style = MaterialTheme.typography.bodySmall)
        fields["note"].cashText()?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}
