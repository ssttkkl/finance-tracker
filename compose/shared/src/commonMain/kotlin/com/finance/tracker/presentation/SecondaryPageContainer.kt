package com.finance.tracker.presentation

import com.finance.tracker.core.WindowSizeClass
import com.finance.tracker.core.PlatformBackHandler
import com.finance.tracker.core.LocalFinanceColorRoles
import com.finance.tracker.core.design.FinanceTokens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

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
    val colors = LocalFinanceColorRoles.current
    val layout = secondaryPageLayoutFor(sizeClass)
    var modalPopupWasCreated by remember { mutableStateOf(layout == SecondaryPageLayout.MODAL) }
    LaunchedEffect(layout) {
        if (layout == SecondaryPageLayout.MODAL) modalPopupWasCreated = true
    }

    if (layout == SecondaryPageLayout.FULL_SCREEN) {
        PlatformBackHandler(enabled = true, onBack = onClose)
    }

    if (layout == SecondaryPageLayout.MODAL || modalPopupWasCreated) {
        Popup(
            alignment = Alignment.TopStart,
            onDismissRequest = onClose,
            properties = PopupProperties(
                focusable = true,
                dismissOnBackPress = layout == SecondaryPageLayout.MODAL,
                dismissOnClickOutside = false,
                clippingEnabled = false,
            ),
        ) {
            if (layout == SecondaryPageLayout.FULL_SCREEN) {
                SecondaryPageFullScreen(title, sizeClass, onClose, modifier, content, footer)
            } else {
                BoxWithConstraints(
                    Modifier.fillMaxSize()
                        .background(FinanceTokens.darkColors.overlay.copy(alpha = 0.38f))
                        .testTag("secondary-page-overlay"),
                    contentAlignment = Alignment.Center,
                ) {
                    val shape = RoundedCornerShape(if (sizeClass == WindowSizeClass.REGULAR) 22.dp else 24.dp)
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
                        shadowElevation = 0.dp,
                    ) {
                        Column(Modifier.fillMaxSize()) {
                            SecondaryPageHeader(title, compact = false, sizeClass = sizeClass, onClose = onClose)
                            HorizontalDivider(color = colors.fieldBorder)
                            SecondaryPageBody(Modifier.weight(1f), colors.surface, content)
                            SecondaryPageFooter(colors.surface, sizeClass, footer)
                        }
                    }
                }
            }
        }
    } else {
        SecondaryPageFullScreen(title, sizeClass, onClose, modifier, content, footer)
    }
}

@Composable
private fun SecondaryPageFullScreen(
    title: String,
    sizeClass: WindowSizeClass,
    onClose: () -> Unit,
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
    footer: @Composable () -> Unit,
) {
    val colors = LocalFinanceColorRoles.current
    Column(
        modifier = modifier.fillMaxSize().background(colors.surface).testTag("secondary-page-fullscreen"),
    ) {
        SecondaryPageHeader(title, compact = true, sizeClass = sizeClass, onClose = onClose)
        HorizontalDivider(color = colors.fieldBorder)
        SecondaryPageBody(Modifier.weight(1f), colors.surface, content)
        SecondaryPageFooter(colors.surface, sizeClass, footer)
    }
}

@Composable
private fun SecondaryPageHeader(title: String, compact: Boolean, sizeClass: WindowSizeClass, onClose: () -> Unit) {
    val colors = LocalFinanceColorRoles.current
    val closeLabel = localizedText(if (compact) "copy_855fa817d2" else "copy_6c14bd7f6f")
    val controlBackground = colors.fieldBorder.copy(alpha = 0.45f)
    val initialControlFocus = remember { FocusRequester() }
    var initialControlFocused by remember { mutableStateOf(false) }
    LaunchedEffect(compact) {
        withFrameNanos { }
        initialControlFocus.requestFocus()
    }
    Row(
        modifier = Modifier.fillMaxWidth().height(
            when {
                compact -> 72.dp
                sizeClass == WindowSizeClass.REGULAR -> 78.dp
                else -> 84.dp
            },
        ).padding(
            horizontal = when {
                compact -> 16.dp
                sizeClass == WindowSizeClass.REGULAR -> 24.dp
                else -> 32.dp
            },
        ).testTag("secondary-page-header"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (compact) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(44.dp)
                    .focusRequester(initialControlFocus)
                    .onFocusChanged { initialControlFocused = it.isFocused }
                    .focusable()
                    .background(controlBackground, CircleShape)
                    .then(if (initialControlFocused) Modifier.border(2.dp, colors.accent, CircleShape) else Modifier)
                    .semantics { contentDescription = closeLabel }
                    .testTag("secondary-page-back"),
            ) {
                Text("←", style = MaterialTheme.typography.headlineSmall, fontSize = 24.sp)
            }
        }
        Text(
            title,
            modifier = Modifier.weight(1f).testTag("secondary-page-title"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
            fontSize = if (compact) 22.sp else 28.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (!compact) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(44.dp)
                    .focusRequester(initialControlFocus)
                    .onFocusChanged { initialControlFocused = it.isFocused }
                    .focusable()
                    .background(controlBackground, CircleShape)
                    .then(if (initialControlFocused) Modifier.border(2.dp, colors.accent, CircleShape) else Modifier)
                    .semantics { contentDescription = closeLabel }
                    .testTag("secondary-page-close"),
            ) {
                Text("×", style = MaterialTheme.typography.headlineSmall, fontSize = 26.sp)
            }
        }
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
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

@Composable
private fun SecondaryPageFooter(surface: Color, sizeClass: WindowSizeClass, footer: @Composable () -> Unit) {
    val colors = LocalFinanceColorRoles.current
    Column(Modifier.fillMaxWidth().background(surface)) {
        if (sizeClass == WindowSizeClass.COMPACT) {
            HorizontalDivider(color = colors.fieldBorder, modifier = Modifier.testTag("secondary-page-footer-divider"))
        }
        Box(
            Modifier.fillMaxWidth()
                .padding(
                horizontal = when (sizeClass) {
                    WindowSizeClass.COMPACT -> 16.dp
                    WindowSizeClass.REGULAR -> 24.dp
                    WindowSizeClass.WIDE -> 32.dp
                },
            )
                .padding(
                    top = if (sizeClass == WindowSizeClass.COMPACT) 8.dp else 16.dp,
                    bottom = if (sizeClass == WindowSizeClass.COMPACT) 16.dp else 24.dp,
                )
                .testTag("secondary-page-footer"),
        ) {
            footer()
        }
    }
}
