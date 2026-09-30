package com.finance.tracker.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import com.composeunstyled.UnstyledButton
import com.composeunstyled.UnstyledTextField
import com.composeunstyled.TextInput
import com.finance.tracker.core.design.FinanceTokens

enum class FinanceButtonVariant { Primary, Secondary, Tertiary, Danger }
enum class FinanceButtonSize { Small, Medium, Large }

@Composable
internal fun FinanceSelect(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    semanticId: String? = null,
) {
    ChoicePicker(
        label = label,
        value = value,
        options = options,
        onSelected = onSelected,
        modifier = modifier,
        enabled = enabled,
        semanticId = semanticId,
    )
}

@Composable
internal fun FinanceFilterBar(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    FinanceSurface(modifier = modifier) {
        Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
internal fun FinanceSurface(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val colors = FinanceTokens.forTheme(isSystemInDarkTheme())
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = modifier.fillMaxWidth()
            .border(1.dp, colors.fieldBorder.copy(alpha = 0.25f), shape)
            .background(colors.surface, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
internal fun FinanceButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: FinanceButtonVariant = FinanceButtonVariant.Primary,
    size: FinanceButtonSize = FinanceButtonSize.Medium,
    enabled: Boolean = true,
    loading: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val colors = FinanceTokens.forTheme(dark)
    var focused by remember { mutableStateOf(false) }
    val background = when (variant) {
        FinanceButtonVariant.Primary -> colors.accent
        FinanceButtonVariant.Secondary -> colors.surface
        FinanceButtonVariant.Tertiary -> Color.Transparent
        FinanceButtonVariant.Danger -> colors.danger
    }
    val foreground = when {
        !enabled -> colors.foreground.copy(alpha = 0.55f)
        variant == FinanceButtonVariant.Danger && dark -> colors.background
        variant == FinanceButtonVariant.Primary || variant == FinanceButtonVariant.Danger -> colors.accentForeground
        else -> colors.foreground
    }
    val shape = RoundedCornerShape(22.dp)
    val border = when {
        focused -> colors.focus
        variant == FinanceButtonVariant.Tertiary -> Color.Transparent
        variant == FinanceButtonVariant.Primary -> colors.accent
        variant == FinanceButtonVariant.Danger -> colors.danger
        else -> colors.fieldBorder.copy(alpha = 0.55f)
    }
    val horizontal = when (size) {
        FinanceButtonSize.Small -> 10.dp
        FinanceButtonSize.Medium -> 14.dp
        FinanceButtonSize.Large -> 18.dp
    }
    UnstyledButton(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier
            .then(if (enabled && !loading) Modifier else Modifier.semantics { disabled() })
            .onFocusChanged { focused = it.isFocused }
            .heightIn(min = 44.dp)
            .border(1.dp, border, shape)
            .background(if (enabled && !loading) background else if (variant == FinanceButtonVariant.Tertiary) Color.Transparent else colors.disabled, shape)
            .padding(horizontal = horizontal, vertical = 10.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides foreground) {
            Box(contentAlignment = Alignment.Center) {
                if (loading) Text(localizedText("copy_1cac8ac7f5"), color = foreground)
                else content()
            }
        }
    }
}

@Composable
internal fun FinanceSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    FinanceButton(onClick = onClick, modifier = modifier, enabled = enabled, variant = FinanceButtonVariant.Secondary, content = content)
}

@Composable
internal fun FinanceTertiaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    FinanceButton(onClick = onClick, modifier = modifier, enabled = enabled, variant = FinanceButtonVariant.Tertiary, content = content)
}

@Composable
internal fun FinanceTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    semanticId: String? = null,
    enabled: Boolean = true,
    isError: Boolean = false,
    singleLine: Boolean = true,
) {
    val state = rememberTextFieldState(value)
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value, focused) {
        if (!focused && state.text.toString() != value) state.edit { replace(0, length, value) }
    }
    snapshotTextChanges(state, onValueChange)
    val colors = FinanceTokens.forTheme(isSystemInDarkTheme())
    val shape = RoundedCornerShape(10.dp)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = colors.foreground)
        Box(
            modifier = (if (semanticId == null) Modifier else Modifier.testTag(semanticId))
                .fillMaxWidth()
                .border(1.dp, if (isError) colors.danger else if (focused) colors.focus else colors.fieldBorder.copy(alpha = 0.55f), shape)
                .background(if (enabled) colors.field else colors.disabled, shape)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            UnstyledTextField(
                state = state,
                enabled = enabled,
                accessibilityLabel = label,
                lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.Default,
                textStyle = TextStyle(color = colors.foreground),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            ) { TextInput(placeholder = { Text(label, color = colors.foreground.copy(alpha = 0.55f)) }) }
        }
    }
}

@Composable
private fun snapshotTextChanges(state: TextFieldState, onValueChange: (String) -> Unit) {
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    androidx.compose.runtime.LaunchedEffect(state) {
        androidx.compose.runtime.snapshotFlow { state.text.toString() }.collect { latestOnValueChange(it) }
    }
}

@Composable
internal fun FinanceAmount(value: String, modifier: Modifier = Modifier, negative: Boolean = value.startsWith("-")) {
    val colors = FinanceTokens.forTheme(isSystemInDarkTheme())
    Text(value, modifier = modifier, color = if (negative) colors.danger else colors.accent)
}

@Composable
internal fun <T> FinanceDataTable(
    rows: List<T>,
    modifier: Modifier = Modifier,
    empty: @Composable () -> Unit,
    row: @Composable (T) -> Unit,
) {
    Column(modifier.fillMaxWidth().widthIn(max = 1480.dp)) {
        if (rows.isEmpty()) empty() else rows.forEach { row(it) }
    }
}

@Composable
internal fun RowScope.FinanceTableCell(value: String, weight: Float, header: Boolean = false) {
    Text(
        text = value,
        modifier = Modifier.weight(weight).padding(horizontal = 8.dp, vertical = 10.dp),
        style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
        color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}
