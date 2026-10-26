package com.finance.tracker.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.finance.tracker.core.*
import com.finance.tracker.domain.*

@Composable
internal fun CashImportPreviewPanel(item: ImportProjectedItem, detail: ImportItemDetail?, planId: String, busy: Boolean,
    sizeClass: WindowSizeClass, accounts: List<Account>, categories: List<CashCategory>, options: LedgerOptions,
    errorCode: String?, onRetry: () -> Unit, onClose: () -> Unit) {
    var expanded by remember(planId, item.itemId) { mutableStateOf<String?>(null) }
    CashRecordModalPanel(sizeClass, localizedText("cash_import_detail_title"), true,
        semanticStateKey = "$planId:${item.itemId}", onDismiss = onClose, onBack = onClose) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(top = if (sizeClass == WindowSizeClass.COMPACT) 0.dp else 24.dp, bottom = 24.dp).testTag("cash.import.detail"),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Text(localizedText(if (item.anticipatedState == "draft") "cash_import_draft" else "cash_import_posted"),
                style = MaterialTheme.typography.labelLarge, color = LocalFinanceColorRoles.current.warning)
            Text(localizedText(if (!item.statisticsEligible) "cash_import_excluded_statistics" else "cash_import_included_statistics"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CashRecordSummary(if (item.display.amount == null) localizedText("cash_import_amount_unknown") else cashAmountWithCurrencyLabel(item.display.amount, item.display.currency), item.display.amount?.startsWith("-") == true,
                item.display.counterparty, localDateTimeDisplayLabel(item.display.occurredAt),
                buildList {
                    add("copy_c3d92b20c8" to item.display.accounts.joinToString("、").ifEmpty { localizedText("display_not_provided") })
                    add("copy_a42e73f0a7" to cashCategoryDisplayPath(categories.firstOrNull { it.id == item.display.categoryId }))
                    item.display.note?.takeIf { it.isNotBlank() }?.let { add("copy_e0361480e3" to it) }
                })
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            errorCode?.let { Text(ledgerErrorText(it), color = MaterialTheme.colorScheme.error) }
            if (detail == null) {
                if (!busy) TextButton(onClick = onRetry) { Text(localizedText("copy_e2d53a6d3a")) }
            } else {
                val issues = detail.issues.distinctBy { it.code to it.fieldPath }
                if (issues.isNotEmpty()) CashImportDetailSection(localizedText("cash_import_issues")) {
                    issues.forEach { issue ->
                        Text(issue.message, color = LocalFinanceColorRoles.current.warning, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                val differences = detail.fieldDiffs.filter { it.current != it.incoming }.groupBy { ImportMemberRef(it.rowRef, it.transactionId) }
                if (differences.isNotEmpty()) CashImportDetailSection(localizedText("cash_import_source_differences")) {
                    differences.forEach { (reference, values) ->
                        val key = "source:${reference.rowRef}:${reference.transactionId}"
                        CashImportDisclosure(localizedText("cash_import_source_changed"), expanded == key,
                            onClick = { expanded = if (expanded == key) null else key }) {
                            values.forEach { difference ->
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                            }
                        }
                    }
                }
                if (detail.relationChoices.isNotEmpty()) CashImportDetailSection(localizedText("cash_import_relation_candidates")) {
                    detail.relationChoices.distinctBy { it.suggestionId }.forEach { group ->
                        val key = "relation:${group.suggestionId}"
                        CashImportDisclosure(group.kind?.let { cashRelationTypeLabel(it, options) } ?: localizedText("cash_import_related_entries"),
                            expanded == key, onClick = { expanded = if (expanded == key) null else key },
                            modifier = Modifier.testTag("cash.import.relation-${group.suggestionId}")) {
                            Text(localizedText(if (group.action == "accept") "cash_import_pair_proposed" else "cash_import_pair_unmerged"),
                                style = MaterialTheme.typography.bodyMedium)
                            group.code?.let { Text(cashImportTaskErrorText(it), color = MaterialTheme.colorScheme.error) }
                            if (group.candidates.isEmpty()) {
                                CashImportRelationMembers(group.members, detail.relationMembers, accounts)
                            } else {
                                group.candidates.forEachIndexed { index, candidate ->
                                    if (index > 0) HorizontalDivider()
                                    Column(Modifier.fillMaxWidth().testTag("cash.import.candidate-${candidate.candidateId}"),
                                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        candidate.code?.let { Text(cashImportTaskErrorText(it), color = MaterialTheme.colorScheme.error) }
                                        CashImportRelationMembers(candidate.members, detail.relationMembers, accounts)
                                    }
                                }
                            }
                            if (group.commonFieldConflicts.isNotEmpty()) {
                                group.commonFieldConflicts.forEach { (field, values) ->
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        cashFieldLabel(field)?.let { Text(localizedText(it), style = MaterialTheme.typography.titleSmall) }
                                        values.distinct().forEach { value ->
                                            Text(if (value == StructuredNull || value == StructuredText("") || value == StructuredArray(emptyList())) localizedText("cash_clear_value")
                                                else cashBusinessFieldValue(field, value, accounts, categories, options), style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (detail.relationEffects.isNotEmpty()) CashImportDetailSection(localizedText("cash_import_existing_relations")) {
                    detail.relationEffects.forEach { effect ->
                        Text(cashRelationTypeLabel(effect.kind, options), style = MaterialTheme.typography.titleSmall)
                        CashImportRelationMembers(effect.members, detail.relationMembers + detail.members, accounts)
                    }
                }
                CashImportDetailSection(localizedText("cash_import_statement_original")) {
                    CashImportDisclosure(localizedText("cash_statement_content"), expanded == "sources",
                        onClick = { expanded = if (expanded == "sources") null else "sources" },
                        modifier = Modifier.testTag("cash.import.sources")) {
                        (detail.members + detail.relationMembers).distinctBy { ImportMemberRef(it.rowRef, it.transactionId) }.forEach { member ->
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                CashImportMemberSummary(member, accounts)
                                val lines = cashStatementLines(member.fields.fields["source_payload"])
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

@Composable
private fun CashImportDetailSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        HorizontalDivider()
        content()
    }
}

@Composable
private fun CashImportDisclosure(title: String, expanded: Boolean, onClick: () -> Unit,
    modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Text(localizedText(if (expanded) "cash_import_collapse" else "cash_import_process"))
            }
        }
        if (expanded) Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

@Composable
private fun CashImportRelationMembers(refs: List<ImportMemberRef>, members: List<ImportMemberDetail>, accounts: List<Account>) {
    val actual = importRelationMembers(refs, members)
    if (actual == null) Text(localizedText("cash_import_member_missing"), color = MaterialTheme.colorScheme.error)
    else actual.forEach { CashImportMemberSummary(it, accounts) }
}

internal fun importRelationMembers(refs: List<ImportMemberRef>, members: List<ImportMemberDetail>): List<ImportMemberDetail>? {
    if (refs.isEmpty() || refs.any { it.rowRef == null && it.transactionId == null }) return null
    val byReference = members.associateBy { ImportMemberRef(it.rowRef, it.transactionId) }
    return refs.map { byReference[it] ?: return null }
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
private fun cashImportMemberAccountLabel(fields: Map<String, StructuredValue>, accounts: List<Account>): String {
    val ids = fields["account_id"].cashText()?.let(::listOf) ?: (fields["components"] as? StructuredArray)?.values.orEmpty()
        .mapNotNull { (it as? StructuredObject)?.fields?.get("account_id").cashText() }
    return ids.distinct().map { id -> accounts.firstOrNull { it.id.toString() == id }?.name ?: localizedText("display_not_provided") }
        .joinToString("、").ifEmpty { localizedText("display_not_provided") }
}
