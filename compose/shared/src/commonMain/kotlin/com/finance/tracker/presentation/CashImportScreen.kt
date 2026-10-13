package com.finance.tracker.presentation

import com.finance.tracker.core.*
import com.finance.tracker.core.design.GeneratedTokens
import com.finance.tracker.domain.*

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.size
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import com.composeunstyled.UnstyledButton

private class PlatformImportFileSource(private val file: PlatformFile) : FileSource {
    override val name: String get() = file.name
    override val mediaType: String? get() = file.mimeType()?.toString()
    override val size: Long? get() = file.size().takeIf { it >= 0 }
    override suspend fun read(): ByteArray = file.readBytes()
}

@Composable
internal fun CashImportScreen(
    repository: CashImportRepository,
    workspaceId: String,
    sizeClass: WindowSizeClass,
    canWrite: Boolean,
    onBack: () -> Unit,
    onDone: () -> Unit,
    diagnostics: DiagnosticLogger = NoOpDiagnosticLogger,
) {
    val viewModel: CashImportViewModel = viewModel(
        key = "cash-import:$workspaceId",
        factory = viewModelFactory { initializer { CashImportViewModel(repository, diagnostics) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.reset() }
    DisposableEffect(viewModel) { onDispose { viewModel.reset() } }
    LaunchedEffect(state.stage) { if (state.stage == ImportStage.SUCCESS) onDone() }

    val stage = state.stage
    val selectedFiles = state.selectedFiles
    val scan = state.scan
    val accountDrafts = state.accountDrafts
    val preview = state.preview
    val allocationDrafts = state.allocationDrafts
    val previewFilter = state.previewFilter
    val relationDrafts = state.relationDrafts
    val relationFilter = state.relationFilter
    val result = state.result
    val passwords = state.passwords
    val busy = state.busy
    val error = state.errorCode?.let { importErrorText(it) }
    val footerActionWidth = if (sizeClass == WindowSizeClass.COMPACT) 108.dp else 96.dp

    val filePicker = rememberFilePickerLauncher(
        type = FileKitType.File(extensions = listOf("csv", "xls", "xlsx", "pdf")),
        mode = FileKitMode.Multiple(maxItems = MAX_IMPORT_FILES),
        onError = { viewModel.setPickerError() },
        onResult = { files -> viewModel.receiveFiles(files?.map(::PlatformImportFileSource)) },
    )

    FinanceLightSurfaceTheme {
        SecondaryPageContainer(
            title = localizedText("cash_import_title"),
            sizeClass = sizeClass,
            onClose = onBack,
            footer = {
                if (!canWrite) {
                    FinanceTertiaryButton(onClick = onBack) { Text(localizedText("copy_855fa817d2")) }
                } else when (stage) {
                    ImportStage.SELECT -> {
                        val canScan = selectedFiles.isNotEmpty() && !busy && scan?.files?.none {
                            it.status == "error" || (it.status == "password_required" && passwords[it.index.toString()].isNullOrBlank())
                        } != false
                        if (sizeClass == WindowSizeClass.COMPACT) {
                            Column(
                                Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CashImportDiscardNote(Modifier.fillMaxWidth())
                                ImportScanAction(
                                    enabled = canScan,
                                    busy = busy,
                                    modifier = Modifier.width(footerActionWidth).testTag(SemanticIds.importNext),
                                    onClick = { viewModel.scanSelectedFiles() },
                                )
                            }
                        } else {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CashImportDiscardNote()
                                ImportScanAction(
                                    enabled = canScan,
                                    busy = busy,
                                    modifier = Modifier.width(footerActionWidth).testTag(SemanticIds.importNext),
                                    onClick = { viewModel.scanSelectedFiles() },
                                )
                            }
                        }
                    }
                    ImportStage.MAPPING -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FinanceTertiaryButton(onClick = { viewModel.stage(ImportStage.SELECT) }, enabled = !busy, modifier = Modifier.width(footerActionWidth).testTag(SemanticIds.importPrevious)) { Text(localizedText("copy_75ef1241c0")) }
                        FinanceButton(onClick = { viewModel.loadPreview() }, enabled = state.mappingComplete() && !busy, modifier = Modifier.width(footerActionWidth).testTag(SemanticIds.importNext)) { Text(if (busy) localizedText("copy_a99736a830") else localizedText("copy_b8a3f85115")) }
                    }
                    ImportStage.PREVIEW -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FinanceTertiaryButton(onClick = { viewModel.stage(ImportStage.MAPPING) }, enabled = !busy, modifier = Modifier.width(footerActionWidth).testTag(SemanticIds.importPrevious)) { Text(localizedText("copy_75ef1241c0")) }
                        FinanceButton(onClick = {
                            if (preview?.let { hasIncompleteAllocations(it, allocationDrafts) } == true) {
                                viewModel.setErrorCode("import_component_allocation_incomplete")
                            } else if (preview?.items?.any { it.components.orEmpty().size > 1 } == true) {
                                viewModel.loadPreview(ImportStage.RELATIONS)
                            } else {
                                viewModel.stage(ImportStage.RELATIONS)
                            }
                        }, enabled = !busy && preview?.let { !hasIncompleteAllocations(it, allocationDrafts) } == true, modifier = Modifier.width(footerActionWidth).testTag(SemanticIds.importNext)) { Text(localizedText("copy_1fc1afc5c5")) }
                    }
                    ImportStage.RELATIONS -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FinanceTertiaryButton(onClick = { viewModel.stage(ImportStage.PREVIEW) }, enabled = !busy, modifier = Modifier.width(footerActionWidth).testTag(SemanticIds.importPrevious)) { Text(localizedText("copy_75ef1241c0")) }
                        FinanceButton(onClick = { viewModel.commitImport() }, enabled = state.canCommit(), modifier = Modifier.width(footerActionWidth).testTag(SemanticIds.importConfirm)) { Text(if (busy) localizedText("copy_cd74f09629") else localizedText("copy_de6433b70d")) }
                    }
                    ImportStage.SUCCESS -> FinanceButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(localizedText("copy_855fa817d2")) }
                }
            },
        ) {
            if (canWrite && stage != ImportStage.SUCCESS) {
                Box(
                    Modifier.fillMaxWidth()
                        .padding(horizontal = if (sizeClass == WindowSizeClass.COMPACT) 12.dp else 36.dp)
                        .padding(top = 24.dp, bottom = 4.dp),
                ) {
                    ImportStepper(stage, sizeClass, busy, scan != null, preview != null) { target -> viewModel.stage(target) }
                }
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                FeaturePage(
                    localizedText("cash_import_title"),
                    SemanticIds.importScreen,
                    modifier = Modifier.widthIn(max = if (stage == ImportStage.RELATIONS) 1480.dp else 720.dp).fillMaxWidth(),
                    showTitle = false,
                    scrollable = false,
                    contentPadding = PaddingValues(
                        start = when (sizeClass) {
                            WindowSizeClass.COMPACT -> 16.dp
                            WindowSizeClass.REGULAR -> 24.dp
                            WindowSizeClass.WIDE -> 32.dp
                        },
                        end = when (sizeClass) {
                            WindowSizeClass.COMPACT -> 16.dp
                            WindowSizeClass.REGULAR -> 24.dp
                            WindowSizeClass.WIDE -> 32.dp
                        },
                        top = if (sizeClass == WindowSizeClass.COMPACT) 8.dp else 12.dp,
                        bottom = if (sizeClass == WindowSizeClass.COMPACT) 16.dp else 12.dp,
                    ),
                ) {
                    if (!canWrite) {
                        StateMessage(localizedText("copy_459f937086"))
                    } else {
                        InlineError(error)
                        when (stage) {
                            ImportStage.SELECT -> {
                                Column(
                                    Modifier.fillMaxWidth().testTag("import.stage-content"),
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    Text(localizedText("cash_import_select_file_heading"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                    ImportUploadSurface(
                                        selectedFiles = selectedFiles,
                                        busy = busy,
                                        onChoose = { filePicker.launch() },
                                    )
                                    selectedFiles.forEachIndexed { index, picked ->
                                        ImportOutlinedCard {
                                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                Text("▤", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.headlineSmall)
                                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                                    Text(picked.identity.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                    Text(formatFileSize(picked.identity.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    scan?.files?.firstOrNull { it.index == index }?.let { status ->
                                                        Text(importFileStatus(status), style = MaterialTheme.typography.bodySmall)
                                                    }
                                                }
                                                FinanceTertiaryButton(
                                                    onClick = { viewModel.removeFile(picked.identity.digest) },
                                                    enabled = !busy,
                                                    modifier = Modifier.testTag("${SemanticIds.importRemoveFile}.${picked.identity.digest}"),
                                                ) { Text(localizedText("copy_2f752c005e")) }
                                            }
                                        }
                                    }
                                    scan?.files?.filter { it.status == "password_required" }?.forEach { fileStatus ->
                                        val picked = selectedFiles.getOrNull(fileStatus.index) ?: return@forEach
                                        val passwordLabel = localizedText("copy_f1b6b76a6f", picked.identity.name)
                                        OutlinedTextField(
                                            value = passwords[fileStatus.index.toString()].orEmpty(),
                                            onValueChange = { viewModel.updatePassword(fileStatus.index, it) },
                                            label = { Text(passwordLabel) },
                                            singleLine = true,
                                            visualTransformation = PasswordVisualTransformation(),
                                            modifier = Modifier.fillMaxWidth()
                                                .testTag("${SemanticIds.importFilePassword}.${fileStatus.index}")
                                                .semantics(mergeDescendants = true) { contentDescription = passwordLabel },
                                        )
                                    }
                                    scan?.files?.filter { it.status == "error" }?.forEach { fileStatus ->
                                        InlineError("${selectedFiles.getOrNull(fileStatus.index)?.identity?.name ?: fileStatus.filename}：${importFileStatus(fileStatus)}")
                                    }
                                }
                            }
                            ImportStage.MAPPING -> scan?.let { current ->
                                Column(
                                    Modifier.fillMaxWidth().testTag("import.stage-content"),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    ImportOutlinedCard {
                                        Text(localizedText("copy_56bc6903d6", current.channelLabel, current.groups.size), style = MaterialTheme.typography.titleSmall)
                                        Text(current.files.joinToString(" · ") { it.filename.orEmpty() }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (current.unresolvedCount > 0) Text(localizedText("copy_40b67ad6b4", current.unresolvedCount), color = MaterialTheme.colorScheme.error)
                                    }
                                    Text(localizedText("cash_import_mapping_heading"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                    current.groups.forEach { group ->
                                        val draft = accountDrafts[group.groupId]
                                        val sharedDrafts = accountDrafts.values.mapNotNull(ImportAccountDraft::newAccount).distinctBy { it.draftId }
                                        ImportOutlinedCard {
                                            if (sizeClass == WindowSizeClass.COMPACT) {
                                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                                    ImportMappingSource(group)
                                                    ChoicePicker(
                                                        localizedText("copy_8ab177def7"),
                                                        draft?.newAccount?.let { "__draft__${it.draftId}" } ?: draft?.accountId?.toString().orEmpty(),
                                                        listOf("" to localizedText("copy_ce1ec4bd2e")) + current.accounts.map { it.id.toString() to it.name } +
                                                            sharedDrafts.map { "__draft__${it.draftId}" to localizedText("copy_ec72e9b1d3", it.name) } + ("__create__" to localizedText("copy_93b0b8ba3b")),
                                                        { viewModel.selectAccount(group, it) },
                                                        semanticId = "${SemanticIds.importMapping}.${group.groupId}",
                                                        fieldStyle = true,
                                                    )
                                                }
                                            } else {
                                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                                    ImportMappingSource(group, Modifier.weight(1.2f))
                                                    ChoicePicker(
                                                        localizedText("copy_8ab177def7"),
                                                        draft?.newAccount?.let { "__draft__${it.draftId}" } ?: draft?.accountId?.toString().orEmpty(),
                                                        listOf("" to localizedText("copy_ce1ec4bd2e")) + current.accounts.map { it.id.toString() to it.name } +
                                                            sharedDrafts.map { "__draft__${it.draftId}" to localizedText("copy_ec72e9b1d3", it.name) } + ("__create__" to localizedText("copy_93b0b8ba3b")),
                                                        { viewModel.selectAccount(group, it) },
                                                        modifier = Modifier.weight(1f),
                                                        semanticId = "${SemanticIds.importMapping}.${group.groupId}",
                                                        fieldStyle = true,
                                                    )
                                                }
                                            }
                                            draft?.newAccount?.let { account ->
                                                LabeledInput(account.name, { value -> viewModel.updateNewAccount(group.groupId) { it.copy(name = value.take(255)) } }, localizedText("copy_238452a115"))
                                                ChoicePicker(localizedText("copy_082c31df2b"), account.type, listOf("cash" to localizedText("copy_ca5bd21330"), "loan" to localizedText("copy_099f8b5c68"), "lend" to localizedText("copy_4a2637c23f")), { value -> viewModel.updateNewAccount(group.groupId) { it.copy(type = value) } })
                                                Text(localizedText("copy_b241eb825a", account.currencies.joinToString(" / ")))
                                            }
                                            val selectedAccount = current.accounts.firstOrNull { it.id == draft?.accountId }
                                            val missingCurrencies = if (selectedAccount == null) emptyList() else group.currencies.filterNot { it in selectedAccount.currencies.orEmpty() }
                                            if (missingCurrencies.isNotEmpty()) Text(localizedText("copy_6f136836ac", missingCurrencies.joinToString("、")))
                                        }
                                    }
                                }
                            }
                            ImportStage.PREVIEW -> preview?.let { current ->
                                Column(
                                    Modifier.fillMaxWidth().testTag("import.stage-content"),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Text(localizedText("copy_0433a1c196"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                    ImportFilterBar(
                                        selected = previewFilter,
                                        semanticPrefix = "import.preview-filter",
                                        options = listOf(
                                            "all" to localizedText("copy_f229c64eb7", current.summary.total),
                                            "new" to localizedText("copy_4be80bff60", current.summary.new),
                                            "existing" to localizedText("copy_eb4f38232d", current.summary.existing),
                                            "unresolved" to localizedText("copy_6d5268fd33", current.summary.unresolved),
                                            "requires_allocation" to localizedText("copy_44d8ec2ba9", incompleteAllocationCount(current, allocationDrafts)),
                                        ),
                                        onSelect = viewModel::setPreviewFilter,
                                    )
                                    val visibleItems = current.items.filter { previewFilter == "all" || it.status == previewFilter }
                                    val monthGroups = importPreviewMonthGroups(visibleItems)
                                    if (visibleItems.isEmpty()) ImportEmptyState(if (current.items.isEmpty()) localizedText("copy_e0329985a9") else localizedText("copy_43dd57a28f"))
                                    monthGroups.forEach { group ->
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(importPreviewMonthLabel(group.month), style = MaterialTheme.typography.titleMedium)
                                            if (group.summary?.currencies.isNullOrEmpty()) {
                                                Text(localizedText("copy_9c522d837a"), style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                        group.summary?.currencies.orEmpty().forEach { totals ->
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text(localizedText("copy_5e64d382a1", importPreviewSummaryAmount("income", totals.income), totals.currency), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                                                Text(localizedText("copy_19c079014a", importPreviewSummaryAmount("expense", totals.expense), totals.currency), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                        group.items.forEach { item ->
                                            ImportOutlinedCard(Modifier.testTag("import-item-${item.recordId}")) {
                                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                    Text(importPreviewDateTimeLabel(item.occurredAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    ImportStatusPill(importStatusLabel(item.status), item.status)
                                                }
                                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                        Text(item.counterparty.ifBlank { "-" }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                                        Text(item.note.ifBlank { "-" }, style = MaterialTheme.typography.bodyMedium)
                                                        Text("${item.accountName} · ${importRecordTypeLabel(item.recordType)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    }
                                                    val direction = importPreviewDirection(item)
                                                    Text(
                                                        importPreviewAmountLabel(item),
                                                        color = when (direction) {
                                                            "income" -> GeneratedTokens.Core.Color.SemanticIncome
                                                            "expense" -> GeneratedTokens.Core.Color.SemanticExpense
                                                            else -> MaterialTheme.colorScheme.onSurface
                                                        },
                                                        style = MaterialTheme.typography.titleMedium,
                                                    )
                                                }
                                                item.message.takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                                if (item.components.orEmpty().size > 1) {
                                                    Text(localizedText("copy_a62d0b68c2"), style = MaterialTheme.typography.labelLarge)
                                                    val key = importItemKey(item)
                                                    val currentAmounts = allocationDrafts[key].orEmpty()
                                                    item.components.orEmpty().forEachIndexed { index, component ->
                                                        LabeledInput(
                                                            currentAmounts.getOrElse(index) { component.amount?.removePrefix("+")?.removePrefix("-").orEmpty() },
                                                            { value -> viewModel.updateAllocation(key, index, value) },
                                                            component.sourceLabel,
                                                            semanticId = "${SemanticIds.importAllocation}.$key.$index",
                                                        )
                                                    }
                                                    val balance = allocationBalance(item.amount, currentAmounts)
                                                    Text(when (balance.state) {
                                                        "complete" -> localizedText("copy_67bca171d2", balance.total, item.currency)
                                                        "invalid" -> localizedText("copy_d9ea7affdf")
                                                        else -> if (balance.difference.startsWith("-")) localizedText("copy_4c52e0e5fc", balance.difference.drop(1), item.currency) else localizedText("copy_9be1d4be37", balance.difference, item.currency)
                                                    }, color = if (balance.state == "complete") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                                                }
                                            }
                                        }
                                    }
                                    if (importUnresolvedCount(current) > 0) Text(localizedText("copy_3652352085", importUnresolvedCount(current)))
                                    if (importUnsupportedCount(current) > 0) InlineError(localizedText("copy_1b89edc1fd"))
                                }
                            }
                            ImportStage.RELATIONS -> preview?.let { current ->
                                Column(
                                    Modifier.fillMaxWidth().testTag("import.stage-content"),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Text(localizedText("cash_import_relation_heading"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                    val linkedCount = current.relations.count { relation ->
                                        val draft = relationDrafts[relation.id]
                                        val status = draft?.status ?: if (relation.automatic) "automatic" else "pending"
                                        status == "automatic" || status == "accepted"
                                    }
                                    val pendingCount = current.relations.count { relation ->
                                        val draft = relationDrafts[relation.id]
                                        (draft?.status ?: if (relation.automatic) "automatic" else "pending") == "pending"
                                    }
                                    ImportFilterBar(
                                        selected = relationFilter,
                                        semanticPrefix = "import.relation-filter",
                                        options = listOf(
                                            "all" to localizedText("cash_import_filter_all", current.relations.size),
                                            "pending" to localizedText("cash_import_filter_pending", pendingCount),
                                            "linked" to localizedText("cash_import_filter_linked", linkedCount),
                                        ),
                                        onSelect = viewModel::setRelationFilter,
                                    )
                                    val filtered = current.relations.filter { relation ->
                                        val draft = relationDrafts[relation.id]
                                        val status = draft?.status ?: if (relation.automatic) "automatic" else "pending"
                                        relationFilter == "all" ||
                                            (relationFilter == "linked" && (status == "automatic" || status == "accepted")) ||
                                            (relationFilter == "pending" && status == "pending")
                                    }
                                    if (current.relations.isEmpty() || filtered.isEmpty()) ImportEmptyState(localizedText("copy_f7ad800eb1"))
                                    filtered.forEach { relation ->
                                        val draft = relationDrafts[relation.id] ?: ImportRelationDraft(relation.kind, if (relation.automatic) "automatic" else "pending", relation.secondary)
                                        ImportRelationCard(
                                            relation = relation,
                                            draft = draft,
                                            sizeClass = sizeClass,
                                            onKindSelected = { value -> viewModel.setRelationDraft(relation, draft.copy(kind = value)) },
                                            onCandidateSelected = { value ->
                                                val selected = relation.candidates.firstOrNull { importItemKey(it) == value }
                                                viewModel.setRelationDraft(relation, draft.copy(status = if (selected == null) "pending" else "accepted", secondary = selected, restoreStatus = null, restoreSecondary = null))
                                            },
                                            onToggleRejected = { viewModel.toggleRelationRejected(relation, draft) },
                                        )
                                    }
                                }
                            }
                            ImportStage.SUCCESS -> result?.let { committed ->
                                Column(
                                    Modifier.fillMaxWidth().testTag("import.stage-content"),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Box(
                                        Modifier.size(56.dp).background(GeneratedTokens.Light.Color.StateSuccessSurface, CircleShape),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text("✓", color = GeneratedTokens.Core.Color.SemanticIncome, style = MaterialTheme.typography.headlineSmall)
                                    }
                                    Text(localizedText("copy_75c33f5c2e"), style = MaterialTheme.typography.titleLarge)
                                    Text(localizedText("copy_07c4b4b5e5", committed.newRows, committed.updatedRows, preview?.summary?.existing ?: 0, committed.skippedRows))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportUploadSurface(
    selectedFiles: List<PickedImportFile>,
    busy: Boolean,
    onChoose: () -> Unit,
) {
    val colors = LocalFinanceColorRoles.current
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp)
            .background(colors.accent.copy(alpha = 0.015f), shape)
            .border(1.5.dp, colors.accent.copy(alpha = 0.14f), shape)
            .testTag("import.dropzone")
            .padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier.size(60.dp).background(colors.accent.copy(alpha = 0.07f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            ImportUploadIcon(colors.accent)
        }
        Text(
            selectedFiles.firstOrNull()?.identity?.name ?: localizedText("cash_import_add_file_heading"),
            style = MaterialTheme.typography.titleMedium,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            localizedText("cash_import_file_formats"),
            style = MaterialTheme.typography.bodySmall,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FinanceButton(
            onClick = onChoose,
            modifier = Modifier.widthIn(min = 176.dp).testTag(SemanticIds.importChooseFile),
            variant = FinanceButtonVariant.Secondary,
            shape = CircleShape,
            borderColor = colors.accent,
            borderWidth = 2.dp,
            enabled = !busy,
        ) {
            Text(
                if (selectedFiles.isEmpty()) localizedText("cash_import_choose_file") else localizedText("copy_7c3a726882"),
                color = colors.accent,
            )
        }
    }
}

@Composable
private fun CashImportDiscardNote(modifier: Modifier = Modifier) {
    Text(
        localizedText("cash_import_discard_note"),
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ImportScanAction(
    enabled: Boolean,
    busy: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    FinanceButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        borderColor = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
    ) {
        Text(if (busy) localizedText("copy_4a5e035eb9") else localizedText("cash_import_next"))
    }
}

@Composable
private fun ImportUploadIcon(color: Color) {
    Canvas(Modifier.size(32.dp)) {
        val midX = size.width / 2f
        val stroke = 2.dp.toPx()
        drawLine(color, Offset(midX, size.height * 0.57f), Offset(midX, size.height * 0.16f), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(midX, size.height * 0.16f), Offset(size.width * 0.31f, size.height * 0.36f), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(midX, size.height * 0.16f), Offset(size.width * 0.69f, size.height * 0.36f), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.2f, size.height * 0.57f), Offset(size.width * 0.2f, size.height * 0.83f), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.2f, size.height * 0.83f), Offset(size.width * 0.8f, size.height * 0.83f), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.8f, size.height * 0.83f), Offset(size.width * 0.8f, size.height * 0.57f), stroke, cap = StrokeCap.Round)
    }
}

@Composable
private fun ImportOutlinedCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    contentSpacing: androidx.compose.ui.unit.Dp = 10.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalFinanceColorRoles.current
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier.fillMaxWidth()
            .border(1.dp, colors.fieldBorder, shape)
            .background(colors.surface, shape)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(contentSpacing),
        content = content,
    )
}

@Composable
private fun ImportMappingSource(group: ImportSourceGroup, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(group.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        Text(
            localizedText("copy_92356f95e8", group.maskedEvidence, group.currencies.joinToString(" / "), group.rowCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImportFilterBar(
    selected: String,
    semanticPrefix: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    val colors = LocalFinanceColorRoles.current
    FlowRow(
        modifier = Modifier.fillMaxWidth().selectableGroup().testTag(semanticPrefix),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            UnstyledButton(
                onClick = { onSelect(value) },
                modifier = Modifier.heightIn(min = 44.dp)
                    .background(if (active) colors.accent.copy(alpha = 0.12f) else colors.disabled.copy(alpha = 0.55f), CircleShape)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .semantics {
                        role = Role.Button
                        this.selected = active
                    }
                    .testTag("$semanticPrefix.$value"),
            ) {
                Text(
                    label,
                    color = if (active) colors.foreground else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ImportEmptyState(message: String) {
    val colors = LocalFinanceColorRoles.current
    Box(
        Modifier.fillMaxWidth().heightIn(min = 72.dp)
            .border(1.dp, colors.fieldBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ImportStatusPill(
    label: String,
    status: String,
    actionTestTag: String? = null,
    actionExpanded: Boolean = false,
    onActionClick: (() -> Unit)? = null,
) {
    val colors = LocalFinanceColorRoles.current
    val isRejected = status == "rejected" || status == "unresolved" || status == "requires_allocation"
    val background = when {
        isRejected -> MaterialTheme.colorScheme.errorContainer
        status == "pending" -> colors.disabled.copy(alpha = 0.58f)
        else -> colors.accent.copy(alpha = 0.1f)
    }
    val foreground = when {
        isRejected -> MaterialTheme.colorScheme.error
        status == "pending" -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> colors.accent
    }
    val actionsDescription = localizedText("cash_import_more_actions")
    val chip: @Composable () -> Unit = {
        Box(Modifier.background(background, CircleShape).padding(horizontal = 12.dp, vertical = 7.dp)) {
            Text(label, color = foreground, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
    if (onActionClick == null) {
        chip()
    } else {
        var focused by remember(actionTestTag) { mutableStateOf(false) }
        UnstyledButton(
            onClick = onActionClick,
            modifier = Modifier.heightIn(min = 44.dp)
                .onFocusChanged { focused = it.isFocused }
                .border(1.dp, if (focused) colors.focus else Color.Transparent, CircleShape)
                .semantics {
                    role = Role.Button
                    contentDescription = actionsDescription
                    selected = actionExpanded
                }
                .testTag(actionTestTag ?: "import.relation-actions"),
        ) {
            Box(contentAlignment = Alignment.Center) { chip() }
        }
    }
}

@Composable
private fun ImportRelationCard(
    relation: ImportRelation,
    draft: ImportRelationDraft,
    sizeClass: WindowSizeClass,
    onKindSelected: (String) -> Unit,
    onCandidateSelected: (String) -> Unit,
    onToggleRejected: () -> Unit,
) {
    val currentSecondary = draft.secondary ?: relation.secondary
    val rejected = draft.status == "rejected"
    var actionsExpanded by remember(relation.id) { mutableStateOf(false) }
    val status = when {
        rejected -> "rejected"
        relation.automatic -> "automatic"
        draft.status == "accepted" -> "accepted"
        else -> "pending"
    }
    val statusLabel = when (status) {
        "rejected" -> localizedText("copy_4c7c52c706")
        "automatic", "accepted" -> localizedText("cash_import_status_linked")
        else -> localizedText("cash_import_status_pending")
    }
    ImportOutlinedCard(
        modifier = Modifier.testTag("import.relation-row.${relation.id}"),
        contentPadding = PaddingValues(if (sizeClass == WindowSizeClass.COMPACT) 12.dp else 16.dp),
        contentSpacing = 8.dp,
    ) {
        if (sizeClass == WindowSizeClass.COMPACT) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ImportRelationRecordView(relation.primary, Modifier.weight(1f))
                    Text("→", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleLarge)
                    ImportRelationTarget(relation, draft, currentSecondary, rejected, Modifier.weight(1f), onCandidateSelected)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    ImportStatusPill(
                        statusLabel,
                        status,
                        actionTestTag = "import.relation-actions.${relation.id}",
                        actionExpanded = actionsExpanded,
                        onActionClick = { actionsExpanded = !actionsExpanded },
                    )
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ImportRelationRecordView(relation.primary, Modifier.weight(1f))
                Text("→", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleLarge)
                ImportRelationTarget(relation, draft, currentSecondary, rejected, Modifier.weight(1.1f), onCandidateSelected)
                ImportStatusPill(
                    statusLabel,
                    status,
                    actionTestTag = "import.relation-actions.${relation.id}",
                    actionExpanded = actionsExpanded,
                    onActionClick = { actionsExpanded = !actionsExpanded },
                )
            }
        }
        if (actionsExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!relation.automatic && !rejected) {
                    ChoicePicker(
                        localizedText("copy_80d486a71f"),
                        draft.kind,
                        listOf("payment_mirror" to localizedText("copy_2b6feb78fe"), "refund_offset" to localizedText("copy_b441aa2b9b"), "transfer_pair" to localizedText("copy_e4aaa144b7")),
                        onKindSelected,
                        fieldStyle = true,
                    )
                }
                FinanceTertiaryButton(onClick = onToggleRejected) {
                    Text(if (rejected) localizedText("copy_3290b42bd1") else localizedText("copy_86ab462000"))
                }
            }
        }
    }
}

@Composable
private fun ImportRelationRecordView(record: ImportRelationRecord, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(formatLocalDateTime(record.occurredAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "${record.counterparty.ifBlank { localizedText("copy_204c9c54c2") }} · ${record.amount} ${record.currency}",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ImportRelationTarget(
    relation: ImportRelation,
    draft: ImportRelationDraft,
    currentSecondary: ImportRelationRecord?,
    rejected: Boolean,
    modifier: Modifier,
    onCandidateSelected: (String) -> Unit,
) {
    if (relation.automatic) {
        if (currentSecondary != null) ImportRelationTargetRecordView(currentSecondary, modifier)
        else Text(localizedText("copy_778fc8f994"), modifier, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else if (rejected) {
        if (currentSecondary != null) ImportRelationTargetRecordView(currentSecondary, modifier)
        else Text(localizedText("copy_4c7c52c706"), modifier, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        ChoicePicker(
            "",
            draft.secondary?.let(::importItemKey) ?: "",
            listOf("" to localizedText("copy_0153e1a001")) + relation.candidates.map { importItemKey(it) to importRelationRecordLabel(it) },
            onCandidateSelected,
            modifier = modifier,
            semanticId = "${SemanticIds.importRelations}.${relation.id}",
            autoScrollMenuIntoView = true,
            fieldStyle = true,
            simpleMenuOnWeb = true,
        )
    }
}

@Composable
private fun ImportRelationTargetRecordView(record: ImportRelationRecord, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            record.counterparty.ifBlank { localizedText("copy_204c9c54c2") },
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${formatLocalDateTime(record.occurredAt)} · ${record.amount} ${record.currency}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ImportStepper(
    stage: ImportStage,
    sizeClass: WindowSizeClass,
    busy: Boolean,
    hasScan: Boolean,
    hasPreview: Boolean,
    onSelect: (ImportStage) -> Unit,
) {
    val steps = listOf(
        ImportStage.SELECT to localizedText("cash_import_step_file"),
        ImportStage.MAPPING to localizedText("cash_import_step_account"),
        ImportStage.PREVIEW to localizedText("cash_import_step_review"),
        ImportStage.RELATIONS to localizedText("cash_import_step_relation"),
    )
    val currentIndex = steps.indexOfFirst { it.first == stage }
    val colors = LocalFinanceColorRoles.current
    Row(Modifier.fillMaxWidth().testTag("import.stepper"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        steps.forEachIndexed { index, (step, label) ->
            val current = stage == step
            val done = index < currentIndex
            val enabled = !busy && index <= currentIndex &&
                (step == ImportStage.SELECT || hasScan && index == 1 || hasPreview && index >= 2)
            UnstyledButton(
                onClick = { onSelect(step) },
                enabled = enabled,
                modifier = Modifier.weight(1f).testTag("${SemanticIds.importStepper}.$index")
                    .semantics(mergeDescendants = true) {
                        role = Role.Button
                        selected = current
                        if (!enabled) disabled()
                    },
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier.size(32.dp)
                            .background(if (current || done) GeneratedTokens.Core.Color.BrandPrimary else colors.disabled, CircleShape)
                            .testTag("${SemanticIds.importStepper}.$index.indicator"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (done) "✓" else (index + 1).toString(),
                            color = if (current || done) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        label,
                        color = if (current) GeneratedTokens.Core.Color.BrandPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = if (sizeClass == WindowSizeClass.COMPACT) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                        fontSize = if (sizeClass == WindowSizeClass.COMPACT) 14.sp else 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun importRelationRecordLabel(record: ImportRelationRecord): String =
    "${record.counterparty.ifBlank { localizedText("copy_204c9c54c2") }} · ${record.amount} ${record.currency}"

@Composable
private fun importRecordTypeLabel(value: String): String = when (value) {
    "consumption", "expense" -> localizedText("copy_71d6df4130")
    "refund" -> localizedText("copy_b82ef83b7f")
    "reversal" -> localizedText("copy_47524db6a4")
    "transfer_reversal" -> localizedText("copy_ac69079e02")
    "withdrawal_in" -> localizedText("copy_f1c19482a9")
    "withdrawal_out" -> localizedText("copy_a471daf080")
    "transfer_in" -> localizedText("copy_5b87cb874f")
    "transfer_out" -> localizedText("copy_e39b50c02d")
    "repayment" -> localizedText("copy_a9af9c2d42")
    "income" -> localizedText("copy_aaaf7ca11e")
    "investment_in" -> localizedText("copy_1f9774478d")
    "investment_out" -> localizedText("copy_f80e7c74fd")
    "interest" -> localizedText("copy_a5b7f233fa")
    "fee" -> localizedText("copy_9836a95e9d")
    "fx_in" -> localizedText("copy_69778bdcd4")
    "fx_out" -> localizedText("copy_8efd23d759")
    "bank_security_transfer" -> localizedText("copy_c18e325ed4")
    "merged" -> localizedText("copy_9c8acd3d30")
    else -> localizedText("copy_1a26edf94a")
}

@Composable
private fun importFileStatus(file: ImportFileScan): String = when (file.status) {
    "ready" -> localizedText("copy_5707975153", file.channelLabel.orEmpty())
    "password_required" -> localizedText("copy_4908aa3009")
    else -> localizedText("copy_53514e0040")
}

private fun formatFileSize(size: Long): String = when {
    size < 1024 -> "$size B"
    size < 1024L * 1024L -> "${(size + 1023) / 1024} KB"
    else -> "${(size / (1024L * 1024L))}.${((size % (1024L * 1024L)) * 10 / (1024L * 1024L))} MB"
}

@Composable
private fun importStatusLabel(status: String): String = when (status) {
    "new" -> localizedText("copy_956fb0cc79")
    "existing" -> localizedText("copy_a867d42ddf")
    "unresolved" -> localizedText("copy_d041bb6737")
    "requires_allocation" -> localizedText("copy_feb85ed082")
    else -> localizedText("copy_f9c565b337")
}

private fun importAllocationCount(preview: ImportPreview): Int = preview.summary.requiresAllocation
    .takeIf { it > 0 } ?: preview.items.count { it.status == "requires_allocation" }

private fun incompleteAllocationCount(preview: ImportPreview, drafts: Map<String, List<String>>): Int =
    preview.items.filter { it.components.orEmpty().size > 1 }.count { item ->
        allocationBalance(item.amount, drafts[importItemKey(item)].orEmpty()).state != "complete"
    }

private fun importUnresolvedCount(preview: ImportPreview): Int = preview.summary.unresolved
    .takeIf { it > 0 } ?: preview.items.count { it.status == "unresolved" }

private fun importUnsupportedCount(preview: ImportPreview): Int = (preview.summary.unsupported - importUnresolvedCount(preview)).coerceAtLeast(0)

private fun hasIncompleteAllocations(preview: ImportPreview, drafts: Map<String, List<String>>): Boolean =
    preview.items.filter { it.components.orEmpty().size > 1 }.any { item ->
        allocationBalance(item.amount, drafts[importItemKey(item)].orEmpty()).state != "complete"
    }

@Composable
private fun importMappingError(code: String?): String? = when (code) {
    "import_account_unavailable" -> localizedText("copy_03c994116c")
    "import_account_name_conflict" -> localizedText("copy_e5e27271b5")
    "import_account_draft_invalid" -> localizedText("copy_3ee2de090b")
    "import_mapping_incomplete" -> localizedText("copy_02ec7f0183")
    "import_composite_payment_unresolved" -> localizedText("copy_5a8a3e7f03")
    "import_component_allocation_incomplete" -> localizedText("copy_240cf25f9e")
    "import_component_amount_invalid" -> localizedText("copy_3581074e60")
    else -> null
}

@Composable
private fun importErrorText(code: String): String = importMappingError(code) ?: when (code) {
    "import_password_required" -> localizedText("copy_d925c10d8c")
    "import_password_invalid" -> localizedText("copy_eb3d4679e0")
    "import_channel_unrecognized" -> localizedText("copy_13e3557403")
    "import_mapping_stale" -> localizedText("copy_f9529f1543")
    "import_relation_reconfirmation_required" -> localizedText("copy_fda94c82c4")
    "import_preview_stale" -> localizedText("copy_cc7eb85420")
    "relation_impact_required" -> localizedText("copy_ee4303c207")
    "import_file_unsupported_type" -> localizedText("copy_e473077a21")
    "import_file_too_large" -> localizedText("copy_05e60942e2")
    "import_file_size_unavailable" -> localizedText("copy_6087e12a0f")
    "import_file_too_many" -> localizedText("copy_2ea314ef4c")
    "import_file_read_failed" -> localizedText("copy_7265b90625")
    "import_file_picker_failed" -> localizedText("copy_cb28b5a6f2")
    "network_unavailable" -> localizedText("copy_e0a1c9fd69")
    "temporarily_unavailable" -> localizedText("copy_8d856685b1")
    "authentication_required" -> localizedText("copy_8e38717a57")
    "workspace_forbidden" -> localizedText("copy_dd9a4bdee0")
    "unknown_error" -> localizedText("copy_382ef5a830")
    else -> localizedText("copy_8d856685b1")
}
