package com.finance.tracker

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private data class CategoryDraft(
    val id: String?,
    val name: String,
    val description: String,
    val parentId: String?,
)

@Composable
internal fun CashCategoryScreen(api: FinanceApiClient, sizeClass: WindowSizeClass, canWrite: Boolean) {
    var directory by remember(api) { mutableStateOf<CashCategoryDirectoryDto?>(null) }
    var loading by remember(api) { mutableStateOf(true) }
    var error by remember(api) { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf<CategoryDraft?>(null) }
    var busy by remember { mutableStateOf(false) }
    var editorError by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<CashCategoryDto?>(null) }
    var deleteImpact by remember { mutableStateOf<CashCategoryDeleteImpactDto?>(null) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun load() {
        scope.launch {
            loading = true
            error = null
            try {
                directory = api.fetchCashCategories()
            } catch (cause: Throwable) {
                error = userError(cause)
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(api) { load() }

    fun saveCategory(value: CategoryDraft) {
        if (!canWrite) return
        val name = value.name.trim()
        if (name.isEmpty()) {
            editorError = "请输入分类名称。"
            return
        }
        scope.launch {
            busy = true
            editorError = null
            try {
                val current = directory ?: api.fetchCashCategories()
                if (value.id == null) {
                    api.createCashCategory(name, value.parentId, value.description.trim(), current.revision.toLong())
                } else {
                    api.updateCashCategory(value.id, name, value.parentId, value.description.trim(), current.revision.toLong())
                }
                draft = null
                load()
            } catch (cause: Throwable) {
                editorError = userError(cause)
            } finally {
                busy = false
            }
        }
    }

    fun requestDelete(item: CashCategoryDto) {
        if (!canWrite) return
        scope.launch {
            deleteError = null
            try {
                deleteImpact = api.fetchCashCategoryDeletionImpact(item.id)
                deleting = item
            } catch (cause: Throwable) {
                deleteError = userError(cause)
                error = deleteError
            }
        }
    }

    fun moveCategory(item: CashCategoryDto, direction: String) {
        if (!canWrite) return
        scope.launch {
            busy = true
            error = null
            try {
                val revision = directory?.revision ?: api.fetchCashCategories().revision
                api.reorderCashCategory(item.id, direction, revision.toLong())
                load()
            } catch (cause: Throwable) {
                error = userError(cause)
            } finally {
                busy = false
            }
        }
    }

    val items = directory?.items.orEmpty()
    val filtered = filterCashCategoriesByAncestorPath(items, search)
    val parentOptions = availableCategoryParents(items, draft?.id)

    FeaturePage("收支分类", SemanticIds.cashCategoriesScreen) {
        InlineError(error)
        if (loading && directory == null) StateMessage("正在读取分类…")
        if (error != null && directory == null) {
            StateMessage(error.orEmpty(), isError = true) {
                TextButton(onClick = ::load) { Text("重试") }
            }
        }
        if (directory != null || draft != null) {
            if (sizeClass != WindowSizeClass.WIDE) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CategoryDirectory(
                        items = filtered,
                        allItems = items,
                        canWrite = canWrite,
                        search = search,
                        sizeClass = sizeClass,
                        onSearch = { search = it },
                        onCreateRoot = {
                            draft = CategoryDraft(null, "", "", null)
                            editorError = null
                        },
                        onEdit = { item ->
                            draft = CategoryDraft(item.id, item.name, item.description.orEmpty(), item.parentId)
                            editorError = null
                        },
                        onCreateChild = { parentId ->
                            draft = CategoryDraft(null, "", "", parentId)
                            editorError = null
                        },
                        onMove = ::moveCategory,
                        onDelete = ::requestDelete,
                    )
                    draft?.let {
                        CategoryEditor(
                            draft = it,
                            parents = parentOptions,
                            busy = busy,
                            error = editorError,
                            onChange = { draft = it },
                            onCancel = { draft = null },
                            onSave = ::saveCategory,
                        )
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                    CategoryDirectory(
                        items = filtered,
                        allItems = items,
                        canWrite = canWrite,
                        search = search,
                        sizeClass = sizeClass,
                        onSearch = { search = it },
                        onCreateRoot = {
                            draft = CategoryDraft(null, "", "", null)
                            editorError = null
                        },
                        onEdit = { item ->
                            draft = CategoryDraft(item.id, item.name, item.description.orEmpty(), item.parentId)
                            editorError = null
                        },
                        onCreateChild = { parentId ->
                            draft = CategoryDraft(null, "", "", parentId)
                            editorError = null
                        },
                        onMove = ::moveCategory,
                        onDelete = ::requestDelete,
                        modifier = Modifier.weight(1.1f),
                    )
                    draft?.let {
                        CategoryEditor(
                            draft = it,
                            parents = parentOptions,
                            busy = busy,
                            error = editorError,
                            onChange = { draft = it },
                            onCancel = { draft = null },
                            onSave = ::saveCategory,
                            modifier = Modifier.weight(0.9f),
                        )
                    } ?: StateMessage("选择一个分类。", modifier = Modifier.weight(0.9f))
                }
            }
        }
    }

    if (deleting != null && deleteImpact != null) {
        val item = deleting!!
        val impact = deleteImpact!!
        val impactText = when {
            impact.childCount > 0 -> "请先处理 ${impact.childCount} 个子分类。"
            impact.directUsageCount > 0 -> "${impact.directUsageCount} 笔流水会变为无分类。"
            else -> "确认删除「${item.name}」。"
        }
        ConfirmationDialog(
            title = "删除分类",
            message = impactText,
            confirmLabel = "删除",
            onConfirm = {
                scope.launch {
                    busy = true
                    deleteError = null
                    try {
                        api.deleteCashCategory(item.id, impact)
                        deleting = null
                        deleteImpact = null
                        draft = null
                        load()
                    } catch (cause: Throwable) {
                        deleteError = userError(cause)
                    } finally {
                        busy = false
                    }
                }
            },
            onDismiss = { deleting = null; deleteImpact = null; deleteError = null },
            error = deleteError,
            busy = busy,
            confirmEnabled = canWrite && canDeleteCashCategory(impact),
        )
    }

}

@Composable
private fun CategoryDirectory(
    items: List<CashCategoryDto>,
    allItems: List<CashCategoryDto>,
    canWrite: Boolean,
    search: String,
    sizeClass: WindowSizeClass,
    onSearch: (String) -> Unit,
    onCreateRoot: () -> Unit,
    onEdit: (CashCategoryDto) -> Unit,
    onCreateChild: (String) -> Unit,
    onMove: (CashCategoryDto, String) -> Unit,
    onDelete: (CashCategoryDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(modifier) {
        Text("分类目录", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
        LabeledInput(search, onSearch, "搜索分类", semanticId = SemanticIds.cashCategorySearch)
        if (items.isEmpty()) Text(if (search.isBlank()) "还没有分类。" else "没有匹配的分类。")
        items.forEach { item ->
            HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth().padding(start = ((item.depth - 1) * 16).dp)) {
                Text(item.name, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                val path = (item.path.map { it.name } + item.name).joinToString(" / ")
                if (item.path.isNotEmpty()) Text(path, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                if (sizeClass != WindowSizeClass.WIDE) {
                    Column {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { onEdit(item) }, enabled = canWrite) { Text("编辑") }
                            TextButton(onClick = { onCreateChild(item.id) }, enabled = canWrite && item.depth < 5) { Text("新增子类") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { onMove(item, "before") }, enabled = canWrite && categoryCanMove(allItems, item.id, "before")) { Text("上移") }
                            TextButton(onClick = { onMove(item, "after") }, enabled = canWrite && categoryCanMove(allItems, item.id, "after")) { Text("下移") }
                            TextButton(onClick = { onDelete(item) }, enabled = canWrite) { Text("删除") }
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { onEdit(item) }, enabled = canWrite) { Text("编辑") }
                        TextButton(onClick = { onCreateChild(item.id) }, enabled = canWrite && item.depth < 5) { Text("新增子类") }
                        TextButton(onClick = { onMove(item, "before") }, enabled = canWrite && categoryCanMove(allItems, item.id, "before")) { Text("上移") }
                        TextButton(onClick = { onMove(item, "after") }, enabled = canWrite && categoryCanMove(allItems, item.id, "after")) { Text("下移") }
                        TextButton(onClick = { onDelete(item) }, enabled = canWrite) { Text("删除") }
                    }
                }
            }
        }
        HorizontalDivider()
        TextButton(onClick = onCreateRoot, enabled = canWrite, modifier = Modifier.testTag("cash-category-create")) { Text("新建一级分类") }
    }
}

@Composable
private fun CategoryEditor(
    draft: CategoryDraft,
    parents: List<CashCategoryDto>,
    busy: Boolean,
    error: String?,
    onChange: (CategoryDraft) -> Unit,
    onCancel: () -> Unit,
    onSave: (CategoryDraft) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(modifier) {
        Text(if (draft.id == null) if (draft.parentId == null) "新增一级分类" else "新增子分类" else "编辑分类", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
        LabeledInput(draft.name, { onChange(draft.copy(name = it.take(40))) }, "分类名称", semanticId = SemanticIds.cashCategoryName)
        ChoicePicker(
            label = "上级分类",
            value = draft.parentId.orEmpty(),
            options = listOf("" to "无（一级分类）") + parents.map { it.id to (it.path.map(CashCategoryPathItemDto::name) + it.name).joinToString(" / ") },
            onSelected = { onChange(draft.copy(parentId = it.ifEmpty { null })) },
        )
        LabeledInput(draft.description, { onChange(draft.copy(description = it.take(500))) }, "分类描述", singleLine = false)
        InlineError(error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onCancel, enabled = !busy) { Text("取消") }
            Button(onClick = { onSave(draft) }, enabled = !busy, modifier = Modifier.testTag(SemanticIds.cashCategorySave)) {
                Text(if (busy) "保存中…" else if (draft.id == null) "创建分类" else "保存")
            }
        }
    }
}
