package com.finance.tracker.presentation

import com.finance.tracker.core.WindowSizeClass
import com.finance.tracker.core.PlatformBackHandler
import com.finance.tracker.core.design.FinanceTokens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

internal enum class SecondaryPageLayout {
    FULL_SCREEN,
    MODAL,
}

internal fun secondaryPageLayoutFor(sizeClass: WindowSizeClass): SecondaryPageLayout =
    if (sizeClass == WindowSizeClass.COMPACT) SecondaryPageLayout.FULL_SCREEN else SecondaryPageLayout.MODAL

/** Shared host for page-level secondary destinations; callers supply task content and footer actions. */
@Composable
internal fun SecondaryPageContainer(
    title: String,
    sizeClass: WindowSizeClass,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    footer: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = FinanceTokens.forTheme(androidx.compose.foundation.isSystemInDarkTheme())
    val layout = secondaryPageLayoutFor(sizeClass)

    if (layout == SecondaryPageLayout.FULL_SCREEN) {
        PlatformBackHandler(enabled = true, onBack = onClose)
        Column(
            modifier = modifier.fillMaxSize().background(colors.background).testTag("secondary-page-fullscreen"),
        ) {
            SecondaryPageHeader(title, compact = true, onClose = onClose)
            HorizontalDivider(color = colors.fieldBorder)
            SecondaryPageBody(Modifier.weight(1f), colors.surface, content)
            SecondaryPageFooter(colors.surface, footer)
        }
    } else {
        Dialog(
            onDismissRequest = onClose,
            properties = DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
            ),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val shape = RoundedCornerShape(24.dp)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .fillMaxHeight(0.9f)
                        .border(1.dp, colors.fieldBorder, shape)
                        .testTag("secondary-page-modal"),
                    shape = shape,
                    color = colors.surface,
                    contentColor = colors.foreground,
                    tonalElevation = 0.dp,
                    shadowElevation = 16.dp,
                ) {
                    Column(Modifier.fillMaxSize()) {
                        SecondaryPageHeader(title, compact = false, onClose = onClose)
                        HorizontalDivider(color = colors.fieldBorder)
                        SecondaryPageBody(Modifier.weight(1f), colors.surface, content)
                        SecondaryPageFooter(colors.surface, footer)
                    }
                }
            }
        }
    }
}

@Composable
private fun SecondaryPageHeader(title: String, compact: Boolean, onClose: () -> Unit) {
    val closeLabel = localizedText(if (compact) "copy_855fa817d2" else "copy_6c14bd7f6f")
    Row(
        modifier = Modifier.fillMaxWidth().height(if (compact) 72.dp else 80.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconButton(
            onClick = onClose,
            modifier = Modifier.size(44.dp)
                .semantics { contentDescription = closeLabel }
                .testTag(if (compact) "secondary-page-back" else "secondary-page-close"),
        ) {
            Text(if (compact) "←" else "×", style = MaterialTheme.typography.headlineSmall, fontSize = 28.sp)
        }
        Text(
            title,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SecondaryPageBody(
    modifier: Modifier,
    surface: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth().background(surface)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

@Composable
private fun SecondaryPageFooter(surface: Color, footer: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(surface)) {
        HorizontalDivider()
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            footer()
        }
    }
}
