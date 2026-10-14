package com.finance.tracker.presentation

import com.finance.tracker.core.WindowSizeClass
import com.finance.tracker.core.PlatformBackHandler
import com.finance.tracker.core.LocalFinanceColorRoles
import com.finance.tracker.core.design.FinanceTokens
import com.finance.tracker.resources.Res
import com.finance.tracker.resources.ic_arrow_back
import com.finance.tracker.resources.ic_close

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import org.jetbrains.compose.resources.painterResource

internal enum class SecondaryPageLayout {
    FULL_SCREEN,
    MODAL,
}

internal fun secondaryPageLayoutFor(sizeClass: WindowSizeClass): SecondaryPageLayout =
    if (sizeClass == WindowSizeClass.COMPACT) SecondaryPageLayout.FULL_SCREEN else SecondaryPageLayout.MODAL

internal data class SecondaryPageOverlayMenu(
    val key: String,
    val anchor: LayoutCoordinates,
    val options: List<Pair<String, String>>,
    val selectedValue: String,
    val onSelected: (String) -> Unit,
    val triggerFocusRequester: FocusRequester,
    val optionFocusRequesters: List<FocusRequester>,
    val focusedOptionIndex: Int,
    val anchorRevision: Int = 0,
)

internal class SecondaryPageOverlayMenuHost {
    var rootCoordinates by mutableStateOf<LayoutCoordinates?>(null)
    var menu by mutableStateOf<SecondaryPageOverlayMenu?>(null)
        private set

    fun isShowing(key: String): Boolean = menu?.key == key

    fun show(menu: SecondaryPageOverlayMenu) {
        this.menu = menu
    }

    fun updateAnchor(key: String, anchor: LayoutCoordinates) {
        val current = menu?.takeIf { it.key == key } ?: return
        menu = current.copy(anchor = anchor, anchorRevision = current.anchorRevision + 1)
    }

    fun updateFocusedOption(key: String, index: Int) {
        val current = menu?.takeIf { it.key == key } ?: return
        if (current.focusedOptionIndex != index) menu = current.copy(focusedOptionIndex = index)
    }

    fun moveFocus(key: String, delta: Int) {
        val current = menu?.takeIf { it.key == key } ?: return
        if (current.options.isEmpty()) return
        val nextIndex = (current.focusedOptionIndex + delta).coerceIn(0, current.options.lastIndex)
        current.optionFocusRequesters.getOrNull(nextIndex)?.requestFocus()
    }

    fun selectFocused(key: String) {
        val current = menu?.takeIf { it.key == key } ?: return
        current.options.getOrNull(current.focusedOptionIndex)?.let { select(it.first) }
    }

    fun dismiss(restoreFocus: Boolean = true) {
        val current = menu ?: return
        menu = null
        if (restoreFocus) current.triggerFocusRequester.requestFocus()
    }

    fun select(value: String) {
        val current = menu ?: return
        menu = null
        current.onSelected(value)
        current.triggerFocusRequester.requestFocus()
    }
}

internal val LocalSecondaryPageOverlayMenuHost = staticCompositionLocalOf<SecondaryPageOverlayMenuHost?> { null }

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
            SecondaryPageOverlayHost(Modifier.fillMaxSize()) {
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
        }
    } else {
        SecondaryPageOverlayHost(Modifier.fillMaxSize()) {
            SecondaryPageFullScreen(title, sizeClass, onClose, modifier, content, footer)
        }
    }
}

@Composable
private fun SecondaryPageOverlayHost(modifier: Modifier, content: @Composable () -> Unit) {
    val host = remember { SecondaryPageOverlayMenuHost() }
    CompositionLocalProvider(LocalSecondaryPageOverlayMenuHost provides host) {
        Box(modifier.onGloballyPositioned { host.rootCoordinates = it }) {
            content()
            SecondaryPageOverlayMenuLayer(host, Modifier.matchParentSize())
        }
    }
}

@Composable
private fun SecondaryPageOverlayMenuLayer(host: SecondaryPageOverlayMenuHost, modifier: Modifier = Modifier) {
    val menu = host.menu
    val rootCoordinates = host.rootCoordinates
    val density = LocalDensity.current
    LaunchedEffect(menu?.key) {
        menu?.let { currentMenu ->
            withFrameNanos { }
            currentMenu.optionFocusRequesters.getOrNull(currentMenu.focusedOptionIndex)?.requestFocus()
        }
    }
    if (menu == null || rootCoordinates == null || !menu.anchor.isAttached) return

    BoxWithConstraints(modifier.zIndex(10f)) {
        val anchorPosition = rootCoordinates.localPositionOf(menu.anchor, Offset.Zero)
        val anchorX = with(density) { anchorPosition.x.toDp() }
        val anchorY = with(density) { anchorPosition.y.toDp() }
        val anchorWidth = with(density) { menu.anchor.size.width.toDp() }
        val anchorHeight = with(density) { menu.anchor.size.height.toDp() }
        val margin = 12.dp
        val gap = 4.dp
        val availableWidth = (maxWidth - margin * 2).coerceAtLeast(160.dp)
        val menuWidth = anchorWidth.coerceAtLeast(260.dp).coerceAtMost(availableWidth)
        val menuMaxHeight = (maxHeight - margin * 2).coerceAtLeast(120.dp).coerceAtMost(420.dp)
        val estimatedHeight = (menu.options.size * 48 + 16).dp.coerceAtMost(menuMaxHeight)
        val belowSpace = maxHeight - (anchorY + anchorHeight + gap)
        val aboveSpace = anchorY - gap - margin
        val placeBelow = belowSpace >= estimatedHeight || belowSpace >= aboveSpace
        val maxMenuY = (maxHeight - estimatedHeight - margin).coerceAtLeast(margin)
        val menuY = if (placeBelow) {
            (anchorY + anchorHeight + gap).coerceIn(margin, maxMenuY)
        } else {
            (anchorY - estimatedHeight - gap).coerceIn(margin, maxMenuY)
        }
        val menuX = anchorX.coerceIn(margin, (maxWidth - menuWidth - margin).coerceAtLeast(margin))

        Box(
            Modifier.fillMaxSize().pointerInput(menu.key) {
                detectTapGestures(onTap = { host.dismiss() })
            },
        )
        Card(
            modifier = Modifier
                .offset(menuX, menuY)
                .width(menuWidth)
                .heightIn(max = menuMaxHeight)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                .testTag("choice-overlay-menu")
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        host.dismiss()
                        true
                    } else false
                },
            shape = RoundedCornerShape(10.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                Modifier
                    .heightIn(max = menuMaxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp),
            ) {
                menu.options.forEachIndexed { index, (optionValue, optionLabel) ->
                    val isSelected = optionValue == menu.selectedValue
                    TextButton(
                        onClick = { host.select(optionValue) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(menu.optionFocusRequesters[index])
                            .focusable()
                            .onFocusChanged { if (it.isFocused) host.updateFocusedOption(menu.key, index) }
                            .testTag("choice-option-$optionValue")
                            .semantics(mergeDescendants = true) {
                                contentDescription = optionLabel
                                role = Role.Button
                                selected = isSelected
                            },
                    ) {
                        Text(
                            optionLabel,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
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
                PageNavigationGlyph(isClose = false, color = colors.foreground)
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
                PageNavigationGlyph(isClose = true, color = colors.foreground)
            }
        }
    }
}

@Composable
private fun PageNavigationGlyph(isClose: Boolean, color: Color) {
    Icon(
        painter = painterResource(if (isClose) Res.drawable.ic_close else Res.drawable.ic_arrow_back),
        contentDescription = null,
        modifier = Modifier.size(20.dp),
        tint = color,
    )
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
