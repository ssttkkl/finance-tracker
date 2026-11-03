package com.finance.tracker.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
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
import kotlinx.coroutines.flow.flowOf
import kotlin.random.Random

private data class ImportTaskNotice(val source: String, val textKey: String, val count: Int? = null, val retry: (() -> Unit)? = null)

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
    var auxiliaryFailed by remember { mutableStateOf(false) }
    var auxiliaryFailure by remember { mutableStateOf<Throwable?>(null) }
    var auxiliaryAttempt by remember { mutableStateOf(0) }
    var fileNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var pendingFiles by remember { mutableStateOf<Map<String, CashImportPendingFile>>(emptyMap()) }
    var queuedFileRemovals by remember(state.taskId) { mutableStateOf<Set<String>>(emptySet()) }
    var hiddenClientFileRefs by remember(state.taskId) { mutableStateOf<Set<String>>(emptySet()) }
    var activeFileRemovals by remember(state.taskId) { mutableStateOf<Set<String>>(emptySet()) }
    val files = remember { mutableMapOf<String, FileSource>() }
    var replacing by remember { mutableStateOf<String?>(null) }
    var retryingLocal by remember { mutableStateOf<String?>(null) }
    var replacementPassword by remember { mutableStateOf<String?>(null) }
    val snackbar = remember(state.taskId) { SnackbarHostState() }
    var notice by remember(state.taskId) { mutableStateOf<ImportTaskNotice?>(null) }
    val noticeText = notice?.let { if (it.count == null) localizedText(it.textKey) else localizedText(it.textKey, it.count) }
    val retryLabel = localizedText("copy_e2d53a6d3a")
    LaunchedEffect(notice, noticeText) {
        val shown = notice ?: return@LaunchedEffect
        val message = noticeText?.takeIf(String::isNotEmpty) ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(message, actionLabel = retryLabel.takeIf { shown.retry != null })
        if (notice == shown) {
            notice = null
            if (result == SnackbarResult.ActionPerformed) shown.retry?.invoke()
        }
    }
    val fileStepNotice = cashImportFileStepNotice(state)
    LaunchedEffect(fileStepNotice) {
        if (notice?.source == "files" && notice?.textKey != fileStepNotice) notice = null
    }
    var accountUnknownNotice by remember { mutableStateOf(false) }
    var processingOpen by remember { mutableStateOf(false) }
    var selectedItem by remember { mutableStateOf<ImportProjectedItem?>(null) }
    var accountForm by remember { mutableStateOf<String?>(null) }
    var accountFormSource by remember { mutableStateOf<ImportSourceAccount?>(null) }
    var accountCreateSourceRef by remember { mutableStateOf<String?>(null) }
    var accountCreatePriorIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var accountDirty by remember { mutableStateOf(false) }
    var discardAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var exitDialog by remember { mutableStateOf<ImportTaskExitAction?>(null) }
    var exitChoice by remember(exitDialog) { mutableStateOf<String?>(null) }
    var exitNavigation by remember { mutableStateOf<(() -> Unit)?>(null) }
    var closingTaskId by remember { mutableStateOf<Long?>(null) }
    var undoAfterClose by remember { mutableStateOf(false) }
    var undoStarted by remember { mutableStateOf(false) }
    fun showBusyNotice() { notice = ImportTaskNotice("busy", "cash_import_busy_exit") }
    fun selectAccount(ref: String, id: String) {
        if (notice?.source == "account") notice = null
        account.loadAccount(id,
            onLoaded = { task.selectAccount(ref, ImportAccountTarget(it.accountId, it.revision)) },
            onReadFailure = { notice = ImportTaskNotice("account", "cash_import_account_read_failed", retry = { selectAccount(ref, id) }) })
    }
    val listState = rememberLazyListState()
    var mappingStep by remember(state.taskId) { mutableStateOf(false) }
    val reviewItemsFlow = remember(state.plan?.planId, state.filter, repository) {
        val planId = state.plan?.planId
        if (planId == null) flowOf(PagingData.empty())
        else Pager(
            config = PagingConfig(pageSize = 20, initialLoadSize = 20, prefetchDistance = 5,
                enablePlaceholders = true, jumpThreshold = 20),
            pagingSourceFactory = { CashImportItemsPagingSource(repository, planId, state.filter, onInternalFailure = task::failPageContract) {
                task.markItemsLoaded(planId, state.filter)
            } },
        ).flow
    }
    val reviewRows = reviewItemsFlow.collectAsLazyPagingItems()

    fun closeAccountForm() {
        accountForm = null
        accountFormSource = null
        accountCreateSourceRef = null
        accountCreatePriorIds = emptySet()
        accountDirty = false
    }

    LaunchedEffect(task, account) { task.restore(); account.restore() }
    DisposableEffect(task, account) { onDispose { task.detach(); account.detach(); files.clear(); replacementPassword = null } }
    LaunchedEffect(auxiliaryAttempt) {
        auxiliaryReady = false
        auxiliaryFailed = false
        auxiliaryFailure = null
        try {
            accounts = finance.fetchCashAccounts(); categories = finance.fetchCashCategories().items
            options = finance.fetchLedgerOptions(); auxiliaryReady = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { auxiliaryFailed = true; auxiliaryFailure = failure
            if (failure !is DomainFailure || failure.category == FailureCategory.UNKNOWN || failure.code == "internal_error")
                createDiagnosticLogger().record(DiagnosticFeature.CASH_IMPORT, DiagnosticAction.LOAD, "unknown_error", null, failure::class.simpleName)
        }
    }
    val currentAccounts = accounts.filterNot { cached -> accountState.accounts.any { it.id == cached.id.toString() } } +
        accountState.accounts.map { fresh -> Account(fresh.id.toLong(), fresh.name, fresh.type, fresh.active, fresh.currencies) }
    LaunchedEffect(state.receipt?.receiptId) { if (state.receipt != null) onDone() }
    LaunchedEffect(state.plan?.planId) { selectedItem = null }
    LaunchedEffect(state.context?.files, state.busy, pendingFiles) {
        if (state.busy) return@LaunchedEffect
        val retained = state.context?.files.orEmpty().filter { it.canRetry }.map { it.clientFileRef }.toSet()
        files.keys.retainAll(retained + state.localFileFailures.map { it.clientFileRef } + state.unprocessedFileRefs + pendingFiles.keys)
    }
    LaunchedEffect(state.context?.files, state.localFileFailures, state.unprocessedFileRefs, state.errorCode,
        state.busy, queuedFileRemovals, activeFileRemovals) {
        if (state.busy) return@LaunchedEffect
        val byClientRef = state.context?.files.orEmpty().associateBy { it.clientFileRef }
        for (clientRef in queuedFileRemovals) {
            val file = byClientRef[clientRef]
            when {
                file != null && state.canEdit && clientRef !in activeFileRemovals -> {
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
                    hiddenClientFileRefs = hiddenClientFileRefs - clientRef
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
        accountCreateSourceRef) {
        val sourceRef = accountCreateSourceRef
        if (sourceRef != null && !accountState.busy && accountState.errorCode == null && accountState.pendingKeys.isEmpty()) {
            val createdId = accountState.accounts.firstOrNull { it.id !in accountCreatePriorIds }?.id
            if (createdId != null) {
                accountForm = null
                accountFormSource = null
                accountCreateSourceRef = null
                accountCreatePriorIds = emptySet()
                accountDirty = false
                selectAccount(sourceRef, createdId)
            }
        }
    }
    fun completeExit() { val action = exitNavigation; exitNavigation = null; exitDialog = null; action?.invoke() }
    fun closeTask(undo: Boolean) {
        val decision = importTaskExitAction(task.state.value, account.state.value)
        if (decision == ImportTaskExitAction.BLOCK) { showBusyNotice(); return }
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
        if (task.state.value.busy || account.state.value.busy) { showBusyNotice(); return }
        if (cashImportLoadBlocked(auxiliaryFailed, account.state.value) || task.state.value.internalFailure != null || account.state.value.internalFailure != null) { action(); return }
        if (accountDirty) { discardAction = { accountDirty = false; accountForm = null; exitNavigation = action
            when (val decision = importTaskExitAction(task.state.value, account.state.value)) {
                ImportTaskExitAction.BLOCK -> showBusyNotice()
                ImportTaskExitAction.KEEP_RECOVERY -> completeExit()
                ImportTaskExitAction.FINISH, ImportTaskExitAction.CANCEL -> closeTask(false)
                else -> exitDialog = decision
            }
        }; return }
        exitNavigation = action
        when (val decision = importTaskExitAction(task.state.value, account.state.value)) {
            ImportTaskExitAction.BLOCK -> showBusyNotice()
            ImportTaskExitAction.KEEP_RECOVERY -> completeExit()
            ImportTaskExitAction.FINISH, ImportTaskExitAction.CANCEL -> closeTask(false)
            else -> exitDialog = decision
        }
    }
    val latestExit by rememberUpdatedState<(()->Unit)->Unit> { requestExit(it) }
    DisposableEffect(registerExit) { registerExit { latestExit(it) }; onDispose { registerExit(null) } }
    fun receive(sources: List<FileSource>?) {
        if (sources.isNullOrEmpty()) { replacementPassword = null; replacing = null; retryingLocal = null; return }
        val previous = replacing?.let { ref -> state.context?.files?.firstOrNull { it.fileRef == ref } }
        if (replacing != null && (previous == null || sources.size != 1) || retryingLocal != null && sources.size != 1) {
            notice = ImportTaskNotice("picker", "cash_import_reselect_one_file"); replacementPassword = null; replacing = null; retryingLocal = null; return
        }
        val uploads = sources.map { source ->
            val ref = previous?.clientFileRef ?: retryingLocal ?: "file-${Random.nextLong().toULong().toString(16)}"
            files[ref] = source; fileNames = fileNames + (ref to source.name)
            pendingFiles = pendingFiles + (ref to CashImportPendingFile(ref, source.name, state.context?.revision))
            ImportUpload(ref, source, replacementPassword)
        }
        task.upload(uploads, replacing)
        val rejected = task.state.value.localFileFailures.count { failed ->
            failed.code == "import_file_too_many" && uploads.any { it.clientFileRef == failed.clientFileRef }
        }
        if (rejected > 0) notice = ImportTaskNotice("capacity", "cash_import_capacity_notice", rejected)
        replacing = null; retryingLocal = null; replacementPassword = null
    }
    val picker = if (demoStatement == null) rememberFilePickerLauncher(
        type = FileKitType.File(listOf("csv", "xls", "xlsx", "pdf")), mode = FileKitMode.Multiple(maxItems = 20),
        onError = { notice = ImportTaskNotice("picker", "copy_cb28b5a6f2"); replacementPassword = null; replacing = null; retryingLocal = null }, onResult = { result -> receive(result?.map(::PlatformCashImportFileSource)) }) else null
    fun pick(ref: String?, password: String? = null) { replacing = ref; replacementPassword = password; if (notice?.source == "picker") notice = null; if (demoStatement != null) receive(listOf(demoStatement)) else picker?.launch() }
    val result = state.receipt != null || state.frozenCommand != null
    val busy = state.busy || accountState.busy || closingTaskId != null
    LaunchedEffect(busy) { if (!busy && notice?.source == "busy") notice = null }
    val hasProcessing = state.phase == ImportTaskPhase.REVIEW && (state.plan?.summary?.get("duplicate") ?: 0L) +
        (state.plan?.summary?.get("skip") ?: 0L) + (state.plan?.summary?.get("relations_failed") ?: 0L) > 0L
    val title = localizedText("cash_import_title")
    val canDismissLayer = selectedItem == null && !processingOpen && accountForm == null &&
        discardAction == null && exitDialog == null && !accountUnknownNotice
    PlatformModalEscapeHandler(enabled = isWebPlatform && canDismissLayer, onEscape = { requestExit(onBack) })
    Dialog(onDismissRequest = { requestExit(onBack) }, properties = DialogProperties(
        usePlatformDefaultWidth = false, dismissOnBackPress = !isWebPlatform, dismissOnClickOutside = false,
    )) {
      Surface(Modifier.fillMaxSize().testTag("cash.import.dialog").semantics { paneTitle = title },
          shape = RectangleShape, color = MaterialTheme.colorScheme.surface) {
       Box(Modifier.fillMaxSize()) {
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
        else if (cashImportLoadBlocked(auxiliaryFailed, accountState) || state.internalFailure != null || accountState.internalFailure != null) {
            val recoverable = auxiliaryFailure as? DomainFailure
            val code = recoverable?.code ?: accountState.errorCode
            val programError = state.internalFailure != null || accountState.internalFailure != null
            // 4xx 业务码显示具体原因；5xx 与程序错误仍显示“系统错误”，避免把服务端故障说成可自助处理。
            val businessMessage = !programError && code != null && (recoverable == null || recoverable.status in 400..499)
            val retryable = !programError && cashImportRecoverableCode(code)
            StateMessage(
                text = if (businessMessage && code != null) cashImportTaskErrorText(code)
                    else localizedText("cash_import_system_error"),
                isError = true,
                modifier = Modifier.testTag("cash.import.blocked"),
            ) {
                if (retryable) TextButton(onClick = { if (auxiliaryFailed) auxiliaryAttempt++ else account.restore() }, enabled = !busy,
                    modifier = Modifier.testTag("cash.import.load-retry")) { Text(localizedText("copy_e2d53a6d3a")) }
            }
        }
        else {
            val step = if (state.phase == ImportTaskPhase.REVIEW) 2 else if (mappingStep) 1 else 0
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                // 表单步骤与居中 720 表单同轴；核对步骤与整宽列表同轴（父级已留 24 px）。
                CashImportTaskSteps(step, Modifier.then(
                    if (step < 2) Modifier.widthIn(max = 720.dp) else Modifier
                ).fillMaxWidth())
            }
            if (step == 2) Text(
                localizedText("cash_import_step_review_description"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
            state.errorCode?.let { Text(cashImportTaskErrorText(it), color = MaterialTheme.colorScheme.error) }
            state.errorDiagnostic?.let { CashImportDiagnostic(it) }
            if (state.plan?.invalidatedChoices?.isNotEmpty() == true) Text(localizedText("cash_import_preview_invalid"), color = MaterialTheme.colorScheme.error)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (state.phase == ImportTaskPhase.REVIEW) CashImportReviewList(state, reviewRows, categories, options, sizeClass, listState,
                    onFilter = task::selectReviewFilter,
                    onOpen = { item -> selectedItem = item; if (item.itemId !in state.details) task.loadDetail(item.itemId) },
                    onProcessing = if (hasProcessing) ({ processingOpen = true; task.loadProcessing() }) else null,
                    modifier = Modifier.fillMaxSize())
                else CashImportPreparation(if (canWrite) state else state.copy(busy = true), accountState, canWrite, fileNames,
                    pendingFiles.values.toList(), hiddenClientFileRefs,
                    { ref -> pendingFiles = pendingFiles - ref }, { ref ->
                        pendingFiles = pendingFiles - ref
                        queuedFileRemovals = queuedFileRemovals + ref
                        hiddenClientFileRefs = hiddenClientFileRefs + ref
                    }, { pick(it) },
                    onRetry = { file, password ->
                        val source = files[file.clientFileRef]
                        if (source == null) pick(file.fileRef, password.takeIf(String::isNotEmpty))
                        else {
                            pendingFiles = pendingFiles + (file.clientFileRef to CashImportPendingFile(file.clientFileRef, source.name, state.context?.revision))
                            task.upload(listOf(ImportUpload(file.clientFileRef, source, password.takeIf(String::isNotEmpty))), file.fileRef)
                        }
                    }, onRetryLocal = { ref ->
                        val source = files[ref]
                        if (source == null) { retryingLocal = ref; pick(null) }
                        else {
                            pendingFiles = pendingFiles + (ref to CashImportPendingFile(ref, source.name, state.context?.revision))
                            task.upload(listOf(ImportUpload(ref, source)))
                        }
                    }, onRemoveLocal = { ref ->
                        task.removeLocalFileFailure(ref)
                        files.remove(ref); fileNames = fileNames - ref; pendingFiles = pendingFiles - ref
                    }, onRemove = { fileRef ->
                        val uploaded = state.context?.files?.firstOrNull { it.fileRef == fileRef }
                        uploaded?.clientFileRef?.let(task::removeLocalFileFailure)
                        val clientRef = uploaded?.clientFileRef
                        if (clientRef != null && state.canRemoveFilesWhileUploading) {
                            // 上传进行中不能立即改上下文：先隐藏该行，等上传结束后执行移除。
                            queuedFileRemovals = queuedFileRemovals + clientRef
                            hiddenClientFileRefs = hiddenClientFileRefs + clientRef
                        } else task.removeFile(fileRef)
                    },
                    onSelectAccount = { ref, id -> if (id == null) task.selectAccount(ref, ImportAccountTarget(null, null))
                        else selectAccount(ref, id) },
                    onMergeSameNameAcrossChannels = task::setMergeSameNameAcrossChannels,
                    onCreateAccount = { source ->
                        accountCreatePriorIds = accountState.accounts.mapTo(mutableSetOf()) { it.id } + accountState.currentAccounts.keys
                        accountForm = "new"
                        accountFormSource = source
                        accountCreateSourceRef = source.sourceAccountRef
                    }, onResolveAccounts = account::resolvePending, mappingStep = mappingStep, modifier = Modifier.fillMaxSize())
            }
            HorizontalDivider()
            Row(Modifier.align(Alignment.CenterHorizontally).then(if (step < 2) Modifier.widthIn(max = 720.dp) else Modifier).fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                if (state.phase == ImportTaskPhase.PREPARE && !mappingStep) {
                  val blockedReason = fileStepNotice?.let { localizedText(it) }
                  Button(onClick = {
                    if (blockedReason == null) mappingStep = true
                    else notice = ImportTaskNotice("files", requireNotNull(fileStepNotice))
                  }, enabled = !busy && pendingFiles.isEmpty() && canWrite && state.canEdit && (blockedReason != null || state.context?.canPreview == true),
                    colors = if (blockedReason == null) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier.testTag("cash.import.to-accounts").semantics { if (blockedReason != null) stateDescription = blockedReason }) {
                    Text(localizedText("cash_import_next"))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Icon(painterResource(Res.drawable.ic_arrow_forward), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                  }
                }
                else if (state.needsContextRefresh || state.plan?.invalidatedChoices?.isNotEmpty() == true) Button(onClick = task::restartPreview,
                    enabled = !busy && canWrite, modifier = Modifier.testTag("cash.import.restart")) { Text(localizedText("cash_import_restart_review")) }
                else if (state.phase == ImportTaskPhase.REVIEW && !state.previewDirty) Button(onClick = task::confirm,
                    enabled = !busy && canWrite && state.canConfirm, modifier = Modifier.testTag("cash.import.confirm")) { Text(localizedText("cash_import_confirm_all")) }
                else Button(onClick = {
                    val groups = state.context?.sourceAccounts.orEmpty()
                    val selections = state.choices.accountSelections.associateBy { it.sourceAccountRef }
                    val currenciesByAccount = linkedMapOf<String, MutableSet<String>>()
                    for (group in groups) {
                        val accountId = selections[group.sourceAccountRef]?.target?.accountId ?: continue
                        currenciesByAccount.getOrPut(accountId) { linkedSetOf() }.addAll(group.currencies)
                    }
                    val suggestedSnapshots = groups.mapNotNull { group -> group.suggestedAccount?.let { it.accountId to it } }.toMap()
                    account.ensureCurrencies(currenciesByAccount.mapValues { it.value.toList() }, suggestedSnapshots) { latestAccounts ->
                        for (group in groups) {
                            val current = selections[group.sourceAccountRef]?.target ?: continue
                            val accountId = current.accountId ?: continue
                            val latest = latestAccounts[accountId] ?: continue
                            task.selectAccount(group.sourceAccountRef, ImportAccountTarget(accountId, latest.revision))
                        }
                        task.preview()
                    }
                }, enabled = !busy && canWrite && auxiliaryReady && accountState.ready && state.canEdit && state.context?.canPreview == true,
                    modifier = Modifier.testTag("cash.import.review")) {
                    Text(localizedText("cash_import_next"))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Icon(painterResource(Res.drawable.ic_arrow_forward), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                }
            }
        }
          }
        }
        if (accountForm == null) SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(bottom = 64.dp).testTag("cash.import.file-notice"))
       }
      }
    }
    if (processingOpen && state.plan != null) CashImportProcessingPanel(state, sizeClass,
        onMore = { task.loadProcessing(append = true) }, onRetry = { task.loadProcessing() }, onClose = { if (!state.busy) processingOpen = false })
    val item = selectedItem
    if (item != null && state.plan != null) CashImportPreviewPanel(item, state.details[item.itemId], state.plan!!.planId, busy || !canWrite,
        sizeClass, currentAccounts, categories, options, state.errorCode, { task.loadDetail(item.itemId) }, { selectedItem = null })
    accountForm?.let { id ->
        val current = accountState.currentAccounts[id]
        CashImportAccountForm(current, id != "new" && current == null, busy || !canWrite, sizeClass, accountState.errorCode,
            snackbar = snackbar, pending = accountState.pendingKeys.isNotEmpty(), onResolve = account::resolvePending,
            initialName = accountFormSource?.displayName.orEmpty(),
            initialType = cashImportAccountTypeForName(accountFormSource?.displayName.orEmpty()),
            initialCurrencies = accountFormSource?.currencies.orEmpty(),
            onDirty = { accountDirty = it }, onClose = {
                if (account.state.value.busy || task.state.value.busy) showBusyNotice()
                else if (account.state.value.pendingKeys.isNotEmpty()) accountUnknownNotice = true
                else if (accountDirty) discardAction = {
                    if (account.state.value.busy || task.state.value.busy) showBusyNotice()
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
            text = { Column(Modifier.selectableGroup()) {
                Text(localizedText(if (decision == ImportTaskExitAction.ASK_ACCOUNTS) "cash_import_account_exit_body" else "cash_import_account_unknown_body"))
                accountState.undoFailures.values.distinct().forEach { Text(cashImportTaskErrorText(it)) }
                val choices = listOfNotNull(
                    "cash_import_undo_exit".takeIf { decision == ImportTaskExitAction.ASK_ACCOUNTS },
                    "cash_import_check_result".takeIf { decision != ImportTaskExitAction.ASK_ACCOUNTS && accountState.pendingKeys.isNotEmpty() },
                    "cash_import_keep_exit")
                choices.forEach { choice -> Row(Modifier.fillMaxWidth().selectable(selected = exitChoice == choice, enabled = !busy,
                    role = Role.RadioButton, onClick = { exitChoice = choice }), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = exitChoice == choice, onClick = null, enabled = !busy)
                    Text(localizedText(choice))
                } }
            } }, confirmButton = { TextButton(onClick = {
                when (exitChoice) {
                    "cash_import_undo_exit" -> closeTask(true)
                    "cash_import_check_result" -> account.resolvePending()
                    "cash_import_keep_exit" -> if (state.context == null && state.receipt == null && state.frozenCommand == null) completeExit() else closeTask(false)
                }
            }, enabled = !busy && exitChoice != null) { Text(localizedText(exitChoice ?: "copy_fadf24dbc5")) }
            }, dismissButton = { TextButton(onClick = { exitDialog = null }) { Text(localizedText("cash_import_continue_edit")) } })
    }
    if (accountUnknownNotice) AlertDialog(onDismissRequest = { accountUnknownNotice = false },
        title = { Text(localizedText("cash_import_account_unknown")) },
        confirmButton = { TextButton(onClick = { account.resolvePending(); accountUnknownNotice = false }, enabled = !accountState.busy) { Text(localizedText("cash_import_check_result")) } },
        dismissButton = { TextButton(onClick = { accountUnknownNotice = false }, enabled = !accountState.busy) { Text(localizedText("cash_import_continue_edit")) } })
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
                }
                // 权威拒绝（服务端明确未提交）只提供返回，不重放原命令，也不引导再查结果。
                val authoritativeRefusal = state.submission == ImportSubmissionState.RETRY_ORIGINAL && state.errorCode != null
                if (receipt == null && !authoritativeRefusal &&
                    (state.internalFailure == null || state.submission != ImportSubmissionState.RETRY_ORIGINAL)) {
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
private fun CashImportTaskSteps(current: Int, modifier: Modifier = Modifier) {
    Row(modifier.padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("cash_import_step_file", "cash_import_step_account", "cash_import_step_review").forEachIndexed { index, label ->
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${if (index < current) "✓" else (index + 1).toString()}  ${localizedText(label)}", style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (index == current) FontWeight.Bold else FontWeight.Normal,
                    color = if (index <= current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(thickness = if (index == current) 2.dp else 1.dp,
                    color = if (index <= current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

internal fun cashImportLoadBlocked(auxiliaryFailed: Boolean, accounts: ImportAccountUiState): Boolean =
    auxiliaryFailed || (!accounts.ready && !accounts.busy && accounts.errorCode != null)

internal fun importTaskHasUnexpectedFailure(receipt: ImportReceipt): Boolean =
    receipt.commitOutcome?.hasNewFailures ?: (receipt.status == "failed")

internal fun importTaskProcessedRows(receipt: ImportReceipt): Long =
    listOf("created_posted", "updated_posted", "saved_draft", "finalized_draft", "duplicate", "skipped").sumOf { receipt.summary[it] ?: 0L }
