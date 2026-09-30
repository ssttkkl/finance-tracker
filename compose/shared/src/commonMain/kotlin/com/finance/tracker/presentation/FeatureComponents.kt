package com.finance.tracker.presentation

import com.finance.tracker.core.*
import com.finance.tracker.domain.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.finance.tracker.core.design.GeneratedTokens

@Composable
internal fun FeaturePage(
    title: String,
    semanticId: String,
    modifier: Modifier = Modifier,
    headerActions: (@Composable () -> Unit)? = null,
    headerActionsInline: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 1480.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = GeneratedTokens.Core.Space.Token4, vertical = GeneratedTokens.Core.Space.Token3)
            .testTag(semanticId),
        verticalArrangement = Arrangement.spacedBy(GeneratedTokens.Core.Space.Token4),
    ) {
        if (headerActions != null && headerActionsInline) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.testTag("app-page-title"), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
                headerActions()
            }
        } else {
            Text(title, modifier = Modifier.testTag("app-page-title"), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
            headerActions?.invoke()
        }
        content()
    }
}

@Composable
internal fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    FinanceSurface(modifier = modifier, content = content)
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
    var wasFocused by remember(semanticId) { mutableStateOf(false) }
    val tagged = modifier.onFocusChanged {
        if (it.isFocused) {
            wasFocused = true
        } else if (wasFocused) {
            wasFocused = false
            onBlur?.invoke()
        }
    }
    FinanceTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = tagged,
        semanticId = semanticId,
        singleLine = singleLine,
        enabled = enabled,
        isError = isError,
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
        trailingIcon = { TextButton(onClick = { pickerState.selectedDateMillis = isoDateToUtcMillis(value); open = true }, enabled = enabled) { Text(localizedText("copy_70b208202c")) } },
        shape = MaterialTheme.shapes.small,
    )
    if (open) {
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text(localizedText("copy_6c14bd7f6f")) }
            },
            dismissButton = {
                Row {
                    if (value.isNotEmpty()) TextButton(onClick = { onValueChange(""); open = false }) { Text(localizedText("copy_7b15e5e8e7")) }
                    TextButton(onClick = { open = false }) { Text(localizedText("copy_4d0b4688c7")) }
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
        trailingIcon = { TextButton(onClick = { open = true }, enabled = enabled) { Text(localizedText("copy_70b208202c")) } },
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
                }) { Text(localizedText("copy_f526c89937")) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(localizedText("copy_4d0b4688c7")) } },
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
    onInvalidValue: () -> Unit = {},
) {
    LaunchedEffect(value) {
        if (!isValidLocalDateTime(value)) onInvalidValue()
    }
    val date = value.take(10)
    val time = value.substringAfter('T', "").take(5)
    val dateField: @Composable (Modifier) -> Unit = { fieldModifier ->
        DatePickerInput(
            value = date,
            onValueChange = { selectedDate -> onValueChange("${selectedDate}T$time") },
            label = localizedText("copy_b6fed9af83"),
            modifier = fieldModifier,
            enabled = enabled,
        )
    }
    val timeField: @Composable (Modifier) -> Unit = { fieldModifier ->
        TimePickerInput(
            value = time,
            onValueChange = { selectedTime -> onValueChange("${date}T$selectedTime") },
            label = localizedText("copy_89b4aa6364"),
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
        if (!isValidLocalDateTime(value)) Text(localizedText("copy_d96835c5f4"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
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
    val selectedLabel = options.firstOrNull { it.first == value }?.second ?: value.ifEmpty { localizedText("copy_778fc8f994") }
    val trigger: @Composable () -> Unit = {
        FinanceButton(
            onClick = { expanded = !expanded },
            enabled = enabled,
            variant = FinanceButtonVariant.Secondary,
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
                val menuShape = RoundedCornerShape(6.dp)
                Card(
                    modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, menuShape),
                    shape = menuShape,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
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
                        TextButton(onClick = { expanded = false }) { Text(localizedText("copy_6c14bd7f6f")) }
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
    FinanceButton(onClick = onClick, enabled = enabled, loading = busy, modifier = modifier) {
        Text(if (busy) localizedText("copy_1cac8ac7f5") else label)
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
            TextButton(onClick = onConfirm, enabled = !busy && confirmEnabled) { Text(if (busy) localizedText("copy_1cac8ac7f5") else confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(localizedText("copy_4d0b4688c7")) } },
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
        TextButton(onClick = onCancel, enabled = !busy) { Text(localizedText("copy_4d0b4688c7")) }
        BusyButton(primaryLabel, busy, onPrimary, enabled = primaryEnabled)
    }
}

@Composable
internal fun userError(cause: Throwable): String = localizedText(userErrorResourceKey(cause))

internal fun userErrorResourceKey(cause: Throwable): String {
    val failure = cause as? DomainFailure ?: return "copy_382ef5a830"
    if (failure.category != FailureCategory.RECOVERABLE) return "copy_382ef5a830"
    return when (failure.code) {
        "authentication_required" -> "copy_8e38717a57"
        "workspace_forbidden" -> "copy_dd9a4bdee0"
        "network_unavailable" -> "copy_e0a1c9fd69"
        "conflict", "projection_version_conflict", "revision_conflict" -> "copy_e6bcabc334"
        "invalid_record", "invalid_category", "invalid_workspace_name" -> "copy_245533bbc7"
        "relation_impact_required" -> "copy_158b593262"
        "invalid_filter" -> "copy_a3181b5117"
        "invalid_cursor" -> "copy_c70b07dcbf"
        "investment.updated" -> "copy_73bb0a253d"
        "valuation.invalid_display_currency" -> "copy_466b674272"
        "category.duplicate_name" -> "copy_5449de9c15"
        "category.depth_limit" -> "copy_63d3cf7b13"
        "category.invalid_name" -> "copy_0c1ca213b2"
        "category.invalid_description" -> "copy_4db735902d"
        "category.has_children" -> "copy_f4bdccd114"
        "category.revision_conflict" -> "copy_0386a8ce78"
        "import_password_required" -> "copy_0b292384b1"
        "import_password_invalid" -> "copy_40830925d6"
        "token_store_unavailable" -> "copy_74726f9a95"
        else -> "copy_e6a62f3e45"
    }
}
