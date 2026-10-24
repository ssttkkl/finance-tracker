package com.finance.tracker.presentation

import com.finance.tracker.core.*
import com.finance.tracker.domain.*
import com.finance.tracker.resources.Res
import com.finance.tracker.resources.ic_add
import com.finance.tracker.resources.ic_check
import com.finance.tracker.resources.ic_checklist
import com.finance.tracker.resources.ic_close
import com.finance.tracker.resources.ic_delete
import com.finance.tracker.resources.ic_file_download
import com.finance.tracker.resources.ic_keyboard_arrow_down
import com.finance.tracker.resources.ic_label
import com.finance.tracker.resources.ic_link
import com.finance.tracker.resources.ic_link_off
import com.finance.tracker.resources.ic_search
import com.finance.tracker.resources.ic_visibility

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun CashLedgerScreen(
    repository: FinanceRepository,
    session: Session,
    sizeClass: WindowSizeClass,
    onNavigate: (AppPage) -> Unit,
    diagnostics: DiagnosticLogger = NoOpDiagnosticLogger,
    cashLedgerReloadRevision: Int = 0,
    maintenanceRepository: CashMaintenanceRepository,
    maintenanceRecovery: CashMaintenanceRecoveryStore,
) {
    val viewModel: CashLedgerViewModel = viewModel(
        key = "cash-ledger:${session.user.email}:${session.activeWorkspaceId.orEmpty()}:${repository.hashCode()}",
        factory = viewModelFactory { initializer { CashLedgerViewModel(repository, repository, diagnostics = diagnostics) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentTask: CashRecordTaskViewModel = viewModel(
        key = "cash-current:${session.user.email}:${session.activeWorkspaceId}:${maintenanceRepository.hashCode()}",
        factory = viewModelFactory { initializer { CashRecordTaskViewModel(maintenanceRepository, maintenanceRecovery) } },
    )
    val currentState by currentTask.state.collectAsStateWithLifecycle()
    var showCurrentDetail by remember(currentTask) { mutableStateOf(false) }
    var currentProjection by remember(currentTask) { mutableStateOf<CashProjection?>(null) }
    var currentEvidence by remember(currentTask) { mutableStateOf<CashSourceEvidence?>(null) }
    var sourceError by remember(currentTask) { mutableStateOf<String?>(null) }
    val currentScope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(currentTask) { currentTask.restore() }
    LaunchedEffect(currentState.recoveryReady) { if (currentState.unresolved) showCurrentDetail = true }
    LaunchedEffect(currentState.savedRevision) {
        if (currentState.savedRevision > 0) {
            viewModel.reload()
            currentEvidence = null
        }
    }
    LaunchedEffect(currentState.record?.projectionRef) {
        currentState.record?.projectionRef?.let { ref ->
            if (currentProjection?.projectionVersion != ref.projectionVersion || currentProjection?.projectionId != ref.projectionId) {
                try {
                    val evidence = maintenanceRepository.sources(ref.projectionId)
                    if (evidence.projectionVersion != ref.projectionVersion) throw DomainFailure("record_changed", 409, FailureCategory.RECOVERABLE)
                    currentProjection = evidence.projection
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { currentProjection = null }
            }
        }
    }
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
    var relatedSectionExpanded by remember(state.selectedRow?.recordId) { mutableStateOf(false) }

    val canWrite = session.workspaces.firstOrNull { it.id == session.activeWorkspaceId }?.role?.canWrite == true
    val referencesError = state.referencesErrorCode?.let { ledgerErrorText(it) }
    val refreshError = (pagedRows.loadState.refresh as? LoadState.Error)?.error?.let { ledgerErrorText(ledgerPagingErrorCode(it)) }
    val appendError = (pagedRows.loadState.append as? LoadState.Error)?.error?.let { ledgerErrorText(ledgerPagingErrorCode(it)) }
    val classifyError = state.classifyErrorCode?.let { ledgerErrorText(it) }
    val deleteProjectionError = state.deleteProjectionErrorCode?.let { ledgerErrorText(it) }
    val recordSaveError = state.recordSaveErrorCode?.let { ledgerErrorText(it) }
    val detailActionError = state.detailActionErrorCode?.let { ledgerErrorText(it) }
    var actionsMenuExpanded by remember { mutableStateOf(false) }
    var batchCategoryDialogOpen by remember { mutableStateOf(false) }
    var batchCategoryDraft by remember(batchCategoryDialogOpen) { mutableStateOf(state.selectedCategory) }
    val selectionMode = state.selectedIds.isNotEmpty()
    LaunchedEffect(selectionMode) {
        actionsMenuExpanded = selectionMode
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
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(GeneratedTokens.Core.Space.Token4)) {
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
            }
        }
        val pendingItemCount = state.page?.pendingItemCount ?: 0
        if (pendingItemCount > 0 || state.filters.confirmationStatus == "pending") {
            FilterChip(
                selected = state.filters.confirmationStatus == "pending",
                onClick = { viewModel.updateFilters(state.filters.copy(confirmationStatus = if (state.filters.confirmationStatus == "pending") null else "pending")) },
                label = { Text(if (pendingItemCount > 0) localizedText("cash_pending_count", pendingItemCount.toString()) else localizedText("cash_confirmation_pending")) },
                modifier = Modifier.testTag("ledger.filter.pending"),
            )
        }
        val summariesByMonth = state.page?.monthlySummaries.orEmpty().associateBy(CashMonthlySummary::month)
        val appendReachedEnd = (pagedRows.loadState.append as? LoadState.NotLoading)?.endOfPaginationReached == true
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f).testTag(SemanticIds.ledgerList),
            contentPadding = PaddingValues(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
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
            items(
                count = pagedRows.itemCount,
                key = pagedRows.itemKey { it.projectionId },
            ) { index ->
                val row = pagedRows[index]
                if (row != null) {
                    val month = localMonthKey(row.occurredAt)
                    val previousMonth = if (index == 0) null else pagedRows.peek(index - 1)?.let { localMonthKey(it.occurredAt) }
                    val nextMonth = if (index == pagedRows.itemCount - 1) null else pagedRows.peek(index + 1)?.let { localMonthKey(it.occurredAt) }
                    val monthStart = month != previousMonth
                    val monthEnd = if (nextMonth != null) {
                        month != nextMonth
                    } else {
                        index == pagedRows.itemCount - 1 && (appendReachedEnd || pagedRows.loadState.append is LoadState.Loading || appendError != null)
                    }
                    val baseShape = MaterialTheme.shapes.medium
                    val monthShape = RoundedCornerShape(
                        topStart = if (monthStart) baseShape.topStart else CornerSize(0.dp),
                        topEnd = if (monthStart) baseShape.topEnd else CornerSize(0.dp),
                        bottomEnd = if (monthEnd) baseShape.bottomEnd else CornerSize(0.dp),
                        bottomStart = if (monthEnd) baseShape.bottomStart else CornerSize(0.dp),
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(top = if (monthStart && index > 0) GeneratedTokens.Core.Space.Token3 else 0.dp),
                        shape = monthShape,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        tonalElevation = 0.dp,
                    ) {
                        Column(Modifier.fillMaxWidth().testTag("${SemanticIds.ledgerTable}-$month")) {
                            if (monthStart) {
                                CashProjectionMonthHeader(
                                    group = CashProjectionMonthGroup(month, emptyList(), summariesByMonth[month]),
                                    compact = sizeClass == WindowSizeClass.COMPACT,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                                if (sizeClass != WindowSizeClass.COMPACT) {
                                    CashLedgerTableHeader(modifier = Modifier.padding(horizontal = 16.dp))
                                }
                            }
                            CashProjectionRow(
                                item = row,
                                checked = row.projectionId in state.selectedIds,
                                canWrite = canWrite,
                                sizeClass = sizeClass,
                                selectionMode = selectionMode,
                                lastInMonth = monthEnd,
                                onChecked = { viewModel.toggleSelection(row.projectionId, it) },
                                onOpen = {
                                    if (!currentState.unresolved) {
                                        currentProjection = row; currentEvidence = null; sourceError = null
                                        currentTask.open(row.transactionId)
                                    }
                                    showCurrentDetail = true
                                },
                            )
                        }
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

        FloatingActionButtonMenu(
            expanded = actionsMenuExpanded,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp),
            button = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (selectionMode) {
                        Surface(
                            shape = MaterialTheme.shapes.extraLarge,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ) {
                            Text(
                                localizedText("ledger_selection_count", state.selectedIds.size.toString()),
                                modifier = Modifier.testTag(SemanticIds.ledgerSelectedCount).padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                    ToggleFloatingActionButton(
                        checked = actionsMenuExpanded,
                        onCheckedChange = { if (canWrite) actionsMenuExpanded = it },
                        modifier = Modifier
                            .testTag(SemanticIds.ledgerActionsFab)
                            .semantics { if (!canWrite) disabled() },
                    ) {
                        Icon(
                            painter = painterResource(
                                when {
                                    selectionMode -> Res.drawable.ic_checklist
                                    actionsMenuExpanded -> Res.drawable.ic_close
                                    else -> Res.drawable.ic_add
                                },
                            ),
                            contentDescription = localizedText("ledger_actions"),
                        )
                    }
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

    if (showCurrentDetail) {
        CashCurrentRecordPanel(currentState, currentProjection, sizeClass, currentEvidence, sourceError,
            onLoadSources = {
                val ref = currentState.record?.projectionRef?.projectionId ?: currentProjection?.projectionId
                val target = currentState.transactionId
                val version = currentState.record?.projectionRef?.projectionVersion
                if (sourceError == "record_changed" && target != null) {
                    sourceError = null; currentEvidence = null; currentTask.open(target)
                } else if (ref != null) currentScope.launch {
                    sourceError = null
                    try {
                        val evidence = maintenanceRepository.sources(ref)
                        if (evidence.projectionVersion != version) throw DomainFailure("record_changed", 409, FailureCategory.RECOVERABLE)
                        if (currentTask.state.value.transactionId == target && currentTask.state.value.record?.projectionRef?.projectionVersion == version) currentEvidence = evidence
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (failure: Exception) { if (currentTask.state.value.transactionId == target) sourceError = (failure as? DomainFailure)?.code ?: "request_failed" }
                }
            },
            onRetry = { currentState.transactionId?.let(currentTask::open) },
            onResolve = currentTask::resolve,
            onRetryOriginal = currentTask::retryOriginal,
            onClose = { if (currentTask.close()) showCurrentDetail = false },
            onResolveIssue = if (canWrite) currentTask::resolveIssue else null,
            onSaveDraft = if (canWrite) ({ patch, finalize ->
                if (finalize) currentState.record?.let { currentTask.prepare(CashMaintenanceRequest("finalize_draft", it.transactionId, it.rowRevision, patch)) }
                else currentTask.saveDraft(patch)
            }) else null,
            onBeginRelations = if (canWrite) currentTask::beginRelations else null,
            onSaveRelationChoices = currentTask::saveRelationChoices,
            onConfirmPlan = currentTask::confirmPlan,
            onCancelPlan = currentTask::dismissPlan,
            onResolveMemberIssue = if (canWrite) currentTask::resolveMemberIssue else null,
            onOpenMember = currentTask::open,
            accounts = state.accounts,
            categories = state.categories,
            options = state.options,
        )
    }

    PlatformModalEscapeHandler(
        enabled = isWebPlatform && batchCategoryDialogOpen,
        onEscape = { batchCategoryDialogOpen = false },
    )
    if (batchCategoryDialogOpen) {
        AlertDialog(
            modifier = Modifier.testTag(SemanticIds.ledgerCategoryDialog),
            onDismissRequest = { batchCategoryDialogOpen = false },
            title = { Text(localizedText("ledger_category_dialog_title")) },
            text = {
                ChoicePicker(
                    label = localizedText("ledger_category_label"),
                    value = batchCategoryDraft,
                    options = listOf("" to localizedText("copy_3759bf861f"), UNCATEGORIZED_SELECTION to localizedText("copy_f11956caf6")) + state.categories.map { it.id to cashCategoryDisplayPath(it) },
                    onSelected = { batchCategoryDraft = it },
                    enabled = canWrite && !state.classifyBusy,
                    semanticId = SemanticIds.ledgerCategoryPicker,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        batchCategoryDialogOpen = false
                        viewModel.setSelectedCategory(batchCategoryDraft)
                        viewModel.classifySelection(canWrite)
                    },
                    enabled = canWrite && !state.classifyBusy && batchCategoryDraft.isNotEmpty(),
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

    if (classifyError != null) {
        PlatformModalEscapeHandler(enabled = isWebPlatform, onEscape = viewModel::dismissClassifyError)
        AlertDialog(
            modifier = Modifier.testTag("ledger.category.error-dialog"),
            onDismissRequest = viewModel::dismissClassifyError,
            title = { Text(localizedText("ledger_category_error_title")) },
            text = { Text(classifyError) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissClassifyError, modifier = Modifier.testTag("ledger.category.error-acknowledge")) {
                    Text(localizedText("dialog_error_acknowledge"))
                }
            },
        )
    } else if (deleteProjectionError != null && state.deleteProjectionImpact == null) {
        PlatformModalEscapeHandler(enabled = isWebPlatform, onEscape = viewModel::dismissDeleteProjectionError)
        AlertDialog(
            modifier = Modifier.testTag("ledger.delete.error-dialog"),
            onDismissRequest = viewModel::dismissDeleteProjectionError,
            title = { Text(localizedText("ledger_delete_error_title")) },
            text = { Text(deleteProjectionError) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissDeleteProjectionError, modifier = Modifier.testTag("ledger.delete.error-acknowledge")) {
                    Text(localizedText("dialog_error_acknowledge"))
                }
            },
        )
    }

    val hasRecordConfirmation = state.confirmRecordDiscard || state.confirmRelationImpact ||
        state.deleteRecordConfirm || state.pendingRelationRemovalId != null || state.pendingRelationDissolveAll
    val hasRecordAcknowledgement = recordSaveError != null || detailActionError != null
    val showRecordTaskPanel = recordPanelOpen && !hasRecordConfirmation && !hasRecordAcknowledgement && !state.relationComposerOpen
    val showRelationComposer = recordPanelOpen && state.relationComposerOpen && !hasRecordConfirmation && !hasRecordAcknowledgement

    if (showRecordTaskPanel) {
        val draft = state.recordDraft
        val compactFullScreen = sizeClass == WindowSizeClass.COMPACT
        val panelTitle = when {
            draft?.id != null -> localizedText("copy_6a5c4515c6")
            draft != null -> localizedText("copy_1f62e3f53f")
            else -> localizedText("copy_8cb72a44a5")
        }
        CashRecordModalPanel(
            sizeClass = sizeClass,
            title = panelTitle,
            compactFullScreen = compactFullScreen,
            semanticStateKey = if (draft == null) "record-detail" else "record-editor",
            onDismiss = viewModel::dismissRecordPanel,
            onBack = viewModel::dismissRecordPanel,
            footer = when {
                draft == null && !state.recordLoading && state.recordDetail?.record != null -> ({
                    CashRecordDetailActions(state, viewModel, canWrite, viewModel::editRecord)
                })
                draft != null -> ({
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = viewModel::cancelRecordEdit, modifier = Modifier.testTag(SemanticIds.recordCancel)) {
                            Text(localizedText("copy_4d0b4688c7"))
                        }
                        Button(
                            onClick = { viewModel.saveRecord(canWrite) },
                            enabled = canWrite && draft.amount.isNotBlank() && isExactDecimalString(draft.amount) &&
                                draft.accountName.isNotBlank() && draft.recordType.isNotBlank() && isValidLocalDateTime(draft.occurredAt),
                            modifier = Modifier.testTag(SemanticIds.recordSave),
                        ) {
                            Icon(painterResource(Res.drawable.ic_check), contentDescription = null)
                            Text(if (state.recordBusy) localizedText("copy_6644f06197") else localizedText("copy_fadf24dbc5"))
                        }
                    }
                })
                else -> null
            },
        ) {
            if (draft != null) {
                CashRecordEditorContent(
                    draft = draft,
                    sizeClass = sizeClass,
                    canWrite = canWrite,
                    accounts = state.accounts,
                    categories = state.categories,
                    options = state.options,
                    busy = state.recordBusy,
                    error = null,
                    onChange = viewModel::updateRecordDraft,
                    onInvalidAmount = viewModel::reportInvalidRecordAmount,
                    onInvalidDateTime = viewModel::reportInvalidRecordDateTime,
                )
            } else {
                CashRecordDetailContent(
                    state = state,
                    viewModel = viewModel,
                    canWrite = canWrite,
                    options = state.options,
                    relatedExpanded = relatedSectionExpanded,
                    onToggleRelated = { relatedSectionExpanded = !relatedSectionExpanded },
                    onEditRelated = viewModel::editRecord,
                )
            }
        }
    }

    if (showRelationComposer) {
        val compactFullScreen = sizeClass == WindowSizeClass.COMPACT
        CashRecordModalPanel(
            sizeClass = sizeClass,
            title = localizedText("copy_f1c5c548ba"),
            compactFullScreen = compactFullScreen,
            semanticStateKey = "relation-composer",
            onDismiss = viewModel::closeRelationComposer,
            onBack = viewModel::closeRelationComposer,
            footer = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = viewModel::closeRelationComposer, modifier = Modifier.testTag("record.relation-create.cancel")) {
                        Text(localizedText("copy_4d0b4688c7"))
                    }
                    Button(
                        onClick = { viewModel.createRelation(canWrite) },
                        enabled = canWrite && state.relationTarget != null,
                        modifier = Modifier.testTag("record.relation-create.confirm"),
                    ) {
                        Icon(painterResource(Res.drawable.ic_link), contentDescription = null)
                        Text(if (state.relationBusy) localizedText("copy_12c5c83f56") else localizedText("copy_8120c99c99"))
                    }
                }
            },
        ) {
            CashRecordRelationComposerContent(
                state = state,
                viewModel = viewModel,
                canWrite = canWrite,
                options = state.options,
            )
        }
    }

    if (recordPanelOpen && state.confirmRecordDiscard) {
        PlatformModalEscapeHandler(enabled = isWebPlatform, onEscape = viewModel::dismissRecordDiscardConfirmation)
        AlertDialog(
            modifier = Modifier.testTag("ledger.record.discard-dialog"),
            onDismissRequest = viewModel::dismissRecordDiscardConfirmation,
            title = { Text(localizedText("ledger_record_discard_title")) },
            text = { Text(localizedText("ledger_record_discard_message")) },
            confirmButton = {
                TextButton(
                    onClick = viewModel::confirmRecordDiscard,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("ledger.record.discard-confirm"),
                ) { Text(localizedText("ledger_record_discard_confirm")) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissRecordDiscardConfirmation, modifier = Modifier.testTag("ledger.record.discard-continue")) {
                    Text(localizedText("ledger_record_discard_continue"))
                }
            },
        )
    } else if (recordPanelOpen && state.deleteRecordConfirm && state.recordDetail?.record != null) {
        val recordDetail = requireNotNull(state.recordDetail)
        val hasRelations = recordDetail.relations.any { it.status == "accepted" }
        PlatformModalEscapeHandler(enabled = isWebPlatform, onEscape = viewModel::dismissDeleteRecord)
        AlertDialog(
            modifier = Modifier.testTag("ledger.record.delete-dialog"),
            onDismissRequest = viewModel::dismissDeleteRecord,
            title = { Text(localizedText("copy_a995e3b53e")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (hasRelations) localizedText("copy_7a51756482") else localizedText("copy_42ece48726"))
                    if (hasRelations) {
                        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                Modifier.fillMaxWidth().selectable(
                                    selected = state.deleteRecordMode == "delete_current_dissolve",
                                    onClick = { viewModel.selectDeleteRecordMode("delete_current_dissolve") },
                                    role = Role.RadioButton,
                                ).testTag("record.delete.scope.current"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = state.deleteRecordMode == "delete_current_dissolve", onClick = null)
                                Text(localizedText("copy_6f762bedaa"))
                            }
                            Row(
                                Modifier.fillMaxWidth().selectable(
                                    selected = state.deleteRecordMode == "delete_all",
                                    onClick = { viewModel.selectDeleteRecordMode("delete_all") },
                                    role = Role.RadioButton,
                                ).testTag("record.delete.scope.group"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = state.deleteRecordMode == "delete_all", onClick = null)
                                Text(localizedText("copy_22edecb09e"))
                            }
                        }
                    }
                    InlineError(detailActionError)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.deleteRecord(state.deleteRecordMode, canWrite) },
                    enabled = canWrite,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("record.delete.confirm"),
                ) {
                    Text(if (state.deleteRecordBusy) localizedText("copy_1cac8ac7f5") else localizedText("copy_3c06abe116"))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDeleteRecord, modifier = Modifier.testTag("record.delete.cancel")) {
                    Text(localizedText("copy_4d0b4688c7"))
                }
            },
        )
    } else if (recordPanelOpen && (state.pendingRelationRemovalId != null || state.pendingRelationDissolveAll)) {
        val pendingRelation = state.recordDetail?.relations?.firstOrNull { it.id == state.pendingRelationRemovalId }
        val relatedRecord = pendingRelation?.let { relation ->
            if (relation.primaryRecord?.id == state.recordDetail?.record?.id) relation.secondaryRecord else relation.primaryRecord
        }
        val unlinkingOne = state.pendingRelationRemovalId != null
        val dismiss = viewModel::dismissPendingRelationRemoval
        PlatformModalEscapeHandler(enabled = isWebPlatform, onEscape = dismiss)
        AlertDialog(
            modifier = Modifier.testTag("ledger.record.relation-removal-dialog"),
            onDismissRequest = dismiss,
            title = { Text(localizedText(if (unlinkingOne) "ledger_relation_unlink_title" else "ledger_relation_dissolve_title")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (unlinkingOne) {
                            localizedText(
                                "ledger_relation_unlink_message",
                                relatedRecord?.counterparty?.takeIf(String::isNotBlank)
                                    ?: relatedRecord?.accountName
                                    ?: localizedText("copy_6ea13ee38c"),
                            )
                        } else localizedText("ledger_relation_dissolve_message"),
                    )
                    InlineError(detailActionError)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.confirmPendingRelationRemoval(canWrite) },
                    enabled = canWrite,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("record.relation-removal.confirm"),
                ) {
                    Text(
                        if (state.relationBusy) localizedText(if (unlinkingOne) "ledger_relation_unlinking" else "ledger_relation_dissolving")
                        else localizedText(if (unlinkingOne) "ledger_relation_unlink_confirm" else "ledger_relation_dissolve_confirm"),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = dismiss, modifier = Modifier.testTag("record.relation-removal.cancel")) {
                    Text(localizedText("ledger_relation_keep"))
                }
            },
        )
    } else if (recordPanelOpen && recordSaveError != null) {
        PlatformModalEscapeHandler(enabled = isWebPlatform, onEscape = viewModel::dismissRecordSaveError)
        AlertDialog(
            modifier = Modifier.testTag("ledger.record.save-error-dialog"),
            onDismissRequest = viewModel::dismissRecordSaveError,
            title = { Text(localizedText("ledger_record_save_error_title")) },
            text = { Text(recordSaveError) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissRecordSaveError, modifier = Modifier.testTag("ledger.record.save-error-acknowledge")) {
                    Text(localizedText("dialog_error_acknowledge"))
                }
            },
        )
    } else if (recordPanelOpen && state.confirmRelationImpact) {
        PlatformModalEscapeHandler(enabled = isWebPlatform, onEscape = viewModel::dismissRelationImpactConfirmation)
        AlertDialog(
            modifier = Modifier.testTag("record.relation-impact.dialog"),
            onDismissRequest = viewModel::dismissRelationImpactConfirmation,
            title = { Text(localizedText("copy_e17cb4ec33")) },
            text = { Text(localizedText("copy_24c943b133")) },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.saveRecord(canWrite, confirmImpact = true) },
                    enabled = canWrite,
                    modifier = Modifier.testTag("record.relation-impact.confirm"),
                ) { Text(if (state.recordBusy) localizedText("copy_1cac8ac7f5") else localizedText("copy_2b9228c01b")) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissRelationImpactConfirmation, modifier = Modifier.testTag("record.relation-impact.cancel")) {
                    Text(localizedText("copy_4d0b4688c7"))
                }
            },
        )
    } else if (recordPanelOpen && detailActionError != null) {
        PlatformModalEscapeHandler(enabled = isWebPlatform, onEscape = viewModel::dismissDetailActionError)
        AlertDialog(
            modifier = Modifier.testTag("ledger.record.action-error-dialog"),
            onDismissRequest = viewModel::dismissDetailActionError,
            title = { Text(localizedText("ledger_record_action_error_title")) },
            text = { Text(detailActionError) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissDetailActionError, modifier = Modifier.testTag("ledger.record.action-error-acknowledge")) {
                    Text(localizedText("dialog_error_acknowledge"))
                }
            },
        )
    } else if (!recordPanelOpen && recordSaveError != null) {
        PlatformModalEscapeHandler(enabled = isWebPlatform, onEscape = viewModel::dismissRecordSaveError)
        AlertDialog(
            modifier = Modifier.testTag("ledger.record.save-error-dialog"),
            onDismissRequest = viewModel::dismissRecordSaveError,
            title = { Text(localizedText("ledger_record_save_error_title")) },
            text = { Text(recordSaveError) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissRecordSaveError, modifier = Modifier.testTag("ledger.record.save-error-acknowledge")) {
                    Text(localizedText("dialog_error_acknowledge"))
                }
            },
        )
    } else if (!recordPanelOpen && detailActionError != null) {
        PlatformModalEscapeHandler(enabled = isWebPlatform, onEscape = viewModel::dismissDetailActionError)
        AlertDialog(
            modifier = Modifier.testTag("ledger.record.action-error-dialog"),
            onDismissRequest = viewModel::dismissDetailActionError,
            title = { Text(localizedText("ledger_record_action_error_title")) },
            text = { Text(detailActionError) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissDetailActionError, modifier = Modifier.testTag("ledger.record.action-error-acknowledge")) {
                    Text(localizedText("dialog_error_acknowledge"))
                }
            },
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
            semanticId = "ledger.delete-projection",
        )
    }
}

@Composable
private fun CashLedgerTableHeader(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().testTag(SemanticIds.ledgerTableHeader),
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
private fun CashProjectionMonthHeader(group: CashProjectionMonthGroup, compact: Boolean, modifier: Modifier = Modifier) {
    val summaries = group.summary?.currencies.orEmpty()
    Column(
        modifier = modifier.fillMaxWidth().testTag(SemanticIds.ledgerSummary),
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
    val closeDetailedFilters: () -> Unit = { detailedVisible = false }

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
            horizontalArrangement = Arrangement.spacedBy(4.dp),
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
                contentPadding = PaddingValues(horizontal = 4.dp),
            ) {
                Text(detailedFiltersLabel)
                Icon(
                    painter = painterResource(Res.drawable.ic_keyboard_arrow_down),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
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
            dragHandle = { BottomSheetDefaults.DragHandle() },
            properties = ModalBottomSheetProperties(
                shouldDismissOnBackPress = !isWebPlatform,
                shouldDismissOnClickOutside = true,
            ),
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
                Surface(
                    modifier = Modifier.widthIn(max = 560.dp)
                    .fillMaxWidth(0.92f)
                    .heightIn(max = 760.dp)
                        .semantics { paneTitle = detailedFiltersLabel },
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = AlertDialogDefaults.TonalElevation,
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
private fun CashFilterGroup(
    title: String,
    semanticId: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag(semanticId),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        },
    )
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
    val closeLabel = localizedText("copy_6c14bd7f6f")
    var activeDatePicker by remember { mutableStateOf<String?>(null) }
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
    val normalizedEconomicType = if (value.economicType == "bank_security_transfer") "internal_transfer" else value.economicType
    val transferSubtypes = typeOptions.firstOrNull { it.economicType == normalizedEconomicType }?.transferSubtypes.orEmpty()
    val transferSubtypeFilter: @Composable (Modifier) -> Unit = { fieldModifier ->
        ChoicePicker(
            localizedText("copy_82fa0e654c"),
            value.transferSubtype.orEmpty(),
            listOf("" to localizedText("copy_778fc8f994")) + transferSubtypes.map { it to transferSubtypeLabel(it) },
            { onChange(value.copy(transferSubtype = it.ifEmpty { null })) },
            modifier = fieldModifier,
            semanticId = SemanticIds.ledgerFilterTransferSubtype,
        )
    }
    val compositionFilter: @Composable (Modifier) -> Unit = { fieldModifier ->
        ChoicePicker(
            localizedText("copy_cc498b84ff"),
            value.composition.orEmpty(),
            listOf("" to localizedText("copy_778fc8f994"), "single" to localizedText("copy_4a70b7e986"), "payment_mirror" to localizedText("copy_2b6feb78fe"), "refund_offset" to localizedText("copy_b441aa2b9b"), "combined" to localizedText("copy_e5847fcd94")),
            { onChange(value.copy(composition = it.ifEmpty { null })) },
            modifier = fieldModifier,
            semanticId = SemanticIds.ledgerFilterComposition,
        )
    }
    val amountMinFilter: @Composable (Modifier) -> Unit = { fieldModifier ->
        val invalid = value.amountMin != null && !isExactDecimalString(value.amountMin)
        OutlinedTextField(
            value = value.amountMin.orEmpty(),
            onValueChange = { onChange(value.copy(amountMin = it.ifBlank { null })) },
            modifier = fieldModifier
                .onFocusLost(onFocusLost = { if (invalid) onInvalidAmount() })
                .testTag(SemanticIds.ledgerFilterAmountMinimum)
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
                .testTag(SemanticIds.ledgerFilterAmountMaximum)
                .accessibleTextFieldLabel(localizedText("copy_e84edb075b")),
            label = { Text(localizedText("copy_e84edb075b")) },
            isError = invalid,
            singleLine = true,
        )
    }
    Column(
        modifier = modifier.fillMaxWidth()
            .then(if (compact) Modifier.fillMaxHeight() else Modifier.wrapContentHeight())
            .testTag(SemanticIds.ledgerFilterDetails),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.testTag("ledger.filter.close")
                    .semantics { contentDescription = closeLabel },
            ) {
                Icon(painterResource(Res.drawable.ic_close), contentDescription = null)
            }
            Text(
                localizedText("copy_dcce9a144a"),
                modifier = Modifier.testTag("ledger.filter.title"),
                style = MaterialTheme.typography.titleLarge,
            )
        }
        HorizontalDivider()
        Column(
            modifier = (if (compact) Modifier.weight(1f) else Modifier).fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            CashFilterGroup(
                title = localizedText("ledger_filter_group_time"),
                semanticId = SemanticIds.ledgerFilterGroupTime,
            ) {
                if (compact || activeDatePicker != null) {
                    DatePickerInput(value.dateFrom.orEmpty(), { onChange(value.copy(dateFrom = it.ifBlank { null })) }, localizedText("copy_1f29196891"), semanticId = SemanticIds.ledgerFilterDateFrom, inlinePicker = true, inlinePickerActive = activeDatePicker == "from", onInlinePickerActiveChange = { activeDatePicker = if (it) "from" else null })
                    DatePickerInput(value.dateTo.orEmpty(), { onChange(value.copy(dateTo = it.ifBlank { null })) }, localizedText("copy_f4b9b2b5de"), semanticId = SemanticIds.ledgerFilterDateTo, inlinePicker = true, inlinePickerActive = activeDatePicker == "to", onInlinePickerActiveChange = { activeDatePicker = if (it) "to" else null })
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        DatePickerInput(value.dateFrom.orEmpty(), { onChange(value.copy(dateFrom = it.ifBlank { null })) }, localizedText("copy_1f29196891"), modifier = Modifier.weight(1f), semanticId = SemanticIds.ledgerFilterDateFrom, inlinePicker = true, inlinePickerActive = activeDatePicker == "from", onInlinePickerActiveChange = { activeDatePicker = if (it) "from" else null })
                        DatePickerInput(value.dateTo.orEmpty(), { onChange(value.copy(dateTo = it.ifBlank { null })) }, localizedText("copy_f4b9b2b5de"), modifier = Modifier.weight(1f), semanticId = SemanticIds.ledgerFilterDateTo, inlinePicker = true, inlinePickerActive = activeDatePicker == "to", onInlinePickerActiveChange = { activeDatePicker = if (it) "to" else null })
                    }
                }
            }
            CashFilterGroup(
                title = localizedText("ledger_filter_group_amount"),
                semanticId = SemanticIds.ledgerFilterGroupAmount,
            ) {
                if (compact) {
                    currencyFilter(Modifier.fillMaxWidth())
                    amountMinFilter(Modifier.fillMaxWidth())
                    amountMaxFilter(Modifier.fillMaxWidth())
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        currencyFilter(Modifier.weight(1f))
                        amountMinFilter(Modifier.weight(1f))
                        amountMaxFilter(Modifier.weight(1f))
                    }
                }
            }
            CashFilterGroup(
                title = localizedText("ledger_filter_group_category"),
                semanticId = SemanticIds.ledgerFilterGroupCategory,
            ) {
                categoryFilter(Modifier.fillMaxWidth())
                ChoicePicker(localizedText("cash_confirmation_filter"), value.confirmationStatus.orEmpty(),
                    listOf("" to localizedText("ledger_quick_all"), "pending" to localizedText("cash_confirmation_pending"), "confirmed" to localizedText("cash_confirmation_confirmed")),
                    { onChange(value.copy(confirmationStatus = it.ifEmpty { null })) }, modifier = Modifier.fillMaxWidth(), semanticId = "ledger.filter.confirmation")
                ChoicePicker(localizedText("cash_record_state"), value.state.orEmpty(),
                    listOf("" to localizedText("ledger_quick_all"), "draft" to localizedText("cash_state_draft"), "posted" to localizedText("cash_state_posted")),
                    { onChange(value.copy(state = it.ifEmpty { null })) }, modifier = Modifier.fillMaxWidth(), semanticId = "ledger.filter.state")
            }
            CashFilterGroup(
                title = localizedText("ledger_filter_group_account"),
                semanticId = SemanticIds.ledgerFilterGroupAccount,
            ) {
                accountFilter(Modifier.fillMaxWidth())
            }
            CashFilterGroup(
                title = localizedText("ledger_filter_group_other"),
                semanticId = SemanticIds.ledgerFilterGroupOther,
            ) {
                if (!compact && transferSubtypes.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        transferSubtypeFilter(Modifier.weight(1f))
                        compositionFilter(Modifier.weight(1f))
                    }
                } else {
                    if (transferSubtypes.isNotEmpty()) transferSubtypeFilter(Modifier.fillMaxWidth())
                    compositionFilter(Modifier.fillMaxWidth())
                }
            }
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
    lastInMonth: Boolean,
    onChecked: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    val recordModifier = Modifier.testTag("${SemanticIds.ledgerRecord}-${item.projectionId}")
    if (sizeClass == WindowSizeClass.COMPACT) {
        Column(
            modifier = recordModifier.fillMaxWidth().combinedClickable(
                onClick = { if (selectionMode) onChecked(!checked) else onOpen() },
                onLongClick = { if (canWrite && !selectionMode) onChecked(true) },
            ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(GeneratedTokens.Core.Space.Token4),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                        listOfNotNull(cashProjectionEconomicTypeLabel(item), source, localizedText(if (item.confirmationStatus == "pending") "cash_confirmation_pending" else "cash_confirmation_confirmed")).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FinanceAmount(cashProjectionAmountLabel(item), negative = item.amount?.startsWith("-") == true)
            }
            if (!lastInMonth) HorizontalDivider(Modifier.padding(start = GeneratedTokens.Core.Space.Token4))
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
                    Text(localizedText(if (item.confirmationStatus == "pending") "cash_confirmation_pending" else "cash_confirmation_confirmed"),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (item.confirmationStatus == "pending") LocalFinanceColorRoles.current.warning else MaterialTheme.colorScheme.onSurfaceVariant)
                    cashProjectionSourceLabel(item)?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                FinanceAmount(
                    cashProjectionAmountLabel(item),
                    modifier = Modifier.weight(0.13f).padding(horizontal = 8.dp),
                    negative = item.amount?.startsWith("-") == true,
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
            if (!lastInMonth) HorizontalDivider()
        }
    }
}
