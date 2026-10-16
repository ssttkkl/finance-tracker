package com.finance.tracker.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.finance.tracker.core.LocalFinanceColorRoles

@Composable
internal fun FinanceFilterBar(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    FinanceSurface(modifier = modifier) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
internal fun FinanceSurface(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val colors = LocalFinanceColorRoles.current
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
internal fun FinanceAmount(
    value: String,
    modifier: Modifier = Modifier,
    negative: Boolean = value.startsWith("-"),
    textAlign: TextAlign? = null,
) {
    val colors = LocalFinanceColorRoles.current
    Text(
        value,
        modifier = modifier,
        color = if (negative) colors.danger else colors.accent,
        style = LocalTextStyle.current.withTabularNumbers(),
        textAlign = textAlign,
    )
}

internal fun TextStyle.withTabularNumbers(): TextStyle = copy(fontFeatureSettings = "tnum")

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
internal fun RowScope.FinanceTableCell(
    value: String,
    weight: Float,
    header: Boolean = false,
    textAlign: TextAlign = TextAlign.Start,
    modifier: Modifier = Modifier,
) {
    Text(
        text = value,
        modifier = Modifier.weight(weight).padding(horizontal = 8.dp, vertical = 10.dp).then(modifier),
        style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
        color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        textAlign = textAlign,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}
