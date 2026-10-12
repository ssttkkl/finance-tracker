package com.finance.tracker.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.finance.tracker.core.SemanticIds
import com.finance.tracker.core.WindowSizeClass
import com.finance.tracker.core.design.FinanceTokens

@Composable
internal fun AuthResponsiveShell(
    content: @Composable (modifier: Modifier, compact: Boolean, wide: Boolean, viewportHeight: Dp) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().testTag(SemanticIds.authScreen),
    ) {
        val sizeClass = WindowSizeClass.fromWidthDp(maxWidth.value.toInt())
        val compact = sizeClass == WindowSizeClass.COMPACT
        val viewportHeight = maxHeight
        val verticalInset = 16.dp
        Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = verticalInset),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (compact) Modifier else Modifier.heightIn(min = (viewportHeight - verticalInset * 2).coerceAtLeast(0.dp))),
                    contentAlignment = if (compact) Alignment.TopCenter else Alignment.Center,
                ) {
                    content(
                        Modifier.widthIn(max = 1120.dp),
                        compact,
                        sizeClass == WindowSizeClass.WIDE,
                        viewportHeight,
                    )
                }
            }
        }
    }
}

@Composable
internal fun AuthEntryCard(
    compact: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = FinanceTokens.forTheme(androidx.compose.foundation.isSystemInDarkTheme())
    Surface(
        modifier = modifier.widthIn(max = 440.dp).fillMaxWidth().testTag(SemanticIds.authCard),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, colors.fieldBorder.copy(alpha = 0.12f)),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(if (compact) 20.dp else 32.dp),
            content = content,
        )
    }
}
