package com.finance.tracker.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.finance.tracker.core.*
import com.finance.tracker.domain.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CashImportReviewList(state: ImportTaskUiState, categories: List<CashCategory>, options: LedgerOptions,
    sizeClass: WindowSizeClass, listState: LazyListState, onFilter: (String) -> Unit, onMore: () -> Unit, onOpen: (String) -> Unit,
    onProcessing: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    var filter by remember(state.plan?.planId) { mutableStateOf("all") }
    val counts = state.plan?.itemCounts.orEmpty()
    val total = counts["all"] ?: if (counts.isNotEmpty()) (counts["confirmed"] ?: 0L) + (counts["pending"] ?: 0L) else state.items.size.toLong()
    val complete = state.itemsInitialized && state.filter == "all" && state.nextCursor == null && state.items.size.toLong() == total
    val detailsReady = state.items.all { !it.detailAvailable || it.itemId in state.details }
    val drafts = state.items.count { it.anticipatedState == "draft" }
    val items = state.items.filter { item -> when (filter) {
        "draft" -> item.anticipatedState == "draft"
        "manual" -> state.details[item.itemId]?.relationChoices?.any { it.action == "defer" } == true
        else -> true
    } }.sortedWith(compareBy<ImportProjectedItem> { if (it.anticipatedState == "draft") 0 else if (state.details[it.itemId]?.relationChoices?.isNotEmpty() == true) 1 else 2 })
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf("cash_import_expected_items" to total.toString(), "cash_import_draft" to if (complete) drafts.toString() else "—",
                "cash_import_posted" to if (complete) (state.items.size - drafts).toString() else "—").forEach { (label, value) ->
                Column(Modifier.weight(1f).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(value, style = MaterialTheme.typography.headlineMedium,
                        color = if (label == "cash_import_draft" && drafts > 0) LocalFinanceColorRoles.current.warning else MaterialTheme.colorScheme.onSurface)
                    Text(localizedText(label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        HorizontalDivider()
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(filter == "draft", { filter = "draft" }, label = { Text(localizedText("cash_import_draft")) },
                enabled = state.canEdit && complete, modifier = Modifier.testTag("cash.import.filter-draft"))
            FilterChip(filter == "manual", { filter = "manual" }, label = { Text(localizedText("cash_import_manual_pair")) },
                enabled = state.canEdit && complete && detailsReady, modifier = Modifier.testTag("cash.import.filter-manual"))
            FilterChip(filter == "all", { filter = "all"; if (state.filter != "all") onFilter("all") },
                label = { Text(localizedText("ledger_quick_all")) }, enabled = state.canEdit, modifier = Modifier.testTag("cash.import.filter-all"))
        }
        Text(localizedText(if (complete) "cash_import_filter_scope" else "cash_import_loaded_scope", state.items.size.toString()),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("cash.import.items"), state = listState) {
            if (sizeClass != WindowSizeClass.COMPACT) item { CashLedgerTableHeader(showSelection = false, statusOnly = true) }
            itemsIndexed(items, key = { _, item -> item.itemId }) { index, item ->
                val display = item.display
                val detail = state.details[item.itemId]
                val pairLabels = detail?.relationChoices.orEmpty().filter { it.action in setOf("accept", "defer") }.map { group ->
                    val method = localizedText(if (group.action == "accept") "cash_import_auto_pair" else "cash_import_manual_pair")
                    val kind = group.kind?.let { cashRelationTypeLabel(it, options) }.orEmpty()
                    val refs = group.members.ifEmpty { group.candidates.flatMap { it.members }.distinct() }
                    val members = importRelationMembers(refs, detail!!.relationMembers + detail.members)
                    val sources = members?.map { member ->
                        listOfNotNull(member.fields.fields["source_type"].cashText(), member.fields.fields["account_name"].cashText(),
                            member.fields.fields["counterparty"].cashText()).distinct().joinToString(" · ").ifEmpty { localizedText("display_not_provided") }
                    }?.joinToString(" ↔ ").orEmpty()
                    listOf(method, kind, sources).filter { it.isNotBlank() }.joinToString(" · ")
                }
                val explanation = (item.attentionReasons.map { it.message } + pairLabels).distinct().joinToString("；")
                CashLedgerRow(CashLedgerRowContent("import-${item.itemId}", display.occurredAt,
                    display.counterparty.orEmpty(), explanation.ifBlank { display.note.orEmpty() },
                    display.accounts.joinToString("、").ifEmpty { localizedText("cash_import_no_account") },
                    cashCategoryDisplayPath(categories.firstOrNull { it.id == display.categoryId }),
                    localizedText(if (item.anticipatedState == "draft") "cash_import_draft" else "cash_import_posted"),
                    if (detail == null && item.detailAvailable) localizedText("cash_import_details_loading") else null,
                    item.confirmationStatus, if (display.amount == null) localizedText("cash_import_amount_unknown") else cashAmountWithCurrencyLabel(display.amount, display.currency),
                    display.amount?.startsWith("-") == true),
                    checked = false, canWrite = false, sizeClass = sizeClass, selectionMode = false,
                    lastInMonth = index == items.lastIndex, onChecked = {}, onOpen = { onOpen(item.itemId) }, showSelection = false)
            }
            if (!state.itemsInitialized) item {
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                else TextButton(onClick = { onFilter("all") }) { Text(localizedText("copy_e2d53a6d3a")) }
            }
            if (state.itemsInitialized && items.isEmpty()) item {
                Text(localizedText("cash_import_empty_filter"), Modifier.padding(vertical = 24.dp))
            }
            if (state.nextCursor != null) item {
                TextButton(onClick = onMore, enabled = state.canEdit, modifier = Modifier.testTag("cash.import.more")) { Text(localizedText("cash_import_more")) }
            }
        }
        if (onProcessing != null) TextButton(onClick = onProcessing, enabled = state.canEdit,
            modifier = Modifier.testTag("cash.import.processing")) { Text(localizedText("cash_import_processing_details")) }
    }
}
