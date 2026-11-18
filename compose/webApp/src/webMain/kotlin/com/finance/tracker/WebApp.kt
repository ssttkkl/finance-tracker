package com.finance.tracker

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.window.ComposeViewport
import com.finance.tracker.app.App
import com.finance.tracker.app.isComposeDemoModeBuildEnabled

@OptIn(ExperimentalComposeUiApi::class)
internal fun startWebApp(onFirstFrame: () -> Unit) {
    var firstFramePending = true
    ComposeViewport(viewportContainerId = "compose-viewport") {
        Box(Modifier.fillMaxSize().drawWithContent {
            drawContent()
            if (firstFramePending) {
                firstFramePending = false
                onFirstFrame()
            }
        }) {
            App(demoMode = isComposeDemoModeBuildEnabled())
        }
    }
}
