package com.finance.tracker.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.finance.tracker.core.*
import com.finance.tracker.domain.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CashImportReviewList(state: ImportTaskUiState, categories: List<CashCategory>, sizeClass: WindowSizeClass,
    listState: LazyListState, onFilter: (String) -> Unit, onMore: () -> Unit, onOpen: (String) -> Unit,
    modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val pending = state.plan?.itemCounts?.get("pending") ?: 0L
            if (pending > 0) FilterChip(selected = state.filter == "pending", onClick = { onFilter("pending") },
                label = { Text(localizedText("cash_pending_count", pending.toString())) }, enabled = state.canEdit,
                modifier = Modifier.testTag("cash.import.filter-pending"))
            FilterChip(selected = state.filter == "all", onClick = { onFilter("all") },
                label = { Text(localizedText("ledger_quick_all")) }, enabled = state.canEdit,
                modifier = Modifier.testTag("cash.import.filter-all"))
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("cash.import.items"), state = listState) {
            if (sizeClass != WindowSizeClass.COMPACT) item { CashLedgerTableHeader(showSelection = false, statusOnly = true) }
            itemsIndexed(state.items, key = { _, item -> item.itemId }) { index, item ->
                val display = item.display
                CashLedgerRow(CashLedgerRowContent("import-${item.itemId}", display.occurredAt,
                    display.counterparty.orEmpty(), display.note.orEmpty(),
                    display.accounts.joinToString("、").ifEmpty { localizedText("display_not_provided") },
                    cashCategoryDisplayPath(categories.firstOrNull { it.id == display.categoryId }), null,
                    if (item.members.size > 1) localizedText("cash_source_merged") else null,
                    item.confirmationStatus, cashAmountWithCurrencyLabel(display.amount, display.currency), display.amount?.startsWith("-") == true),
                    checked = false, canWrite = false, sizeClass = sizeClass, selectionMode = false,
                    lastInMonth = index == state.items.lastIndex, onChecked = {}, onOpen = { onOpen(item.itemId) }, showSelection = false)
            }
            if (!state.itemsInitialized) item {
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                else TextButton(onClick = { onFilter(state.filter) }) { Text(localizedText("copy_e2d53a6d3a")) }
            }
            if (state.itemsInitialized && state.items.isEmpty()) item {
                Text(localizedText(if (state.filter == "pending") "cash_import_no_pending" else "cash_import_no_changes"),
                    Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.bodyMedium)
            }
            if (state.nextCursor != null) item {
                TextButton(onClick = onMore, enabled = state.canEdit, modifier = Modifier.testTag("cash.import.more")) {
                    Text(localizedText("cash_import_more"))
                }
            }
        }
    }
}
