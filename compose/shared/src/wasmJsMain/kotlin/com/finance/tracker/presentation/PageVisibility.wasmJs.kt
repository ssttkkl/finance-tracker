package com.finance.tracker.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import org.w3c.dom.events.Event

@Composable
internal actual fun rememberPageVisible(): Boolean {
    var visible by remember { mutableStateOf(pageVisible()) }
    DisposableEffect(Unit) {
        val listener: (Event) -> Unit = { visible = pageVisible() }
        document.addEventListener("visibilitychange", listener)
        onDispose { document.removeEventListener("visibilitychange", listener) }
    }
    return visible
}

private fun pageVisible(): Boolean = js("document.visibilityState === 'visible'")
