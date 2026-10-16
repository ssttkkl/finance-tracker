package com.finance.tracker.presentation

import com.finance.tracker.core.*
import com.finance.tracker.domain.*
import com.finance.tracker.resources.Res
import com.finance.tracker.resources.ic_add
import com.finance.tracker.resources.ic_close
import com.finance.tracker.resources.ic_delete
import com.finance.tracker.resources.ic_file_download
import com.finance.tracker.resources.ic_keyboard_arrow_down
import com.finance.tracker.resources.ic_label
import com.finance.tracker.resources.ic_search
import com.finance.tracker.resources.ic_visibility

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.material3.OutlinedTextField
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.finance.tracker.core.design.GeneratedTokens
import org.jetbrains.compose.resources.painterResource

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun CashLedgerScreen(
    repository: FinanceRepository,
    session: Session,
    sizeClass: WindowSizeClass,
    onNavigate: (AppPage) -> Unit,
    diagnostics: DiagnosticLogger = NoOpDiagnosticLogger,
    cashLedgerReloadRevision: Int = 0,
) {
    val viewModel: CashLedgerViewModel = viewModel(
        key = "cash-ledger:${session.activeWorkspaceId.orEmpty()}",
        factory = viewModelFactory { initializer { CashLedgerViewModel(repository, repository, diagnostics = diagnostics) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pagedRows = viewModel.cashProjections.collectAsLazyPagingItems()
    val listState = rememberLazyListState()
    LaunchedEffect(viewModel, cashLedgerReloadRevision) {
        if (cashLedgerReloadRevision == 0) viewModel.initialize()
        else viewModel.reloadAfterImport()
    }
    LaunchedEffect(state.filters, state.listRevision, cashLedgerReloadRevision) {
        listState.scrollToItem(0)
    }
    val recordPanelOpen = state.selectedRow != null || state.recordDraft != null

    val canWrite = session.workspaces.firstOrNull { it.id == session.activeWorkspaceId }?.role?.canWrite == true
    val referencesError = state.referencesErrorCode?.let { ledgerErrorText(it) }
    val refreshError = (pagedRows.loadState.refresh as? LoadState.Error)?.error?.let { ledgerErrorText(ledgerPagingErrorCode(it)) }
    val appendError = (pagedRows.loadState.append as? LoadState.Error)?.error?.let { ledgerErrorText(ledgerPagingErrorCode(it)) }
    val classifyError = state.classifyErrorCode?.let { ledgerErrorText(it) }
    val deleteProjectionError = state.deleteProjectionErrorCode?.let { ledgerErrorText(it) }
    val recordSaveError = state.recordSaveErrorCode?.let { ledgerErrorText(it) }
    var actionsMenuExpanded by remember { mutableStateOf(false) }
    var batchCategoryDialogOpen by remember { mutableStateOf(false) }
    val selectionMode = state.selectedIds.isNotEmpty()
    LaunchedEffect(selectionMode) {
        if (selectionMode) actionsMenuExpanded = false
    }

    Box(Modifier.fillMaxSize()) {
        FeaturePage(
            localizedText("copy_c1f4f28c6c"),
            SemanticIds.ledgerScreen,
            modifier = Modifier.fillMaxSize(),
            showTitle = false,
            scrollable = false,
            contentPadding = PaddingValues(
                start = GeneratedTokens.Core.Space.Token4,
                top = GeneratedTokens.Core.Space.Token3,
                end = GeneratedTokens.Core.Space.Token4,
                bottom = 104.dp,
            ),
        ) {
        if (referencesError != null) StateMessage(referencesError, isError = true) {
            TextButton(onClick = viewModel::loadReferences) { Text(localizedText("copy_e2d53a6d3a")) }
        }
        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 0.dp,
        ) {
            val summariesByMonth = state.page?.monthlySummaries.orEmpty().associateBy(CashMonthlySummary::month)
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag(SemanticIds.ledgerList),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                item(key = "cash-ledger-filters") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CashFilterPanel(
                            value = state.filters,
                            sizeClass = sizeClass,
                            onChange = viewModel::updateFilters,
                            accounts = state.accounts,
                            categories = state.categories,
                            currencies = state.filterOptions.currencies,
                            typeOptions = state.filterOptions.economicTypes,
                            onReset = viewModel::clearFilters,
                            onInvalidAmount = viewModel::reportInvalidAmountFilter,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                if (selectionMode) {
                    item(key = "cash-ledger-selection-count") {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag(SemanticIds.ledgerSelectedCount),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                localizedText("ledger_selection_count", state.selectedIds.size.toString()),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            InlineError(classifyError ?: deleteProjectionError)
                        }
                    }
                }
                when {
                    pagedRows.loadState.refresh is LoadState.Loading && pagedRows.itemCount == 0 ->
                        item(key = "cash-ledger-loading") { StateMessage(localizedText("copy_ced64cb470")) }
                    refreshError != null && pagedRows.itemCount == 0 ->
                        item(key = "cash-ledger-refresh-error") {
                            StateMessage(refreshError, isError = true) {
                                TextButton(onClick = { pagedRows.retry() }, modifier = Modifier.testTag(SemanticIds.ledgerRetry)) {
                                    Text(localizedText("copy_e2d53a6d3a"))
                                }
                            }
                        }
                    pagedRows.loadState.refresh is LoadState.NotLoading && pagedRows.itemCount == 0 ->
                        item(key = "cash-ledger-empty") {
                            StateMessage(localizedText("copy_8eb3d2788b"), modifier = Modifier.testTag(SemanticIds.ledgerEmpty))
                        }
                }
                if (pagedRows.itemCount > 0 && sizeClass != WindowSizeClass.COMPACT) {
                    item(key = "cash-ledger-table-header") { CashLedgerTableHeader() }
                }
                items(
                    count = pagedRows.itemCount,
                    key = pagedRows.itemKey { it.projectionId },
                ) { index ->
                    val row = pagedRows[index]
                    if (row != null) {
                        val month = localMonthKey(row.occurredAt)
                        val previousMonth = if (index == 0) null else pagedRows.peek(index - 1)?.let { localMonthKey(it.occurredAt) }
                        Column(Modifier.fillMaxWidth().testTag("${SemanticIds.ledgerTable}-$month")) {
                            if (month != previousMonth) {
                                CashProjectionMonthHeader(
                                    group = CashProjectionMonthGroup(month, emptyList(), summariesByMonth[month]),
                                    compact = sizeClass == WindowSizeClass.COMPACT,
                                )
                            }
                            CashProjectionRow(
                                item = row,
                                checked = row.projectionId in state.selectedIds,
                                canWrite = canWrite,
                                sizeClass = sizeClass,
                                selectionMode = selectionMode,
                                onChecked = { viewModel.toggleSelection(row.projectionId, it) },
                                onOpen = { viewModel.openRow(row) },
                            )
                        }
                    }
                }
                if (pagedRows.loadState.append is LoadState.Loading) {
                    item(key = "cash-ledger-append-loading") {
                        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator()
                        }
                    }
                }
                if (appendError != null) {
                    item(key = "cash-ledger-append-error") {
                        StateMessage(appendError, isError = true) {
                            TextButton(
                                onClick = { pagedRows.retry() },
                                modifier = Modifier.testTag(SemanticIds.ledgerAppendRetry),
                            ) { Text(localizedText("copy_b647ee0bdc")) }
                        }
                    }
                }
            }
        }
        }

        FloatingActionButtonMenu(
            expanded = actionsMenuExpanded,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp),
            button = {
                ToggleFloatingActionButton(
                    checked = actionsMenuExpanded,
                    onCheckedChange = { if (canWrite) actionsMenuExpanded = it },
                    modifier = Modifier
                        .testTag(SemanticIds.ledgerActionsFab)
                        .semantics { if (!canWrite) disabled() },
                ) {
                    Icon(
                        painter = painterResource(if (actionsMenuExpanded) Res.drawable.ic_close else Res.drawable.ic_add),
                        contentDescription = localizedText("ledger_actions"),
                    )
                }
            },
        ) {
            if (selectionMode) {
                FloatingActionButtonMenuItem(
                    onClick = {
                        actionsMenuExpanded = false
                        if (canWrite) batchCategoryDialogOpen = true
                    },
                    icon = { Icon(painterResource(Res.drawable.ic_label), contentDescription = null) },
                    text = { Text(localizedText("ledger_action_set_category")) },
                    modifier = Modifier.testTag(SemanticIds.ledgerBatchCategory),
                )
                FloatingActionButtonMenuItem(
                    onClick = {
                        actionsMenuExpanded = false
                        viewModel.requestDeleteSelection(canWrite)
                    },
                    icon = { Icon(painterResource(Res.drawable.ic_delete), contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    text = { Text(localizedText("ledger_action_delete"), color = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.testTag(SemanticIds.ledgerBatchDelete),
                )
                FloatingActionButtonMenuItem(
                    onClick = {
                        actionsMenuExpanded = false
                        viewModel.clearSelection()
                    },
                    icon = { Icon(painterResource(Res.drawable.ic_close), contentDescription = null) },
                    text = { Text(localizedText("ledger_action_cancel_selection")) },
                    modifier = Modifier.testTag(SemanticIds.ledgerCancelSelection),
                )
            } else {
                FloatingActionButtonMenuItem(
                    onClick = {
                        actionsMenuExpanded = false
                        if (canWrite) viewModel.startNewRecord(localDateTimeForInput())
                    },
                    icon = { Icon(painterResource(Res.drawable.ic_add), contentDescription = null) },
                    text = { Text(localizedText("copy_1f62e3f53f")) },
                    modifier = Modifier.testTag(SemanticIds.ledgerAdd),
                )
                FloatingActionButtonMenuItem(
                    onClick = {
                        actionsMenuExpanded = false
                        if (canWrite) onNavigate(AppPage.CASH_IMPORT)
                    },
                    icon = { Icon(painterResource(Res.drawable.ic_file_download), contentDescription = null, modifier = Modifier.testTag("ledger.import.icon")) },
                    text = { Text(localizedText("copy_aee4e0d769")) },
                    modifier = Modifier.testTag(SemanticIds.ledgerImport),
                )
            }
        }
    }

    if (batchCategoryDialogOpen) {
        AlertDialog(
            modifier = Modifier.testTag(SemanticIds.ledgerCategoryDialog),
            onDismissRequest = { batchCategoryDialogOpen = false },
            title = { Text(localizedText("ledger_category_dialog_title")) },
            text = {
                ChoicePicker(
                    label = localizedText("ledger_category_label"),
                    value = state.selectedCategory,
                    options = listOf("" to localizedText("copy_3759bf861f"), UNCATEGORIZED_SELECTION to localizedText("copy_f11956caf6")) + state.categories.map { it.id to cashCategoryDisplayPath(it) },
                    onSelected = viewModel::setSelectedCategory,
                    enabled = canWrite && !state.classifyBusy,
                    semanticId = SemanticIds.ledgerCategoryPicker,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        batchCategoryDialogOpen = false
                        viewModel.classifySelection(canWrite)
                    },
                    enabled = canWrite && !state.classifyBusy && state.selectedCategory.isNotEmpty(),
                    modifier = Modifier.testTag(SemanticIds.ledgerCategoryApply),
                ) { Text(localizedText("ledger_category_apply")) }
            },
            dismissButton = {
                TextButton(
                    onClick = { batchCategoryDialogOpen = false },
                    modifier = Modifier.testTag(SemanticIds.ledgerCategoryCancel),
                ) { Text(localizedText("ledger_category_cancel")) }
            },
        )
    }

    if (recordPanelOpen) {
        val draft = state.recordDraft
        val onPanelBack = {
            when {
                state.deleteRecordConfirm -> viewModel.dismissDeleteRecord()
                state.confirmRelationImpact -> viewModel.dismissRelationImpactConfirmation()
                state.relationComposerOpen -> viewModel.closeRelationComposer()
                else -> viewModel.dismissRecordPanel()
            }
        }
        val panelTitle = when {
            draft?.id != null -> localizedText("copy_6a5c4515c6")
            draft != null -> localizedText("copy_1f62e3f53f")
            else -> localizedText("copy_8cb72a44a5")
        }
        CashRecordModalPanel(
            sizeClass = sizeClass,
            title = panelTitle,
            onDismiss = viewModel::dismissRecordPanel,
            onBack = onPanelBack,
        ) {
            if (draft != null) {
                CashRecordEditorContent(
                    draft = draft,
                    sizeClass = sizeClass,
                    accounts = state.accounts,
                    categories = state.categories,
                    options = state.options,
                    busy = state.recordBusy,
                    error = recordSaveError,
                    onChange = viewModel::updateRecordDraft,
                    onSave = { viewModel.saveRecord(canWrite) },
                    onCancel = viewModel::cancelRecordEdit,
                    onInvalidAmount = viewModel::reportInvalidRecordAmount,
                    onInvalidDateTime = viewModel::reportInvalidRecordDateTime,
                )
            } else {
                CashRecordDetailContent(
                    state = state,
                    viewModel = viewModel,
                    canWrite = canWrite,
                    options = state.options,
                    onEdit = { viewModel.editRecord() },
                    onEditRelated = viewModel::editRecord,
                )
            }
        }
    }
    if (state.confirmRelationImpact && state.recordDraft != null) {
        ConfirmationDialog(
            title = localizedText("copy_e17cb4ec33"),
            message = localizedText("copy_24c943b133"),
            confirmLabel = localizedText("copy_2b9228c01b"),
            onConfirm = { viewModel.saveRecord(canWrite, confirmImpact = true) },
            onDismiss = viewModel::dismissRelationImpactConfirmation,
            busy = state.recordBusy,
        )
    }
    state.deleteProjectionImpact?.let { impact ->
        ConfirmationDialog(
            title = localizedText("copy_77fe08d5f9"),
            message = localizedText("copy_599187ecd0", impact.projectionCount, impact.transactionCount, impact.relationGroupCount),
            confirmLabel = localizedText("copy_3755f56f2f"),
            onConfirm = { viewModel.confirmDeleteSelection(canWrite) },
            onDismiss = viewModel::dismissDeleteSelection,
            error = deleteProjectionError,
            busy = state.deleteProjectionBusy,
            destructive = true,
        )
    }
}

@Composable
private fun CashLedgerTableHeader() {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(SemanticIds.ledgerTableHeader),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.weight(0.05f))
        FinanceTableCell(localizedText("ledger_column_occurred_at"), weight = 0.12f, header = true, modifier = Modifier.testTag(SemanticIds.ledgerColumnDate))
        FinanceTableCell(localizedText("copy_c3d92b20c8"), weight = 0.15f, header = true, modifier = Modifier.testTag(SemanticIds.ledgerColumnAccount))
        FinanceTableCell(localizedText("ledger_column_transaction_info"), weight = 0.24f, header = true, modifier = Modifier.testTag(SemanticIds.ledgerColumnTransactionInfo))
        FinanceTableCell(localizedText("copy_435c5259e4"), weight = 0.12f, header = true, modifier = Modifier.testTag(SemanticIds.ledgerColumnCategory))
        FinanceTableCell(localizedText("copy_e81089915c"), weight = 0.11f, header = true, modifier = Modifier.testTag(SemanticIds.ledgerColumnEconomicType))
        FinanceTableCell(localizedText("copy_34943c40c9"), weight = 0.13f, header = true, textAlign = TextAlign.End, modifier = Modifier.testTag(SemanticIds.ledgerColumnAmount))
        Spacer(Modifier.weight(0.08f))
    }
    HorizontalDivider()
}

@Composable
private fun CashProjectionMonthHeader(group: CashProjectionMonthGroup, compact: Boolean) {
    val summaries = group.summary?.currencies.orEmpty()
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag(SemanticIds.ledgerSummary),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (compact) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Text(localMonthLabel(group.month), style = MaterialTheme.typography.titleMedium)
                if (summaries.isEmpty()) {
                    Text(localizedText("copy_9c522d837a"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        summaries.forEach { summary ->
                            Text(summary.currency, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(localizedText("copy_b2dfe7c9d3", displaySignedSummaryAmount("income", summary.income)), style = MaterialTheme.typography.bodySmall.withTabularNumbers(), color = MaterialTheme.colorScheme.primary)
                            Text(localizedText("copy_bdbfa1d0c9", displaySignedSummaryAmount("expense", summary.expense)), style = MaterialTheme.typography.bodySmall.withTabularNumbers(), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(localMonthLabel(group.month), style = MaterialTheme.typography.titleMedium)
                if (summaries.isEmpty()) {
                    Text(localizedText("copy_9c522d837a"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        summaries.forEach { summary ->
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(summary.currency, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(localizedText("copy_b2dfe7c9d3", displaySignedSummaryAmount("income", summary.income)), style = MaterialTheme.typography.bodySmall.withTabularNumbers(), color = MaterialTheme.colorScheme.primary)
                                Text(localizedText("copy_bdbfa1d0c9", displaySignedSummaryAmount("expense", summary.expense)), style = MaterialTheme.typography.bodySmall.withTabularNumbers(), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ledgerErrorText(code: String): String = userError(
    DomainFailure(
        code = code,
        status = 0,
        category = if (code == "unknown_error") FailureCategory.UNKNOWN else FailureCategory.RECOVERABLE,
    ),
)

private fun ledgerPagingErrorCode(cause: Throwable): String = when (cause) {
    is DomainFailure -> if (cause.category == FailureCategory.RECOVERABLE) cause.code else "unknown_error"
    else -> "unknown_error"
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun CashFilterPanel(
    value: CashFilters,
    sizeClass: WindowSizeClass,
    onChange: (CashFilters) -> Unit,
    accounts: List<Account>,
    categories: List<CashCategory>,
    currencies: List<String>,
    typeOptions: List<CashEconomicTypeFilterOption>,
    onReset: () -> Unit,
    onInvalidAmount: () -> Unit,
) {
    val compact = sizeClass == WindowSizeClass.COMPACT
    var detailedVisible by remember { mutableStateOf(false) }
    val detailedFiltersLabel = localizedText("copy_dcce9a144a")
    val filterSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val selectedType = when {
        value.economicType == "bank_security_transfer" -> "internal_transfer"
        value.economicType.isNullOrEmpty() && !value.transferSubtype.isNullOrEmpty() -> "internal_transfer"
        else -> value.economicType.orEmpty()
    }
    val orderedTypes = listOf("expense", "income", "internal_transfer")
    val closeDetailedFilters: () -> Unit = {
        detailedVisible = false
    }

    Column(
        modifier = Modifier.fillMaxWidth().testTag(SemanticIds.ledgerFilters),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = value.counterparty.orEmpty(),
            onValueChange = { onChange(value.copy(counterparty = it.ifBlank { null })) },
            modifier = Modifier.fillMaxWidth()
                .accessibleTextFieldLabel(localizedText("ledger_search_counterparty"))
                .testTag(SemanticIds.ledgerSearch),
            placeholder = { Text(localizedText("ledger_search_counterparty")) },
            leadingIcon = {
                Icon(
                    painter = painterResource(Res.drawable.ic_search),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            singleLine = true,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilterChip(
                selected = selectedType.isEmpty(),
                onClick = { onChange(value.copy(economicType = null, transferSubtype = null)) },
                label = { Text(localizedText("ledger_quick_all")) },
                modifier = Modifier.testTag("${SemanticIds.ledgerQuickType}-all"),
            )
            orderedTypes.forEach { economicType ->
                FilterChip(
                    selected = selectedType == economicType,
                    onClick = { onChange(value.copy(economicType = economicType, transferSubtype = null)) },
                    label = { Text(cashEconomicTypeQuickFilterLabel(economicType)) },
                    modifier = Modifier.testTag("${SemanticIds.ledgerQuickType}-$economicType"),
                )
            }
            TextButton(
                onClick = { detailedVisible = true },
                modifier = Modifier.testTag(SemanticIds.ledgerMoreFilters),
            ) {
                Text(detailedFiltersLabel)
                Icon(
                    painter = painterResource(Res.drawable.ic_keyboard_arrow_down),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (detailedVisible && compact) {
        PlatformModalEscapeHandler(enabled = isWebPlatform, onEscape = closeDetailedFilters)
        ModalBottomSheet(
            onDismissRequest = closeDetailedFilters,
            sheetState = filterSheetState,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            dragHandle = { BottomSheetDefaults.DragHandle() },
        ) {
            CashDetailedFilters(
                value = value,
                sizeClass = sizeClass,
                onChange = onChange,
                accounts = accounts,
                categories = categories,
                currencies = currencies,
                typeOptions = typeOptions,
                onReset = onReset,
                onClose = closeDetailedFilters,
                onInvalidAmount = onInvalidAmount,
                modifier = Modifier.fillMaxWidth().heightIn(max = 700.dp),
            )
        }
    } else if (detailedVisible) {
        PlatformModalEscapeHandler(enabled = isWebPlatform, onEscape = closeDetailedFilters)
        Dialog(
            onDismissRequest = closeDetailedFilters,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = !isWebPlatform,
                dismissOnClickOutside = true,
            ),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (isWebPlatform) {
                    Box(
                        Modifier.fillMaxSize()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.38f))
                            .pointerInput(Unit) { detectTapGestures(onTap = { closeDetailedFilters() }) },
                    )
                }
                Surface(
                    modifier = Modifier.widthIn(max = 720.dp)
                    .fillMaxWidth(0.92f)
                    .heightIn(max = 760.dp)
                        .semantics { contentDescription = detailedFiltersLabel },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 0.dp,
                ) {
                    CashDetailedFilters(
                        value = value,
                        sizeClass = sizeClass,
                        onChange = onChange,
                        accounts = accounts,
                        categories = categories,
                        currencies = currencies,
                        typeOptions = typeOptions,
                        onReset = onReset,
                        onClose = closeDetailedFilters,
                        onInvalidAmount = onInvalidAmount,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun cashEconomicTypeQuickFilterLabel(value: String): String = when (value) {
    "expense" -> localizedText("ledger_quick_expense")
    "income" -> localizedText("ledger_quick_income")
    "internal_transfer" -> localizedText("ledger_quick_transfer")
    else -> economicTypeLabel(value)
}

@Composable
private fun CashDetailedFilters(
    value: CashFilters,
    sizeClass: WindowSizeClass,
    onChange: (CashFilters) -> Unit,
    accounts: List<Account>,
    categories: List<CashCategory>,
    currencies: List<String>,
    typeOptions: List<CashEconomicTypeFilterOption>,
    onReset: () -> Unit,
    onClose: () -> Unit,
    onInvalidAmount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val compact = sizeClass == WindowSizeClass.COMPACT
    val accountFilter: @Composable (Modifier) -> Unit = { fieldModifier ->
        ChoicePicker(localizedText("copy_c3d92b20c8"), value.accountId.orEmpty(), listOf("" to localizedText("copy_c5e5d57837")) + accounts.map { it.id.toString() to it.name }, { onChange(value.copy(accountId = it.ifEmpty { null })) }, modifier = fieldModifier, semanticId = SemanticIds.ledgerFilterAccount)
    }
    val categoryFilter: @Composable (Modifier) -> Unit = { fieldModifier ->
        ChoicePicker(localizedText("copy_435c5259e4"), value.categoryId.orEmpty(), listOf("" to localizedText("copy_a8e369c4b6"), "__uncategorized__" to localizedText("copy_f11956caf6")) + categories.map { it.id to cashCategoryDisplayPath(it) }, {
            onChange(value.copy(categoryId = if (it.isEmpty() || it == "__uncategorized__") null else it, uncategorized = if (it == "__uncategorized__") "true" else null))
        }, modifier = fieldModifier, semanticId = SemanticIds.ledgerFilterCategory)
    }
    val currenciesWithCurrent = (listOfNotNull(value.currency) + currencies).distinct()
    val currencyFilter: @Composable (Modifier) -> Unit = { fieldModifier ->
        ChoicePicker(localizedText("copy_a81ab5e100"), value.currency.orEmpty(), listOf("" to localizedText("copy_7f566e2869")) + currenciesWithCurrent.map { it to it }, { onChange(value.copy(currency = it.ifEmpty { null })) }, modifier = fieldModifier, semanticId = SemanticIds.ledgerFilterCurrency)
    }
    val amountMinFilter: @Composable (Modifier) -> Unit = { fieldModifier ->
        val invalid = value.amountMin != null && !isExactDecimalString(value.amountMin)
        OutlinedTextField(
            value = value.amountMin.orEmpty(),
            onValueChange = { onChange(value.copy(amountMin = it.ifBlank { null })) },
            modifier = fieldModifier
                .onFocusLost(onFocusLost = { if (invalid) onInvalidAmount() })
                .accessibleTextFieldLabel(localizedText("copy_d1e673ac4e")),
            label = { Text(localizedText("copy_d1e673ac4e")) },
            isError = invalid,
            singleLine = true,
        )
    }
    val amountMaxFilter: @Composable (Modifier) -> Unit = { fieldModifier ->
        val invalid = value.amountMax != null && !isExactDecimalString(value.amountMax)
        OutlinedTextField(
            value = value.amountMax.orEmpty(),
            onValueChange = { onChange(value.copy(amountMax = it.ifBlank { null })) },
            modifier = fieldModifier
                .onFocusLost(onFocusLost = { if (invalid) onInvalidAmount() })
                .accessibleTextFieldLabel(localizedText("copy_e84edb075b")),
            label = { Text(localizedText("copy_e84edb075b")) },
            isError = invalid,
            singleLine = true,
        )
    }
    val normalizedEconomicType = if (value.economicType == "bank_security_transfer") "internal_transfer" else value.economicType
    val transferSubtypes = typeOptions.firstOrNull { it.economicType == normalizedEconomicType }?.transferSubtypes.orEmpty()

    Column(
        modifier = modifier.fillMaxWidth()
            .then(if (compact) Modifier.fillMaxHeight() else Modifier.wrapContentHeight())
            .testTag(SemanticIds.ledgerFilterDetails),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(localizedText("copy_dcce9a144a"), style = MaterialTheme.typography.titleLarge)
        }
        HorizontalDivider()
        Column(
            modifier = (if (compact) Modifier.weight(1f) else Modifier).fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (compact) {
                DatePickerInput(value.dateFrom.orEmpty(), { onChange(value.copy(dateFrom = it.ifBlank { null })) }, localizedText("copy_1f29196891"))
                DatePickerInput(value.dateTo.orEmpty(), { onChange(value.copy(dateTo = it.ifBlank { null })) }, localizedText("copy_f4b9b2b5de"))
                accountFilter(Modifier)
                categoryFilter(Modifier)
                currencyFilter(Modifier)
                amountMinFilter(Modifier.fillMaxWidth())
                amountMaxFilter(Modifier.fillMaxWidth())
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    DatePickerInput(value.dateFrom.orEmpty(), { onChange(value.copy(dateFrom = it.ifBlank { null })) }, localizedText("copy_1f29196891"), modifier = Modifier.weight(1f))
                    DatePickerInput(value.dateTo.orEmpty(), { onChange(value.copy(dateTo = it.ifBlank { null })) }, localizedText("copy_f4b9b2b5de"), modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    accountFilter(Modifier.weight(1f))
                    categoryFilter(Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    currencyFilter(Modifier.weight(1f))
                    amountMinFilter(Modifier.weight(1f))
                    amountMaxFilter(Modifier.weight(1f))
                }
            }
            if (transferSubtypes.isNotEmpty()) {
                ChoicePicker(
                    localizedText("copy_82fa0e654c"),
                    value.transferSubtype.orEmpty(),
                    listOf("" to localizedText("copy_778fc8f994")) + transferSubtypes.map { it to transferSubtypeLabel(it) },
                    { onChange(value.copy(transferSubtype = it.ifEmpty { null })) },
                )
            }
            ChoicePicker(
                localizedText("copy_cc498b84ff"),
                value.composition.orEmpty(),
                listOf("" to localizedText("copy_778fc8f994"), "single" to localizedText("copy_4a70b7e986"), "payment_mirror" to localizedText("copy_2b6feb78fe"), "refund_offset" to localizedText("copy_b441aa2b9b"), "combined" to localizedText("copy_e5847fcd94")),
                { onChange(value.copy(composition = it.ifEmpty { null })) },
            )
        }
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onReset) { Text(localizedText("copy_7b15e5e8e7")) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClose) { Text(localizedText("ledger_filter_done")) }
        }
    }
}

@Composable
private fun CashProjectionRow(
    item: CashProjection,
    checked: Boolean,
    canWrite: Boolean,
    sizeClass: WindowSizeClass,
    selectionMode: Boolean,
    onChecked: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    val recordModifier = Modifier.testTag("${SemanticIds.ledgerRecord}-${item.projectionId}")
    if (sizeClass == WindowSizeClass.COMPACT) {
        SectionCard(
            modifier = recordModifier.combinedClickable(
                onClick = { if (selectionMode) onChecked(!checked) else onOpen() },
                onLongClick = { if (canWrite && !selectionMode) onChecked(true) },
            ),
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (selectionMode) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = onChecked,
                        enabled = canWrite,
                        modifier = Modifier.testTag("${SemanticIds.ledgerRecordCheckbox}-${item.projectionId}"),
                    )
                }
                Column(
                    Modifier.weight(1f).testTag(SemanticIds.ledgerOpenRecord),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(item.counterparty.ifBlank { "—" }, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(item.note.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(localDateTimeDisplayLabel(item.occurredAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${cashProjectionAccountLabel(item)} · ${cashCategoryDisplayPath(item.category)}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val source = cashProjectionSourceLabel(item)
                    Text(
                        listOfNotNull(cashProjectionEconomicTypeLabel(item), source).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FinanceAmount(cashProjectionAmountLabel(item), negative = item.amount.startsWith("-"))
            }
        }
    } else {
        Column(recordModifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(0.05f), contentAlignment = Alignment.Center) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = onChecked,
                        enabled = canWrite,
                        modifier = Modifier.testTag("${SemanticIds.ledgerRecordCheckbox}-${item.projectionId}"),
                    )
                }
                FinanceTableCell(localDateTimeDisplayLabel(item.occurredAt), weight = 0.12f)
                FinanceTableCell(cashProjectionAccountLabel(item), weight = 0.15f)
                Column(
                    Modifier.weight(0.24f).padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        item.counterparty.ifBlank { "—" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        item.note.ifBlank { "—" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FinanceTableCell(cashCategoryDisplayPath(item.category), weight = 0.12f)
                Column(
                    Modifier.weight(0.11f).padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(cashProjectionEconomicTypeLabel(item), style = MaterialTheme.typography.bodySmall)
                    cashProjectionSourceLabel(item)?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                FinanceAmount(
                    cashProjectionAmountLabel(item),
                    modifier = Modifier.weight(0.13f).padding(horizontal = 8.dp),
                    negative = item.amount.startsWith("-"),
                    textAlign = TextAlign.End,
                )
                Box(Modifier.weight(0.08f), contentAlignment = Alignment.CenterEnd) {
                    IconButton(
                        onClick = onOpen,
                        modifier = Modifier.testTag(SemanticIds.ledgerOpenRecord),
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_visibility),
                            contentDescription = localizedText("copy_faea8c1db9"),
                        )
                    }
                }
            }
            HorizontalDivider()
        }
    }
}
