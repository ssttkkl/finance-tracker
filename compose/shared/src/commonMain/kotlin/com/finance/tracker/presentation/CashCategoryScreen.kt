package com.finance.tracker.presentation

import com.finance.tracker.core.*
import com.finance.tracker.domain.CashCategory
import com.finance.tracker.domain.CashCategoryRepository
import com.finance.tracker.domain.DomainFailure
import com.finance.tracker.domain.FailureCategory

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.finance.tracker.resources.Res
import com.finance.tracker.resources.ic_more_vert
import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField

@Composable
internal fun CashCategoryScreen(
    repository: CashCategoryRepository,
    workspaceId: String,
    sizeClass: WindowSizeClass,
    canWrite: Boolean,
    diagnostics: DiagnosticLogger = NoOpDiagnosticLogger,
) {
    val viewModel: CashCategoryViewModel = viewModel(
        key = "cash-categories:$workspaceId",
        factory = viewModelFactory { initializer { CashCategoryViewModel(repository, diagnostics = diagnostics) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.load() }

    val items = state.directory?.items.orEmpty()
    val filtered = filterCashCategoriesByAncestorPath(items, state.search)
    val parentOptions = availableCategoryParents(items, state.draft?.id)
    val pageError = state.errorCode?.let { categoryErrorText(it) }
    val editorError = state.editorErrorCode?.let { categoryErrorText(it) }
    val deleteError = state.deleteErrorCode?.let { categoryErrorText(it) }
    val busy = state.busy || state.loadingDeleteImpact

    val actionFocus = remember { mutableMapOf<String, FocusRequester>() }
    val createFocus = remember { FocusRequester() }
    val sectionFocus = remember { FocusRequester() }
    var lastEditorId by remember { mutableStateOf<String?>(null) }
    var editorWasOpen by remember { mutableStateOf(false) }
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(state.draft != null, state.busy, windowInfo.isWindowFocused) {
        if (state.draft != null) editorWasOpen = true
        if (state.draft == null && editorWasOpen && !state.busy && windowInfo.isWindowFocused) {
            withFrameNanos { }
            val target = lastEditorId
            when {
                target == null -> createFocus.requestFocus()
                filtered.any { it.id == target } -> actionFocus[target]?.requestFocus()
                else -> sectionFocus.requestFocus()
            }
            editorWasOpen = false
        }
    }
    ManagementSectionContent(Modifier.fillMaxWidth().focusRequester(sectionFocus).focusable().testTag(SemanticIds.cashCategoriesScreen)) {
        Text(localizedText("navigation_cash_categories"), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        InlineError(pageError)
        if (state.loading && state.directory == null) StateMessage(localizedText("copy_07c21b1c4e"))
        if (pageError != null && state.directory == null) {
            StateMessage(pageError, isError = true) {
                TextButton(onClick = viewModel::load) { Text(localizedText("copy_e2d53a6d3a")) }
            }
        }
        if (state.directory != null) {
            CategoryDirectory(
                items = filtered, allItems = items, canWrite = canWrite, search = state.search,
                busy = busy, onSearch = viewModel::setSearch,
                onCreateRoot = { lastEditorId = null; viewModel.startCreate() },
                onEdit = { lastEditorId = it.id; viewModel.edit(it) },
                onCreateChild = { lastEditorId = it; viewModel.startChild(it) },
                onMove = { item, direction -> viewModel.move(item, direction, canWrite) },
                onDelete = { viewModel.requestDelete(it, canWrite) },
                actionFocus = actionFocus, createFocus = createFocus,
            )
        }
    }
    state.draft?.let { draft ->
        CategoryEditorDialog(
            draft, parentOptions, sizeClass, busy, editorError,
            viewModel::updateDraft, { if (!busy) viewModel.cancelEditor() }, { viewModel.save(canWrite) },
        )
    }

    val item = state.deleting
    val impact = state.deleteImpact
    if (item != null && impact != null) {
        val impactText = when {
            impact.childCount > 0 -> localizedText("copy_fc079766e9", impact.childCount)
            impact.directUsageCount > 0 -> localizedText("copy_166f66a0f6", impact.directUsageCount)
            else -> localizedText("copy_8f19827d18", item.name)
        }
        ConfirmationDialog(
            title = localizedText("copy_6add292ad3"),
            message = impactText,
            confirmLabel = localizedText("copy_3755f56f2f"),
            onConfirm = { viewModel.confirmDelete(canWrite) },
            onDismiss = viewModel::dismissDelete,
            error = deleteError,
            busy = busy,
            destructive = true,
            confirmEnabled = canWrite && canDeleteCashCategory(impact),
            semanticId = "cash-category.delete",
        )
    } else if (item == null && deleteError != null) {
        PlatformModalEscapeHandler(enabled = isWebPlatform, onEscape = viewModel::dismissDelete)
        AlertDialog(
            modifier = Modifier.testTag("cash-category.delete-error-dialog"),
            onDismissRequest = viewModel::dismissDelete,
            title = { Text(localizedText("cash_category_delete_error_title")) },
            text = { Text(deleteError) },
            confirmButton = {
                TextButton(
                    onClick = viewModel::dismissDelete,
                    modifier = Modifier.testTag("cash-category.delete-error-acknowledge"),
                ) { Text(localizedText("dialog_error_acknowledge")) }
            },
        )
    }
}

@Composable
private fun categoryErrorText(code: String): String = userError(
    DomainFailure(code, 400, FailureCategory.RECOVERABLE),
)

@Composable
private fun CategoryDirectory(
    items: List<CashCategory>,
    allItems: List<CashCategory>,
    canWrite: Boolean,
    search: String,
    busy: Boolean,
    onSearch: (String) -> Unit,
    onCreateRoot: () -> Unit,
    onEdit: (CashCategory) -> Unit,
    onCreateChild: (String) -> Unit,
    onMove: (CashCategory, String) -> Unit,
    onDelete: (CashCategory) -> Unit,
    actionFocus: MutableMap<String, FocusRequester>,
    createFocus: FocusRequester,
) {
    OutlinedTextField(
        value = search, onValueChange = onSearch,
        modifier = Modifier.widthIn(max = 400.dp).fillMaxWidth().testTag(SemanticIds.cashCategorySearch)
            .accessibleTextFieldLabel(localizedText("copy_c3113f2316")),
        label = { Text(localizedText("copy_c3113f2316")) }, singleLine = true,
    )
    if (items.isEmpty()) Text(if (search.isBlank()) localizedText("copy_85d37bf07a") else localizedText("copy_01152a1690"))
    items.forEach { item ->
        androidx.compose.runtime.key(item.id) {
            var menuOpen by remember { mutableStateOf(false) }
            val requester = actionFocus.getOrPut(item.id) { FocusRequester() }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().testTag("cash-category.row.${item.id}"), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(start = (if (search.isBlank()) (item.depth - 1) * 12 else 0).dp)) {
                    Text(item.name, style = MaterialTheme.typography.bodyLarge)
                    if (search.isNotBlank() && item.path.isNotEmpty()) {
                        Text(item.path.joinToString(" / ") { it.name }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TextButton(
                    onClick = { onEdit(item) }, enabled = canWrite && !busy,
                    modifier = Modifier.focusRequester(requester).testTag("cash-category.edit.${item.id}"),
                ) { Text(localizedText("copy_a7f814c0a4")) }
                Box {
                    IconButton(
                        onClick = { menuOpen = true }, enabled = canWrite && !busy,
                        modifier = Modifier.testTag("cash-category.more.${item.id}"),
                    ) { Icon(painterResource(Res.drawable.ic_more_vert), localizedText("management_category_more")) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(localizedText("copy_3a5fb928ac")) },
                            onClick = { menuOpen = false; onCreateChild(item.id) }, enabled = item.depth < 5,
                        )
                        DropdownMenuItem(
                            text = { Text(localizedText("copy_8a0c839791")) },
                            onClick = { menuOpen = false; onMove(item, "before") }, enabled = categoryCanMove(allItems, item.id, "before"),
                        )
                        DropdownMenuItem(
                            text = { Text(localizedText("copy_05c46fa3b7")) },
                            onClick = { menuOpen = false; onMove(item, "after") }, enabled = categoryCanMove(allItems, item.id, "after"),
                        )
                        DropdownMenuItem(
                            text = { Text(localizedText(if (allItems.any { it.parentId == item.id }) "management_category_has_children" else "copy_3755f56f2f"), color = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; onDelete(item) },
                            enabled = allItems.none { it.parentId == item.id },
                        )
                    }
                }
            }
        }
    }
    HorizontalDivider()
    TextButton(
        onClick = onCreateRoot, enabled = canWrite && !busy,
        modifier = Modifier.focusRequester(createFocus).testTag("cash-category-create"),
    ) { Text(localizedText("copy_1ea769ea2c")) }
}

@Composable
private fun CategoryEditorDialog(
    draft: CashCategoryDraft,
    parents: List<CashCategory>,
    sizeClass: WindowSizeClass,
    busy: Boolean,
    error: String?,
    onChange: (CashCategoryDraft) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    val compact = sizeClass == WindowSizeClass.COMPACT
    val nameFocus = remember { FocusRequester() }
    val title = localizedText(if (draft.id == null) if (draft.parentId == null) "copy_982fa4706a" else "copy_e829c71fc6" else "copy_9ad1ea5822")
    PlatformModalEscapeHandler(enabled = isWebPlatform && !busy, onEscape = onCancel)
    Dialog(
        onDismissRequest = { if (!busy) onCancel() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !isWebPlatform && !busy, dismissOnClickOutside = !compact && !busy),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(if (compact) 0.dp else 24.dp), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight)
                    .then(if (compact) Modifier.fillMaxHeight() else Modifier)
                    .semantics { paneTitle = title }.testTag("cash-category.editor"),
                shape = if (compact) androidx.compose.ui.graphics.RectangleShape else MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                    ModalHeader(
                        variant = if (compact) ModalHeaderVariant.FullScreenDialog else ModalHeaderVariant.BasicDialog,
                        onClose = onCancel, closeEnabled = !busy, closeTestTag = "cash-category.editor.close",
                    ) { Text(title, style = MaterialTheme.typography.titleLarge) }
                    if (compact) HorizontalDivider()
                    Column(
                        Modifier.weight(1f, fill = compact).verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp).padding(top = if (compact) 24.dp else 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        OutlinedTextField(
                            value = draft.name, onValueChange = { onChange(draft.copy(name = it.take(40))) },
                            modifier = Modifier.fillMaxWidth().focusRequester(nameFocus).testTag(SemanticIds.cashCategoryName)
                                .accessibleTextFieldLabel(localizedText("copy_3fc303551e")),
                            label = { Text(localizedText("copy_3fc303551e")) }, singleLine = true, enabled = !busy,
                        )
                        ChoicePicker(
                            label = localizedText("copy_0cacc9c3b0"), value = draft.parentId.orEmpty(),
                            options = listOf("" to localizedText("copy_6e83bd81ca")) + parents.map { it.id to (it.path.map { pathItem -> pathItem.name } + it.name).joinToString(" / ") },
                            onSelected = { onChange(draft.copy(parentId = it.ifEmpty { null })) }, enabled = !busy,
                            semanticId = SemanticIds.cashCategoryParent,
                        )
                        OutlinedTextField(
                            value = draft.description, onValueChange = { onChange(draft.copy(description = it.take(500))) },
                            modifier = Modifier.fillMaxWidth().accessibleTextFieldLabel(localizedText("copy_7d11fd745d")),
                            label = { Text(localizedText("copy_7d11fd745d")) }, enabled = !busy,
                        )
                        InlineError(error)
                    }
                    if (compact) HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth().then(if (compact) Modifier.height(56.dp).padding(horizontal = 24.dp) else Modifier.padding(24.dp)),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onCancel, enabled = !busy, modifier = Modifier.testTag("cash-category.editor.cancel")) { Text(localizedText("copy_4d0b4688c7")) }
                        Button(onClick = onSave, enabled = !busy, modifier = Modifier.testTag(SemanticIds.cashCategorySave)) {
                            Text(if (busy) localizedText("copy_6644f06197") else if (draft.id == null) localizedText("copy_cbd700515a") else localizedText("copy_fadf24dbc5"))
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { withFrameNanos { }; nameFocus.requestFocus() }
}
