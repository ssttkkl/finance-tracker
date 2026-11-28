@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.finance.tracker.presentation

import androidx.compose.runtime.Composable
import kotlinx.coroutines.await
import kotlin.js.JsAny
import kotlin.js.Promise

@Composable
internal actual fun rememberAssistantReplyClipboard(): suspend (String) -> Unit = { text ->
    writeReply(text).await()
}

private fun writeReply(text: String): Promise<JsAny?> =
    js("navigator.clipboard.writeText(text)")
