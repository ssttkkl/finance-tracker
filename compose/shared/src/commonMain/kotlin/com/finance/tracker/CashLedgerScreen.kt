package com.finance.tracker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private data class CashRecordDraft(
    val id: String? = null,
    val originalCategoryId: String? = null,
    val occurredAt: String = "",
    val amount: String = "",
    val currency: String = "CNY",
    val counterparty: String = "",
    val counterpartyAccount: String = "",
    val note: String = "",
    val accountName: String = "",
    val recordType: String = "",
    val recordSubtype: String = "not_applicable",
    val categoryId: String? = null,
    val projectionVersion: Long? = null,
)

@Composable
internal fun CashLedgerScreen(
    api: FinanceApiClient,
    session: SessionDto,
    sizeClass: WindowSizeClass,
    onNavigate: (AppPage) -> Unit,
) {
    var accounts by remember(api) { mutableStateOf<List<AccountDto>>(emptyList()) }
    var categories by remember(api) { mutableStateOf<List<CashCategoryDto>>(emptyList()) }
    var options by remember(api) { mutableStateOf(LedgerOptionsDto()) }
    var page by remember(api) { mutableStateOf<CashPageDto?>(null) }
    var filterOptions by remember(api) { mutableStateOf(CashFilterOptionsDto()) }
    var rows by remember(api) { mutableStateOf<List<CashProjectionDto>>(emptyList()) }
    var nextCursor by remember(api) { mutableStateOf<String?>(null) }
    var projectionVersion by remember(api) { mutableStateOf(0L) }
    var loading by remember(api) { mutableStateOf(true) }
    var loadingMore by remember(api) { mutableStateOf(false) }
    var appendError by remember(api) { mutableStateOf<String?>(null) }
    var error by remember(api) { mutableStateOf<String?>(null) }
    var referencesError by remember(api) { mutableStateOf<String?>(null) }
    var appliedFilters by remember { mutableStateOf(CashFiltersDto()) }
    var editingFilters by remember { mutableStateOf(CashFiltersDto()) }
    var reloadKey by remember { mutableStateOf(0) }
    val selectedIds = remember { mutableStateListOf<String>() }
    var selectedCategory by remember { mutableStateOf("") }
    var selectedRow by remember { mutableStateOf<CashProjectionDto?>(null) }
    var evidence by remember { mutableStateOf<EvidenceDto?>(null) }
    var recordDetail by remember { mutableStateOf<CashRecordDetailDto?>(null) }
    var recordLoading by remember { mutableStateOf(false) }
    var recordError by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<CashRecordDraft?>(null) }
    var recordBusy by remember { mutableStateOf(false) }
    var recordErrorMessage by remember { mutableStateOf<String?>(null) }
    var confirmRelationImpact by remember { mutableStateOf(false) }
    var deleteProjectionImpact by remember { mutableStateOf<CashProjectionDeleteImpactDto?>(null) }
    var deleteProjectionError by remember { mutableStateOf<String?>(null) }
    var classifyBusy by remember { mutableStateOf(false) }
    var classifyError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val canWrite = session.workspaces.firstOrNull { it.id == session.activeWorkspaceId }?.role?.canWrite == true
    val rowGroups = cashProjectionMonthGroups(rows, page?.monthlySummaries.orEmpty())

    fun loadReferences() {
        scope.launch {
            referencesError = null
            var failure: Throwable? = null
            try { accounts = api.fetchCashAccounts() } catch (cause: Throwable) { failure = cause }
            try { options = api.fetchLedgerOptions() } catch (cause: Throwable) { failure = failure ?: cause }
            try { categories = api.fetchCashCategories().items } catch (cause: Throwable) { failure = failure ?: cause }
            referencesError = failure?.let(::userError)
        }
    }

    LaunchedEffect(api) { loadReferences() }

    LaunchedEffect(api, appliedFilters, reloadKey) {
        loading = true
        error = null
        appendError = null
        rows = emptyList()
        selectedIds.clear()
        try {
            val result = api.fetchCashPage(appliedFilters)
            page = result
            filterOptions = result.filterOptions
            rows = result.items
            nextCursor = result.nextCursor
            projectionVersion = result.projectionVersion
        } catch (cause: Throwable) {
            page = null
            error = userError(cause)
        } finally {
            loading = false
        }
    }

    fun openRow(item: CashProjectionDto) {
        selectedRow = item
        evidence = null
        recordDetail = null
        recordError = null
        recordLoading = true
        scope.launch {
            try {
                val loadedEvidence = api.fetchEvidence(item.projectionId)
                evidence = loadedEvidence
                recordDetail = api.fetchCashRecord(loadedEvidence.rootRecord.id)
            } catch (cause: Throwable) {
                recordError = userError(cause)
            } finally {
                recordLoading = false
            }
        }
    }

    fun loadMoreRows() {
        val cursor = nextCursor ?: return
        if (loadingMore) return
        scope.launch {
            loadingMore = true
            appendError = null
            try {
                val result = api.fetchCashPage(appliedFilters, cursor)
                if (result.projectionVersion != projectionVersion) {
                    reloadKey++
                } else {
                    val existingIds = rows.mapTo(mutableSetOf(), CashProjectionDto::projectionId)
                    rows = rows + result.items.filter { it.projectionId !in existingIds }
                    nextCursor = result.nextCursor
                    page = page?.copy(monthlySummaries = result.monthlySummaries)
                }
            } catch (cause: Throwable) {
                appendError = userError(cause)
            } finally {
                loadingMore = false
            }
        }
    }

    fun startNewRecord() {
        recordErrorMessage = null
        confirmRelationImpact = false
        val firstAccount = accounts.firstOrNull()
        val firstType = options.recordTypes.firstOrNull()
        draft = CashRecordDraft(
            occurredAt = localDateTimeForInput(),
            accountName = firstAccount?.name.orEmpty(),
            currency = firstAccount?.currencies?.firstOrNull() ?: "CNY",
            recordType = firstType?.value.orEmpty(),
            recordSubtype = firstType?.subtypes?.firstOrNull()?.value ?: "not_applicable",
        )
    }

    fun openEditRecord(recordOverride: CashRecordDto? = null) {
        val detail = recordDetail ?: return
        val record = recordOverride ?: detail.record
        draft = CashRecordDraft(
            id = record.id,
            originalCategoryId = record.categoryId ?: record.category?.id,
            occurredAt = record.occurredAt.take(16),
            amount = record.amount,
            currency = record.currency,
            counterparty = record.counterparty,
            counterpartyAccount = record.counterpartyAccount,
            note = record.note,
            accountName = record.accountName,
            recordType = record.recordType,
            recordSubtype = record.recordSubtype,
            categoryId = record.categoryId ?: record.category?.id,
            projectionVersion = evidence?.projectionVersion,
        )
        recordErrorMessage = null
    }

    fun saveDraft(value: CashRecordDraft, confirmImpact: Boolean = false) {
        scope.launch {
            recordBusy = true
            recordErrorMessage = null
            try {
                val body = cashRecordBody(value, confirmImpact)
                val result = if (value.id == null) api.createCashRecord(body) else api.updateCashRecord(value.id, body)
                draft = null
                val currentSelection = selectedRow
                if (currentSelection == null) {
                    recordDetail = result
                } else {
                    openRow(currentSelection)
                }
                reloadKey++
            } catch (cause: Throwable) {
                if ((cause as? ApiFailure)?.code == "relation_impact_required" && !confirmImpact) {
                    confirmRelationImpact = true
                } else {
                    recordErrorMessage = userError(cause)
                }
            } finally {
                recordBusy = false
            }
        }
    }

    FeaturePage("收支账本", SemanticIds.ledgerScreen) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = ::startNewRecord, enabled = canWrite, modifier = Modifier.testTag(SemanticIds.ledgerAdd)) { Text("记一笔") }
            OutlinedButton(onClick = { onNavigate(AppPage.CASH_IMPORT) }, enabled = canWrite, modifier = Modifier.testTag(SemanticIds.ledgerImport)) { Text("导入账单") }
        }
        if (referencesError != null) StateMessage(referencesError.orEmpty(), isError = true) {
            TextButton(onClick = ::loadReferences) { Text("重试") }
        }
        CashFilterPanel(
            value = editingFilters,
            sizeClass = sizeClass,
            onChange = {
                editingFilters = it
                appliedFilters = it
                selectedIds.clear()
            },
            accounts = accounts,
            categories = categories,
            currencies = filterOptions.currencies,
            typeOptions = filterOptions.economicTypes,
            onReset = {
                editingFilters = CashFiltersDto()
                appliedFilters = CashFiltersDto()
                selectedIds.clear()
            },
        )
        rowGroups.forEach { group ->
            SectionCard(modifier = Modifier.testTag(SemanticIds.ledgerSummary)) {
                Text(localMonthLabel(group.month), style = MaterialTheme.typography.titleMedium)
                if (group.summary?.currencies.isNullOrEmpty()) Text("无收支", style = MaterialTheme.typography.bodySmall)
                group.summary?.currencies.orEmpty().forEach { currencySummary ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        if (sizeClass == WindowSizeClass.COMPACT) {
                            Column(Modifier.weight(1f)) {
                                Text("收入 ${displaySignedSummaryAmount("income", currencySummary.income)}", color = MaterialTheme.colorScheme.primary)
                                Text("支出 ${displaySignedSummaryAmount("expense", currencySummary.expense)}", color = MaterialTheme.colorScheme.error)
                            }
                            Text(currencySummary.currency)
                        } else {
                            Text(currencySummary.currency)
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("收入 ${displaySignedSummaryAmount("income", currencySummary.income)}", color = MaterialTheme.colorScheme.primary)
                                Text("支出 ${displaySignedSummaryAmount("expense", currencySummary.expense)}", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
        if (selectedIds.isNotEmpty()) {
            SectionCard {
                Text("已选 ${selectedIds.size} 项", style = MaterialTheme.typography.titleMedium)
                ChoicePicker(
                    label = "收支分类",
                    value = selectedCategory,
                    options = listOf("" to "选择分类", "__uncategorized__" to "无分类") + categories.map { it.id to cashCategoryDisplayPath(it) },
                    onSelected = { selectedCategory = it },
                    enabled = canWrite,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                classifyBusy = true
                                classifyError = null
                                try {
                                    api.classifyCashProjections(selectedIds.toList(), projectionVersion, selectedCategory.takeUnless { it.isEmpty() || it == "__uncategorized__" })
                                    reloadKey++
                                } catch (cause: Throwable) {
                                    classifyError = userError(cause)
                                } finally {
                                    classifyBusy = false
                                }
                            }
                        },
                        enabled = canWrite && selectedCategory.isNotEmpty() && !classifyBusy,
                    ) { Text(if (classifyBusy) "处理中…" else "应用分类") }
                    TextButton(onClick = {
                        scope.launch {
                            deleteProjectionError = null
                            try {
                                deleteProjectionImpact = api.fetchCashProjectionDeleteImpact(selectedIds.toList(), projectionVersion)
                            } catch (cause: Throwable) {
                                deleteProjectionError = userError(cause)
                            }
                        }
                    }, enabled = canWrite) { Text("删除所选") }
                    TextButton(onClick = { selectedIds.clear(); classifyError = null; deleteProjectionError = null }) { Text("取消选择") }
                }
                InlineError(classifyError ?: deleteProjectionError)
            }
        }
        if (loading && rows.isEmpty()) StateMessage("正在读取收支账本…")
        if (error != null) StateMessage(error.orEmpty(), isError = true) {
            TextButton(onClick = { reloadKey++ }, modifier = Modifier.testTag(SemanticIds.ledgerRetry)) { Text("重试") }
        }
        if (!loading && error == null && rows.isEmpty()) StateMessage("没有匹配的收支记录。", modifier = Modifier.testTag(SemanticIds.ledgerEmpty))
        if (rows.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(
                    checked = rows.all { it.projectionId in selectedIds },
                    onCheckedChange = { checked ->
                        if (checked) {
                            rows.map(CashProjectionDto::projectionId).filterNot { it in selectedIds }.forEach(selectedIds::add)
                        } else {
                            selectedIds.removeAll(rows.map(CashProjectionDto::projectionId).toSet())
                        }
                    },
                    enabled = canWrite,
                )
                Text("全选当前已加载")
            }
        }
        rowGroups.forEach { group ->
            group.items.forEach { row ->
                CashProjectionRow(
                    item = row,
                    checked = row.projectionId in selectedIds,
                    canWrite = canWrite,
                    onChecked = { checked ->
                        if (checked && row.projectionId !in selectedIds) selectedIds.add(row.projectionId)
                        if (!checked) selectedIds.remove(row.projectionId)
                    },
                    onOpen = { openRow(row) },
                    onClassify = {
                        selectedIds.clear()
                        selectedIds.add(row.projectionId)
                        selectedCategory = row.category?.id ?: "__uncategorized__"
                    },
                )
            }
        }
        InlineError(deleteProjectionError)
        if (appendError != null) {
            StateMessage(appendError.orEmpty(), isError = true) {
                TextButton(onClick = ::loadMoreRows) { Text("重试加载") }
            }
        }
        if (nextCursor != null && appendError == null) {
            Button(
                onClick = ::loadMoreRows,
                enabled = !loadingMore,
            ) { Text(if (loadingMore) "正在读取…" else "加载更多") }
        }
    }

    if (selectedRow != null) {
        CashRecordDetailDialog(
            row = selectedRow!!,
            evidence = evidence,
            detail = recordDetail,
            loading = recordLoading,
            error = recordError,
            onRetry = { selectedRow?.let(::openRow) },
            canWrite = canWrite,
            api = api,
            accounts = accounts,
            categories = categories,
            options = options,
            onEdit = { openEditRecord() },
            onEditRelated = { related -> openEditRecord(related) },
            onClose = { selectedRow = null; evidence = null; recordDetail = null },
            onChanged = { updated ->
                if (updated == null) {
                    selectedRow = null
                    evidence = null
                    recordDetail = null
                } else {
                    recordDetail = updated
                    selectedRow?.let { selected ->
                        scope.launch { evidence = runCatching { api.fetchEvidence(selected.projectionId) }.getOrNull() }
                    }
                }
                reloadKey++
            },
        )
    }
    draft?.let { activeDraft ->
        CashRecordEditorDialog(
            draft = activeDraft,
            sizeClass = sizeClass,
            accounts = accounts,
            categories = categories,
            options = options,
            busy = recordBusy,
            error = recordErrorMessage,
            onChange = { draft = it },
            onSave = { saveDraft(activeDraft) },
            onCancel = { draft = null; confirmRelationImpact = false },
        )
    }
    if (confirmRelationImpact && draft != null) {
        ConfirmationDialog(
            title = "确认修改",
            message = "更改分类会影响关联流水。",
            confirmLabel = "继续保存",
            onConfirm = { saveDraft(draft!!, confirmImpact = true); confirmRelationImpact = false },
            onDismiss = { confirmRelationImpact = false },
            busy = recordBusy,
        )
    }
    deleteProjectionImpact?.let { impact ->
        ConfirmationDialog(
            title = "删除所选收支",
            message = "将删除 ${impact.projectionCount} 个收支条目、${impact.transactionCount} 笔流水，并处理 ${impact.relationGroupCount} 组关联。",
            confirmLabel = "删除",
            onConfirm = {
                scope.launch {
                    try {
                        api.deleteCashProjections(selectedIds.toList(), projectionVersion)
                        deleteProjectionImpact = null
                        selectedIds.clear()
                        reloadKey++
                    } catch (cause: Throwable) {
                        deleteProjectionError = userError(cause)
                    }
                }
            },
            onDismiss = { deleteProjectionImpact = null },
            error = deleteProjectionError,
        )
    }
}

@Composable
private fun CashFilterPanel(
    value: CashFiltersDto,
    sizeClass: WindowSizeClass,
    onChange: (CashFiltersDto) -> Unit,
    accounts: List<AccountDto>,
    categories: List<CashCategoryDto>,
    currencies: List<String>,
    typeOptions: List<CashEconomicTypeFilterOptionDto>,
    onReset: () -> Unit,
) {
    val compact = sizeClass == WindowSizeClass.COMPACT
    val accountFilter: @Composable (Modifier) -> Unit = { modifier ->
        ChoicePicker("账户", value.accountId.orEmpty(), listOf("" to "全部账户") + accounts.map { it.id.toString() to it.name }, { onChange(value.copy(accountId = it.ifEmpty { null })) }, modifier)
    }
    val categoryFilter: @Composable (Modifier) -> Unit = { modifier ->
        ChoicePicker("分类", value.categoryId.orEmpty(), listOf("" to "全部分类", "__uncategorized__" to "无分类") + categories.map { it.id to cashCategoryDisplayPath(it) }, {
            onChange(value.copy(categoryId = if (it.isEmpty() || it == "__uncategorized__") null else it, uncategorized = if (it == "__uncategorized__") "true" else null))
        }, modifier)
    }
    val counterpartyFilter: @Composable (Modifier) -> Unit = { modifier ->
        LabeledInput(value.counterparty.orEmpty(), { onChange(value.copy(counterparty = it.ifBlank { null })) }, "交易对方", modifier = modifier)
    }
    val currenciesWithCurrent = (listOfNotNull(value.currency) + currencies).distinct()
    val currencyFilter: @Composable (Modifier) -> Unit = { modifier ->
        ChoicePicker("币种", value.currency.orEmpty(), listOf("" to "全部币种") + currenciesWithCurrent.map { it to it }, { onChange(value.copy(currency = it.ifEmpty { null })) }, modifier)
    }
    val amountMinFilter: @Composable (Modifier) -> Unit = { modifier ->
        LabeledInput(value.amountMin.orEmpty(), { onChange(value.copy(amountMin = it.ifBlank { null })) }, "最低金额", modifier = modifier, isError = value.amountMin != null && !isExactDecimalString(value.amountMin))
    }
    val amountMaxFilter: @Composable (Modifier) -> Unit = { modifier ->
        LabeledInput(value.amountMax.orEmpty(), { onChange(value.copy(amountMax = it.ifBlank { null })) }, "最高金额", modifier = modifier, isError = value.amountMax != null && !isExactDecimalString(value.amountMax))
    }
    SectionCard(modifier = Modifier.testTag(SemanticIds.ledgerFilters)) {
        Text("筛选", style = MaterialTheme.typography.titleMedium)
        if (compact) {
            DatePickerInput(value.dateFrom.orEmpty(), { onChange(value.copy(dateFrom = it.ifBlank { null })) }, "开始日期")
            DatePickerInput(value.dateTo.orEmpty(), { onChange(value.copy(dateTo = it.ifBlank { null })) }, "结束日期")
            accountFilter(Modifier)
            categoryFilter(Modifier)
            counterpartyFilter(Modifier)
            currencyFilter(Modifier)
            amountMinFilter(Modifier)
            amountMaxFilter(Modifier)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                DatePickerInput(value.dateFrom.orEmpty(), { onChange(value.copy(dateFrom = it.ifBlank { null })) }, "开始日期", modifier = Modifier.weight(1f))
                DatePickerInput(value.dateTo.orEmpty(), { onChange(value.copy(dateTo = it.ifBlank { null })) }, "结束日期", modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                accountFilter(Modifier.weight(1f))
                categoryFilter(Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                counterpartyFilter(Modifier.weight(1f))
                currencyFilter(Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                amountMinFilter(Modifier.weight(1f))
                amountMaxFilter(Modifier.weight(1f))
            }
        }
        ChoicePicker(
            "收支类型",
            value.economicType.orEmpty(),
            listOf("" to "全部收支") + typeOptions.map { it.economicType to economicTypeLabel(it.economicType) },
            { onChange(value.copy(economicType = it.ifEmpty { null }, transferSubtype = null)) },
        )
        if (!value.economicType.isNullOrEmpty()) {
            val subtypes = typeOptions.firstOrNull { it.economicType == value.economicType }?.transferSubtypes.orEmpty()
            if (subtypes.isNotEmpty()) ChoicePicker("资金转移类型", value.transferSubtype.orEmpty(), listOf("" to "全部") + subtypes.map { it to transferSubtypeLabel(it) }, { onChange(value.copy(transferSubtype = it.ifEmpty { null })) })
        }
        ChoicePicker("合并状态", value.composition.orEmpty(), listOf("" to "全部", "single" to "未合并", "payment_mirror" to "同笔支付", "refund_offset" to "退款冲销", "combined" to "其他合并"), { onChange(value.copy(composition = it.ifEmpty { null })) })
        TextButton(onClick = onReset) { Text("清除") }
    }
}

@Composable
private fun CashProjectionRow(
    item: CashProjectionDto,
    checked: Boolean,
    canWrite: Boolean,
    onChecked: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onClassify: () -> Unit,
) {
    SectionCard(modifier = Modifier.testTag("${SemanticIds.ledgerRecord}-${item.projectionId}")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(checked = checked, onCheckedChange = onChecked, enabled = canWrite)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.counterparty.ifBlank { item.account?.name ?: "收支记录" }, style = MaterialTheme.typography.titleMedium)
                Text("${localDateTimeDisplayLabel(item.occurredAt)}　${cashProjectionAccountLabel(item)}　${cashCategoryDisplayPath(item.category)}", style = MaterialTheme.typography.bodySmall)
                Text(cashProjectionEconomicTypeLabel(item), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                cashProjectionSourceLabel(item)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (item.note.isNotBlank()) Text(item.note, style = MaterialTheme.typography.bodySmall)
            }
            Text(cashProjectionAmountLabel(item), style = MaterialTheme.typography.titleMedium, color = if (item.amount.startsWith("-")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onOpen, modifier = Modifier.testTag(SemanticIds.ledgerOpenRecord)) { Text("查看详情") }
            TextButton(onClick = onClassify, enabled = canWrite) { Text("更改分类") }
        }
    }
}

@Composable
private fun CashRecordDetailDialog(
    row: CashProjectionDto,
    evidence: EvidenceDto?,
    detail: CashRecordDetailDto?,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    canWrite: Boolean,
    api: FinanceApiClient,
    accounts: List<AccountDto>,
    categories: List<CashCategoryDto>,
    options: LedgerOptionsDto,
    onEdit: () -> Unit,
    onEditRelated: (CashRecordDto) -> Unit,
    onClose: () -> Unit,
    onChanged: (CashRecordDetailDto?) -> Unit,
) {
    val record = detail?.record
    var showRelationComposer by remember(row.projectionId) { mutableStateOf(false) }
    var relationQuery by remember(row.projectionId) { mutableStateOf("") }
    var relationCandidates by remember(row.projectionId) { mutableStateOf<List<CashRecordDto>>(emptyList()) }
    var relationType by remember(row.projectionId) { mutableStateOf(options.relationTypes.firstOrNull()?.value ?: "payment_mirror") }
    var relationDateFrom by remember(row.projectionId) { mutableStateOf("") }
    var relationDateTo by remember(row.projectionId) { mutableStateOf("") }
    var relationNextCursor by remember(row.projectionId) { mutableStateOf<String?>(null) }
    var relationPageNumber by remember(row.projectionId) { mutableStateOf(1) }
    var relationPageStarts by remember(row.projectionId) { mutableStateOf<List<String?>>(listOf(null)) }
    var relationLoadError by remember(row.projectionId) { mutableStateOf(false) }
    var relationErrorPage by remember(row.projectionId) { mutableStateOf<Int?>(null) }
    var relationErrorCursor by remember(row.projectionId) { mutableStateOf<String?>(null) }
    var relationTarget by remember(row.projectionId) { mutableStateOf<String?>(null) }
    var relationBusy by remember(row.projectionId) { mutableStateOf(false) }
    var editingRelationId by remember(row.projectionId) { mutableStateOf<String?>(null) }
    var editingRelationKind by remember(row.projectionId) { mutableStateOf<String?>(null) }
    var localError by remember(row.projectionId) { mutableStateOf<String?>(null) }
    var deleteConfirm by remember(row.projectionId) { mutableStateOf(false) }
    var deleteBusy by remember(row.projectionId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun loadRelationPage(targetPage: Int, cursor: String?) {
        val activeRecord = record ?: return
        relationBusy = true
        relationLoadError = false
        relationErrorPage = null
        relationErrorCursor = null
        localError = null
        try {
            val result = api.fetchCashRecords(
                query = relationQuery.trim(),
                excludeId = activeRecord.id,
                dateFrom = relationDateFrom,
                dateTo = relationDateTo,
                cursor = cursor,
                limit = 20,
            )
            relationCandidates = result.items
            relationNextCursor = result.nextCursor
            relationPageNumber = targetPage
            relationPageStarts = relationPageStarts.take(targetPage) + result.nextCursor
            relationTarget = relationTarget?.takeIf { selected -> result.items.any { it.id == selected } }
        } catch (cause: Throwable) {
            relationCandidates = emptyList()
            relationNextCursor = null
            relationLoadError = true
            relationErrorPage = targetPage
            relationErrorCursor = cursor
            localError = userError(cause)
        } finally {
            relationBusy = false
        }
    }

    LaunchedEffect(record?.id) {
        val current = record ?: return@LaunchedEffect
        val range = cashRelationDateRange(getLocalDateForInstant(current.occurredAt))
        relationDateFrom = range?.from.orEmpty()
        relationDateTo = range?.to.orEmpty()
    }

    LaunchedEffect(showRelationComposer, record?.id, relationQuery, relationDateFrom, relationDateTo) {
        if (!showRelationComposer || record == null) return@LaunchedEffect
        relationTarget = null
        relationPageNumber = 1
        relationPageStarts = listOf(null)
        if (!isValidCashRelationDateFilter(relationDateFrom, relationDateTo)) {
            relationCandidates = emptyList()
            relationNextCursor = null
            relationLoadError = false
            return@LaunchedEffect
        }
        if (relationQuery.isNotBlank()) delay(250)
        loadRelationPage(targetPage = 1, cursor = null)
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        SectionCard(modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp).heightIn(max = 760.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("流水详情", style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = onClose) { Text("关闭") }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    loading -> Text("正在读取流水…")
                    error != null -> {
                        InlineError(error)
                        TextButton(onClick = onRetry) { Text("重试") }
                    }
                    record != null -> {
                        Text(record.counterparty.ifBlank { record.accountName }, style = MaterialTheme.typography.titleLarge)
                        Text("${record.amount} ${record.currency}　${formatLocalDateTime(record.occurredAt)}")
                        Text("${cashRecordTypeLabel(record.recordType, options)} · ${record.accountName} · ${cashCategoryDisplayPath(record.category)}")
                        record.counterpartyAccount.takeIf(String::isNotBlank)?.let { Text("对方账户：$it") }
                        record.note.takeIf(String::isNotBlank)?.let { Text(it) }
                        HorizontalDivider()
                        Text("关联流水", style = MaterialTheme.typography.titleMedium)
                        if (evidence != null) {
                            HorizontalDivider()
                            Text("来源：${cashEvidenceSourceLabel(evidence)}", style = MaterialTheme.typography.bodySmall)
                            record.components.orEmpty().takeIf { it.size > 1 }?.let { components ->
                                Text("支付组成项", style = MaterialTheme.typography.titleMedium)
                                components.forEach { component ->
                                    Text("${component.account?.name ?: component.accountName ?: "未指定账户"} · ${component.amount} ${component.currency}")
                                }
                            }
                            evidence.members.filterNot { it.id == evidence.rootRecord.id }.forEach { member ->
                                HorizontalDivider()
                                Text("${cashEvidenceMemberLabel(member, options, evidence.projection)} · ${formatLocalDateTime(member.occurredAt)}", style = MaterialTheme.typography.titleSmall)
                                Text("${member.amount} ${member.currency} · ${member.accountName ?: member.account?.name ?: "多个账户"}")
                                Text("交易对方：${member.counterparty.ifBlank { "-" }} · 流水类型：${cashEvidenceMemberLabel(member, options, evidence.projection)}")
                                Text("分类：${cashCategoryDisplayPath(member.category)} · 备注：${member.note.ifBlank { "-" }} · 来源：${member.sourceType ?: "-"}")
                                Text(cashEvidenceMemberImpactLabel(member), style = MaterialTheme.typography.bodySmall)
                            }
                            evidence.refundTimeline.forEach { refund -> Text("退款 · ${formatLocalDateTime(refund.occurredAt)} · ${refund.amount} ${refund.currency}") }
                            evidence.acceptedRelations.forEach { relation -> Text("已关联：${cashRelationTypeLabel(relation.kind, options)}") }
                        }
                        detail.relations.filter { it.status == "accepted" }.forEach { relation ->
                            val related = if (relation.primaryRecord?.id == record.id) relation.secondaryRecord else relation.primaryRecord
                            HorizontalDivider()
                            Text(relation.label, style = MaterialTheme.typography.titleMedium)
                            val relatedEvidence = evidence?.members?.firstOrNull { member ->
                                member.id == related?.id || member.recordId == related?.id
                            }
                            Text("${related?.amount ?: "-"} ${related?.currency.orEmpty()} · ${related?.let { formatLocalDateTime(it.occurredAt) } ?: "-"}")
                            Text("${related?.accountName ?: "-"} · ${related?.counterparty?.ifBlank { "未填写交易对方" } ?: "-"}")
                            related?.counterpartyAccount?.takeIf(String::isNotBlank)?.let { Text("对方账户：$it") }
                            Text("流水类型：${related?.let { cashRecordTypeLabel(it.recordType, options) } ?: "-"} · 分类：${cashCategoryDisplayPath(related?.category)}")
                            Text("备注：${related?.note?.ifBlank { "-" } ?: "-"} · 来源：${related?.sourceType ?: "-"}")
                            relatedEvidence?.let { Text("影响：${cashEvidenceMemberImpactLabel(it)}") }
                            TextButton(onClick = { related?.let(onEditRelated) }, enabled = canWrite && !relationBusy && related != null) { Text("编辑流水") }
                            if (editingRelationId == relation.id) {
                                ChoicePicker("关联类型", editingRelationKind ?: relation.kind, options.relationTypes.map { it.value to it.label }, { editingRelationKind = it }, enabled = canWrite && !relationBusy)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TextButton(onClick = {
                                        scope.launch {
                                            relationBusy = true
                                            localError = null
                                            try {
                                                val updated = api.updateCashRelation(relation.id, editingRelationKind ?: relation.kind)
                                                editingRelationId = null
                                                editingRelationKind = null
                                                onChanged(updated)
                                            } catch (cause: Throwable) { localError = userError(cause) }
                                            finally { relationBusy = false }
                                        }
                                    }, enabled = canWrite && !relationBusy && editingRelationKind != relation.kind) { Text("保存") }
                                    TextButton(onClick = { editingRelationId = null; editingRelationKind = null }, enabled = !relationBusy) { Text("取消") }
                                }
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TextButton(onClick = { editingRelationId = relation.id; editingRelationKind = relation.kind }, enabled = canWrite && !relationBusy) { Text("更改类型") }
                                    TextButton(onClick = {
                                        scope.launch {
                                            relationBusy = true
                                            localError = null
                                            try { api.cancelCashRelation(relation.id); onChanged(api.fetchCashRecord(record.id)) }
                                            catch (cause: Throwable) { localError = userError(cause) }
                                            finally { relationBusy = false }
                                        }
                                    }, enabled = canWrite && !relationBusy) { Text("取消关联") }
                                }
                            }
                        }
                        if (showRelationComposer) {
                            HorizontalDivider()
                            Text("关联另一笔流水", style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DatePickerInput(relationDateFrom, { relationDateFrom = it }, "开始日期", modifier = Modifier.weight(1f))
                                DatePickerInput(relationDateTo, { relationDateTo = it }, "结束日期", modifier = Modifier.weight(1f))
                            }
                            LabeledInput(relationQuery, { relationQuery = it }, "搜索流水（对方、账户或金额）")
                            ChoicePicker("关联类型", relationType, options.relationTypes.map { it.value to it.label }, { relationType = it })
                            if (relationBusy) Text("正在搜索…", style = MaterialTheme.typography.bodySmall)
                            if (relationLoadError) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("无法读取流水。", color = MaterialTheme.colorScheme.error)
                                    TextButton(onClick = {
                                        val failedPage = relationErrorPage ?: 1
                                        val failedCursor = relationErrorCursor
                                        scope.launch { loadRelationPage(failedPage, failedCursor) }
                                    }, enabled = !relationBusy) { Text("重试") }
                                }
                            }
                            if (!relationBusy && !relationLoadError && relationCandidates.isEmpty() && isValidCashRelationDateFilter(relationDateFrom, relationDateTo)) Text("没有找到流水。", style = MaterialTheme.typography.bodySmall)
                            relationCandidates.forEach { candidate ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    androidx.compose.material3.RadioButton(
                                        selected = relationTarget == candidate.id,
                                        onClick = { relationTarget = candidate.id },
                                        enabled = canWrite && !relationBusy,
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(candidate.counterparty.ifBlank { "未填写交易对方" }, style = MaterialTheme.typography.titleSmall)
                                        Text("${candidate.accountName} · ${formatLocalDateTime(candidate.occurredAt)}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text("${candidate.amount} ${candidate.currency}", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                            if (relationCandidates.isNotEmpty()) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("第 $relationPageNumber 页", style = MaterialTheme.typography.bodySmall)
                                    Row {
                                        TextButton(onClick = { scope.launch { loadRelationPage(relationPageNumber - 1, relationPageStarts.getOrNull(relationPageNumber - 2)) } }, enabled = relationPageNumber > 1 && !relationBusy) { Text("上一页") }
                                        TextButton(onClick = { scope.launch { loadRelationPage(relationPageNumber + 1, relationNextCursor) } }, enabled = relationNextCursor != null && !relationBusy) { Text("下一页") }
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { showRelationComposer = false; relationCandidates = emptyList(); relationTarget = null }) { Text("取消") }
                                Button(onClick = {
                                    val target = relationTarget ?: return@Button
                                    scope.launch {
                                        relationBusy = true
                                        localError = null
                                        try {
                                            val updated = api.createCashRelation(record.id, target, relationType)
                                            showRelationComposer = false
                                            relationCandidates = emptyList()
                                            relationTarget = null
                                            onChanged(updated)
                                        } catch (cause: Throwable) { localError = userError(cause) }
                                        finally { relationBusy = false }
                                    }
                                }, enabled = canWrite && relationTarget != null && !relationBusy) { Text(if (relationBusy) "正在添加…" else "添加关联") }
                            }
                        }
                        if (detail.relations.none { it.status == "accepted" } && !showRelationComposer) {
                            Text("暂无关联流水。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                InlineError(localError)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (record != null && canWrite) {
                    Button(onClick = onEdit) { Text("编辑") }
                    TextButton(onClick = {
                        val range = record.occurredAt.let { cashRelationDateRange(getLocalDateForInstant(it)) }
                        relationDateFrom = range?.from.orEmpty()
                        relationDateTo = range?.to.orEmpty()
                        relationQuery = ""
                        relationTarget = null
                        relationCandidates = emptyList()
                        relationNextCursor = null
                        relationPageNumber = 1
                        relationPageStarts = listOf(null)
                        relationLoadError = false
                        showRelationComposer = true
                    }, enabled = !relationBusy) { Text("新增关联") }
                    TextButton(onClick = {
                        scope.launch {
                            relationBusy = true
                            localError = null
                            try { onChanged(api.dissolveCashRelations(record.id)) }
                            catch (cause: Throwable) { localError = userError(cause) }
                            finally { relationBusy = false }
                        }
                    }, enabled = !relationBusy && detail.relations.any { it.status == "accepted" }) { Text("解散关联") }
                    TextButton(onClick = { deleteConfirm = true }, enabled = !deleteBusy) { Text("删除") }
                }
            }
        }
    }
    if (deleteConfirm && record != null) {
        AlertDialog(
            onDismissRequest = { if (!deleteBusy) deleteConfirm = false },
            title = { Text("删除流水") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val hasRelations = detail.relations.any { it.status == "accepted" }
                    Text(if (hasRelations) "选择如何处理当前关联。" else "删除后将从账本中移除。")
                    InlineError(localError)
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        scope.launch {
                            deleteBusy = true
                            try { api.deleteCashRecord(record.id, "delete_current_dissolve"); deleteConfirm = false; onChanged(null) }
                            catch (cause: Throwable) { localError = userError(cause) }
                            finally { deleteBusy = false }
                        }
                    }, enabled = !deleteBusy) { Text(if (detail.relations.any { it.status == "accepted" }) "仅删除此流水" else "确认删除") }
                    if (detail.relations.any { it.status == "accepted" }) TextButton(onClick = {
                        scope.launch {
                            deleteBusy = true
                            try { api.deleteCashRecord(record.id, "delete_all"); deleteConfirm = false; onChanged(null) }
                            catch (cause: Throwable) { localError = userError(cause) }
                            finally { deleteBusy = false }
                        }
                    }, enabled = !deleteBusy) { Text("删除整笔收支") }
                }
            },
            dismissButton = { TextButton(onClick = { deleteConfirm = false }, enabled = !deleteBusy) { Text("取消") } },
        )
    }
}

@Composable
private fun CashRecordEditorDialog(
    draft: CashRecordDraft,
    sizeClass: WindowSizeClass,
    accounts: List<AccountDto>,
    categories: List<CashCategoryDto>,
    options: LedgerOptionsDto,
    busy: Boolean,
    error: String?,
    onChange: (CashRecordDraft) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val selectedAccount = accounts.firstOrNull { it.name == draft.accountName }
    val selectedType = options.recordTypes.firstOrNull { it.value == draft.recordType }
    Dialog(onDismissRequest = { if (!busy) onCancel() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        SectionCard(modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp).heightIn(max = 800.dp)) {
            Text(if (draft.id == null) "记一笔" else "编辑流水", style = MaterialTheme.typography.headlineSmall)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DateTimePickerInput(draft.occurredAt, { onChange(draft.copy(occurredAt = it)) }, "发生时间", sizeClass)
                if (sizeClass == WindowSizeClass.COMPACT) {
                    LabeledInput(draft.amount, { onChange(draft.copy(amount = it)) }, "金额", semanticId = SemanticIds.recordAmount, isError = draft.amount.isNotBlank() && !isExactDecimalString(draft.amount))
                    ChoicePicker("币种", draft.currency, (selectedAccount?.currencies.orEmpty().ifEmpty { listOf(draft.currency) }).distinct().map { it to it }, { onChange(draft.copy(currency = it)) }, semanticId = SemanticIds.recordCurrency)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LabeledInput(draft.amount, { onChange(draft.copy(amount = it)) }, "金额", modifier = Modifier.weight(1f), semanticId = SemanticIds.recordAmount, isError = draft.amount.isNotBlank() && !isExactDecimalString(draft.amount))
                        ChoicePicker("币种", draft.currency, (selectedAccount?.currencies.orEmpty().ifEmpty { listOf(draft.currency) }).distinct().map { it to it }, { onChange(draft.copy(currency = it)) }, Modifier.weight(1f), semanticId = SemanticIds.recordCurrency)
                    }
                }
                ChoicePicker("账户", draft.accountName, accounts.map { it.name to it.name }, { accountName ->
                    val next = accounts.firstOrNull { it.name == accountName }
                    onChange(draft.copy(accountName = accountName, currency = next?.currencies?.firstOrNull() ?: draft.currency))
                }, semanticId = SemanticIds.recordAccount)
                ChoicePicker("流水类型", draft.recordType, options.recordTypes.map { it.value to it.label }, { type ->
                    val selected = options.recordTypes.firstOrNull { it.value == type }
                    onChange(draft.copy(recordType = type, recordSubtype = selected?.subtypes?.firstOrNull()?.value ?: "not_applicable"))
                }, semanticId = SemanticIds.recordType)
                if (selectedType?.subtypes?.isNotEmpty() == true) ChoicePicker("流水子类型", draft.recordSubtype, selectedType.subtypes.map { it.value to it.label }, { onChange(draft.copy(recordSubtype = it)) })
                ChoicePicker("收支分类", draft.categoryId.orEmpty(), listOf("" to "无分类") + categories.map { it.id to it.name }, { onChange(draft.copy(categoryId = it.ifEmpty { null })) }, semanticId = SemanticIds.recordCategory)
                LabeledInput(draft.counterparty, { onChange(draft.copy(counterparty = it)) }, "交易对方", semanticId = SemanticIds.recordCounterparty)
                LabeledInput(draft.counterpartyAccount, { onChange(draft.copy(counterpartyAccount = it)) }, "对方账户")
                LabeledInput(draft.note, { onChange(draft.copy(note = it)) }, "备注", semanticId = SemanticIds.recordNote, singleLine = false)
                InlineError(error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onCancel, enabled = !busy, modifier = Modifier.testTag(SemanticIds.recordCancel)) { Text("取消") }
                Button(onClick = onSave, enabled = !busy && draft.amount.isNotBlank() && isExactDecimalString(draft.amount) && draft.accountName.isNotBlank() && draft.recordType.isNotBlank() && isValidLocalDateTime(draft.occurredAt), modifier = Modifier.testTag(SemanticIds.recordSave)) { Text(if (busy) "保存中…" else "保存") }
            }
        }
    }
}

private fun cashRecordBody(draft: CashRecordDraft, confirmRelationImpact: Boolean) = buildJsonObject {
    put("occurred_at", draft.occurredAt)
    put("amount", draft.amount)
    put("currency", draft.currency)
    put("counterparty", draft.counterparty)
    put("counterparty_account", draft.counterpartyAccount)
    put("note", draft.note)
    put("account_name", draft.accountName)
    put("record_type", draft.recordType)
    put("record_subtype", draft.recordSubtype.ifBlank { "not_applicable" })
    put("category_id", draft.categoryId?.let(::JsonPrimitive) ?: JsonNull)
    if (draft.id != null && draft.categoryId != draft.originalCategoryId && draft.projectionVersion != null) {
        put("projection_version", draft.projectionVersion)
    }
    if (confirmRelationImpact) put("confirm_relation_impact", true)
}

private fun economicTypeLabel(value: String): String = when (value) {
    "expense" -> "消费"
    "income" -> "收入"
    "internal_transfer" -> "资金转移"
    else -> value
}

private fun transferSubtypeLabel(value: String): String = when (value) {
    "bank_security_transfer" -> "银证转账"
    "cross_currency_remittance" -> "跨币种汇款"
    "ordinary_transfer" -> "普通转账"
    "currency_exchange" -> "个人购汇"
    else -> value
}

private fun localDateTimeForInput(): String = "${getLocalDateForInput()}T12:00"
