package com.finance.tracker.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString

@Composable
@Suppress("DEPRECATION")
internal actual fun rememberAssistantReplyClipboard(): suspend (String) -> Unit {
    val clipboard = LocalClipboardManager.current
    return { clipboard.setText(AnnotatedString(it)) }
}
