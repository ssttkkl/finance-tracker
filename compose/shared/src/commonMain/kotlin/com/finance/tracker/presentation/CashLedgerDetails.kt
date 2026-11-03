package com.finance.tracker.presentation

import com.finance.tracker.core.*
import com.finance.tracker.domain.*
import com.finance.tracker.resources.Res
import com.finance.tracker.resources.ic_close

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.jetbrains.compose.resources.painterResource
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
internal fun CashRecordModalPanel(
    sizeClass: WindowSizeClass,
    title: String,
    compactFullScreen: Boolean,
    semanticStateKey: Any? = null,
    onDismiss: () -> Unit,
    onBack: () -> Unit,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val compact = sizeClass == WindowSizeClass.COMPACT
    val fullScreen = compact && compactFullScreen
    PlatformModalEscapeHandler(enabled = isWebPlatform, onEscape = onBack)
    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !isWebPlatform,
            dismissOnClickOutside = !fullScreen,
        ),
    ) {
        key(sizeClass, title, semanticStateKey) {
            CashRecordModalOverlay(
                sizeClass = sizeClass,
                title = title,
                onDismiss = onDismiss,
                onBack = onBack,
                compactFullScreen = fullScreen,
                footer = footer,
                content = content,
            )
        }
    }
}

@Composable
private fun CashRecordModalOverlay(
    sizeClass: WindowSizeClass,
    title: String,
    onDismiss: () -> Unit,
    onBack: () -> Unit,
    compactFullScreen: Boolean,
    footer: (@Composable () -> Unit)?,
    content: @Composable () -> Unit,
) {
    val compact = sizeClass == WindowSizeClass.COMPACT
    val fullScreen = compact && compactFullScreen
    val sideSheet = !compact
    val horizontalMargin = when (sizeClass) {
        WindowSizeClass.COMPACT -> if (fullScreen) 0.dp else 16.dp
        WindowSizeClass.REGULAR -> 16.dp
        WindowSizeClass.WIDE -> 56.dp
    }
    val layoutDirection = LocalLayoutDirection.current
    val endDirection = if (layoutDirection == LayoutDirection.Ltr) 1f else -1f
    val dragThresholdPx = with(LocalDensity.current) { 120.dp.toPx() }
    var sideSheetDragOffset by remember(sizeClass, title) { mutableFloatStateOf(0f) }
    var sideSheetWidthPx by remember(sizeClass, title) { mutableFloatStateOf(0f) }
    val animationScope = rememberCoroutineScope()
    val sideSheetDragModifier = Modifier.pointerInput(sizeClass, title, layoutDirection) {
        detectHorizontalDragGestures(
            onHorizontalDrag = { change, dragAmount ->
                change.consume()
                val proposedOffset = sideSheetDragOffset + dragAmount
                sideSheetDragOffset = if (proposedOffset * endDirection > 0f) proposedOffset else 0f
            },
            onDragEnd = {
                val currentOffset = sideSheetDragOffset
                if (currentOffset * endDirection >= dragThresholdPx) {
                    animationScope.launch {
                        Animatable(currentOffset).animateTo(
                            targetValue = sideSheetWidthPx * endDirection,
                            animationSpec = tween(durationMillis = 180),
                        ) { sideSheetDragOffset = value }
                        onBack()
                    }
                } else {
                    animationScope.launch {
                        Animatable(currentOffset).animateTo(
                            targetValue = 0f,
                            animationSpec = tween(durationMillis = 180),
                        ) { sideSheetDragOffset = value }
                    }
                }
            },
            onDragCancel = { sideSheetDragOffset = 0f },
        )
    }
    Box(
        modifier = Modifier.fillMaxSize().padding(start = if (compact) 0.dp else horizontalMargin),
        contentAlignment = if (compact) Alignment.Center else Alignment.CenterEnd,
    ) {
        if (!compact) {
            Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null, onClick = onBack))
        }
        Box(
            modifier = Modifier.fillMaxSize().then(if (sideSheet) Modifier.padding(vertical = 16.dp) else Modifier),
            contentAlignment = if (compact) Alignment.Center else Alignment.CenterEnd,
        ) {
            Surface(
                modifier = (if (fullScreen) {
                    Modifier.fillMaxHeight().widthIn(max = 560.dp).fillMaxWidth()
                } else {
                    Modifier.widthIn(max = 560.dp)
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .then(if (sideSheet) Modifier.offset(x = -16.dp) else Modifier)
                        .onSizeChanged { sideSheetWidthPx = it.width.toFloat() }
                        .offset { IntOffset(sideSheetDragOffset.roundToInt(), 0) }
                        .then(sideSheetDragModifier)
                })
                    .testTag(SemanticIds.ledgerRecordPanel)
                    .semantics { paneTitle = title },
                shape = if (fullScreen) RectangleShape else MaterialTheme.shapes.large,
                color = if (fullScreen) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 0.dp,
            ) {
                ModalScaffold(
                    modifier = Modifier.fillMaxWidth()
                        .then(if (fullScreen || !compact) Modifier.fillMaxHeight() else Modifier)
                        .then(if (fullScreen || !compact) Modifier.windowInsetsPadding(WindowInsets.safeDrawing) else Modifier),
                    contentFill = fullScreen || !compact,
                    contentModifier = Modifier.padding(horizontal = 24.dp)
                        .then(if (fullScreen) Modifier.padding(top = 24.dp) else Modifier)
                        .testTag("ledger.record-panel.content"),
                    header = {
                        ModalHeader(
                            variant = if (sideSheet) ModalHeaderVariant.SideSheet else ModalHeaderVariant.FullScreenDialog,
                            onClose = onDismiss,
                            titleSpacing = when {
                                sideSheet -> 12.dp
                                fullScreen -> 8.dp
                                else -> 12.dp
                            },
                            headerTestTag = "ledger.record-panel.header",
                            closeTestTag = "ledger.record-panel.close",
                        ) {
                            Text(
                                title,
                                modifier = Modifier.testTag("ledger.record-panel.title"),
                                style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    footer = footer?.let { footerContent ->
                        {
                            if (sideSheet) {
                                SideSheetActionArea(
                                    content = footerContent,
                                    testTag = "ledger.record-panel.footer",
                                )
                            } else {
                                Box(
                                    Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 24.dp)
                                        .testTag("ledger.record-panel.footer"),
                                    contentAlignment = Alignment.CenterEnd,
                                ) { footerContent() }
                            }
                        }
                    },
                ) { content() }
            }
        }
    }
}

@Composable
internal fun economicTypeLabel(value: String): String = when (value) {
    "expense" -> localizedText("copy_71d6df4130")
    "income" -> localizedText("copy_aaaf7ca11e")
    "internal_transfer" -> localizedText("copy_4dabeefa09")
    else -> value
}

internal fun localDateTimeForInput(): String = "${getLocalDateForInput()}T12:00"
