package com.finance.tracker.presentation

import com.finance.tracker.core.*
import com.finance.tracker.core.design.GeneratedTokens
import com.finance.tracker.domain.*
import com.finance.tracker.resources.Res
import com.finance.tracker.resources.ic_arrow_back
import com.finance.tracker.resources.ic_arrow_forward
import com.finance.tracker.resources.ic_check
import com.finance.tracker.resources.ic_close
import com.finance.tracker.resources.ic_description
import com.finance.tracker.resources.ic_edit
import com.finance.tracker.resources.ic_file_download
import com.finance.tracker.resources.ic_link
import com.finance.tracker.resources.ic_link_off
import com.finance.tracker.resources.ic_more_vert

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
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
import org.jetbrains.compose.resources.painterResource

private val inlineImportErrorCodes = setOf(
    "import_password_required",
    "import_password_invalid",
    "import_file_unsupported_type",
    "import_file_too_large",
    "import_file_size_unavailable",
    "import_file_too_many",
    "import_file_read_failed",
    "import_file_picker_failed",
    "import_mapping_incomplete",
    "import_composite_payment_unresolved",
    "import_component_allocation_incomplete",
    "import_component_amount_invalid",
)

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
    demoStatement: FileSource? = null,
) {
    val viewModel: CashImportViewModel = viewModel(
        key = "cash-import:$workspaceId",
        factory = viewModelFactory { initializer { CashImportViewModel(repository, diagnostics) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
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
    var dismissedGeneralErrorCode by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.errorCode) { dismissedGeneralErrorCode = null }
    val generalError = error.takeIf { state.errorCode?.let { code -> code !in inlineImportErrorCodes } == true }
    val showGeneralError = generalError != null && state.errorCode != dismissedGeneralErrorCode
    val dismissGeneralError = { dismissedGeneralErrorCode = state.errorCode }
    val inlineError = error.takeIf { state.errorCode?.let { code -> code in inlineImportErrorCodes } == true }
    var editingAccountGroupId by remember { mutableStateOf<String?>(null) }
    var editingAccountName by remember { mutableStateOf("") }
    var editingAccountType by remember { mutableStateOf("cash") }
    var editingAccountTypeMenuExpanded by remember { mutableStateOf(false) }
    var discardConfirmOpen by remember { mutableStateOf(false) }
    var busyCloseNotice by remember { mutableStateOf(false) }
    val compact = sizeClass == WindowSizeClass.COMPACT
    val editingDraft = editingAccountGroupId?.let { groupId -> accountDrafts[groupId]?.newAccount }
    val hasUnsubmittedWork = stage != ImportStage.SUCCESS &&
        (selectedFiles.isNotEmpty() || scan != null || stage != ImportStage.SELECT)
    val closeImport = {
        viewModel.reset()
        onBack()
    }
    val requestClose = {
        when {
            showGeneralError -> dismissGeneralError()
            editingAccountGroupId != null -> {
                editingAccountGroupId = null
                editingAccountTypeMenuExpanded = false
            }
            busyCloseNotice -> busyCloseNotice = false
            discardConfirmOpen -> discardConfirmOpen = false
            busy -> busyCloseNotice = true
            hasUnsubmittedWork -> discardConfirmOpen = true
            else -> closeImport()
        }
    }
    LaunchedEffect(busy) {
        if (!busy) busyCloseNotice = false
    }

    val canScan = selectedFiles.isNotEmpty() && scan?.files?.none {
        it.status == "error" || (it.status == "password_required" && passwords[it.index.toString()].isNullOrBlank())
    } != false
    val canContinueFromPreview = preview?.let { !hasIncompleteAllocations(it, allocationDrafts) } == true
    val primaryActionLabel = when (stage) {
        ImportStage.SELECT -> localizedText("cash_import_next")
        ImportStage.MAPPING -> localizedText("copy_b8a3f85115")
        ImportStage.PREVIEW -> localizedText("copy_1fc1afc5c5")
        ImportStage.RELATIONS -> localizedText("copy_de6433b70d")
        ImportStage.SUCCESS -> ""
    }
    val primaryActionEnabled = when (stage) {
        ImportStage.SELECT -> canScan
        ImportStage.MAPPING -> state.mappingComplete()
        ImportStage.PREVIEW -> canContinueFromPreview
        ImportStage.RELATIONS -> state.canCommitIgnoringBusy()
        ImportStage.SUCCESS -> false
    }
    val runPrimaryAction = {
        when (stage) {
            ImportStage.SELECT -> viewModel.scanSelectedFiles()
            ImportStage.MAPPING -> viewModel.loadPreview()
            ImportStage.PREVIEW -> {
                if (preview?.items?.any { it.components.orEmpty().size > 1 } == true) {
                    viewModel.loadPreview(ImportStage.RELATIONS)
                } else {
                    viewModel.stage(ImportStage.RELATIONS)
                }
            }
            ImportStage.RELATIONS, ImportStage.SUCCESS -> Unit
        }
    }
    val modalEdit = !compact && editingDraft != null && stage == ImportStage.MAPPING
    val modalDiscard = !compact && discardConfirmOpen
    val modalBusyNotice = busyCloseNotice
    val modalError = !compact && showGeneralError
    val modalSubtaskActive = modalEdit || modalDiscard || modalBusyNotice || modalError
    val modalTitle = when {
        compact -> localizedText("cash_import_title")
        modalError -> localizedText("cash_import_error_title")
        modalEdit -> localizedText("cash_import_edit_new_account_title")
        modalDiscard -> localizedText("cash_import_discard_title")
        modalBusyNotice -> localizedText("cash_import_busy_close_title")
        else -> localizedText("cash_import_title")
    }
    val dismissAccountEdit = {
        editingAccountGroupId = null
        editingAccountTypeMenuExpanded = false
    }
    val applyAccountEdit = {
        if (editingAccountName.isNotBlank()) {
            editingAccountGroupId?.let { groupId ->
                viewModel.updateNewAccount(groupId) {
                    it.copy(name = editingAccountName, type = editingAccountType)
                }
            }
            editingAccountGroupId = null
            editingAccountTypeMenuExpanded = false
        }
    }
    val footerButtonArrangement = if (sizeClass == WindowSizeClass.COMPACT) {
        Arrangement.SpaceBetween
    } else {
        Arrangement.spacedBy(8.dp, Alignment.End)
    }

    val filePicker = if (demoStatement == null) {
        rememberFilePickerLauncher(
            type = FileKitType.File(extensions = listOf("csv", "xls", "xlsx", "pdf")),
            mode = FileKitMode.Multiple(maxItems = MAX_IMPORT_FILES),
            onError = { viewModel.setPickerError() },
            onResult = { files -> viewModel.receiveFiles(files?.map(::PlatformImportFileSource)) },
        )
    } else null

    SecondaryPageContainer(
        title = modalTitle,
        sizeClass = sizeClass,
        onClose = requestClose,
        contentStateKey = when {
            compact && editingDraft != null && stage == ImportStage.MAPPING -> "account-edit"
            compact && discardConfirmOpen -> "discard-confirm"
            compact && showGeneralError -> "general-error"
            else -> null
        },
        escapeEnabled = !editingAccountTypeMenuExpanded,
        showFooter = sizeClass != WindowSizeClass.COMPACT || stage == ImportStage.SUCCESS || !canWrite,
        compactHeaderAction = {
            if (sizeClass == WindowSizeClass.COMPACT) {
                when {
                    modalDiscard -> TextButton(
                        onClick = closeImport,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.testTag("cash-import.discard-confirm"),
                    ) { Text(localizedText("cash_import_discard")) }
                    modalEdit -> TextButton(
                        onClick = applyAccountEdit,
                        modifier = Modifier.testTag("import.edit.apply"),
                    ) { Text(localizedText("cash_import_apply_account_changes")) }
                    modalError -> TextButton(
                        onClick = dismissGeneralError,
                        modifier = Modifier.testTag("cash-import.error-acknowledge"),
                    ) { Text(localizedText("cash_import_error_acknowledge")) }
                    modalBusyNotice -> TextButton(
                        onClick = { busyCloseNotice = false },
                        modifier = Modifier.testTag("cash-import.busy-continue"),
                    ) { Text(localizedText("cash_import_busy_continue")) }
                    canWrite && stage != ImportStage.SUCCESS -> Box(Modifier.testTag("secondary-page-primary-action")) {
                        TextButton(
                            onClick = runPrimaryAction,
                            enabled = primaryActionEnabled,
                            modifier = Modifier.testTag(if (stage == ImportStage.RELATIONS) SemanticIds.importConfirm else SemanticIds.importNext),
                        ) { Text(primaryActionLabel) }
                    }
                }
            }
        },
        footer = {
            when {
                modalError -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = dismissGeneralError, modifier = Modifier.testTag("cash-import.error-acknowledge")) {
                        Text(localizedText("cash_import_error_acknowledge"))
                    }
                }
                modalEdit -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = dismissAccountEdit, modifier = Modifier.testTag("import.edit.cancel")) {
                        Text(localizedText("copy_4d0b4688c7"))
                    }
                    TextButton(
                        onClick = applyAccountEdit,
                        enabled = editingAccountName.isNotBlank(),
                        modifier = Modifier.testTag("import.edit.apply"),
                    ) {
                        Text(localizedText("cash_import_apply_account_changes"))
                    }
                }
                modalDiscard -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { discardConfirmOpen = false }, modifier = Modifier.testTag("cash-import.discard-cancel")) {
                        Text(localizedText("cash_import_continue"))
                    }
                    TextButton(
                        onClick = closeImport,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.testTag("cash-import.discard-confirm"),
                    ) { Text(localizedText("cash_import_discard")) }
                }
                modalBusyNotice -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { busyCloseNotice = false }, modifier = Modifier.testTag("cash-import.busy-continue")) {
                        Text(localizedText("cash_import_busy_continue"))
                    }
                }
                !canWrite -> Unit
                else -> when (stage) {
                ImportStage.SELECT -> {
                    if (sizeClass != WindowSizeClass.COMPACT) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = requestClose, modifier = Modifier.testTag("cash-import.cancel")) {
                                Text(localizedText("cash_import_cancel"))
                            }
                            ImportScanAction(
                                enabled = canScan,
                                busy = busy,
                                modifier = Modifier.testTag(SemanticIds.importNext),
                                onClick = { viewModel.scanSelectedFiles() },
                            )
                        }
                    }
                }
                ImportStage.MAPPING -> if (sizeClass != WindowSizeClass.COMPACT) Row(Modifier.fillMaxWidth(), horizontalArrangement = footerButtonArrangement) {
                    OutlinedButton(onClick = { viewModel.stage(ImportStage.SELECT) }, enabled = !busy, modifier = Modifier.testTag(SemanticIds.importPrevious)) {
                        Icon(painterResource(Res.drawable.ic_arrow_back), contentDescription = null, modifier = Modifier.size(18.dp).testTag("import.previous.icon"))
                        Spacer(Modifier.size(8.dp))
                        Text(localizedText("copy_75ef1241c0"))
                    }
                    if (sizeClass != WindowSizeClass.COMPACT) Button(onClick = runPrimaryAction, enabled = primaryActionEnabled, modifier = Modifier.testTag(SemanticIds.importNext)) {
                        Text(if (busy) localizedText("copy_a99736a830") else localizedText("copy_b8a3f85115"))
                        Spacer(Modifier.size(8.dp))
                        Icon(painterResource(Res.drawable.ic_arrow_forward), contentDescription = null, modifier = Modifier.size(18.dp).testTag("import.next.icon"))
                    }
                }
                ImportStage.PREVIEW -> if (sizeClass != WindowSizeClass.COMPACT) Row(Modifier.fillMaxWidth(), horizontalArrangement = footerButtonArrangement) {
                    OutlinedButton(onClick = { viewModel.stage(ImportStage.MAPPING) }, enabled = !busy, modifier = Modifier.testTag(SemanticIds.importPrevious)) {
                        Icon(painterResource(Res.drawable.ic_arrow_back), contentDescription = null, modifier = Modifier.size(18.dp).testTag("import.previous.icon"))
                        Spacer(Modifier.size(8.dp))
                        Text(localizedText("copy_75ef1241c0"))
                    }
                    if (sizeClass != WindowSizeClass.COMPACT) Button(onClick = runPrimaryAction, enabled = primaryActionEnabled, modifier = Modifier.testTag(SemanticIds.importNext)) {
                        Text(localizedText("copy_1fc1afc5c5"))
                        Spacer(Modifier.size(8.dp))
                        Icon(painterResource(Res.drawable.ic_arrow_forward), contentDescription = null, modifier = Modifier.size(18.dp).testTag("import.next.icon"))
                    }
                }
                ImportStage.RELATIONS -> if (sizeClass != WindowSizeClass.COMPACT) Row(Modifier.fillMaxWidth(), horizontalArrangement = footerButtonArrangement) {
                    OutlinedButton(onClick = { viewModel.stage(ImportStage.PREVIEW) }, enabled = !busy, modifier = Modifier.testTag(SemanticIds.importPrevious)) {
                        Icon(painterResource(Res.drawable.ic_arrow_back), contentDescription = null, modifier = Modifier.size(18.dp).testTag("import.previous.icon"))
                        Spacer(Modifier.size(8.dp))
                        Text(localizedText("copy_75ef1241c0"))
                    }
                    if (sizeClass != WindowSizeClass.COMPACT) Button(onClick = { viewModel.commitImport() }, enabled = state.canCommitIgnoringBusy(), modifier = Modifier.testTag(SemanticIds.importConfirm)) {
                        Icon(painterResource(Res.drawable.ic_check), contentDescription = null, modifier = Modifier.size(18.dp).testTag("import.confirm.icon"))
                        Spacer(Modifier.size(8.dp))
                        Text(if (busy) localizedText("copy_cd74f09629") else localizedText("copy_de6433b70d"))
                    }
                }
                ImportStage.SUCCESS -> Button(onClick = closeImport, modifier = Modifier.fillMaxWidth().testTag("import.return")) {
                    Icon(painterResource(Res.drawable.ic_arrow_back), contentDescription = null, modifier = Modifier.size(18.dp).testTag("import.return.icon"))
                    Spacer(Modifier.size(8.dp))
                    Text(localizedText("copy_855fa817d2"))
                }
                }
            }
        },
    ) {
        if (modalSubtaskActive) {
            when {
                modalError -> InlineError(generalError.orEmpty())
                modalEdit -> {
                    ImportNewAccountEditFields(
                        name = editingAccountName,
                        type = editingAccountType,
                        currencies = editingDraft.currencies,
                        onNameChanged = { editingAccountName = it.take(255) },
                        onTypeChanged = { editingAccountType = it },
                        onTypeMenuExpandedChange = { editingAccountTypeMenuExpanded = it },
                        modifier = Modifier.fillMaxWidth()
                            .testTag(if (sizeClass == WindowSizeClass.COMPACT) "import.mapping.edit-fullscreen" else "import.mapping.edit-inline"),
                    )
                }
                modalDiscard -> Text(
                    localizedText("cash_import_discard_message"),
                    modifier = Modifier.fillMaxWidth().padding(24.dp).testTag("cash-import.discard-message"),
                    style = MaterialTheme.typography.bodyLarge,
                )
                modalBusyNotice -> Text(
                    localizedText("cash_import_busy_close_message"),
                    modifier = Modifier.fillMaxWidth().padding(24.dp).testTag("cash-import.busy-message"),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
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
                    InlineError(inlineError)
                    when (stage) {
                        ImportStage.SELECT -> {
                            Column(
                                Modifier.fillMaxWidth().testTag("import.stage-content"),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                Text(
                                    localizedText("cash_import_select_file_heading"),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                ImportUploadSurface(
                                    selectedFiles = selectedFiles,
                                    demoStatement = demoStatement != null,
                                    busy = busy,
                                    onChoose = {
                                        if (demoStatement != null) viewModel.receiveFiles(listOf(demoStatement))
                                        else filePicker?.launch()
                                    },
                                )
                                selectedFiles.forEachIndexed { index, picked ->
                                    ImportOutlinedCard {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                painter = painterResource(Res.drawable.ic_description),
                                                contentDescription = null,
                                                modifier = Modifier.size(24.dp),
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                                Text(picked.identity.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                Text(formatFileSize(picked.identity.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                scan?.files?.firstOrNull { it.index == index }?.let { status ->
                                                    Text(importFileStatus(status), style = MaterialTheme.typography.bodySmall)
                                                }
                                            }
                                            TextButton(
                                                onClick = { viewModel.removeFile(picked.identity.digest) },
                                                enabled = !busy,
                                                modifier = Modifier.testTag("${SemanticIds.importRemoveFile}.${picked.identity.digest}"),
                                            ) {
                                                Icon(painterResource(Res.drawable.ic_close), contentDescription = null, modifier = Modifier.size(18.dp).testTag("import.remove-file.icon.${picked.identity.digest}"))
                                                Spacer(Modifier.size(8.dp))
                                                Text(localizedText("copy_2f752c005e"))
                                            }
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
                                    Text(localizedText("copy_56bc6903d6", importChannelLabel(current.channel, current.channelLabel), current.groups.size), style = MaterialTheme.typography.titleSmall)
                                    Text(current.files.joinToString(" · ") { it.filename.orEmpty() }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (current.unresolvedCount > 0) Text(localizedText("copy_40b67ad6b4", current.unresolvedCount), color = MaterialTheme.colorScheme.error)
                                }
                                Text(localizedText("cash_import_mapping_heading"), Modifier.testTag("import.mapping.heading"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                current.groups.forEach { group ->
                                    val draft = accountDrafts[group.groupId]
                                    val ownDraftId = "draft-${group.groupId}"
                                    val sharedDrafts = accountDrafts.values.mapNotNull(ImportAccountDraft::newAccount).distinctBy { it.draftId }
                                    val targetOptions = listOf("" to localizedText("copy_ce1ec4bd2e")) +
                                        current.accounts.map {
                                            it.id.toString() to importMappedAccountOptionLabel(it.name, it.type, it.currencies.orEmpty())
                                        } +
                                        sharedDrafts.filterNot { it.draftId == ownDraftId }.map {
                                            "__draft__${it.draftId}" to importMappedAccountOptionLabel(it.name, it.type, it.currencies)
                                        } +
                                        ("__create__" to localizedText("copy_93b0b8ba3b"))
                                    val targetValue = when {
                                        draft?.newAccount?.draftId == ownDraftId -> "__create__"
                                        draft?.newAccount != null -> "__draft__${draft.newAccount.draftId}"
                                        else -> draft?.accountId?.toString().orEmpty()
                                    }
                                    val targetPicker: @Composable (Modifier) -> Unit = { pickerModifier ->
                                        ChoicePicker(
                                            localizedText("copy_8ab177def7"),
                                            targetValue,
                                            targetOptions,
                                            { viewModel.selectAccount(group, it) },
                                            modifier = pickerModifier,
                                            semanticId = "${SemanticIds.importMapping}.${group.groupId}",
                                        )
                                    }
                                    val targetSummary: @Composable () -> Unit = {
                                        val selectedAccount = current.accounts.firstOrNull { it.id == draft?.accountId }
                                        val newAccount = draft?.newAccount
                                        when {
                                            selectedAccount != null -> {
                                                val missingCurrencies = group.currencies.filterNot { it in selectedAccount.currencies.orEmpty() }
                                                if (missingCurrencies.isNotEmpty()) {
                                                    Text(localizedText("copy_6f136836ac", missingCurrencies.joinToString("、")), color = MaterialTheme.colorScheme.error)
                                                }
                                            }
                                            newAccount?.draftId == ownDraftId -> ImportMappedAccountSummary(
                                                name = newAccount.name,
                                                type = newAccount.type,
                                                currencies = newAccount.currencies,
                                                onEdit = {
                                                    editingAccountGroupId = group.groupId
                                                    editingAccountName = newAccount.name
                                                    editingAccountType = newAccount.type
                                                },
                                            )
                                            newAccount != null -> Row(
                                                Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.End,
                                            ) {
                                                TextButton(
                                                    onClick = {
                                                        editingAccountGroupId = group.groupId
                                                        editingAccountName = newAccount.name
                                                        editingAccountType = newAccount.type
                                                    },
                                                    modifier = Modifier.testTag("import.mapping.edit"),
                                                ) {
                                                    Icon(painterResource(Res.drawable.ic_edit), contentDescription = null, modifier = Modifier.size(18.dp).testTag("import.mapping.edit.icon"))
                                                    Spacer(Modifier.size(8.dp))
                                                    Text(localizedText("cash_import_edit_new_account"))
                                                }
                                            }
                                        }
                                    }
                                    ImportOutlinedCard(Modifier.testTag("import.mapping.card.${group.groupId}")) {
                                        if (sizeClass == WindowSizeClass.COMPACT) {
                                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                                ImportMappingSource(group)
                                                targetPicker(Modifier)
                                                targetSummary()
                                            }
                                        } else {
                                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                                ImportMappingSource(group, Modifier.weight(1.2f))
                                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                                    targetPicker(Modifier)
                                                    targetSummary()
                                                }
                                            }
                                        }
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
                                                        "income" -> MaterialTheme.colorScheme.primary
                                                        "expense" -> MaterialTheme.colorScheme.error
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
                                                    OutlinedTextField(
                                                        value = currentAmounts.getOrElse(index) { component.amount?.removePrefix("+")?.removePrefix("-").orEmpty() },
                                                        onValueChange = { value -> viewModel.updateAllocation(key, index, value) },
                                                        modifier = Modifier.testTag("${SemanticIds.importAllocation}.$key.$index")
                                                            .accessibleTextFieldLabel(component.sourceLabel),
                                                        label = { Text(component.sourceLabel) },
                                                        singleLine = true,
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
                                    Modifier.size(56.dp).background(LocalFinanceColorRoles.current.successSurface, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        painter = painterResource(Res.drawable.ic_check),
                                        contentDescription = null,
                                        modifier = Modifier.size(28.dp),
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
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
    if (compact && editingDraft != null && stage == ImportStage.MAPPING) {
        PlatformModalEscapeHandler(enabled = isWebPlatform, onEscape = dismissAccountEdit)
        AlertDialog(
            modifier = Modifier.testTag("import.mapping.edit-dialog"),
            onDismissRequest = dismissAccountEdit,
            title = { Text(localizedText("cash_import_edit_new_account_title")) },
            text = {
                ImportNewAccountEditFields(
                    name = editingAccountName,
                    type = editingAccountType,
                    currencies = editingDraft.currencies,
                    onNameChanged = { editingAccountName = it.take(255) },
                    onTypeChanged = { editingAccountType = it },
                    onTypeMenuExpandedChange = { editingAccountTypeMenuExpanded = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = applyAccountEdit,
                    enabled = editingAccountName.isNotBlank(),
                    modifier = Modifier.testTag("import.edit.apply"),
                ) { Text(localizedText("cash_import_apply_account_changes")) }
            },
            dismissButton = {
                TextButton(onClick = dismissAccountEdit, modifier = Modifier.testTag("import.edit.cancel")) {
                    Text(localizedText("copy_4d0b4688c7"))
                }
            },
        )
    } else if (compact && discardConfirmOpen) {
        PlatformModalEscapeHandler(enabled = isWebPlatform, onEscape = { discardConfirmOpen = false })
        AlertDialog(
            modifier = Modifier.testTag("cash-import.discard-dialog"),
            onDismissRequest = { discardConfirmOpen = false },
            title = { Text(localizedText("cash_import_discard_title")) },
            text = { Text(localizedText("cash_import_discard_message")) },
            confirmButton = {
                TextButton(
                    onClick = closeImport,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("cash-import.discard-confirm"),
                ) { Text(localizedText("cash_import_discard")) }
            },
            dismissButton = {
                TextButton(onClick = { discardConfirmOpen = false }, modifier = Modifier.testTag("cash-import.discard-cancel")) {
                    Text(localizedText("cash_import_continue"))
                }
            },
        )
    } else if (compact && showGeneralError) {
        PlatformModalEscapeHandler(enabled = isWebPlatform, onEscape = dismissGeneralError)
        AlertDialog(
            modifier = Modifier.testTag("cash-import.error-dialog"),
            onDismissRequest = dismissGeneralError,
            title = { Text(localizedText("cash_import_error_title")) },
            text = { Text(generalError.orEmpty()) },
            confirmButton = {
                TextButton(onClick = dismissGeneralError, modifier = Modifier.testTag("cash-import.error-acknowledge")) {
                    Text(localizedText("cash_import_error_acknowledge"))
                }
            },
        )
    }
}

@Composable
private fun ImportNewAccountEditFields(
    name: String,
    type: String,
    currencies: List<String>,
    onNameChanged: (String) -> Unit,
    onTypeChanged: (String) -> Unit,
    onTypeMenuExpandedChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChanged,
            modifier = Modifier.fillMaxWidth()
                .accessibleTextFieldLabel(localizedText("copy_238452a115"))
                .testTag("import.mapping.edit-name"),
            label = { Text(localizedText("copy_238452a115")) },
            singleLine = true,
        )
        ChoicePicker(
            label = localizedText("copy_082c31df2b"),
            value = type,
            options = listOf(
                "cash" to localizedText("copy_ca5bd21330"),
                "loan" to localizedText("copy_099f8b5c68"),
                "lend" to localizedText("copy_4a2637c23f"),
            ),
            onSelected = onTypeChanged,
            semanticId = "import.mapping.edit-type",
            onMenuExpandedChange = onTypeMenuExpandedChange,
        )
        Text(
            localizedText("copy_b241eb825a", currencies.joinToString(" / ")),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ImportUploadSurface(
    selectedFiles: List<PickedImportFile>,
    demoStatement: Boolean,
    busy: Boolean,
    onChoose: () -> Unit,
) {
    val colors = LocalFinanceColorRoles.current
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = Modifier.fillMaxWidth()
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
            if (demoStatement) localizedText("cash_import_demo_statement")
            else selectedFiles.firstOrNull()?.identity?.name ?: localizedText("cash_import_add_file_heading"),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!demoStatement) {
            Text(
                localizedText("cash_import_file_formats"),
                style = MaterialTheme.typography.bodySmall,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(
            onClick = onChoose,
            modifier = Modifier.testTag(SemanticIds.importChooseFile),
            shape = CircleShape,
            border = BorderStroke(2.dp, colors.accent),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.accent),
            enabled = !busy && (!demoStatement || selectedFiles.isEmpty()),
        ) {
            Icon(painterResource(Res.drawable.ic_description), contentDescription = null, modifier = Modifier.size(18.dp).testTag("import.choose-file.icon"), tint = colors.accent)
            Spacer(Modifier.size(8.dp))
            Text(
                when {
                    demoStatement && selectedFiles.isEmpty() -> localizedText("cash_import_use_demo_statement")
                    demoStatement -> localizedText("cash_import_demo_statement_added")
                    selectedFiles.isEmpty() -> localizedText("cash_import_choose_file")
                    else -> localizedText("copy_7c3a726882")
                },
                color = colors.accent,
            )
        }
    }
}

@Composable
private fun ImportScanAction(
    enabled: Boolean,
    busy: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        border = BorderStroke(1.dp, if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Icon(painterResource(Res.drawable.ic_arrow_forward), contentDescription = null, modifier = Modifier.size(18.dp).testTag("import.next.icon"))
        Spacer(Modifier.size(8.dp))
        Text(if (busy) localizedText("copy_4a5e035eb9") else localizedText("cash_import_next"))
    }
}

@Composable
private fun ImportUploadIcon(color: Color) {
    Icon(
        painter = painterResource(Res.drawable.ic_file_download),
        contentDescription = null,
        modifier = Modifier.size(32.dp),
        tint = color,
    )
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

@Composable
private fun importMappedAccountOptionLabel(name: String, type: String, currencies: List<String>): String =
    "$name · ${importAccountTypeLabel(type)} · ${localizedText("copy_b241eb825a", currencies.distinct().sorted().joinToString(" / "))}"

@Composable
private fun ImportMappedAccountSummary(
    name: String,
    type: String,
    currencies: List<String>,
    onEdit: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Text(
                "${importAccountTypeLabel(type)} · ${localizedText("copy_b241eb825a", currencies.joinToString(" / "))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onEdit != null) {
            TextButton(onClick = onEdit, modifier = Modifier.testTag("import.mapping.edit")) {
                Icon(painterResource(Res.drawable.ic_edit), contentDescription = null, modifier = Modifier.size(18.dp).testTag("import.mapping.edit.icon"))
                Spacer(Modifier.size(8.dp))
                Text(localizedText("cash_import_edit_new_account"))
            }
        }
    }
}

@Composable
private fun importAccountTypeLabel(type: String): String = when (type) {
    "cash" -> localizedText("copy_ca5bd21330")
    "loan" -> localizedText("copy_099f8b5c68")
    "lend" -> localizedText("copy_4a2637c23f")
    else -> localizedText("copy_082c31df2b")
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
            FilterChip(
                selected = active,
                onClick = { onSelect(value) },
                modifier = Modifier.testTag("$semanticPrefix.$value"),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = colors.accent.copy(alpha = 0.12f),
                    selectedLabelColor = colors.foreground,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                label = {
                    Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                },
            )
        }
    }
}

@Composable
private fun ImportEmptyState(message: String) {
    val colors = LocalFinanceColorRoles.current
    Box(
        Modifier.fillMaxWidth()
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
        status == "pending" -> colors.warningSurface
        else -> colors.accent.copy(alpha = 0.1f)
    }
    val foreground = when {
        isRejected -> MaterialTheme.colorScheme.error
        status == "pending" -> colors.warning
        else -> colors.accent
    }
    val actionsDescription = localizedText("cash_import_more_actions")
    val chip: @Composable () -> Unit = {
        Row(
            Modifier.background(background, CircleShape).padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = foreground, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            if (onActionClick != null) {
                Icon(
                    painterResource(Res.drawable.ic_more_vert),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp).testTag("${actionTestTag ?: "import.relation-actions"}.icon"),
                    tint = foreground,
                )
            }
        }
    }
    if (onActionClick == null) {
        chip()
    } else {
        var focused by remember(actionTestTag) { mutableStateOf(false) }
        TextButton(
            onClick = onActionClick,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier
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
                    Icon(
                        painter = painterResource(Res.drawable.ic_arrow_forward),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                Icon(
                    painter = painterResource(Res.drawable.ic_arrow_forward),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                    )
                }
                TextButton(onClick = onToggleRejected) {
                    Icon(
                        painterResource(if (rejected) Res.drawable.ic_link else Res.drawable.ic_link_off),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp).testTag("import.relation.toggle.icon"),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(if (rejected) localizedText("copy_3290b42bd1") else localizedText("copy_86ab462000"))
                }
            }
        }
    }
}

@Composable
private fun ImportRelationRecordView(record: ImportRelationRecord, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(formatImportRelationDateTime(record.occurredAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            "${formatImportRelationDateTime(record.occurredAt)} · ${record.amount} ${record.currency}",
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
            TextButton(
                onClick = { onSelect(step) },
                enabled = enabled,
                contentPadding = PaddingValues(0.dp),
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
                        if (done) {
                            Icon(
                                painter = painterResource(Res.drawable.ic_check),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text(
                                (index + 1).toString(),
                                color = if (current) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
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
private fun formatImportRelationDateTime(instant: String): String {
    val parts = localDateTimeDisplayParts(instant) ?: return localizedText("display_not_provided")
    val month = parts.date.month.toString().padStart(2, '0')
    return localizedText("display_date_time_short", parts.date.year, month, parts.date.day, parts.time)
}

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
    "ready" -> localizedText("copy_5707975153", importChannelLabel(file.channel, file.channelLabel.orEmpty()))
    "password_required" -> localizedText("copy_4908aa3009")
    else -> localizedText("copy_53514e0040")
}

@Composable
private fun importChannelLabel(channel: String?, fallback: String): String = when (channel) {
    "bank" -> localizedText("cash_import_channel_bank")
    else -> fallback
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
