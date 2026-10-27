package com.finance.tracker.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import com.finance.tracker.resources.Res
import com.finance.tracker.resources.ic_arrow_back
import com.finance.tracker.resources.ic_arrow_forward
import org.jetbrains.compose.resources.painterResource
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
    var pendingFiles by remember { mutableStateOf<Map<String, CashImportPendingFile>>(emptyMap()) }
    var queuedFileRemovals by remember(state.taskId) { mutableStateOf<Set<String>>(emptySet()) }
    var hiddenClientFileRefs by remember(state.taskId) { mutableStateOf<Set<String>>(emptySet()) }
    var activeFileRemovals by remember(state.taskId) { mutableStateOf<Set<String>>(emptySet()) }
    val files = remember { mutableMapOf<String, FileSource>() }
    var replacing by remember { mutableStateOf<String?>(null) }
    var replacementPassword by remember { mutableStateOf<String?>(null) }
    var accountUnknownNotice by remember { mutableStateOf(false) }
    var processingOpen by remember { mutableStateOf(false) }
    var selectedItem by remember { mutableStateOf<String?>(null) }
    var accountForm by remember { mutableStateOf<String?>(null) }
    var accountFormSource by remember { mutableStateOf<ImportSourceAccount?>(null) }
    var accountCreateSourceRef by remember { mutableStateOf<String?>(null) }
    var accountCreatePriorIds by remember { mutableStateOf<Set<String>>(emptySet()) }
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
    var mappingStep by remember(state.taskId) { mutableStateOf(false) }

    fun closeAccountForm() {
        accountForm = null
        accountFormSource = null
        accountCreateSourceRef = null
        accountCreatePriorIds = emptySet()
        accountDirty = false
    }

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
    LaunchedEffect(state.plan?.planId) { selectedItem = null }
    LaunchedEffect(state.context?.files, state.busy) {
        if (state.busy) return@LaunchedEffect
        val retained = state.context?.files.orEmpty().filter { it.canRetry }.map { it.clientFileRef }.toSet()
        files.keys.retainAll(retained + state.localFileFailures.map { it.clientFileRef } + state.unprocessedFileRefs)
    }
    LaunchedEffect(state.context?.files, state.localFileFailures, state.unprocessedFileRefs, state.errorCode,
        state.busy, queuedFileRemovals, activeFileRemovals) {
        if (state.busy) return@LaunchedEffect
        val byClientRef = state.context?.files.orEmpty().associateBy { it.clientFileRef }
        for (clientRef in queuedFileRemovals) {
            val file = byClientRef[clientRef]
            when {
                file != null && state.errorCode == null && clientRef !in activeFileRemovals -> {
                    activeFileRemovals = activeFileRemovals + clientRef
                    task.removeFile(file.fileRef)
                    return@LaunchedEffect
                }
                clientRef in activeFileRemovals && state.errorCode != null -> {
                    queuedFileRemovals = queuedFileRemovals - clientRef
                    activeFileRemovals = activeFileRemovals - clientRef
                    hiddenClientFileRefs = hiddenClientFileRefs - clientRef
                }
                else -> {
                    queuedFileRemovals = queuedFileRemovals - clientRef
                    activeFileRemovals = activeFileRemovals - clientRef
                }
            }
        }
    }
    LaunchedEffect(state.context?.files, state.context?.revision, state.localFileFailures, state.unprocessedFileRefs,
        state.errorCode, state.busy) {
        val revision = state.context?.revision
        val failures = state.localFileFailures.mapTo(mutableSetOf()) { it.clientFileRef } + state.unprocessedFileRefs
        val uploaded = state.context?.files.orEmpty().associateBy { it.clientFileRef }
        pendingFiles = pendingFiles.mapValues { (clientRef, pending) ->
            if (pending.parseSucceeded != null) pending
            else if (clientRef in failures) pending.copy(parseSucceeded = false)
            else {
                val file = uploaded[clientRef]
                val contextAdvanced = pending.startingRevision == null || revision != null && revision > pending.startingRevision
                when {
                    file != null && contextAdvanced && file.status in setOf("ready", "partial") -> pending.copy(parseSucceeded = true)
                    file != null && contextAdvanced && file.status in setOf("failed", "password_required") -> pending.copy(parseSucceeded = false)
                    !state.busy && state.errorCode != null -> pending.copy(parseSucceeded = false)
                    else -> pending
                }
            }
        }
    }
    LaunchedEffect(accountState.busy, accountState.currentAccounts, accountState.accounts, accountState.pendingKeys,
        accountCreateSourceRef, selecting) {
        val selection = selecting
        if (selection != null && !accountState.busy) {
            selecting = null
            accountState.currentAccounts[selection.second]?.let { task.selectAccount(selection.first, ImportAccountTarget(it.accountId, it.revision)) }
        }
        val sourceRef = accountCreateSourceRef
        if (sourceRef != null && !accountState.busy && accountState.errorCode == null && accountState.pendingKeys.isEmpty()) {
            val createdId = accountState.accounts.firstOrNull { it.id !in accountCreatePriorIds }?.id
            if (createdId != null) {
                accountForm = null
                accountFormSource = null
                accountCreateSourceRef = null
                accountCreatePriorIds = emptySet()
                accountDirty = false
                selecting = sourceRef to createdId
                account.loadAccount(createdId)
            }
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
        if (accountDirty) { discardAction = { accountDirty = false; accountForm = null; exitNavigation = action
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
            pendingFiles = pendingFiles + (ref to CashImportPendingFile(ref, source.name, state.context?.revision))
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
    val hasProcessing = state.phase == ImportTaskPhase.REVIEW && (state.plan?.summary?.get("duplicate") ?: 0L) +
        (state.plan?.summary?.get("skip") ?: 0L) + (state.plan?.summary?.get("relations_failed") ?: 0L) > 0L
    val title = localizedText("cash_import_title")
    val canDismissLayer = selectedItem == null && !processingOpen && accountForm == null &&
        discardAction == null && exitDialog == null && !busyNotice && !accountUnknownNotice
    PlatformModalEscapeHandler(enabled = isWebPlatform && canDismissLayer, onEscape = { requestExit(onBack) })
    Dialog(onDismissRequest = { requestExit(onBack) }, properties = DialogProperties(
        usePlatformDefaultWidth = false, dismissOnBackPress = !isWebPlatform, dismissOnClickOutside = false,
    )) {
      Surface(Modifier.fillMaxSize().testTag("cash.import.dialog").semantics { paneTitle = title },
          shape = RectangleShape, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
          ModalHeader(ModalHeaderVariant.FullScreenDialog, onClose = { requestExit(onBack) },
              closeEnabled = !busy, headerTestTag = "cash.import.header", closeTestTag = "cash.import.close") {
              Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
          HorizontalDivider()
          Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = CashImportHorizontalContentInset).padding(top = 24.dp)
              .testTag("cash.import.task"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (busy && pendingFiles.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!canWrite) Text(localizedText("copy_dd9a4bdee0"), color = MaterialTheme.colorScheme.error)
        if (result) key(state.receipt?.receiptId, state.submission) { CashImportTaskResult(state, sizeClass, onResolve = task::resolve, onRetry = task::retryOriginal,
            onReturn = { requestExit(onBack) }, enabled = !busy) }
        else {
            val step = if (state.phase == ImportTaskPhase.REVIEW) 2 else if (mappingStep) 1 else 0
            CashImportTaskSteps(step)
            if (step == 2) Text(
                localizedText("cash_import_step_review_description"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
            localError?.let { Text(cashImportTaskErrorText(it), color = MaterialTheme.colorScheme.error) }
            state.errorCode?.let { Text(cashImportTaskErrorText(it), color = MaterialTheme.colorScheme.error) }
            state.errorDiagnostic?.let { CashImportDiagnostic(it) }
            if (!auxiliaryReady) TextButton(onClick = { localError = null; reload++ }, enabled = !busy) { Text(localizedText("copy_e2d53a6d3a")) }
            if (state.plan?.invalidatedChoices?.isNotEmpty() == true) Text(localizedText("cash_import_preview_invalid"), color = MaterialTheme.colorScheme.error)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (state.phase == ImportTaskPhase.REVIEW) CashImportReviewList(state, categories, options, sizeClass, listState,
                    onFilter = { task.loadItems(it) }, onMore = { task.loadItems(append = true) },
                    onOpen = { selectedItem = it; if (it !in state.details) task.loadDetail(it) },
                    onProcessing = if (hasProcessing) ({ processingOpen = true; task.loadProcessing() }) else null,
                    modifier = Modifier.fillMaxSize())
                else CashImportPreparation(if (canWrite) state else state.copy(busy = true), accountState, fileNames,
                    pendingFiles.values.toList(), hiddenClientFileRefs,
                    { ref -> pendingFiles = pendingFiles - ref }, { ref ->
                        pendingFiles = pendingFiles - ref
                        queuedFileRemovals = queuedFileRemovals + ref
                        hiddenClientFileRefs = hiddenClientFileRefs + ref
                    }, { pick(it) },
                    onRetry = { file, password ->
                        val source = files[file.clientFileRef]
                        if (source == null) pick(file.fileRef, password.takeIf(String::isNotEmpty))
                        else task.upload(listOf(ImportUpload(file.clientFileRef, source, password.takeIf(String::isNotEmpty))), file.fileRef)
                    }, onRemove = task::removeFile, onScope = task::setSourceScope,
                    onSelectAccount = { ref, id -> if (id == null) task.selectAccount(ref, ImportAccountTarget(null, null))
                        else { selecting = ref to id; account.loadAccount(id) } },
                    onCreateAccount = { source ->
                        accountCreatePriorIds = accountState.accounts.mapTo(mutableSetOf()) { it.id } + accountState.currentAccounts.keys
                        accountForm = "new"
                        accountFormSource = source
                        accountCreateSourceRef = source.sourceAccountRef
                    }, onResolveAccounts = account::resolvePending, mappingStep = mappingStep, modifier = Modifier.fillMaxSize())
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (state.phase == ImportTaskPhase.REVIEW) TextButton(onClick = { mappingStep = true; task.showPreparation() }, enabled = !busy && state.canEdit,
                    modifier = Modifier.testTag("cash.import.back-accounts")) {
                    Icon(painterResource(Res.drawable.ic_arrow_back), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(localizedText("cash_import_previous"))
                }
                else if (mappingStep) TextButton(onClick = { mappingStep = false }, enabled = !busy,
                    modifier = Modifier.testTag("cash.import.back-files")) {
                    Icon(painterResource(Res.drawable.ic_arrow_back), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(localizedText("cash_import_previous"))
                }
                Spacer(Modifier.weight(1f))
                if (state.phase == ImportTaskPhase.PREPARE && !mappingStep) Button(onClick = { mappingStep = true },
                    enabled = !busy && pendingFiles.isEmpty() && canWrite && state.canEdit && state.context?.canPreview == true,
                    modifier = Modifier.testTag("cash.import.to-accounts")) {
                    Text(localizedText("cash_import_next"))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Icon(painterResource(Res.drawable.ic_arrow_forward), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                }
                else if (state.needsContextRefresh || state.plan?.invalidatedChoices?.isNotEmpty() == true) Button(onClick = task::restartPreview,
                    enabled = !busy && canWrite, modifier = Modifier.testTag("cash.import.restart")) { Text(localizedText("cash_import_restart_review")) }
                else if (state.phase == ImportTaskPhase.REVIEW && !state.previewDirty) Button(onClick = task::confirm,
                    enabled = !busy && canWrite && state.canConfirm, modifier = Modifier.testTag("cash.import.confirm")) { Text(localizedText("cash_import_confirm_all")) }
                else Button(onClick = task::preview, enabled = !busy && canWrite && auxiliaryReady && state.canEdit && state.context?.canPreview == true,
                    modifier = Modifier.testTag("cash.import.review")) {
                    Text(localizedText("cash_import_next"))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Icon(painterResource(Res.drawable.ic_arrow_forward), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                }
            }
        }
          }
        }
      }
    }
    if (processingOpen && state.plan != null) CashImportProcessingPanel(state, sizeClass,
        onMore = { task.loadProcessing(append = true) }, onRetry = { task.loadProcessing() }, onClose = { if (!state.busy) processingOpen = false })
    val item = state.items.firstOrNull { it.itemId == selectedItem }
    if (item != null && state.plan != null) CashImportPreviewPanel(item, state.details[item.itemId], state.plan!!.planId, busy || !canWrite,
        sizeClass, currentAccounts, categories, options, state.errorCode, { task.loadDetail(item.itemId) }, { selectedItem = null })
    accountForm?.let { id ->
        val current = accountState.currentAccounts[id]
        CashImportAccountForm(current, id != "new" && current == null, busy || !canWrite, sizeClass, accountState.errorCode,
            initialName = accountFormSource?.displayName.orEmpty(),
            initialType = cashImportAccountTypeForName(accountFormSource?.displayName.orEmpty()),
            initialCurrencies = accountFormSource?.currencies.orEmpty(),
            onDirty = { accountDirty = it }, onClose = {
                if (account.state.value.busy || task.state.value.busy) busyNotice = true
                else if (account.state.value.pendingKeys.isNotEmpty()) accountUnknownNotice = true
                else if (accountDirty) discardAction = {
                    if (account.state.value.busy || task.state.value.busy) busyNotice = true
                    else if (account.state.value.pendingKeys.isNotEmpty()) accountUnknownNotice = true
                    else closeAccountForm()
                } else closeAccountForm()
            }, onSave = { name, type, codes, active ->
                if (id == "new") {
                    account.create(name, type, codes)
                }
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
private fun CashImportTaskResult(state: ImportTaskUiState, sizeClass: WindowSizeClass, onResolve: () -> Unit, onRetry: () -> Unit, onReturn: () -> Unit, enabled: Boolean) {
    val receipt = state.receipt
    val failed = receipt?.let(::importTaskHasUnexpectedFailure) == true ||
        state.submission == ImportSubmissionState.RETRY_ORIGINAL && state.errorCode != null
    Column(Modifier.fillMaxSize().testTag("cash.import.result"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(top = 32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Text(localizedText(when { failed -> "cash_import_error"; receipt != null && (receipt.pendingItemCount ?: 0) > 0 -> "cash_import_complete_attention";
                    receipt != null -> "cash_import_complete"; else -> "cash_import_unknown" }), style = MaterialTheme.typography.headlineMedium)
                if (failed) {
                    if (receipt?.commitOutcome?.hasBusinessWrites == true) Text(localizedText("cash_import_partial_saved"))
                    val reasons = receipt?.commitOutcome?.reasonCodes.orEmpty()
                    if (reasons.isEmpty()) Text(state.errorCode?.let { cashImportTaskErrorText(it) } ?: localizedText("cash_import_system_error"))
                    else reasons.distinct().forEach { Text(cashImportTaskErrorText(it)) }
                } else if (receipt != null) {
                    Text(localizedText(if (receipt.status == "no_changes") "cash_import_no_changes" else "cash_import_processing",
                        importTaskProcessedRows(receipt).toString()), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider()
                    val metrics = listOf("created_posted" to "cash_import_result_created", "updated_posted" to "cash_import_result_updated",
                        "saved_draft" to "cash_import_result_drafts", "finalized_draft" to "cash_import_result_finalized",
                        "duplicate" to "cash_import_result_duplicate", "skipped" to "cash_import_result_skipped")
                    metrics.chunked(if (sizeClass == WindowSizeClass.COMPACT) 2 else 3).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            row.forEach { (field, label) -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text((receipt.summary[field] ?: 0L).toString(), style = MaterialTheme.typography.headlineMedium)
                                Text(localizedText(label), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } }
                        }
                    }
                    HorizontalDivider()
                    receipt.pendingItemCount?.takeIf { it > 0 }?.let { Text(localizedText("cash_pending_count", it.toString())) }
                    if ((receipt.pendingItemCount ?: 0) > 0) Text(localizedText("cash_import_aftercare"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    state.errorCode?.let { Text(cashImportTaskErrorText(it), color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = if (state.submission == ImportSubmissionState.RETRY_ORIGINAL) onRetry else onResolve, enabled = enabled,
                        modifier = Modifier.testTag("cash.import.resolve")) { Text(localizedText(if (state.submission == ImportSubmissionState.RETRY_ORIGINAL) "cash_import_retry_original" else "cash_import_check_result")) }
                }
                state.errorDiagnostic?.let { CashImportDiagnostic(it) }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onReturn, enabled = enabled, modifier = Modifier.testTag("cash.import.return")) { Text(localizedText("cash_import_back_ledger")) }
        }
    }
}

@Composable
private fun CashImportTaskSteps(current: Int) {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("cash_import_step_files", "cash_import_step_accounts", "cash_import_step_review").forEachIndexed { index, label ->
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${index + 1}  ${localizedText(label)}", style = MaterialTheme.typography.labelLarge,
                    color = if (index == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(color = if (index == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

internal fun importTaskHasUnexpectedFailure(receipt: ImportReceipt): Boolean =
    receipt.commitOutcome?.hasNewFailures ?: (receipt.status == "failed")

internal fun importTaskProcessedRows(receipt: ImportReceipt): Long =
    listOf("created_posted", "updated_posted", "saved_draft", "finalized_draft", "duplicate", "skipped").sumOf { receipt.summary[it] ?: 0L }
