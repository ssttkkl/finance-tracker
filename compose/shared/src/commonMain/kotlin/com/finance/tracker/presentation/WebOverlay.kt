package com.finance.tracker.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier

internal class WebOverlayEntry(val key: Any, val content: @Composable () -> Unit)

internal class WebOverlayController {
    val entries = mutableStateListOf<WebOverlayEntry>()
    fun add(entry: WebOverlayEntry) { entries += entry }
    fun remove(entry: WebOverlayEntry) { entries -= entry }
}

internal val LocalWebOverlayController = compositionLocalOf<WebOverlayController?> { null }

/** 把 Web 弹层放到应用根节点，避免导入/管理页面的约束裁剪。 */
@Composable
internal fun WebRootOverlay(content: @Composable () -> Unit) {
    val controller = LocalWebOverlayController.current ?: return
    val key = remember { Any() }
    val latest = rememberUpdatedState(content)
    DisposableEffect(controller, key) {
        val entry = WebOverlayEntry(key) { latest.value() }
        controller.add(entry)
        onDispose { controller.remove(entry) }
    }
}

@Composable
internal fun WebOverlayHost(controller: WebOverlayController, content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalWebOverlayController provides controller) {
        Box(Modifier.fillMaxSize()) {
            content()
            for (entry in controller.entries) {
                key(entry.key) { entry.content() }
            }
        }
    }
}
