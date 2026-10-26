package com.finance.tracker.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.finance.tracker.core.*
import com.finance.tracker.data.PlatformCashImportFileSource
import com.finance.tracker.domain.*
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import kotlinx.coroutines.CancellationException
import kotlin.random.Random

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CashImportTaskScreen(repository: CashImportLifecycleRepository, finance: FinanceRepository,
    recovery: ImportRecoveryStore, accountRecovery: ImportAccountRecovery, workspaceId: String,
    sizeClass: WindowSizeClass, canWrite: Boolean, registerExit: (((() -> Unit) -> Unit)?) -> Unit,
    onBack: () -> Unit, onDone: () -> Unit, demoStatement: FileSource? = null) {
    val owner = remember(repository, recovery, accountRecovery) { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    key(owner) { CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        CashImportTaskContent(repository, finance, recovery, accountRecovery, workspaceId, sizeClass, canWrite, registerExit, onBack, onDone, demoStatement)
    } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CashImportTaskContent(repository: CashImportLifecycleRepository, finance: FinanceRepository,
    recovery: ImportRecoveryStore, accountRecovery: ImportAccountRecovery, workspaceId: String,
    sizeClass: WindowSizeClass, canWrite: Boolean, registerExit: (((() -> Unit) -> Unit)?) -> Unit,
    onBack: () -> Unit, onDone: () -> Unit, demoStatement: FileSource?) {
    val task: CashImportTaskViewModel = viewModel(key = "cash-import-task:$workspaceId", factory = viewModelFactory {
        initializer { CashImportTaskViewModel(repository, recovery) }
    })
    val account: ImportAccountViewModel = viewModel(key = "cash-import-accounts:$workspaceId", factory = viewModelFactory {
        initializer { ImportAccountViewModel(repository, accountRecovery) }
    })
    val state by task.state.collectAsStateWithLifecycle()
    val accountState by account.state.collectAsStateWithLifecycle()
    var accounts by remember { mutableStateOf<List<Account>>(emptyList()) }
    var categories by remember { mutableStateOf<List<CashCategory>>(emptyList()) }
    var options by remember { mutableStateOf(LedgerOptions()) }
    var auxiliaryReady by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var localError by remember { mutableStateOf<String?>(null) }
    var fileNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val files = remember { mutableMapOf<String, FileSource>() }
    var replacing by remember { mutableStateOf<String?>(null) }
    var replacementPassword by remember { mutableStateOf<String?>(null) }
    var accountUnknownNotice by remember { mutableStateOf(false) }
    var processingOpen by remember { mutableStateOf(false) }
    var selectedItem by remember { mutableStateOf<String?>(null) }
    var accountForm by remember { mutableStateOf<String?>(null) }
    var formSaving by remember { mutableStateOf(false) }
    var detailDirty by remember { mutableStateOf(false) }
    var accountDirty by remember { mutableStateOf(false) }
    var discardAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var exitDialog by remember { mutableStateOf<ImportTaskExitAction?>(null) }
    var exitNavigation by remember { mutableStateOf<(() -> Unit)?>(null) }
    var closingTaskId by remember { mutableStateOf<Long?>(null) }
    var undoAfterClose by remember { mutableStateOf(false) }
    var undoStarted by remember { mutableStateOf(false) }
    var busyNotice by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf<Pair<String, String>?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(task, account) { task.restore(); account.restore() }
    DisposableEffect(task, account) { onDispose { task.detach(); account.detach(); files.clear(); replacementPassword = null } }
    LaunchedEffect(reload) {
        auxiliaryReady = false
        try {
            accounts = finance.fetchCashAccounts(); categories = finance.fetchCashCategories().items
            options = finance.fetchLedgerOptions(); auxiliaryReady = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { localError = (failure as? DomainFailure)?.code ?: "request_failed" }
    }
    val currentAccounts = accounts.filterNot { cached -> accountState.accounts.any { it.id == cached.id.toString() } } +
        accountState.accounts.mapNotNull { fresh -> fresh.id.toLongOrNull()?.let { Account(it, fresh.name, fresh.type, fresh.active, fresh.currencies) } }
    LaunchedEffect(state.receipt?.receiptId) { if (state.receipt != null) onDone() }
    LaunchedEffect(state.plan?.planId) { selectedItem = null; detailDirty = false }
    LaunchedEffect(state.context?.files, state.busy) {
        if (state.busy) return@LaunchedEffect
        val retained = state.context?.files.orEmpty().filter { it.canRetry }.map { it.clientFileRef }.toSet()
        files.keys.retainAll(retained + state.localFileFailures.map { it.clientFileRef } + state.unprocessedFileRefs)
    }
    LaunchedEffect(accountState.busy, accountState.currentAccounts, selecting) {
        val selection = selecting
        if (selection != null && !accountState.busy) {
            selecting = null
            accountState.currentAccounts[selection.second]?.let { task.selectAccount(selection.first, ImportAccountTarget(it.accountId, it.revision)) }
        }
        if (formSaving && !accountState.busy) {
            formSaving = false
            if (accountState.errorCode == null && accountState.pendingKeys.isEmpty()) { accountForm = null; accountDirty = false }
        }
    }
    fun completeExit() { val action = exitNavigation; exitNavigation = null; exitDialog = null; action?.invoke() }
    fun closeTask(undo: Boolean) {
        val decision = importTaskExitAction(task.state.value, account.state.value)
        if (decision == ImportTaskExitAction.BLOCK) { busyNotice = true; return }
        if (decision == ImportTaskExitAction.KEEP_RECOVERY) { completeExit(); return }
        if (undo && decision != ImportTaskExitAction.ASK_ACCOUNTS) { exitDialog = decision.takeIf { it == ImportTaskExitAction.ACCOUNT_UNKNOWN }; return }
        exitDialog = null; undoAfterClose = undo; closingTaskId = state.taskId
        if (state.receipt != null) task.startNew() else task.cancel()
    }
    LaunchedEffect(state.taskId, state.busy, accountState.busy, closingTaskId, undoStarted) {
        val old = closingTaskId ?: return@LaunchedEffect
        if (state.taskId != old && !state.busy) {
            if (undoAfterClose && !undoStarted) { undoStarted = true; account.undo() }
            else if (!accountState.busy) {
                closingTaskId = null; undoStarted = false
                if (undoAfterClose && (accountState.undoFailures.isNotEmpty() || accountState.pendingKeys.isNotEmpty())) exitDialog = ImportTaskExitAction.ACCOUNT_UNKNOWN
                else completeExit()
            }
        } else if (!state.busy && state.errorCode != null) { closingTaskId = null; undoStarted = false }
    }
    fun requestExit(action: () -> Unit) {
        if (task.state.value.busy || account.state.value.busy) { busyNotice = true; return }
        if (detailDirty || accountDirty) { discardAction = { detailDirty = false; accountDirty = false; accountForm = null; selectedItem = null; exitNavigation = action
            when (val decision = importTaskExitAction(task.state.value, account.state.value)) {
                ImportTaskExitAction.BLOCK -> busyNotice = true
                ImportTaskExitAction.KEEP_RECOVERY -> completeExit()
                ImportTaskExitAction.FINISH, ImportTaskExitAction.CANCEL -> closeTask(false)
                else -> exitDialog = decision
            }
        }; return }
        exitNavigation = action
        when (val decision = importTaskExitAction(task.state.value, account.state.value)) {
            ImportTaskExitAction.BLOCK -> busyNotice = true
            ImportTaskExitAction.KEEP_RECOVERY -> completeExit()
            ImportTaskExitAction.FINISH, ImportTaskExitAction.CANCEL -> closeTask(false)
            else -> exitDialog = decision
        }
    }
    val latestExit by rememberUpdatedState<(()->Unit)->Unit> { requestExit(it) }
    DisposableEffect(registerExit) { registerExit { latestExit(it) }; onDispose { registerExit(null) } }
    fun receive(sources: List<FileSource>?) {
        if (sources.isNullOrEmpty()) { replacementPassword = null; replacing = null; return }
        val previous = replacing?.let { ref -> state.context?.files?.firstOrNull { it.fileRef == ref } }
        if (replacing != null && (previous == null || sources.size != 1)) { localError = "import_file_too_many"; replacementPassword = null; replacing = null; return }
        val uploads = sources.map { source ->
            val ref = previous?.clientFileRef ?: "file-${Random.nextLong().toULong().toString(16)}"
            files[ref] = source; fileNames = fileNames + (ref to source.name)
            ImportUpload(ref, source, replacementPassword)
        }
        task.upload(uploads, replacing); replacing = null; replacementPassword = null
    }
    val picker = if (demoStatement == null) rememberFilePickerLauncher(
        type = FileKitType.File(listOf("csv", "xls", "xlsx", "pdf")), mode = FileKitMode.Multiple(maxItems = 20),
        onError = { localError = "import_file_picker_failed"; replacementPassword = null; replacing = null }, onResult = { result -> receive(result?.map(::PlatformCashImportFileSource)) }) else null
    fun pick(ref: String?, password: String? = null) { replacing = ref; replacementPassword = password; localError = null; if (demoStatement != null) receive(listOf(demoStatement)) else picker?.launch() }
    val result = state.receipt != null || state.frozenCommand != null
    val busy = state.busy || accountState.busy || closingTaskId != null
    Column(Modifier.fillMaxSize().padding(16.dp).testTag("cash.import.task"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!canWrite) Text(localizedText("copy_dd9a4bdee0"), color = MaterialTheme.colorScheme.error)
        if (result) CashImportTaskResult(state, onResolve = task::resolve, onRetry = task::retryOriginal,
            onReturn = { requestExit(onBack) }, enabled = !busy)
        else {
            localError?.let { Text(cashImportTaskErrorText(it), color = MaterialTheme.colorScheme.error) }
            state.errorCode?.let { Text(cashImportTaskErrorText(it), color = MaterialTheme.colorScheme.error) }
            state.errorDiagnostic?.let { CashImportDiagnostic(it) }
            if (!auxiliaryReady) TextButton(onClick = { localError = null; reload++ }, enabled = !busy) { Text(localizedText("copy_e2d53a6d3a")) }
            if (state.plan?.invalidatedChoices?.isNotEmpty() == true) Text(localizedText("cash_import_preview_invalid"), color = MaterialTheme.colorScheme.error)
            if (state.phase == ImportTaskPhase.REVIEW && (state.plan?.summary?.get("duplicate") ?: 0L) +
                (state.plan?.summary?.get("skip") ?: 0L) + (state.plan?.summary?.get("relations_failed") ?: 0L) > 0L) {
                TextButton(onClick = { processingOpen = true; task.loadProcessing() }, enabled = state.canEdit,
                    modifier = Modifier.testTag("cash.import.processing")) { Text(localizedText("cash_import_processing_details")) }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (state.phase == ImportTaskPhase.REVIEW) CashImportReviewList(state, categories, sizeClass, listState,
                    { task.loadItems(it) }, { task.loadItems(append = true) }, { selectedItem = it; task.loadDetail(it) }, Modifier.fillMaxSize())
                else CashImportPreparation(if (canWrite) state else state.copy(busy = true), accountState, fileNames, { pick(it) },
                    onRetry = { file, password ->
                        val source = files[file.clientFileRef]
                        if (source == null) pick(file.fileRef, password.takeIf(String::isNotEmpty))
                        else task.upload(listOf(ImportUpload(file.clientFileRef, source, password.takeIf(String::isNotEmpty))), file.fileRef)
                    }, onRemove = task::removeFile, onScope = task::setSourceScope,
                    onSelectAccount = { ref, id -> if (id == null) task.selectAccount(ref, ImportAccountTarget(null, null))
                        else { selecting = ref to id; account.loadAccount(id) } },
                    onCreateAccount = { accountForm = "new" }, onEditAccount = { accountForm = it; account.loadAccount(it) }, onResolveAccounts = account::resolvePending, modifier = Modifier.fillMaxSize())
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = { requestExit(onBack) }, enabled = !busy, modifier = Modifier.testTag("cash.import.return")) { Text(localizedText("cash_import_back_ledger")) }
                if (state.phase == ImportTaskPhase.REVIEW) TextButton(onClick = task::showPreparation, enabled = state.canEdit) { Text(localizedText("cash_import_back_prepare")) }
                if (state.needsContextRefresh || state.plan?.invalidatedChoices?.isNotEmpty() == true) Button(onClick = task::restartPreview,
                    enabled = !busy && canWrite, modifier = Modifier.testTag("cash.import.restart")) { Text(localizedText("cash_import_restart_review")) }
                else if (state.phase == ImportTaskPhase.REVIEW && !state.previewDirty) Button(onClick = task::confirm,
                    enabled = !busy && canWrite && state.canConfirm, modifier = Modifier.testTag("cash.import.confirm")) { Text(localizedText("cash_import_confirm")) }
                else Button(onClick = task::preview, enabled = !busy && canWrite && auxiliaryReady && state.canEdit && state.context?.canPreview == true,
                    modifier = Modifier.testTag("cash.import.review")) { Text(localizedText("cash_import_review")) }
            }
        }
    }
    if (processingOpen && state.plan != null) CashImportProcessingPanel(state, sizeClass,
        onMore = { task.loadProcessing(append = true) }, onRetry = { task.loadProcessing() }, onClose = { if (!state.busy) processingOpen = false })
    val item = state.items.firstOrNull { it.itemId == selectedItem }
    if (item != null && state.plan != null) CashImportPreviewPanel(item, state.details[item.itemId], state.plan!!.planId, busy || !canWrite,
        sizeClass, currentAccounts, categories, options, state.errorCode, { task.loadDetail(item.itemId) }, { selectedItem = null; detailDirty = false },
        onEntry = { task.editEntry(it); task.preview() }, onRow = { task.editRow(it); task.preview() }, onRelation = { task.editPair(it); task.preview() }, onDirty = { detailDirty = it })
    accountForm?.let { id ->
        val current = accountState.currentAccounts[id]
        CashImportAccountForm(current, id != "new" && current == null, busy || !canWrite, sizeClass, accountState.errorCode,
            onDirty = { accountDirty = it }, onClose = {
                if (account.state.value.busy || task.state.value.busy) busyNotice = true
                else if (account.state.value.pendingKeys.isNotEmpty()) accountUnknownNotice = true
                else if (accountDirty) discardAction = {
                    if (account.state.value.busy || task.state.value.busy) busyNotice = true
                    else if (account.state.value.pendingKeys.isNotEmpty()) accountUnknownNotice = true
                    else { accountForm = null; accountDirty = false }
                } else accountForm = null
            }, onSave = { name, type, codes, active ->
                formSaving = true
                if (id == "new") account.create(name, type, codes)
                else if (current != null) account.update(current, StructuredObject(buildMap {
                    if (name != current.name) put("name", StructuredText(name))
                    if (codes != current.currencies) put("currencies", StructuredArray(codes.map(::StructuredText)))
                    if (active != current.active) put("active", StructuredBoolean(active))
                }))
            })
    }
    if (discardAction != null) AlertDialog(onDismissRequest = { discardAction = null }, title = { Text(localizedText("cash_import_discard_edit")) },
        confirmButton = { TextButton(onClick = { val action = discardAction; discardAction = null; action?.invoke() }) { Text(localizedText("cash_import_discard")) } },
        dismissButton = { TextButton(onClick = { discardAction = null }) { Text(localizedText("cash_import_continue_edit")) } })
    exitDialog?.let { decision ->
        AlertDialog(onDismissRequest = { exitDialog = null }, title = { Text(localizedText(if (accountState.undoFailures.isNotEmpty()) "cash_import_undo_failed" else if (decision == ImportTaskExitAction.ASK_ACCOUNTS) "cash_import_account_exit" else "cash_import_account_unknown")) },
            text = { Column {
                Text(localizedText(if (decision == ImportTaskExitAction.ASK_ACCOUNTS) "cash_import_account_exit_body" else "cash_import_account_unknown_body"))
                accountState.undoFailures.values.distinct().forEach { Text(cashImportTaskErrorText(it)) }
            } }, confirmButton = { Column(horizontalAlignment = Alignment.End) {
                if (decision == ImportTaskExitAction.ASK_ACCOUNTS) TextButton(onClick = { closeTask(true) }, enabled = !busy) { Text(localizedText("cash_import_undo_exit")) }
                else if (accountState.pendingKeys.isNotEmpty()) TextButton(onClick = account::resolvePending, enabled = !accountState.busy) { Text(localizedText("cash_import_check_result")) }
                TextButton(onClick = { if (state.context == null && state.receipt == null && state.frozenCommand == null) completeExit() else closeTask(false) }, enabled = !accountState.busy) { Text(localizedText("cash_import_keep_exit")) }
            } }, dismissButton = { TextButton(onClick = { exitDialog = null }) { Text(localizedText("cash_import_continue_edit")) } })
    }
    if (accountUnknownNotice) AlertDialog(onDismissRequest = { accountUnknownNotice = false },
        title = { Text(localizedText("cash_import_account_unknown")) },
        confirmButton = { TextButton(onClick = { account.resolvePending(); accountUnknownNotice = false }, enabled = !accountState.busy) { Text(localizedText("cash_import_check_result")) } },
        dismissButton = { TextButton(onClick = { accountUnknownNotice = false; accountForm = null; accountDirty = false }, enabled = !accountState.busy) { Text(localizedText("cash_import_continue_edit")) } })
    if (busyNotice) AlertDialog(onDismissRequest = { busyNotice = false }, text = { Text(localizedText("cash_import_busy_exit")) },
        confirmButton = { TextButton(onClick = { busyNotice = false }) { Text(localizedText("copy_fadf24dbc5")) } })
}

@Composable
private fun CashImportTaskResult(state: ImportTaskUiState, onResolve: () -> Unit, onRetry: () -> Unit, onReturn: () -> Unit, enabled: Boolean) {
    val receipt = state.receipt
    val failed = receipt?.let(::importTaskHasUnexpectedFailure) == true ||
        state.submission == ImportSubmissionState.RETRY_ORIGINAL && state.errorCode != null
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("cash.import.result"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(localizedText(when { failed -> "cash_import_error"; receipt != null -> "cash_import_complete"; else -> "cash_import_unknown" }), style = MaterialTheme.typography.headlineSmall)
        if (failed) {
            if (receipt?.commitOutcome?.hasBusinessWrites == true) Text(localizedText("cash_import_partial_saved"))
            val reasons = receipt?.commitOutcome?.reasonCodes.orEmpty()
            if (reasons.isEmpty()) Text(state.errorCode?.let { cashImportTaskErrorText(it) } ?: localizedText("cash_import_system_error"))
            else reasons.distinct().forEach { Text(cashImportTaskErrorText(it)) }
        } else if (receipt != null) {
            if (receipt.status == "no_changes") Text(localizedText("cash_import_no_changes"))
            else Text(localizedText("cash_import_processing", importTaskProcessedRows(receipt).toString()))
            receipt.pendingItemCount?.takeIf { it > 0 }?.let { Text(localizedText("cash_pending_count", it.toString())) }
        } else {
            state.errorCode?.let { Text(cashImportTaskErrorText(it), color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = if (state.submission == ImportSubmissionState.RETRY_ORIGINAL) onRetry else onResolve, enabled = enabled,
                modifier = Modifier.testTag("cash.import.resolve")) { Text(localizedText(if (state.submission == ImportSubmissionState.RETRY_ORIGINAL) "cash_import_retry_original" else "cash_import_check_result")) }
        }
        state.errorDiagnostic?.let { CashImportDiagnostic(it) }
        Button(onClick = onReturn, enabled = enabled, modifier = Modifier.testTag("cash.import.return")) { Text(localizedText("cash_import_back_ledger")) }
    }
}

internal fun importTaskHasUnexpectedFailure(receipt: ImportReceipt): Boolean =
    receipt.commitOutcome?.hasNewFailures ?: (receipt.status == "failed")

internal fun importTaskProcessedRows(receipt: ImportReceipt): Long =
    listOf("created_posted", "updated_posted", "saved_draft", "finalized_draft", "duplicate", "skipped").sumOf { receipt.summary[it] ?: 0L }
