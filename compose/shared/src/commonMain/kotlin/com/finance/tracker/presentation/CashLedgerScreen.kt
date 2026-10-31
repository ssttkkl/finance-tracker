package com.finance.tracker.presentation

import com.finance.tracker.core.*
import com.finance.tracker.domain.*
import com.finance.tracker.resources.Res
import com.finance.tracker.resources.ic_add
import com.finance.tracker.resources.ic_checklist
import com.finance.tracker.resources.ic_close
import com.finance.tracker.resources.ic_delete
import com.finance.tracker.resources.ic_file_download
import com.finance.tracker.resources.ic_keyboard_arrow_down
import com.finance.tracker.resources.ic_label
import com.finance.tracker.resources.ic_search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
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
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
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
    creationRepository: CashCreationRepository,
    maintenanceRecovery: CashMaintenanceRecoveryStore,
) {
    val viewModel: CashLedgerViewModel = viewModel(
        key = "cash-ledger:${session.user.email}:${session.activeWorkspaceId.orEmpty()}:${repository.hashCode()}",
        factory = viewModelFactory { initializer { CashLedgerViewModel(repository, repository, diagnostics = diagnostics) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val creationTask: CashCreationViewModel = viewModel(
        key = "cash-creation:${session.user.email}:${session.activeWorkspaceId}:${creationRepository.hashCode()}",
        factory = viewModelFactory { initializer { CashCreationViewModel(creationRepository) } },
    )
    val creationState by creationTask.state.collectAsStateWithLifecycle()
    LaunchedEffect(creationState.savedRevision) { if (creationState.savedRevision > 0) viewModel.reload() }
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
    LaunchedEffect(currentState.transactionId, currentState.record?.rowRevision, currentState.busy, currentState.plan?.planId) {
        val record = currentState.record ?: return@LaunchedEffect
        val hasPendingRelations = record.relations.any { it.active && it.state == "draft" }
        if (!currentState.busy && currentState.plan == null && hasPendingRelations &&
            record.recordOrigin !in setOf("manual", "manual_transfer")) {
            currentTask.beginRelations()
        }
    }
    LaunchedEffect(currentState.record?.projectionRef) {
        currentState.record?.projectionRef?.let { ref ->
            if (currentEvidence?.projectionVersion != ref.projectionVersion || currentEvidence?.projection?.projectionId != ref.projectionId) {
                sourceError = null
                try {
                    val evidence = maintenanceRepository.sources(ref.projectionId)
                    if (evidence.projectionVersion != ref.projectionVersion) throw DomainFailure("record_changed", 409, FailureCategory.RECOVERABLE)
                    currentProjection = evidence.projection
                    currentEvidence = evidence
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    currentEvidence = null
                    sourceError = (failure as? DomainFailure)?.code ?: "request_failed"
                }
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

    val canWrite = session.workspaces.firstOrNull { it.id == session.activeWorkspaceId }?.role?.canWrite == true
    val referencesError = state.referencesErrorCode?.let { ledgerErrorText(it) }
    val appendError = (pagedRows.loadState.append as? LoadState.Error)?.error?.let { ledgerErrorText(ledgerPagingErrorCode(it)) }
    val prependError = (pagedRows.loadState.prepend as? LoadState.Error)?.error?.let { ledgerErrorText(ledgerPagingErrorCode(it)) }
    val fullRefreshError = state.listLoadErrorCode?.let { ledgerErrorText(it) }
    val classifyError = state.classifyErrorCode?.let { ledgerErrorText(it) }
    val deleteProjectionError = state.deleteProjectionErrorCode?.let { ledgerErrorText(it) }
    var actionsMenuExpanded by remember { mutableStateOf(false) }
    var batchCategoryDialogOpen by remember { mutableStateOf(false) }
    var batchCategoryDraft by remember(batchCategoryDialogOpen) { mutableStateOf(state.selectedCategory) }
    val selectionMode = state.selectedIds.isNotEmpty()
    val listReady = state.page != null && fullRefreshError == null
    val fullRefreshPending = !listReady && fullRefreshError == null
    val pageLoadError = (prependError ?: appendError).takeIf { listReady }
    var pullRefreshRevision by remember { mutableStateOf<Int?>(null) }
    val isPullRefreshing = pullRefreshRevision == state.listRevision && fullRefreshPending
    val pullRefreshThresholdPx = with(LocalDensity.current) { 80.dp.toPx() }
    val requestListRefresh = {
        if (listReady && !selectionMode && pullRefreshRevision == null) {
            pullRefreshRevision = state.listRevision + 1
            viewModel.reload()
        }
    }
    val canPullRefresh by rememberUpdatedState(listReady && !selectionMode)
    val currentListRefresh by rememberUpdatedState(requestListRefresh)
    LaunchedEffect(pullRefreshRevision, state.listRevision, listReady, fullRefreshError) {
        val targetRevision = pullRefreshRevision ?: return@LaunchedEffect
        if (targetRevision != state.listRevision || listReady || fullRefreshError != null) {
            pullRefreshRevision = null
        }
    }
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
                bottom = 0.dp,
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
                    pendingItemCount = state.page?.pendingItemCount ?: 0L,
                    sizeClass = sizeClass,
                    onChange = viewModel::updateFilters,
                    accounts = state.accounts,
                    categories = state.categories,
                    currencies = state.filterOptions.currencies,
                    typeOptions = state.filterOptions.economicTypes,
                    onInvalidAmount = viewModel::reportInvalidAmountFilter,
                )
            }
        }
        val summariesByMonth = if (listReady) state.page?.monthlySummaries.orEmpty().associateBy(CashMonthlySummary::month) else emptyMap()
        val monthPositions = if (listReady) state.page?.monthPositions.orEmpty() else emptyList()
        val totalCount = if (listReady) state.page?.totalCount ?: pagedRows.itemCount else 0
        val appendReachedEnd = listReady && (pagedRows.loadState.append as? LoadState.NotLoading)?.endOfPaginationReached == true
        Column(Modifier.fillMaxWidth().weight(1f)) {
            if (pageLoadError != null && pagedRows.itemCount > 0) {
                StateMessage(pageLoadError, isError = true) {
                    TextButton(onClick = { pagedRows.retry() }, modifier = Modifier.testTag(SemanticIds.ledgerRetry)) {
                        Text(localizedText("copy_e2d53a6d3a"))
                    }
                }
            }
            PullToRefreshBox(
                isRefreshing = isPullRefreshing,
                onRefresh = requestListRefresh,
                modifier = Modifier.fillMaxWidth().weight(1f),
                enabled = listReady && !selectionMode,
            ) {
                CashWaterfallViewport(
                    listState = listState,
                    totalCount = if (listReady) totalCount else 0,
                    monthAt = { ordinal -> if (ordinal in 0 until pagedRows.itemCount) pagedRows.peek(ordinal)?.let { localMonthKey(it.occurredAt) } else null },
                    monthStartAt = { ordinal -> cashLedgerMonthPositionForOrdinal(monthPositions, ordinal)?.startIndex ?: 0 },
                    monthPositions = monthPositions,
                    isOrdinalLoaded = { ordinal -> ordinal in 0 until pagedRows.itemCount && pagedRows.peek(ordinal) != null },
                    summaryForMonth = { summariesByMonth[it] },
                    showTotals = true,
                    sizeClass = sizeClass,
                    showSelection = true,
                    auxiliaryColumn = CashLedgerAuxiliaryColumn.TYPE,
                    modifier = Modifier.fillMaxSize().pointerInput(listState) {
                        awaitEachGesture {
                            val down = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial,
                            )
                            val startedAtTop = canPullRefresh && !listState.canScrollBackward
                            var dragDistance = 0f
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                dragDistance += change.positionChangeIgnoreConsumed().y
                            }
                            if (startedAtTop && canPullRefresh && dragDistance >= pullRefreshThresholdPx) {
                                currentListRefresh()
                            }
                        }
                    },
                ) { onHeaderHeight ->
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
                    .testTag(SemanticIds.ledgerList),
                contentPadding = PaddingValues(bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
            when {
                fullRefreshError != null ->
                    item(key = "cash-ledger-refresh-error") {
                        StateMessage(fullRefreshError, isError = true) {
                            TextButton(
                                onClick = {
                                    viewModel.clearListLoadError()
                                    pagedRows.retry()
                                },
                                modifier = Modifier.testTag(SemanticIds.ledgerRetry),
                            ) {
                                Text(localizedText("copy_e2d53a6d3a"))
                            }
                        }
                    }
                !listReady ->
                    items(count = 5, key = { index -> "cash-ledger-loading-$index" }) {
                        CashProjectionPlaceholderRow(compact = sizeClass == WindowSizeClass.COMPACT)
                    }
                pagedRows.itemCount == 0 ->
                    item(key = "cash-ledger-empty") {
                        StateMessage(localizedText("copy_8eb3d2788b"), modifier = Modifier.testTag(SemanticIds.ledgerEmpty))
                    }
            }
            if (listReady) {
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
                    CashWaterfallItem(
                        month = month,
                        first = monthStart,
                        last = monthEnd,
                        gapBefore = monthStart && index > 0,
                        summary = summariesByMonth[month],
                        showTotals = true,
                        sizeClass = sizeClass,
                        showSelection = true,
                        auxiliaryColumn = CashLedgerAuxiliaryColumn.TYPE,
                        onHeaderHeight = onHeaderHeight,
                    ) {
                        Column(Modifier.fillMaxWidth().testTag("${SemanticIds.ledgerTable}-$month")) {
                            CashProjectionRow(
                                item = row,
                                options = state.options,
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
                } else {
                    CashProjectionPlaceholderRow(compact = sizeClass == WindowSizeClass.COMPACT)
                }
            }
            if (pagedRows.loadState.append is LoadState.Loading) {
                item(key = "cash-ledger-append-loading") {
                    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                    }
                }
            }
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
                        if (canWrite) creationTask.open(state.accounts, localDateTimeForInput())
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
                        if (currentTask.state.value.transactionId == target && currentTask.state.value.record?.projectionRef?.projectionVersion == version) {
                            currentProjection = evidence.projection
                            currentEvidence = evidence
                        }
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
            onSaveEntry = if (canWrite) ({ request -> currentTask.prepare(request) }) else null,
            onCancelEntry = currentTask::cancelEdit,
            onBeginRelations = if (canWrite) currentTask::beginRelations else null,
            onSearchAssociations = currentTask::associationTargets,
            onAddAssociation = currentTask::addRelation,
            onRelationCommonFields = currentTask::resolveRelationCommonFields,
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

    if (creationState.draft != null) CashCreationPanel(creationState, state.accounts, state.categories, state.options, sizeClass,
        onChange = creationTask::update, onClose = { if (creationTask.close() && creationState.unknown) viewModel.reload() },
        onSave = { if (canWrite) creationTask.save(state.accounts, getLocalTimeZoneId()) })

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
internal fun CashProjectionPlaceholderRow(compact: Boolean) {
    if (compact) {
        Surface(
            modifier = Modifier.fillMaxWidth()
                .padding(bottom = GeneratedTokens.Core.Space.Token2)
                .testTag(SemanticIds.ledgerLoadingSkeletonCard),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CashLedgerSkeletonLines(Modifier.weight(1f), firstFraction = 0.74f, secondFraction = 0.34f, prominent = true)
                Spacer(Modifier.width(16.dp))
                Spacer(
                    Modifier.width(64.dp)
                        .height(18.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small),
                )
            }
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag(SemanticIds.ledgerLoadingSkeletonTableRow),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(0.05f), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Spacer(Modifier.size(18.dp).background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small))
                    }
                }
                CashLedgerSkeletonLines(Modifier.weight(0.12f).padding(horizontal = 8.dp), 0.82f, 0.54f)
                CashLedgerSkeletonLines(Modifier.weight(0.15f).padding(horizontal = 8.dp), 0.76f, 0.48f)
                CashLedgerSkeletonLines(Modifier.weight(0.24f).padding(horizontal = 8.dp), 0.78f, 0.6f, prominent = true)
                CashLedgerSkeletonLines(Modifier.weight(0.12f).padding(horizontal = 8.dp), 0.74f)
                CashLedgerSkeletonLines(Modifier.weight(0.11f).padding(horizontal = 8.dp), 0.7f, 0.5f)
                Box(Modifier.weight(0.13f).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterEnd) {
                    Spacer(Modifier.fillMaxWidth(0.76f).height(18.dp).background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small))
                }
                Box(Modifier.weight(0.08f), contentAlignment = Alignment.CenterEnd) {
                    Spacer(Modifier.size(20.dp).background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small))
                }
            }
            HorizontalDivider(Modifier.testTag("${SemanticIds.ledgerTableRowDivider}-skeleton"))
        }
    }
}

@Composable
private fun CashLedgerSkeletonLines(
    modifier: Modifier,
    firstFraction: Float,
    secondFraction: Float? = null,
    prominent: Boolean = false,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Spacer(
            Modifier.fillMaxWidth(firstFraction)
                .height(if (prominent) 16.dp else 13.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small),
        )
        if (secondFraction != null) {
            Spacer(
                Modifier.fillMaxWidth(secondFraction)
                    .height(11.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small),
            )
        }
    }
}

@Composable
internal fun CashLedgerGroupSurface(first: Boolean, last: Boolean, gapBefore: Boolean = false, content: @Composable () -> Unit) {
    val baseShape = MaterialTheme.shapes.medium
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = if (gapBefore) GeneratedTokens.Core.Space.Token3 else 0.dp),
        shape = RoundedCornerShape(
            topStart = if (first) baseShape.topStart else CornerSize(0.dp),
            topEnd = if (first) baseShape.topEnd else CornerSize(0.dp),
            bottomEnd = if (last) baseShape.bottomEnd else CornerSize(0.dp),
            bottomStart = if (last) baseShape.bottomStart else CornerSize(0.dp),
        ),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        content = content,
    )
}

internal enum class CashLedgerAuxiliaryColumn { TYPE }

@Composable
internal fun CashLedgerTableHeader(modifier: Modifier = Modifier, showSelection: Boolean = true,
    auxiliaryColumn: CashLedgerAuxiliaryColumn = CashLedgerAuxiliaryColumn.TYPE) {
    Row(
        modifier = modifier.fillMaxWidth().testTag(SemanticIds.ledgerTableHeader),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showSelection) Spacer(Modifier.weight(0.05f))
        FinanceTableCell(localizedText("ledger_column_occurred_at"), weight = 0.12f, header = true, modifier = Modifier.testTag(SemanticIds.ledgerColumnDate))
        FinanceTableCell(localizedText("copy_c3d92b20c8"), weight = 0.15f, header = true, modifier = Modifier.testTag(SemanticIds.ledgerColumnAccount))
        FinanceTableCell(localizedText("ledger_column_transaction_info"), weight = 0.24f, header = true, modifier = Modifier.testTag(SemanticIds.ledgerColumnTransactionInfo))
        FinanceTableCell(localizedText("copy_435c5259e4"), weight = 0.12f, header = true, modifier = Modifier.testTag(SemanticIds.ledgerColumnCategory))
        FinanceTableCell(localizedText("copy_e81089915c"), weight = 0.11f, header = true, modifier = Modifier.testTag(SemanticIds.ledgerColumnEconomicType))
        FinanceTableCell(localizedText("copy_34943c40c9"), weight = 0.13f, header = true, textAlign = TextAlign.End, modifier = Modifier.testTag(SemanticIds.ledgerColumnAmount))
    }
    HorizontalDivider()
}

@Composable
internal fun CashProjectionMonthHeader(group: CashProjectionMonthGroup, compact: Boolean, modifier: Modifier = Modifier) {
    val summaries = group.summary?.currencies.orEmpty()
    val summaryContent: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (summaries.isEmpty()) {
                Text(localizedText("copy_9c522d837a"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
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
    Column(modifier.fillMaxWidth().testTag(SemanticIds.ledgerSummary), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (compact) {
            Text(localMonthLabel(group.month), style = MaterialTheme.typography.titleMedium)
            Box(Modifier.align(Alignment.End)) { summaryContent() }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(localMonthLabel(group.month), style = MaterialTheme.typography.titleMedium)
                summaryContent()
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
    pendingItemCount: Long,
    sizeClass: WindowSizeClass,
    onChange: (CashFilters) -> Unit,
    accounts: List<Account>,
    categories: List<CashCategory>,
    currencies: List<String>,
    typeOptions: List<CashEconomicTypeFilterOption>,
    onInvalidAmount: () -> Unit,
) {
    val compact = sizeClass == WindowSizeClass.COMPACT
    var detailedVisible by remember { mutableStateOf(false) }
    val detailedFiltersLabel = localizedText("copy_dcce9a144a")
    val filterSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val selectedType = when {
        value.economicType.isNullOrEmpty() && !value.businessLabel.isNullOrEmpty() -> "internal_transfer"
        else -> value.economicType.orEmpty()
    }
    val orderedTypes = cashQuickEconomicTypes(typeOptions)
    var draft by remember { mutableStateOf(value) }
    val closeDetailedFilters: () -> Unit = { detailedVisible = false }
    val openDetailedFilters: () -> Unit = { draft = value; detailedVisible = true }
    val applyDetailedFilters: () -> Unit = { onChange(draft); detailedVisible = false }

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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (pendingItemCount > 0 || value.confirmationStatus == "pending") {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = value.confirmationStatus == "pending",
                            onClick = { onChange(value.copy(confirmationStatus = if (value.confirmationStatus == "pending") null else "pending")) },
                            label = { Text(if (pendingItemCount > 0) localizedText("cash_pending_count", pendingItemCount.toString()) else localizedText("cash_confirmation_pending")) },
                            modifier = Modifier.testTag("ledger.filter.pending"),
                        )
                        VerticalDivider(
                            modifier = Modifier.height(24.dp).testTag("ledger.filter.pending-divider"),
                        )
                        FilterChip(
                            selected = selectedType.isEmpty(),
                            onClick = { onChange(value.copy(economicType = null, businessLabel = null)) },
                            label = { Text(localizedText("ledger_quick_all")) },
                            modifier = Modifier.testTag("${SemanticIds.ledgerQuickType}-all"),
                        )
                    }
                } else {
                    FilterChip(
                        selected = selectedType.isEmpty(),
                        onClick = { onChange(value.copy(economicType = null, businessLabel = null)) },
                        label = { Text(localizedText("ledger_quick_all")) },
                        modifier = Modifier.testTag("${SemanticIds.ledgerQuickType}-all"),
                    )
                }
                orderedTypes.forEach { economicType ->
                    FilterChip(
                        selected = selectedType == economicType,
                        onClick = { onChange(value.copy(economicType = economicType, businessLabel = null)) },
                        label = { Text(cashEconomicTypeQuickFilterLabel(economicType)) },
                        modifier = Modifier.testTag("${SemanticIds.ledgerQuickType}-$economicType"),
                    )
                }
            }
            TextButton(
                onClick = openDetailedFilters,
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
                value = draft,
                sizeClass = sizeClass,
                onChange = { draft = it },
                accounts = accounts,
                categories = categories,
                currencies = currencies,
                typeOptions = typeOptions,
                onReset = { draft = CashFilters() },
                onClose = closeDetailedFilters,
                onApply = applyDetailedFilters,
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
            BoxWithConstraints(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 24.dp, vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier.widthIn(min = 280.dp, max = 560.dp)
                        .fillMaxWidth()
                        .heightIn(max = maxHeight)
                        .semantics { paneTitle = detailedFiltersLabel },
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = AlertDialogDefaults.TonalElevation,
                ) {
                    CashDetailedFilters(
                        value = draft,
                        sizeClass = sizeClass,
                        onChange = { draft = it },
                        accounts = accounts,
                        categories = categories,
                        currencies = currencies,
                        typeOptions = typeOptions,
                        onReset = { draft = CashFilters() },
                        onClose = closeDetailedFilters,
                onApply = applyDetailedFilters,
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CashHideZeroAmountSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier,
) {
    ListItem(
        headlineContent = { Text(localizedText("ledger_hide_zero_amount")) },
        modifier = modifier
            .toggleable(
                value = checked,
                onValueChange = onCheckedChange,
                role = Role.Switch,
            )
            .padding(vertical = 8.dp)
            .testTag("ledger.filter.hide-zero-amount"),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = null,
            )
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
    onApply: () -> Unit,
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
    val normalizedEconomicType = value.economicType
    val businessLabels = typeOptions.firstOrNull { it.economicType == normalizedEconomicType }?.businessLabels.orEmpty()
    val businessLabelFilter: @Composable (Modifier) -> Unit = { fieldModifier ->
        ChoicePicker(
            localizedText("copy_82fa0e654c"),
            value.businessLabel.orEmpty(),
            listOf("" to localizedText("copy_778fc8f994")) + businessLabels.map { it to cashBusinessLabel(it) },
            { onChange(value.copy(businessLabel = it.ifEmpty { null })) },
            modifier = fieldModifier,
            semanticId = SemanticIds.ledgerFilterBusinessLabel,
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
        if (compact) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)
                    .testTag("ledger.filter.header"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.testTag("ledger.filter.close")
                        .semantics { contentDescription = closeLabel },
                ) {
                    Icon(painterResource(Res.drawable.ic_close), contentDescription = null, modifier = Modifier.size(24.dp))
                }
                Text(
                    localizedText("copy_dcce9a144a"),
                    modifier = Modifier.testTag("ledger.filter.title"),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        } else {
            ModalHeader(
                variant = ModalHeaderVariant.BasicDialog,
                onClose = onClose,
                headerTestTag = "ledger.filter.header",
                closeTestTag = "ledger.filter.close",
            ) {
                Text(
                    localizedText("copy_dcce9a144a"),
                    modifier = Modifier.testTag("ledger.filter.title"),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }
        HorizontalDivider()
        Column(
            modifier = Modifier.weight(1f, fill = false).fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = if (compact) 20.dp else 24.dp,
                    top = 16.dp,
                    end = if (compact) 20.dp else 24.dp,
                    bottom = if (compact) 16.dp else 24.dp,
                ).testTag("ledger.filter.content"),
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
                val onHideZeroAmountChange: (Boolean) -> Unit = {
                    onChange(value.copy(hideZeroAmount = it))
                }
                CashHideZeroAmountSwitch(
                    checked = value.hideZeroAmount,
                    onCheckedChange = onHideZeroAmountChange,
                    modifier = Modifier.fillMaxWidth(),
                )
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
                if (!compact && businessLabels.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        businessLabelFilter(Modifier.weight(1f))
                        compositionFilter(Modifier.weight(1f))
                    }
                } else {
                    if (businessLabels.isNotEmpty()) businessLabelFilter(Modifier.fillMaxWidth())
                    compositionFilter(Modifier.fillMaxWidth())
                }
            }
        }
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(
                start = if (compact) 20.dp else 24.dp,
                top = if (compact) 8.dp else 0.dp,
                end = if (compact) 20.dp else 24.dp,
                bottom = if (compact) 8.dp else 24.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (compact) TextButton(onClick = onReset, modifier = Modifier.testTag("ledger.filter.reset")) { Text(localizedText("copy_7b15e5e8e7")) }
            Spacer(Modifier.weight(1f))
            if (!compact) TextButton(onClick = onReset, modifier = Modifier.testTag("ledger.filter.reset")) { Text(localizedText("copy_7b15e5e8e7")) }
            if (!compact) Spacer(Modifier.width(8.dp))
            Button(onClick = onApply,
                enabled = listOfNotNull(value.amountMin, value.amountMax).all(::isExactDecimalString),
                modifier = Modifier.testTag("ledger.filter.apply")) { Text(localizedText("ledger_category_apply")) }
        }
    }
}

@Composable
private fun CashProjectionRow(
    item: CashProjection, options: LedgerOptions, checked: Boolean, canWrite: Boolean, sizeClass: WindowSizeClass,
    selectionMode: Boolean, lastInMonth: Boolean, onChecked: (Boolean) -> Unit, onOpen: () -> Unit,
) {
    val typeLabel = cashRecordTypeAndSubtypeLabel(item.recordType, item.recordSubtype, options)
        ?: cashProjectionEconomicTypeLabel(item)
    val businessLabel = item.businessLabel?.let { cashBusinessLabel(it) }
        ?.takeUnless { it in typeLabel.split("－") }
    CashLedgerRow(CashLedgerRowContent(item.projectionId, item.occurredAt, item.counterparty, item.note,
        cashProjectionAccountLabel(item), cashCategoryDisplayPath(item.category), typeLabel,
        null, item.confirmationStatus, cashProjectionAmountLabel(item), item.amount?.startsWith("-") == true,
        statusTags = cashAttentionTags(item.state, item.confirmationStatus, item.attentionCodes),
        showConfirmationStatus = false, businessLabel = businessLabel),
        checked, canWrite, sizeClass, selectionMode, lastInMonth, onChecked, onOpen)
}

internal data class CashLedgerRowContent(
    val id: String, val occurredAt: String?, val counterparty: String, val note: String,
    val accountLabel: String, val categoryLabel: String, val economicLabel: String?, val sourceLabel: String?,
    val confirmationStatus: String, val amountLabel: String, val negative: Boolean,
    val statusTags: List<CashLedgerRowStatusTag> = emptyList(), val showConfirmationStatus: Boolean = false,
    val relationshipSummary: String? = null, val attentionSummary: String? = null,
    val auxiliaryColumn: CashLedgerAuxiliaryColumn = CashLedgerAuxiliaryColumn.TYPE,
    val businessLabel: String? = null,
)

internal enum class CashLedgerRowStatusTag(val testKey: String, val labelId: String) {
    DRAFT("draft", "cash_draft_needs_details"),
    NEEDS_REVIEW("needs-review", "cash_information_needs_review"),
    RELATION_PENDING("relation-pending", "cash_pair_suggestion"),
    SOURCE_UPDATE("source-update", "cash_source_update_needs_review"),
    SOURCE_IDENTITY("source-identity", "cash_source_identity_needs_review"),
}

internal fun cashAttentionTags(state: String, confirmationStatus: String, codes: List<String>): List<CashLedgerRowStatusTag> = buildList {
    if (state == "draft") add(CashLedgerRowStatusTag.DRAFT)
    if ("relation_pending" in codes) add(CashLedgerRowStatusTag.RELATION_PENDING)
    if ("source_update_pending" in codes) add(CashLedgerRowStatusTag.SOURCE_UPDATE)
    if ("source_identity_ambiguous" in codes) add(CashLedgerRowStatusTag.SOURCE_IDENTITY)
    if (confirmationStatus == "pending" && isEmpty()) add(CashLedgerRowStatusTag.NEEDS_REVIEW)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CashLedgerRow(
    item: CashLedgerRowContent, checked: Boolean, canWrite: Boolean, sizeClass: WindowSizeClass,
    selectionMode: Boolean, lastInMonth: Boolean, onChecked: (Boolean) -> Unit, onOpen: () -> Unit,
    showSelection: Boolean = true,
    singleSelection: Boolean = false,
    showOpenAction: Boolean = true,
) {
    val recordBackground = if (item.statusTags.isNotEmpty()) LocalFinanceColorRoles.current.warningSurface else null
    val recordModifier = Modifier.testTag("${SemanticIds.ledgerRecord}-${item.id}")
        .then(recordBackground?.let { Modifier.background(it) } ?: Modifier)
    if (sizeClass == WindowSizeClass.COMPACT) {
        Column(
            modifier = recordModifier.fillMaxWidth().combinedClickable(
                enabled = (selectionMode && canWrite) || showOpenAction,
                onClick = { if (selectionMode) onChecked(!checked) else onOpen() },
                onLongClick = { if (canWrite && !selectionMode) onChecked(true) },
            ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(GeneratedTokens.Core.Space.Token4),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (selectionMode && showSelection) {
                    if (singleSelection && canWrite) RadioButton(
                        selected = checked,
                        onClick = { onChecked(true) },
                        enabled = canWrite,
                        modifier = Modifier.testTag("ledger.record-radio-${item.id}").semantics { contentDescription = item.counterparty },
                    ) else if (!singleSelection) Checkbox(
                        checked = checked,
                        onCheckedChange = onChecked,
                        enabled = canWrite,
                        modifier = Modifier.testTag("${SemanticIds.ledgerRecordCheckbox}-${item.id}"),
                    )
                }
                Column(
                    Modifier.weight(1f).testTag(SemanticIds.ledgerOpenRecord),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(item.counterparty.ifBlank { "—" }, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(item.note.ifBlank { "—" }, Modifier.testTag("ledger.note-${item.id}"), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    item.relationshipSummary?.let {
                        Text(it, Modifier.testTag("ledger.relationship-${item.id}"), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    item.attentionSummary?.let {
                        Text(it, Modifier.testTag("ledger.attention-${item.id}"), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text(localDateTimeDisplayLabel(item.occurredAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${item.accountLabel} · ${item.categoryLabel}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val source = item.sourceLabel
                    val confirmation = if (item.showConfirmationStatus) localizedText(
                        if (item.confirmationStatus == "pending") "cash_confirmation_pending" else "cash_confirmation_confirmed",
                    ) else null
                    listOfNotNull(item.economicLabel, item.businessLabel, source, confirmation).takeIf { it.isNotEmpty() }?.let { labels ->
                        Text(labels.joinToString(" · "), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    CashLedgerRowStatusTags(item)
                }
                FinanceAmount(item.amountLabel, negative = item.negative)
            }
            if (!lastInMonth) HorizontalDivider(Modifier.padding(start = GeneratedTokens.Core.Space.Token4))
        }
    } else {
        Column(recordModifier.fillMaxWidth().clickable(
            enabled = showOpenAction,
            onClickLabel = localizedText("copy_faea8c1db9"),
            onClick = onOpen,
        )) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(vertical = 8.dp)
                    .testTag("${SemanticIds.ledgerTableRowContent}-${item.id}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showSelection) Box(Modifier.weight(0.05f), contentAlignment = Alignment.Center) {
                    if (singleSelection && canWrite) RadioButton(
                        selected = checked,
                        onClick = { onChecked(true) },
                        enabled = canWrite,
                        modifier = Modifier.testTag("ledger.record-radio-${item.id}").semantics { contentDescription = item.counterparty },
                    ) else if (!singleSelection) Checkbox(
                        checked = checked,
                        onCheckedChange = onChecked,
                        enabled = canWrite,
                        modifier = Modifier.testTag("${SemanticIds.ledgerRecordCheckbox}-${item.id}"),
                    )
                }
                FinanceTableCell(localDateTimeDisplayLabel(item.occurredAt), weight = 0.12f)
                FinanceTableCell(item.accountLabel, weight = 0.15f)
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
                        Modifier.testTag("ledger.note-${item.id}"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    item.relationshipSummary?.let {
                        Text(it, Modifier.testTag("ledger.relationship-${item.id}"), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    item.attentionSummary?.let {
                        Text(it, Modifier.testTag("ledger.attention-${item.id}"), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                FinanceTableCell(item.categoryLabel, weight = 0.12f)
                Column(
                    Modifier.weight(0.11f).padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    item.economicLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    item.businessLabel?.let { Text(it, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    CashLedgerRowStatusTags(item)
                    if (item.showConfirmationStatus) Text(localizedText(
                        if (item.confirmationStatus == "pending") "cash_confirmation_pending" else "cash_confirmation_confirmed",
                    ), style = MaterialTheme.typography.labelSmall,
                        color = if (item.confirmationStatus == "pending") LocalFinanceColorRoles.current.warning else MaterialTheme.colorScheme.onSurfaceVariant)
                    item.sourceLabel?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                FinanceAmount(
                    item.amountLabel,
                    modifier = Modifier.weight(0.13f).padding(horizontal = 8.dp),
                    negative = item.negative,
                    textAlign = TextAlign.End,
                )
            }
            if (!lastInMonth) HorizontalDivider(Modifier.testTag("${SemanticIds.ledgerTableRowDivider}-${item.id}"))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CashLedgerRowStatusTags(item: CashLedgerRowContent) {
    if (item.statusTags.isEmpty()) return
    val colorRoles = LocalFinanceColorRoles.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item.statusTags.forEach { tag ->
            val (containerColor, contentColor) = when (tag) {
                CashLedgerRowStatusTag.DRAFT -> colorRoles.warning to colorRoles.background
                CashLedgerRowStatusTag.NEEDS_REVIEW,
                CashLedgerRowStatusTag.RELATION_PENDING,
                CashLedgerRowStatusTag.SOURCE_UPDATE,
                CashLedgerRowStatusTag.SOURCE_IDENTITY -> colorRoles.warning to colorRoles.background
            }
            Surface(
                modifier = Modifier.testTag("ledger.status-tag-${item.id}-${tag.testKey}"),
                color = containerColor,
                contentColor = contentColor,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(localizedText(tag.labelId), Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
