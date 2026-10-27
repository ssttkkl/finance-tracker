package com.finance.tracker.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.finance.tracker.domain.*

@Composable
internal fun CashIssueTreatment(record: CashCurrentRecord, issue: CashRecordIssue, busy: Boolean,
    accounts: List<Account>, categories: List<CashCategory>, options: LedgerOptions,
    onResolve: (String, String?) -> Unit) {
    var target by remember(issue.issueId, issue.revision) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().testTag("cash.issue.treatment-${issue.issueId}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val pending = issue.pendingFields.fields.toMutableMap()
        if (issue.pendingSourcePayloadPresent == true || issue.pendingSourcePayload != null) pending["source_payload"] = issue.pendingSourcePayload ?: StructuredNull
        pending.forEach { (field, incoming) ->
            cashFieldLabel(field)?.let { label ->
                Text(localizedText(label), style = MaterialTheme.typography.labelMedium)
                if (field == "source_payload" || field == "components") {
                    Text(localizedText("cash_current_value"), style = MaterialTheme.typography.labelSmall)
                    CashSourcePatchValue(field, record.fields.fields[field], accounts)
                    Text(localizedText("cash_statement_value"), style = MaterialTheme.typography.labelSmall)
                    CashSourcePatchValue(field, incoming, accounts)
                } else {
                    Text(localizedText("cash_current_value"), style = MaterialTheme.typography.labelSmall)
                    Text(cashBusinessFieldValue(field, record.fields.fields[field], accounts, categories, options), style = MaterialTheme.typography.bodyMedium)
                    Text(localizedText("cash_import_incoming_value", cashBusinessFieldValue(field, incoming, accounts, categories, options)), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if ("merge_into" in issue.allowedResolutions) {
            Text(localizedText("cash_choose_existing_record"), style = MaterialTheme.typography.titleSmall)
            issue.candidates.forEach { candidate ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    RadioButton(selected = target == candidate.transactionId, onClick = { target = candidate.transactionId }, enabled = !busy,
                        modifier = Modifier.testTag("cash.issue.target-${candidate.transactionId}"))
                    Column(Modifier.weight(1f)) {
                        Text(listOfNotNull(candidate.display.amount ?: "—", candidate.display.currency).joinToString(" "))
                        candidate.display.counterparty?.takeIf { it.isNotBlank() }?.let { Text(it) }
                        Text(localDateTimeDisplayLabel(candidate.display.occurredAt), style = MaterialTheme.typography.bodySmall)
                        if (candidate.display.accounts.isNotEmpty()) Text(candidate.display.accounts.joinToString(" / "), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        for (resolution in issue.allowedResolutions) {
            val label = when (resolution) {
                "apply_source" -> "cash_apply_source"
                "keep_existing" -> "cash_keep_existing"
                "create_independent" -> "cash_create_independent"
                "merge_into" -> "cash_merge_existing"
                else -> null
            }
            if (label != null) OutlinedButton(onClick = { onResolve(resolution, target.takeIf { resolution == "merge_into" }) },
                enabled = !busy && (resolution != "merge_into" || target != null),
                modifier = Modifier.testTag("cash.issue.$resolution")) { Text(localizedText(label)) }
        }
    }
}

internal fun cashFieldLabel(field: String): String? = when (field) {
    "amount" -> "copy_34943c40c9"
    "currency" -> "copy_a81ab5e100"
    "occurred_at" -> "copy_51f85a78ca"
    "counterparty" -> "copy_4b5a03c119"
    "counterparty_account" -> "copy_27a7edc87c"
    "note" -> "copy_e0361480e3"
    "category_id" -> "copy_a42e73f0a7"
    "account_id", "account_name" -> "copy_c3d92b20c8"
    "record_type" -> "copy_e81089915c"
    "record_subtype" -> "copy_1fcff8b8d5"
    "counterparty_account_attrs" -> "cash_account_attributes"
    "source_payload" -> "cash_statement_content"
    "components" -> "cash_account_allocations"
    "cash_granularity" -> "cash_account_allocations"
    else -> null
}

@Composable
internal fun CashSourcePatchValue(field: String, value: StructuredValue?, accounts: List<Account>) {
    if (field == "components") {
        val parts = (value as? StructuredArray)?.values.orEmpty()
        if (parts.isEmpty()) Text(localizedText("display_not_provided"))
        parts.forEach { part ->
            val fields = (part as? StructuredObject)?.fields.orEmpty()
            val name = accounts.firstOrNull { it.id.toString() == fields["account_id"].cashText() }?.name ?: localizedText("display_not_provided")
            Text(listOfNotNull(name, fields["amount"].cashText() ?: "—", fields["currency"].cashText()).joinToString(" · "))
        }
    } else {
        val lines = cashStatementLines(value)
        if (lines.isEmpty()) Text(localizedText("cash_statement_empty"))
        else lines.forEach { (label, text) -> Text(if (label.isEmpty()) text else "$label：$text", style = MaterialTheme.typography.bodyMedium) }
    }
}

internal fun cashStatementLines(value: StructuredValue?, prefix: String = ""): List<Pair<String, String>> = when (value) {
    is StructuredObject -> value.fields.filterKeys { !it.startsWith("_") }.flatMap { (key, child) -> cashStatementLines(child, listOf(prefix, key).filter { it.isNotEmpty() }.joinToString(" / ")) }
    is StructuredArray -> value.values.flatMap { cashStatementLines(it, prefix) }
    is StructuredText -> listOf(prefix to value.value)
    is StructuredNumber -> listOf(prefix to value.value)
    else -> emptyList()
}

internal fun cashReadableValue(value: StructuredValue?): String = when (value) {
    is StructuredText -> value.value.ifEmpty { "—" }
    is StructuredNumber -> value.value
    is StructuredArray -> value.values.joinToString("、") { cashReadableValue(it) }.ifEmpty { "—" }
    else -> "—"
}

internal fun cashAccountAttributeLabelKey(value: String): String? = when (value) {
    "full" -> "cash_account_full"
    "tail" -> "cash_account_tail"
    "masked" -> "cash_account_masked"
    "reconstructed" -> "cash_account_reconstructed"
    else -> null
}

@Composable
internal fun cashBusinessFieldValue(field: String, value: StructuredValue?, accounts: List<Account>, categories: List<CashCategory>, options: LedgerOptions): String {
    val text = value.cashText()
    return when (field) {
        "occurred_at" -> localDateTimeDisplayLabel(text)
        "account_id" -> accounts.firstOrNull { it.id.toString() == text }?.name ?: localizedText("display_not_provided")
        "category_id" -> categories.firstOrNull { it.id == text }?.let { cashCategoryDisplayPath(it) } ?: localizedText("copy_f11956caf6")
        "record_type" -> text?.let { cashRecordTypeLabel(it, options) } ?: "—"
        "record_subtype" -> options.recordTypes.flatMap { it.subtypes }.firstOrNull { it.value == text }?.label ?: "—"
        "cash_granularity" -> when (text) { "atomic" -> localizedText("cash_allocation_single"); "aggregate" -> localizedText("cash_allocation_split"); else -> "—" }
        "counterparty_account_attrs" -> (value as? StructuredArray)?.values.orEmpty()
            .mapNotNull { (it as? StructuredText)?.value?.let(::cashAccountAttributeLabelKey) }
            .let { keys -> val labels = mutableListOf<String>(); keys.forEach { labels += localizedText(it) }; labels.joinToString("、").ifEmpty { "—" } }
        else -> cashReadableValue(value)
    }
}

@Composable
internal fun CashMaintenanceConfirmation(state: CashRecordTaskState, accounts: List<Account>, categories: List<CashCategory>, options: LedgerOptions,
    onConfirm: () -> Unit, onCancel: () -> Unit, showActions: Boolean = true) {
    val plan = state.plan ?: return
    Column(Modifier.fillMaxWidth().testTag("cash.maintenance.confirmation"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(localizedText("cash_confirm_changes"), style = MaterialTheme.typography.titleMedium)
        val unresolvedEffect = cashHasUnresolvedEffects(state)
        val differences = state.details.flatMap { it.fieldDiffs }.filter { it.current != it.effective }.distinct()
        val effects = plan.additionalEffects.orEmpty().map { it to cashEffectTargets(it, state.details, state.input) }
        val targets = (effects.flatMap { it.second } + differences.mapNotNull { it.transactionId }).distinct()
        targets.forEachIndexed { index, id ->
            if (index > 0) HorizontalDivider()
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.affectedRecords[id]?.let { CashAffectedRecordSummary(it.fields) }
                val targetDiffs = differences.filter { it.transactionId == id }
                effects.filter { id in it.second }.forEach { (effect, _) ->
                    if (!cashEffectHasVisibleDiff(effect, targetDiffs)) {
                        Text(localizedText(cashMaintenanceEffectLabel(effect.kind)), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                targetDiffs.forEach { CashMaintenanceFieldDifference(it, accounts, categories, options) }
            }
        }
        effects.filter { it.second.isEmpty() }.forEach { (effect, _) ->
            Text(localizedText(cashMaintenanceEffectLabel(effect.kind)), style = MaterialTheme.typography.bodyMedium)
        }
        differences.filter { it.transactionId == null }.forEach { CashMaintenanceFieldDifference(it, accounts, categories, options) }
        val previewTargets = effects.filter { (effect, ids) ->
            effect.kind != "field_value_changed" || ids.any { id ->
                !cashEffectHasVisibleDiff(effect, differences.filter { it.transactionId == id })
            }
        }.flatMap { it.second }.toSet()
        plan.items.filter { item -> item.members.any { it.transactionId in previewTargets } }.forEach { item ->
            Text(localizedText("cash_after_save"), style = MaterialTheme.typography.labelSmall)
            Text(listOfNotNull(item.display.amount ?: "—", item.display.currency).joinToString(" "), style = MaterialTheme.typography.titleMedium)
            item.display.counterparty?.takeIf { it.isNotBlank() }?.let { Text(it) }
            Text(localDateTimeDisplayLabel(item.display.occurredAt), style = MaterialTheme.typography.bodySmall)
            if (item.display.accounts.isNotEmpty()) Text(item.display.accounts.joinToString(" / "), style = MaterialTheme.typography.bodySmall)
        }
        if (unresolvedEffect) Text(localizedText("cash_effect_unavailable"), color = MaterialTheme.colorScheme.error)
        if (state.previewDirty || plan.invalidatedChoices.isNotEmpty()) Text(localizedText("cash_preview_needs_refresh"), color = MaterialTheme.colorScheme.error)
        if (showActions) CashMaintenanceConfirmationActions(state, onConfirm, onCancel)
    }
}

private fun cashEffectHasVisibleDiff(effect: CashAdditionalEffect, differences: List<ImportFieldDiff>): Boolean =
    effect.kind == "field_value_changed" && effect.field != null && cashFieldLabel(effect.field) != null && differences.any { it.field == effect.field }

private fun cashMaintenanceEffectLabel(kind: String): String = when (kind) {
    "field_value_changed" -> "cash_effect_fields"
    "relation_changed" -> "cash_effect_relations"
    "funding_relation_removed" -> "cash_effect_funding"
    "choice_changed" -> "cash_effect_choices"
    "new_attention" -> "cash_effect_attention"
    else -> "cash_confirm_changes"
}

@Composable
private fun CashMaintenanceFieldDifference(diff: ImportFieldDiff, accounts: List<Account>, categories: List<CashCategory>, options: LedgerOptions) {
    val label = cashFieldLabel(diff.field) ?: return
    Text(localizedText(label), style = MaterialTheme.typography.titleSmall)
    if (diff.field in setOf("source_payload", "components")) {
        Text(localizedText("cash_current_value"), style = MaterialTheme.typography.labelSmall)
        CashSourcePatchValue(diff.field, diff.current, accounts)
        Text(localizedText("cash_after_save"), style = MaterialTheme.typography.labelSmall)
        CashSourcePatchValue(diff.field, diff.effective, accounts)
    } else {
        Text(localizedText("cash_current_value"), style = MaterialTheme.typography.labelSmall)
        Text(cashBusinessFieldValue(diff.field, diff.current, accounts, categories, options))
        Text(localizedText("cash_after_save"), style = MaterialTheme.typography.labelSmall)
        Text(cashBusinessFieldValue(diff.field, diff.effective, accounts, categories, options))
    }
}

private fun cashHasUnresolvedEffects(state: CashRecordTaskState): Boolean =
    state.plan?.additionalEffects.orEmpty().any { cashEffectTargets(it, state.details, state.input).isEmpty() }

@Composable
internal fun CashMaintenanceConfirmationActions(state: CashRecordTaskState, onConfirm: () -> Unit, onCancel: () -> Unit, sideSheet: Boolean = false) {
    val plan = state.plan ?: return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!sideSheet) {
        TextButton(onClick = onCancel, enabled = !state.busy && !state.unresolved, modifier = Modifier.testTag("cash.maintenance.cancel")) { Text(localizedText("copy_4d0b4688c7")) }
        }
        Button(onClick = onConfirm, enabled = !state.busy && !state.unresolved && !state.previewDirty && !cashHasUnresolvedEffects(state) && plan.canCommit && plan.invalidatedChoices.isEmpty(),
            modifier = Modifier.testTag("cash.maintenance.confirm")) { Text(localizedText("cash_confirm_save")) }
        if (sideSheet) {
        TextButton(onClick = onCancel, enabled = !state.busy && !state.unresolved, modifier = Modifier.testTag("cash.maintenance.cancel")) { Text(localizedText("copy_4d0b4688c7")) }
        }
    }
}

@Composable
internal fun CashAffectedRecordSummary(fields: StructuredObject) {
    Text(localizedText("cash_current_value"), style = MaterialTheme.typography.labelSmall)
    Text(listOfNotNull(fields.fields["amount"].cashText() ?: "—", fields.fields["currency"].cashText()).joinToString(" "), style = MaterialTheme.typography.titleMedium)
    fields.fields["counterparty"].cashText()?.takeIf { it.isNotBlank() }?.let { Text(it) }
    Text(localDateTimeDisplayLabel(fields.fields["occurred_at"].cashText()), style = MaterialTheme.typography.bodySmall)
    fields.fields["account_name"].cashText()?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}
