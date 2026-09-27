package com.finance.tracker.presentation

import com.finance.tracker.core.*
import com.finance.tracker.domain.CashCategory
import com.finance.tracker.domain.CashCategoryRepository
import com.finance.tracker.domain.DomainFailure
import com.finance.tracker.domain.FailureCategory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

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

    FeaturePage(localizedText("copy_a42e73f0a7"), SemanticIds.cashCategoriesScreen) {
        InlineError(pageError)
        if (state.loading && state.directory == null) StateMessage(localizedText("copy_07c21b1c4e"))
        if (pageError != null && state.directory == null) {
            StateMessage(pageError, isError = true) {
                TextButton(onClick = viewModel::load) { Text(localizedText("copy_e2d53a6d3a")) }
            }
        }
        if (state.directory != null || state.draft != null) {
            if (sizeClass != WindowSizeClass.WIDE) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CategoryDirectory(
                        items = filtered,
                        allItems = items,
                        canWrite = canWrite,
                        search = state.search,
                        sizeClass = sizeClass,
                        busy = busy,
                        onSearch = viewModel::setSearch,
                        onCreateRoot = viewModel::startCreate,
                        onEdit = viewModel::edit,
                        onCreateChild = viewModel::startChild,
                        onMove = { item, direction -> viewModel.move(item, direction, canWrite) },
                        onDelete = { viewModel.requestDelete(it, canWrite) },
                    )
                    state.draft?.let {
                        CategoryEditor(
                            draft = it,
                            parents = parentOptions,
                            busy = busy,
                            error = editorError,
                            onChange = viewModel::updateDraft,
                            onCancel = viewModel::cancelEditor,
                            onSave = { viewModel.save(canWrite) },
                        )
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                    CategoryDirectory(
                        items = filtered,
                        allItems = items,
                        canWrite = canWrite,
                        search = state.search,
                        sizeClass = sizeClass,
                        busy = busy,
                        onSearch = viewModel::setSearch,
                        onCreateRoot = viewModel::startCreate,
                        onEdit = viewModel::edit,
                        onCreateChild = viewModel::startChild,
                        onMove = { item, direction -> viewModel.move(item, direction, canWrite) },
                        onDelete = { viewModel.requestDelete(it, canWrite) },
                        modifier = Modifier.weight(1.1f),
                    )
                    state.draft?.let {
                        CategoryEditor(
                            draft = it,
                            parents = parentOptions,
                            busy = busy,
                            error = editorError,
                            onChange = viewModel::updateDraft,
                            onCancel = viewModel::cancelEditor,
                            onSave = { viewModel.save(canWrite) },
                            modifier = Modifier.weight(0.9f),
                        )
                    } ?: StateMessage(localizedText("copy_b411c4ac59"), modifier = Modifier.weight(0.9f))
                }
            }
        }
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
            confirmEnabled = canWrite && canDeleteCashCategory(impact),
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
    sizeClass: WindowSizeClass,
    busy: Boolean,
    onSearch: (String) -> Unit,
    onCreateRoot: () -> Unit,
    onEdit: (CashCategory) -> Unit,
    onCreateChild: (String) -> Unit,
    onMove: (CashCategory, String) -> Unit,
    onDelete: (CashCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(modifier) {
        Text(localizedText("copy_128c99ed00"), style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
        LabeledInput(search, onSearch, localizedText("copy_c3113f2316"), semanticId = SemanticIds.cashCategorySearch)
        if (items.isEmpty()) Text(if (search.isBlank()) localizedText("copy_85d37bf07a") else localizedText("copy_01152a1690"))
        items.forEach { item ->
            HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth().padding(start = ((item.depth - 1) * 16).dp)) {
                Text(item.name, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                val path = (item.path.map { it.name } + item.name).joinToString(" / ")
                if (item.path.isNotEmpty()) Text(path, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                if (sizeClass != WindowSizeClass.WIDE) {
                    Column {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { onEdit(item) }, enabled = canWrite && !busy) { Text(localizedText("copy_a7f814c0a4")) }
                            TextButton(onClick = { onCreateChild(item.id) }, enabled = canWrite && !busy && item.depth < 5) { Text(localizedText("copy_3a5fb928ac")) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { onMove(item, "before") }, enabled = canWrite && !busy && categoryCanMove(allItems, item.id, "before")) { Text(localizedText("copy_8a0c839791")) }
                            TextButton(onClick = { onMove(item, "after") }, enabled = canWrite && !busy && categoryCanMove(allItems, item.id, "after")) { Text(localizedText("copy_05c46fa3b7")) }
                            TextButton(onClick = { onDelete(item) }, enabled = canWrite && !busy) { Text(localizedText("copy_3755f56f2f")) }
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { onEdit(item) }, enabled = canWrite && !busy) { Text(localizedText("copy_a7f814c0a4")) }
                        TextButton(onClick = { onCreateChild(item.id) }, enabled = canWrite && !busy && item.depth < 5) { Text(localizedText("copy_3a5fb928ac")) }
                        TextButton(onClick = { onMove(item, "before") }, enabled = canWrite && !busy && categoryCanMove(allItems, item.id, "before")) { Text(localizedText("copy_8a0c839791")) }
                        TextButton(onClick = { onMove(item, "after") }, enabled = canWrite && !busy && categoryCanMove(allItems, item.id, "after")) { Text(localizedText("copy_05c46fa3b7")) }
                        TextButton(onClick = { onDelete(item) }, enabled = canWrite && !busy) { Text(localizedText("copy_3755f56f2f")) }
                    }
                }
            }
        }
        HorizontalDivider()
        TextButton(onClick = onCreateRoot, enabled = canWrite && !busy, modifier = Modifier.testTag("cash-category-create")) { Text(localizedText("copy_1ea769ea2c")) }
    }
}

@Composable
private fun CategoryEditor(
    draft: CashCategoryDraft,
    parents: List<CashCategory>,
    busy: Boolean,
    error: String?,
    onChange: (CashCategoryDraft) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(modifier) {
        Text(if (draft.id == null) if (draft.parentId == null) localizedText("copy_982fa4706a") else localizedText("copy_e829c71fc6") else localizedText("copy_9ad1ea5822"), style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
        LabeledInput(draft.name, { onChange(draft.copy(name = it.take(40))) }, localizedText("copy_3fc303551e"), semanticId = SemanticIds.cashCategoryName)
        ChoicePicker(
            label = localizedText("copy_0cacc9c3b0"),
            value = draft.parentId.orEmpty(),
            options = listOf("" to localizedText("copy_6e83bd81ca")) + parents.map { it.id to (it.path.map { pathItem -> pathItem.name } + it.name).joinToString(" / ") },
            onSelected = { onChange(draft.copy(parentId = it.ifEmpty { null })) },
        )
        LabeledInput(draft.description, { onChange(draft.copy(description = it.take(500))) }, localizedText("copy_7d11fd745d"), singleLine = false)
        InlineError(error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onCancel, enabled = !busy) { Text(localizedText("copy_4d0b4688c7")) }
            Button(onClick = onSave, enabled = !busy, modifier = Modifier.testTag(SemanticIds.cashCategorySave)) {
                Text(if (busy) localizedText("copy_6644f06197") else if (draft.id == null) localizedText("copy_cbd700515a") else localizedText("copy_fadf24dbc5"))
            }
        }
    }
}
