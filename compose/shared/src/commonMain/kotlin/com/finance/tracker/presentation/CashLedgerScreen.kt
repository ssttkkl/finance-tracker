package com.finance.tracker.presentation

import com.finance.tracker.core.*
import com.finance.tracker.domain.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

@Composable
internal fun CashLedgerScreen(
    repository: FinanceRepository,
    session: Session,
    sizeClass: WindowSizeClass,
    onNavigate: (AppPage) -> Unit,
    diagnostics: DiagnosticLogger = NoOpDiagnosticLogger,
) {
    val viewModel: CashLedgerViewModel = viewModel(
        key = "cash-ledger:${session.activeWorkspaceId.orEmpty()}",
        factory = viewModelFactory { initializer { CashLedgerViewModel(repository, repository, diagnostics = diagnostics) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.initialize() }

    val canWrite = session.workspaces.firstOrNull { it.id == session.activeWorkspaceId }?.role?.canWrite == true
    val rowGroups = cashProjectionMonthGroups(state.rows, state.page?.monthlySummaries.orEmpty())
    val referencesError = state.referencesErrorCode?.let { ledgerErrorText(it) }
    val pageError = state.errorCode?.let { ledgerErrorText(it) }
    val appendError = state.appendErrorCode?.let { ledgerErrorText(it) }
    val classifyError = state.classifyErrorCode?.let { ledgerErrorText(it) }
    val deleteProjectionError = state.deleteProjectionErrorCode?.let { ledgerErrorText(it) }
    val recordSaveError = state.recordSaveErrorCode?.let { ledgerErrorText(it) }

    FeaturePage(
        localizedText("copy_c1f4f28c6c"),
        SemanticIds.ledgerScreen,
        headerActionsInline = sizeClass != WindowSizeClass.COMPACT,
        headerActions = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FinanceButton(onClick = { viewModel.startNewRecord(localDateTimeForInput()) }, enabled = canWrite, modifier = Modifier.testTag(SemanticIds.ledgerAdd)) { Text(localizedText("copy_1f62e3f53f")) }
                FinanceSecondaryButton(onClick = { onNavigate(AppPage.CASH_IMPORT) }, enabled = canWrite, modifier = Modifier.testTag(SemanticIds.ledgerImport)) { Text(localizedText("copy_aee4e0d769")) }
            }
        },
    ) {
        if (referencesError != null) StateMessage(referencesError, isError = true) {
            TextButton(onClick = viewModel::loadReferences) { Text(localizedText("copy_e2d53a6d3a")) }
        }
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
        rowGroups.forEach { group ->
            SectionCard(modifier = Modifier.testTag(SemanticIds.ledgerSummary)) {
                Text(localMonthLabel(group.month), style = MaterialTheme.typography.titleMedium)
                if (group.summary?.currencies.isNullOrEmpty()) Text(localizedText("copy_9c522d837a"), style = MaterialTheme.typography.bodySmall)
                group.summary?.currencies.orEmpty().forEach { currencySummary ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        if (sizeClass == WindowSizeClass.COMPACT) {
                            Column(Modifier.weight(1f)) {
                                Text(localizedText("copy_b2dfe7c9d3", displaySignedSummaryAmount("income", currencySummary.income)), color = MaterialTheme.colorScheme.primary)
                                Text(localizedText("copy_bdbfa1d0c9", displaySignedSummaryAmount("expense", currencySummary.expense)), color = MaterialTheme.colorScheme.error)
                            }
                            Text(currencySummary.currency)
                        } else {
                            Text(currencySummary.currency)
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text(localizedText("copy_b2dfe7c9d3", displaySignedSummaryAmount("income", currencySummary.income)), color = MaterialTheme.colorScheme.primary)
                                Text(localizedText("copy_bdbfa1d0c9", displaySignedSummaryAmount("expense", currencySummary.expense)), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
        if (state.selectedIds.isNotEmpty()) {
            SectionCard {
                Text(localizedText("copy_f38a8170a8", state.selectedIds.size), style = MaterialTheme.typography.titleMedium)
                ChoicePicker(
                    label = localizedText("copy_a42e73f0a7"),
                    value = state.selectedCategory,
                    options = listOf("" to localizedText("copy_3759bf861f"), UNCATEGORIZED_SELECTION to localizedText("copy_f11956caf6")) + state.categories.map { it.id to cashCategoryDisplayPath(it) },
                    onSelected = viewModel::setSelectedCategory,
                    enabled = canWrite,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.classifySelection(canWrite) },
                        enabled = canWrite && state.selectedCategory.isNotEmpty() && !state.classifyBusy,
                    ) { Text(if (state.classifyBusy) localizedText("copy_1cac8ac7f5") else localizedText("copy_3b314319d9")) }
                    TextButton(onClick = { viewModel.requestDeleteSelection(canWrite) }, enabled = canWrite && !state.deleteProjectionBusy) { Text(localizedText("copy_5d071a7a42")) }
                    TextButton(onClick = viewModel::clearSelection) { Text(localizedText("copy_f02e943954")) }
                }
                InlineError(classifyError ?: deleteProjectionError)
            }
        }
        if (state.loading && state.rows.isEmpty()) StateMessage(localizedText("copy_ced64cb470"))
        if (pageError != null) StateMessage(pageError, isError = true) {
            TextButton(onClick = viewModel::reload, modifier = Modifier.testTag(SemanticIds.ledgerRetry)) { Text(localizedText("copy_e2d53a6d3a")) }
        }
        if (!state.loading && pageError == null && state.rows.isEmpty()) StateMessage(localizedText("copy_8eb3d2788b"), modifier = Modifier.testTag(SemanticIds.ledgerEmpty))
        if (state.rows.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(
                    checked = state.rows.all { it.projectionId in state.selectedIds },
                    onCheckedChange = viewModel::selectAllLoaded,
                    enabled = canWrite,
                )
                Text(localizedText("copy_f0dce17676"))
            }
            if (sizeClass == WindowSizeClass.WIDE) {
                Row(
                    modifier = Modifier.fillMaxWidth().testTag(SemanticIds.ledgerTableHeader),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.width(48.dp))
                    FinanceTableCell(localizedText("copy_b6fed9af83"), weight = 2f, header = true)
                    FinanceTableCell(localizedText("copy_c3d92b20c8"), weight = 2f, header = true)
                    FinanceTableCell(localizedText("copy_34943c40c9"), weight = 1f, header = true)
                    Spacer(Modifier.width(148.dp))
                }
            }
        }
        rowGroups.forEach { group ->
            FinanceDataTable(
                rows = group.items,
                modifier = Modifier.testTag("${SemanticIds.ledgerTable}-${group.month}"),
                empty = { StateMessage(localizedText("copy_8eb3d2788b")) },
            ) { row ->
                CashProjectionRow(
                    item = row,
                    checked = row.projectionId in state.selectedIds,
                    canWrite = canWrite,
                    wide = sizeClass == WindowSizeClass.WIDE,
                    onChecked = { viewModel.toggleSelection(row.projectionId, it) },
                    onOpen = { viewModel.openRow(row) },
                    onClassify = {
                        viewModel.clearSelection()
                        viewModel.toggleSelection(row.projectionId, selected = true)
                        viewModel.setSelectedCategory(row.category?.id ?: UNCATEGORIZED_SELECTION)
                    },
                )
            }
        }
        if (appendError != null) {
            StateMessage(appendError, isError = true) {
                TextButton(onClick = viewModel::loadMore) { Text(localizedText("copy_b647ee0bdc")) }
            }
        }
        if (state.nextCursor != null && appendError == null) {
            Button(onClick = viewModel::loadMore, enabled = !state.loadingMore) {
                Text(if (state.loadingMore) localizedText("copy_fcabadb2a7") else localizedText("copy_3a0fab4978"))
            }
        }
    }

    if (state.selectedRow != null) {
        CashRecordDetailDialog(
            state = state,
            viewModel = viewModel,
            canWrite = canWrite,
            options = state.options,
            onEdit = { viewModel.editRecord() },
            onEditRelated = viewModel::editRecord,
            onClose = viewModel::closeSelectedRow,
        )
    }
    state.recordDraft?.let { draft ->
        CashRecordEditorDialog(
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
        )
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
    var advancedVisible by remember { mutableStateOf(false) }
    val accountFilter: @Composable (Modifier) -> Unit = { modifier ->
        FinanceSelect(localizedText("copy_c3d92b20c8"), value.accountId.orEmpty(), listOf("" to localizedText("copy_c5e5d57837")) + accounts.map { it.id.toString() to it.name }, { onChange(value.copy(accountId = it.ifEmpty { null })) }, modifier)
    }
    val categoryFilter: @Composable (Modifier) -> Unit = { modifier ->
        FinanceSelect(localizedText("copy_435c5259e4"), value.categoryId.orEmpty(), listOf("" to localizedText("copy_a8e369c4b6"), "__uncategorized__" to localizedText("copy_f11956caf6")) + categories.map { it.id to cashCategoryDisplayPath(it) }, {
            onChange(value.copy(categoryId = if (it.isEmpty() || it == "__uncategorized__") null else it, uncategorized = if (it == "__uncategorized__") "true" else null))
        }, modifier)
    }
    val counterpartyFilter: @Composable (Modifier) -> Unit = { modifier ->
        LabeledInput(value.counterparty.orEmpty(), { onChange(value.copy(counterparty = it.ifBlank { null })) }, localizedText("copy_4b5a03c119"), modifier = modifier)
    }
    val currenciesWithCurrent = (listOfNotNull(value.currency) + currencies).distinct()
    val currencyFilter: @Composable (Modifier) -> Unit = { modifier ->
        FinanceSelect(localizedText("copy_a81ab5e100"), value.currency.orEmpty(), listOf("" to localizedText("copy_7f566e2869")) + currenciesWithCurrent.map { it to it }, { onChange(value.copy(currency = it.ifEmpty { null })) }, modifier)
    }
    val economicTypeFilter: @Composable (Modifier) -> Unit = { modifier ->
        FinanceSelect(
            localizedText("copy_c939d425cd"),
            value.economicType.orEmpty(),
            listOf("" to localizedText("copy_4cfe6e1c20")) + typeOptions.map { it.economicType to economicTypeLabel(it.economicType) },
            { onChange(value.copy(economicType = it.ifEmpty { null }, transferSubtype = null)) },
            modifier,
        )
    }
    val amountMinFilter: @Composable (Modifier) -> Unit = { modifier ->
        val invalid = value.amountMin != null && !isExactDecimalString(value.amountMin)
        LabeledInput(value.amountMin.orEmpty(), { onChange(value.copy(amountMin = it.ifBlank { null })) }, localizedText("copy_d1e673ac4e"), modifier = modifier, isError = invalid, onBlur = { if (invalid) onInvalidAmount() })
    }
    val amountMaxFilter: @Composable (Modifier) -> Unit = { modifier ->
        val invalid = value.amountMax != null && !isExactDecimalString(value.amountMax)
        LabeledInput(value.amountMax.orEmpty(), { onChange(value.copy(amountMax = it.ifBlank { null })) }, localizedText("copy_e84edb075b"), modifier = modifier, isError = invalid, onBlur = { if (invalid) onInvalidAmount() })
    }
    FinanceFilterBar(title = localizedText("copy_dcce9a144a"), modifier = Modifier.testTag(SemanticIds.ledgerFilters)) {
        if (compact) {
            DatePickerInput(value.dateFrom.orEmpty(), { onChange(value.copy(dateFrom = it.ifBlank { null })) }, localizedText("copy_1f29196891"))
            DatePickerInput(value.dateTo.orEmpty(), { onChange(value.copy(dateTo = it.ifBlank { null })) }, localizedText("copy_f4b9b2b5de"))
            accountFilter(Modifier)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                DatePickerInput(value.dateFrom.orEmpty(), { onChange(value.copy(dateFrom = it.ifBlank { null })) }, localizedText("copy_1f29196891"), modifier = Modifier.weight(1f))
                DatePickerInput(value.dateTo.orEmpty(), { onChange(value.copy(dateTo = it.ifBlank { null })) }, localizedText("copy_f4b9b2b5de"), modifier = Modifier.weight(1f))
                if (sizeClass == WindowSizeClass.WIDE) {
                    accountFilter(Modifier.weight(1f))
                    categoryFilter(Modifier.weight(1f))
                    economicTypeFilter(Modifier.weight(1f))
                    currencyFilter(Modifier.weight(1f))
                }
            }
            if (sizeClass == WindowSizeClass.REGULAR) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    accountFilter(Modifier.weight(1f))
                    categoryFilter(Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    economicTypeFilter(Modifier.weight(1f))
                    currencyFilter(Modifier.weight(1f))
                }
            }
        }
        if (advancedVisible) {
            if (compact) {
                categoryFilter(Modifier)
                economicTypeFilter(Modifier)
                currencyFilter(Modifier)
                counterpartyFilter(Modifier)
                amountMinFilter(Modifier)
                amountMaxFilter(Modifier)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    counterpartyFilter(Modifier.weight(1f))
                    amountMinFilter(Modifier.weight(1f))
                    amountMaxFilter(Modifier.weight(1f))
                }
            }
            if (!value.economicType.isNullOrEmpty()) {
                val subtypes = typeOptions.firstOrNull { it.economicType == value.economicType }?.transferSubtypes.orEmpty()
                if (subtypes.isNotEmpty()) FinanceSelect(localizedText("copy_82fa0e654c"), value.transferSubtype.orEmpty(), listOf("" to localizedText("copy_778fc8f994")) + subtypes.map { it to transferSubtypeLabel(it) }, { onChange(value.copy(transferSubtype = it.ifEmpty { null })) })
            }
            FinanceSelect(localizedText("copy_cc498b84ff"), value.composition.orEmpty(), listOf("" to localizedText("copy_778fc8f994"), "single" to localizedText("copy_4a70b7e986"), "payment_mirror" to localizedText("copy_2b6feb78fe"), "refund_offset" to localizedText("copy_b441aa2b9b"), "combined" to localizedText("copy_e5847fcd94")), { onChange(value.copy(composition = it.ifEmpty { null })) })
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = { advancedVisible = !advancedVisible },
                modifier = Modifier.testTag(SemanticIds.ledgerMoreFilters),
            ) { Text(localizedText(if (advancedVisible) "ledger_less_filters" else "ledger_more_filters")) }
            TextButton(onClick = { onReset(); advancedVisible = false }) { Text(localizedText("copy_7b15e5e8e7")) }
        }
    }
}

@Composable
private fun CashProjectionRow(
    item: CashProjection,
    checked: Boolean,
    canWrite: Boolean,
    wide: Boolean,
    onChecked: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onClassify: () -> Unit,
) {
    SectionCard(modifier = Modifier.testTag("${SemanticIds.ledgerRecord}-${item.projectionId}")) {
        if (wide) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(checked = checked, onCheckedChange = onChecked, enabled = canWrite)
                Column(Modifier.weight(2f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(localDateTimeDisplayLabel(item.occurredAt), style = MaterialTheme.typography.bodySmall)
                    Text(item.counterparty.ifBlank { localizedText("copy_58daac42e1") }, style = MaterialTheme.typography.bodyMedium)
                }
                Text(cashProjectionAccountLabel(item), modifier = Modifier.weight(2f), style = MaterialTheme.typography.bodyMedium)
                FinanceAmount(cashProjectionAmountLabel(item), modifier = Modifier.weight(1f), negative = item.amount.startsWith("-"))
                TextButton(onClick = onOpen, modifier = Modifier.testTag(SemanticIds.ledgerOpenRecord)) { Text(localizedText("copy_faea8c1db9")) }
                TextButton(onClick = onClassify, enabled = canWrite) { Text(localizedText("copy_a798298529")) }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(checked = checked, onCheckedChange = onChecked, enabled = canWrite)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.counterparty.ifBlank { item.account?.name ?: localizedText("copy_58daac42e1") }, style = MaterialTheme.typography.titleMedium)
                    Text(localDateTimeDisplayLabel(item.occurredAt), style = MaterialTheme.typography.bodySmall)
                    Text("${cashProjectionAccountLabel(item)} · ${cashCategoryDisplayPath(item.category)}", style = MaterialTheme.typography.bodySmall)
                    Text(cashProjectionEconomicTypeLabel(item), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    cashProjectionSourceLabel(item)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (item.note.isNotBlank()) Text(item.note, style = MaterialTheme.typography.bodySmall)
                }
                FinanceAmount(cashProjectionAmountLabel(item), negative = item.amount.startsWith("-"))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpen, modifier = Modifier.testTag(SemanticIds.ledgerOpenRecord)) { Text(localizedText("copy_faea8c1db9")) }
                TextButton(onClick = onClassify, enabled = canWrite) { Text(localizedText("copy_a798298529")) }
            }
        }
    }
}
