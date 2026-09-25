package com.finance.tracker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collect

@Composable
internal fun FeaturePage(
    title: String,
    semanticId: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 1480.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .testTag(semanticId),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title, modifier = Modifier.testTag("app-page-title"), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
        content()
    }
}

@Composable
internal fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = modifier, shape = RoundedCornerShape(20.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

@Composable
internal fun LabeledInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    semanticId: String? = null,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    isError: Boolean = false,
    onBlur: (() -> Unit)? = null,
) {
    val tagged = (if (semanticId == null) modifier else modifier.testTag(semanticId)).onFocusChanged {
        if (!it.isFocused) onBlur?.invoke()
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = tagged.semantics(mergeDescendants = true) { contentDescription = label }.fillMaxWidth(),
        singleLine = singleLine,
        enabled = enabled,
        isError = isError,
        shape = RoundedCornerShape(12.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DatePickerInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    semanticId: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = isoDateToUtcMillis(value))
    LaunchedEffect(open) {
        if (!open) return@LaunchedEffect
        val initialSelection = pickerState.selectedDateMillis
        var skipInitialValue = true
        snapshotFlow { pickerState.selectedDateMillis }.collect { selectedMillis ->
            if (skipInitialValue) {
                skipInitialValue = false
                return@collect
            }
            if (selectedMillis == null || selectedMillis == initialSelection) return@collect
            onValueChange(isoDateFromUtcMillis(selectedMillis))
            open = false
        }
    }

    OutlinedTextField(
        value = isoDateDisplayLabel(value),
        onValueChange = {},
        label = { Text(label) },
        readOnly = true,
        enabled = enabled,
        modifier = (if (semanticId == null) modifier else modifier.testTag(semanticId))
            .semantics(mergeDescendants = true) { contentDescription = label }
            .fillMaxWidth()
            .clickable(enabled) { pickerState.selectedDateMillis = isoDateToUtcMillis(value); open = true },
        trailingIcon = { TextButton(onClick = { pickerState.selectedDateMillis = isoDateToUtcMillis(value); open = true }, enabled = enabled) { Text("选择") } },
        shape = RoundedCornerShape(12.dp),
    )
    if (open) {
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text("关闭") }
            },
            dismissButton = {
                Row {
                    if (value.isNotEmpty()) TextButton(onClick = { onValueChange(""); open = false }) { Text("清除") }
                    TextButton(onClick = { open = false }) { Text("取消") }
                }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimePickerInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    val parsedHour = value.substringBefore(':').toIntOrNull()?.takeIf { it in 0..23 } ?: 12
    val parsedMinute = value.substringAfter(':', "").toIntOrNull()?.takeIf { it in 0..59 } ?: 0
    val pickerState = rememberTimePickerState(initialHour = parsedHour, initialMinute = parsedMinute, is24Hour = true)
    LaunchedEffect(open, value) {
        if (open) {
            pickerState.hour = parsedHour
            pickerState.minute = parsedMinute
        }
    }
    OutlinedTextField(
        value = value,
        onValueChange = {},
        label = { Text(label) },
        readOnly = true,
        enabled = enabled,
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = label }.fillMaxWidth().clickable(enabled) { open = true },
        trailingIcon = { TextButton(onClick = { open = true }, enabled = enabled) { Text("选择") } },
        shape = RoundedCornerShape(12.dp),
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = { TimePicker(state = pickerState) },
            confirmButton = {
                TextButton(onClick = {
                    onValueChange("${pickerState.hour.toString().padStart(2, '0')}:${pickerState.minute.toString().padStart(2, '0')}")
                    open = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("取消") } },
        )
    }
}

@Composable
internal fun DateTimePickerInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    sizeClass: WindowSizeClass,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val date = value.take(10)
    val time = value.substringAfter('T', "").take(5)
    val dateField: @Composable (Modifier) -> Unit = { fieldModifier ->
        DatePickerInput(
            value = date,
            onValueChange = { selectedDate -> onValueChange("${selectedDate}T$time") },
            label = "日期",
            modifier = fieldModifier,
            enabled = enabled,
        )
    }
    val timeField: @Composable (Modifier) -> Unit = { fieldModifier ->
        TimePickerInput(
            value = time,
            onValueChange = { selectedTime -> onValueChange("${date}T$selectedTime") },
            label = "时间",
            modifier = fieldModifier,
            enabled = enabled,
        )
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (sizeClass == WindowSizeClass.COMPACT) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                dateField(Modifier)
                timeField(Modifier)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                dateField(Modifier.weight(1f))
                timeField(Modifier.weight(1f))
            }
        }
        if (!isValidLocalDateTime(value)) Text("请选择有效的日期和时间。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun ChoicePicker(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    semanticId: String? = null,
) {
    var expanded by remember(label) { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == value }?.second ?: value.ifEmpty { "全部" }
    val trigger: @Composable () -> Unit = {
        TextButton(
            onClick = { expanded = !expanded },
            enabled = enabled,
            modifier = (if (semanticId == null) Modifier else Modifier.testTag(semanticId)).fillMaxWidth(),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(selectedLabel, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
    if (isWebPlatform) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            trigger()
            if (expanded) {
                Card(shape = RoundedCornerShape(16.dp)) {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                            .selectableGroup()
                            .padding(8.dp),
                    ) {
                        options.forEach { (optionValue, optionLabel) ->
                            val isSelected = optionValue == value
                            val selectOption = {
                                expanded = false
                                onSelected(optionValue)
                            }
                            TextButton(
                                onClick = selectOption,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("choice-option-$optionValue")
                                    .semantics(mergeDescendants = true) {
                                        contentDescription = optionLabel
                                        role = Role.RadioButton
                                        selected = isSelected
                                    },
                            ) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = isSelected, onClick = selectOption)
                                    Text(optionLabel, modifier = Modifier.padding(vertical = 4.dp))
                                }
                            }
                        }
                        TextButton(onClick = { expanded = false }) { Text("关闭") }
                    }
                }
            }
        }
    } else {
        androidx.compose.foundation.layout.Box(modifier) {
            trigger()
            androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (optionValue, optionLabel) ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(optionLabel) },
                        onClick = {
                            expanded = false
                            onSelected(optionValue)
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun StateMessage(
    text: String,
    isError: Boolean = false,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    SectionCard(modifier) {
        Text(
            text,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        action?.invoke()
    }
}

@Composable
internal fun InlineError(text: String?) {
    if (text != null) {
        Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun BusyButton(label: String, busy: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled && !busy, modifier = modifier) {
        Text(if (busy) "处理中…" else label)
    }
}

@Composable
internal fun ConfirmationDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    error: String? = null,
    busy: Boolean = false,
    confirmEnabled: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message)
                InlineError(error)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy && confirmEnabled) { Text(if (busy) "处理中…" else confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } },
    )
}

@Composable
internal fun FormActions(
    primaryLabel: String,
    busy: Boolean,
    onPrimary: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    primaryEnabled: Boolean = true,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onCancel, enabled = !busy) { Text("取消") }
        BusyButton(primaryLabel, busy, onPrimary, enabled = primaryEnabled)
    }
}

internal fun userError(cause: Throwable): String = when ((cause as? ApiFailure)?.code) {
    "authentication_required" -> "登录状态已失效，请重新登录。"
    "workspace_forbidden" -> "当前账户无权访问此工作区。"
    "conflict", "projection_version_conflict", "revision_conflict" -> "内容已更新，请重新读取后再试。"
    "invalid_record", "invalid_category", "invalid_workspace_name" -> "请检查填写内容。"
    "relation_impact_required" -> "此操作会影响已关联的流水，请确认后继续。"
    "invalid_filter" -> "筛选条件有误，请检查日期、金额和选项后重试。"
    "invalid_cursor" -> "加载位置已失效，请重新读取。"
    "investment.updated" -> "投资账本已更新，请重新读取。"
    "valuation.invalid_display_currency" -> "币种暂不可用，请换一个币种。"
    "category.duplicate_name" -> "同级分类名称已存在。"
    "category.depth_limit" -> "分类最多 5 级。"
    "category.invalid_name" -> "请输入分类名称。"
    "category.invalid_description" -> "描述不能超过 500 个字符。"
    "category.has_children" -> "请先处理子分类。"
    "category.revision_conflict" -> "分类已更新，请刷新后重试。"
    "import_password_required" -> "请输入账单密码后重试。"
    "import_password_invalid" -> "账单密码不正确。"
    "token_store_unavailable" -> "无法安全保存登录状态，请重试。"
    else -> "暂时无法完成操作，请稍后重试。"
}
