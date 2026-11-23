package com.finance.tracker.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.finance.tracker.core.isWebPlatform

/**
 * Material 3 AlertDialog on native platforms; an in-tree overlay on Web.
 * Compose Web removes the main canvas when a Dialog is disposed, so Web alerts
 * are rendered in the same composition instead.
 */
@Composable
internal fun FinanceAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    webPopup: Boolean = false,
) {
    if (!isWebPlatform) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            modifier = modifier,
            dismissButton = dismissButton,
            title = title,
            text = text,
        )
        return
    }
    if (webPopup) {
        val windowSize = LocalWindowInfo.current.containerSize
        val density = LocalDensity.current
        Popup(alignment = Alignment.TopStart, offset = IntOffset.Zero, properties = PopupProperties(focusable = false)) {
            Box(Modifier.size(with(density) { windowSize.width.toDp() }, with(density) { windowSize.height.toDp() })
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(interactionSource = null, indication = null, onClick = onDismissRequest),
                contentAlignment = Alignment.Center) {
                Surface(modifier = modifier.widthIn(max = 560.dp).padding(24.dp)
                    .clickable(interactionSource = null, indication = null) {},
                    shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        title?.invoke(); text?.invoke()
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { dismissButton?.invoke(); confirmButton() }
                    }
                }
            }
        }
        return
    }
    WebRootOverlay {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f))
                .clickable(interactionSource = null, indication = null, onClick = onDismissRequest),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = modifier.widthIn(max = 560.dp).padding(24.dp)
                    .clickable(interactionSource = null, indication = null) {},
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
            ) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    title?.invoke()
                    text?.invoke()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        dismissButton?.invoke()
                        confirmButton()
                    }
                }
            }
        }
    }
}
