package com.finance.tracker.presentation

import com.finance.tracker.core.*
import com.finance.tracker.domain.*
import com.finance.tracker.resources.Res
import com.finance.tracker.resources.ic_close

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.finance.tracker.core.design.GeneratedTokens
import org.jetbrains.compose.resources.painterResource

internal fun Modifier.accessibleTextFieldLabel(label: String): Modifier =
    semantics { contentDescription = label }

@Composable
internal fun FeaturePage(
    title: String,
    semanticId: String,
    modifier: Modifier = Modifier,
    headerActions: (@Composable () -> Unit)? = null,
    headerActionsInline: Boolean = false,
    showTitle: Boolean = true,
    scrollable: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(
        horizontal = GeneratedTokens.Core.Space.Token4,
        vertical = GeneratedTokens.Core.Space.Token3,
    ),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 1480.dp)
            .let { if (scrollable) it.verticalScroll(rememberScrollState()) else it }
            .padding(contentPadding)
            .testTag(semanticId),
        verticalArrangement = Arrangement.spacedBy(GeneratedTokens.Core.Space.Token4),
    ) {
        if (headerActions != null && headerActionsInline) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (showTitle) Text(title, modifier = Modifier.testTag(SemanticIds.destinationBodyTitle), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
                else Spacer(Modifier.weight(1f))
                headerActions()
            }
        } else {
            if (showTitle) Text(title, modifier = Modifier.testTag(SemanticIds.destinationBodyTitle), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
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
internal fun Modifier.onFocusLost(onFocusLost: () -> Unit): Modifier {
    var wasFocused by remember { mutableStateOf(false) }
    return onFocusChanged { state ->
        if (state.isFocused) {
            wasFocused = true
        } else if (wasFocused) {
            wasFocused = false
            onFocusLost()
        }
    }
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
    inlinePicker: Boolean = false,
    inlinePickerActive: Boolean = false,
    onInlinePickerActiveChange: ((Boolean) -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = isoDateToUtcMillis(value))
    val inlineOpen = inlinePicker && onInlinePickerActiveChange != null && inlinePickerActive
    val closeLabel = localizedText("copy_6c14bd7f6f")
    val setOpen: (Boolean) -> Unit = { next ->
        if (inlinePicker && onInlinePickerActiveChange != null) onInlinePickerActiveChange(next) else open = next
    }

    OutlinedTextField(
        value = isoDateDisplayLabel(value),
        onValueChange = {},
        label = { Text(label) },
        readOnly = true,
        enabled = enabled,
        singleLine = true,
        modifier = (if (semanticId == null) modifier else modifier.testTag(semanticId))
            .semantics(mergeDescendants = true) { contentDescription = label }
            .fillMaxWidth()
            .clickable(enabled) { pickerState.selectedDateMillis = isoDateToUtcMillis(value); setOpen(true) },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (value.isNotEmpty()) {
                    IconButton(
                        onClick = { onValueChange("") },
                        enabled = enabled,
                        modifier = if (semanticId == null) Modifier else Modifier.testTag("$semanticId-clear"),
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_close),
                            contentDescription = localizedText("copy_7b15e5e8e7"),
                        )
                    }
                }
                TextButton(
                    onClick = { pickerState.selectedDateMillis = isoDateToUtcMillis(value); setOpen(true) },
                    enabled = enabled,
                    modifier = semanticId?.let { Modifier.testTag("$it-open") } ?: Modifier,
                ) {
                    Text(localizedText("copy_70b208202c"))
                }
            }
        },
        shape = MaterialTheme.shapes.small,
    )
    if (open || inlineOpen) {
        PlatformModalEscapeHandler(enabled = isWebPlatform, onEscape = { setOpen(false) })
        val confirmSelection = {
            pickerState.selectedDateMillis?.let { onValueChange(isoDateFromUtcMillis(it)) }
            setOpen(false)
        }
        if (inlineOpen) {
            Column(Modifier.fillMaxWidth().testTag(semanticId?.let { "$it-picker" } ?: "date-picker-inline")) {
                DatePicker(state = pickerState)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = { setOpen(false) },
                        modifier = semanticId?.let { Modifier.testTag("$it-cancel") } ?: Modifier,
                    ) { Text(localizedText("copy_4d0b4688c7")) }
                    TextButton(
                        onClick = confirmSelection,
                        enabled = pickerState.selectedDateMillis != null,
                        modifier = semanticId?.let { Modifier.testTag("$it-confirm") } ?: Modifier,
                    ) { Text(localizedText("ledger_filter_done")) }
                }
            }
        } else {
            DatePickerDialog(
                onDismissRequest = { open = false },
                confirmButton = {
                    TextButton(
                        onClick = confirmSelection,
                        enabled = pickerState.selectedDateMillis != null,
                        modifier = semanticId?.let { Modifier.testTag("$it-confirm") } ?: Modifier,
                    ) {
                        Text(localizedText("ledger_filter_done"))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { open = false },
                        modifier = semanticId?.let { Modifier.testTag("$it-cancel") } ?: Modifier,
                    ) { Text(localizedText("copy_4d0b4688c7")) }
                },
            ) {
                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { open = false },
                            modifier = Modifier
                                .semantics { contentDescription = closeLabel }
                                .testTag(semanticId?.let { "$it-close" } ?: "date-picker-close"),
                        ) {
                            Icon(painterResource(Res.drawable.ic_close), contentDescription = null)
                        }
                    }
                    DatePicker(state = pickerState, modifier = semanticId?.let { Modifier.testTag("$it-picker") } ?: Modifier)
                }
            }
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
    inlinePicker: Boolean = false,
    inlinePickerActive: Boolean = false,
    onInlinePickerActiveChange: ((Boolean) -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    val inlineOpen = inlinePicker && onInlinePickerActiveChange != null && inlinePickerActive
    val setOpen: (Boolean) -> Unit = { next ->
        if (inlinePicker && onInlinePickerActiveChange != null) onInlinePickerActiveChange(next) else open = next
    }
    val parsedHour = value.substringBefore(':').toIntOrNull()?.takeIf { it in 0..23 } ?: 12
    val parsedMinute = value.substringAfter(':', "").toIntOrNull()?.takeIf { it in 0..59 } ?: 0
    val pickerState = rememberTimePickerState(initialHour = parsedHour, initialMinute = parsedMinute, is24Hour = true)
    LaunchedEffect(open, inlineOpen, value) {
        if (open || inlineOpen) {
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
        singleLine = true,
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = label }.fillMaxWidth().clickable(enabled) { setOpen(true) },
        trailingIcon = { TextButton(onClick = { setOpen(true) }, enabled = enabled) { Text(localizedText("copy_70b208202c")) } },
        shape = RoundedCornerShape(12.dp),
    )
    if (open || inlineOpen) {
        PlatformModalEscapeHandler(enabled = isWebPlatform, onEscape = { setOpen(false) })
        val confirmSelection = {
            onValueChange("${pickerState.hour.toString().padStart(2, '0')}:${pickerState.minute.toString().padStart(2, '0')}")
            setOpen(false)
        }
        if (inlineOpen) {
            Column(Modifier.fillMaxWidth().testTag("time-picker-inline")) {
                TimePicker(state = pickerState)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { setOpen(false) }) { Text(localizedText("copy_4d0b4688c7")) }
                    TextButton(onClick = confirmSelection) { Text(localizedText("copy_f526c89937")) }
                }
            }
        } else {
            AlertDialog(
                onDismissRequest = { open = false },
                title = { Text(label) },
                text = { TimePicker(state = pickerState) },
                confirmButton = {
                    TextButton(onClick = confirmSelection) { Text(localizedText("copy_f526c89937")) }
                },
                dismissButton = { TextButton(onClick = { open = false }) { Text(localizedText("copy_4d0b4688c7")) } },
            )
        }
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
    var activeInlinePicker by remember { mutableStateOf<String?>(null) }
    val dateField: @Composable (Modifier) -> Unit = { fieldModifier ->
        DatePickerInput(
            value = date,
            onValueChange = { selectedDate -> onValueChange("${selectedDate}T$time") },
            label = localizedText("copy_b6fed9af83"),
            modifier = fieldModifier,
            enabled = enabled,
            inlinePicker = true,
            inlinePickerActive = activeInlinePicker == "date",
            onInlinePickerActiveChange = { activeInlinePicker = if (it) "date" else null },
        )
    }
    val timeField: @Composable (Modifier) -> Unit = { fieldModifier ->
        TimePickerInput(
            value = time,
            onValueChange = { selectedTime -> onValueChange("${date}T$selectedTime") },
            label = localizedText("copy_89b4aa6364"),
            modifier = fieldModifier,
            enabled = enabled,
            inlinePicker = true,
            inlinePickerActive = activeInlinePicker == "time",
            onInlinePickerActiveChange = { activeInlinePicker = if (it) "time" else null },
        )
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (sizeClass == WindowSizeClass.COMPACT || activeInlinePicker != null) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChoicePicker(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    semanticId: String? = null,
    autoScrollMenuIntoView: Boolean = false,
    onMenuExpandedChange: ((Boolean) -> Unit)? = null,
    menuExpanded: Boolean? = null,
    onMenuEscape: (() -> Unit)? = null,
) {
    var internalExpanded by remember(label, semanticId) { mutableStateOf(false) }
    val expanded = menuExpanded ?: internalExpanded
    val menuBringIntoViewRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(expanded, autoScrollMenuIntoView) {
        if (expanded && autoScrollMenuIntoView) menuBringIntoViewRequester.bringIntoView()
    }
    val selectedLabel = options.firstOrNull { it.first == value }?.second ?: value.ifEmpty { localizedText("copy_778fc8f994") }
    val fieldLabel: (@Composable () -> Unit)? = if (label.isBlank()) null else { { Text(label) } }
    val updateExpanded: (Boolean) -> Unit = { nextExpanded ->
        internalExpanded = nextExpanded
        onMenuExpandedChange?.invoke(nextExpanded)
    }
    val dismissMenuFromEscape: () -> Unit = {
        if (expanded) {
            onMenuEscape?.invoke()
            updateExpanded(false)
        }
    }
    PlatformEscapeHandler(enabled = expanded, onEscape = dismissMenuFromEscape)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { updateExpanded(it) },
        modifier = modifier.fillMaxWidth()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && expanded) {
                    dismissMenuFromEscape()
                    true
                } else false
            }
            .then(if (autoScrollMenuIntoView) Modifier.bringIntoViewRequester(menuBringIntoViewRequester) else Modifier),
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = false,
            maxLines = 2,
            label = fieldLabel,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor()
                .fillMaxWidth()
                .then(if (semanticId == null) Modifier else Modifier.testTag(semanticId))
                .then(if (label.isBlank()) Modifier.semantics { contentDescription = selectedLabel } else Modifier),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { updateExpanded(false) },
            modifier = Modifier.testTag("choice-overlay-menu")
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && expanded) {
                        dismissMenuFromEscape()
                        true
                    } else false
                },
        ) {
            options.forEach { (optionValue, optionLabel) ->
                DropdownMenuItem(
                    text = { Text(optionLabel) },
                    onClick = {
                        onSelected(optionValue)
                        updateExpanded(false)
                    },
                    modifier = Modifier.testTag("choice-option-$optionValue"),
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
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
internal fun ConfirmationDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    error: String? = null,
    busy: Boolean = false,
    confirmEnabled: Boolean = true,
    destructive: Boolean = false,
    semanticId: String? = null,
) {
    PlatformModalEscapeHandler(enabled = isWebPlatform, onEscape = onDismiss)
    AlertDialog(
        modifier = semanticId?.let { Modifier.testTag("$it.dialog") } ?: Modifier,
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message)
                InlineError(error)
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = confirmEnabled,
                colors = if (destructive) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.textButtonColors(),
                modifier = semanticId?.let { Modifier.testTag("$it.confirm") } ?: Modifier,
            ) { Text(if (busy) localizedText("copy_1cac8ac7f5") else confirmLabel) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = semanticId?.let { Modifier.testTag("$it.cancel") } ?: Modifier,
            ) { Text(localizedText("copy_4d0b4688c7")) }
        },
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
        TextButton(onClick = onCancel) { Text(localizedText("copy_4d0b4688c7")) }
        Button(onClick = onPrimary, enabled = primaryEnabled && !busy) {
            Text(if (busy) localizedText("copy_1cac8ac7f5") else primaryLabel)
        }
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
