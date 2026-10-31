package com.finance.tracker.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import com.finance.tracker.core.*
import com.finance.tracker.domain.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CashImportReviewList(
    state: ImportTaskUiState,
    rows: LazyPagingItems<ImportProjectedItem>,
    categories: List<CashCategory>,
    options: LedgerOptions,
    sizeClass: WindowSizeClass,
    listState: LazyListState,
    onFilter: (String) -> Unit,
    onOpen: (ImportProjectedItem) -> Unit,
    onProcessing: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val pendingCount = state.plan?.itemCounts?.get("pending") ?: 0L
    val allCount = state.plan?.itemCounts?.get("all")
        ?: (state.plan?.itemCounts?.get("confirmed") ?: 0L) + pendingCount
    LaunchedEffect(state.plan?.planId, state.filter) { listState.scrollToItem(0) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (pendingCount > 0L) {
                FilterChip(
                    selected = state.filter == "pending",
                    onClick = { onFilter("pending") },
                    label = { Text(localizedText("cash_import_pending_count", pendingCount.toString())) },
                    enabled = state.canEdit,
                    modifier = Modifier.testTag("cash.import.filter-pending"),
                )
            }
            FilterChip(
                selected = state.filter == "all",
                onClick = { onFilter("all") },
                label = { Text(localizedText("cash_import_all_count", allCount.toString())) },
                enabled = state.canEdit,
                modifier = Modifier.testTag("cash.import.filter-all"),
            )
        }
        val summariesByMonth = state.plan?.monthlySummaries?.get(state.filter).orEmpty().associateBy(CashMonthlySummary::month)
        CashWaterfallViewport(
            listState = listState,
            totalCount = rows.itemCount,
            monthAt = { ordinal -> rows.peek(ordinal)?.display?.occurredAt?.let(::localMonthKey) },
            monthStartAt = { ordinal ->
                val month = rows.peek(ordinal)?.display?.occurredAt?.let(::localMonthKey)
                var start = ordinal
                while (start > 0 && rows.peek(start - 1)?.display?.occurredAt?.let(::localMonthKey) == month) start--
                start
            },
            monthPositions = emptyList(),
            isOrdinalLoaded = { ordinal -> rows.peek(ordinal) != null },
            summaryForMonth = { summariesByMonth[it] },
            showTotals = true,
            sizeClass = sizeClass,
            showSelection = false,
            auxiliaryColumn = CashLedgerAuxiliaryColumn.TYPE,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { onHeaderHeight ->
            LazyColumn(
                Modifier.fillMaxSize().testTag("cash.import.items"),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                items(
                    count = rows.itemCount,
                    key = rows.itemKey { it.itemId },
                ) { index ->
                    val item = rows[index]
                    if (item == null) {
                        CashProjectionPlaceholderRow(compact = sizeClass == WindowSizeClass.COMPACT)
                    } else {
                        val month = localMonthKey(item.display.occurredAt)
                        val previousMonth = if (index == 0) null else rows.peek(index - 1)?.display?.occurredAt?.let(::localMonthKey)
                        val nextMonth = if (index == rows.itemCount - 1) null else rows.peek(index + 1)?.display?.occurredAt?.let(::localMonthKey)
                        val monthStart = month != previousMonth
                        val monthEnd = nextMonth?.let { it != month } ?: (index == rows.itemCount - 1)
                        val detail = state.details[item.itemId]
                        val relationPending = detail?.relationChoices?.any { it.action == "defer" } == true
                        val statusTags = cashAttentionTags(item.anticipatedState, item.confirmationStatus,
                            item.attentionReasons.map(ImportIssue::code) + if (relationPending) listOf("relation_pending") else emptyList())
                        val pairLabels = detail?.let { currentDetail -> currentDetail.relationChoices
                            .filter { it.action in setOf("accept", "defer") }
                            .map { group ->
                                val kind = group.kind?.let { cashRelationTypeLabel(it, options) }.orEmpty()
                                val refs = group.members.ifEmpty { group.candidates.flatMap { it.members }.distinct() }
                                val members = importRelationMembers(refs, currentDetail.relationMembers + currentDetail.members)
                                val sources = members?.map { member ->
                                    listOfNotNull(
                                        member.fields.fields["source_type"].cashText(),
                                        member.fields.fields["account_name"].cashText(),
                                        member.fields.fields["counterparty"].cashText(),
                                    ).distinct().joinToString(" · ").ifEmpty { localizedText("display_not_provided") }
                                }?.joinToString(" 与 ").orEmpty()
                                listOf(kind, sources).filter(String::isNotBlank).joinToString(" · ")
                            } }.orEmpty()
                        val relationshipSummary = pairLabels.distinct().joinToString("；")
                            .takeIf(String::isNotBlank)
                            ?.let { localizedText("cash_import_relationship_summary", it) }
                        val attentionSummary = item.attentionReasons.map { it.message }
                            .filter(String::isNotBlank).distinct().joinToString("；")
                            .takeIf(String::isNotBlank)
                            ?.let { localizedText("cash_import_attention_summary", it) }

                        CashWaterfallItem(
                            month = month,
                            first = monthStart,
                            last = monthEnd,
                            gapBefore = monthStart && index > 0,
                            summary = summariesByMonth[month],
                            showTotals = true,
                            sizeClass = sizeClass,
                            showSelection = false,
                            auxiliaryColumn = CashLedgerAuxiliaryColumn.TYPE,
                            onHeaderHeight = onHeaderHeight,
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                CashLedgerRow(
                                    item = CashLedgerRowContent(
                                        id = "import-${item.itemId}",
                                        occurredAt = item.display.occurredAt,
                                        counterparty = item.display.counterparty.orEmpty(),
                                        note = item.display.note.orEmpty(),
                                        accountLabel = item.display.accounts.joinToString("、")
                                            .ifEmpty { localizedText("cash_import_no_account") },
                                        categoryLabel = cashCategoryDisplayPath(categories.firstOrNull { it.id == item.display.categoryId }),
                                        economicLabel = cashRecordTypeAndSubtypeLabel(item.display.recordType, item.display.recordSubtype, options),
                                        sourceLabel = null,
                                        confirmationStatus = item.confirmationStatus,
                                        amountLabel = if (item.display.amount == null) localizedText("cash_import_amount_unknown")
                                            else cashAmountWithCurrencyLabel(item.display.amount, item.display.currency),
                                        negative = item.display.amount?.startsWith("-") == true,
                                        statusTags = statusTags,
                                        showConfirmationStatus = false,
                                        relationshipSummary = relationshipSummary,
                                        attentionSummary = attentionSummary,
                                        auxiliaryColumn = CashLedgerAuxiliaryColumn.TYPE,
                                    ),
                                    checked = false,
                                    canWrite = false,
                                    sizeClass = sizeClass,
                                    selectionMode = false,
                                    lastInMonth = monthEnd,
                                    onChecked = {},
                                    onOpen = { onOpen(item) },
                                    showSelection = false,
                                )
                            }
                        }
                    }
                }
                if (rows.loadState.append is LoadState.Loading) {
                    item(key = "cash-import-items-append-loading") {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) { CircularProgressIndicator() }
                    }
                }
                val appendError = rows.loadState.append as? LoadState.Error
                if (appendError != null) {
                    item(key = "cash-import-items-append-error") {
                        StateMessage(cashImportTaskErrorText((appendError.error as? DomainFailure)?.code ?: "request_failed"), isError = true) {
                            TextButton(onClick = rows::retry) { Text(localizedText("copy_e2d53a6d3a")) }
                        }
                    }
                }
            }

            val refreshError = rows.loadState.refresh as? LoadState.Error
            when {
                refreshError != null -> StateMessage(
                    cashImportTaskErrorText((refreshError.error as? DomainFailure)?.code ?: "request_failed"),
                    isError = true,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                ) {
                    TextButton(onClick = rows::retry) { Text(localizedText("copy_e2d53a6d3a")) }
                }
                rows.itemCount == 0 && rows.loadState.refresh is LoadState.Loading ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                rows.itemCount == 0 -> StateMessage(
                    localizedText("cash_import_empty_filter"),
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            }

        }
        if (onProcessing != null) {
            TextButton(
                onClick = onProcessing,
                enabled = state.canEdit,
                modifier = Modifier.testTag("cash.import.processing"),
            ) { Text(localizedText("cash_import_processing_details")) }
        }
    }
}
