package com.finance.tracker.app

import com.finance.tracker.app.App

import androidx.compose.ui.window.ComposeUIViewController

fun isComposeDemoModeEnabled(): Boolean = isComposeDemoModeBuildEnabled()

fun MainViewController(demoMode: Boolean = false) =
    ComposeUIViewController { App(demoMode = demoMode) }
