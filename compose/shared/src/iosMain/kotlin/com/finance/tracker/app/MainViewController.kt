package com.finance.tracker.app

import com.finance.tracker.core.getConfiguredApiOrigin
import com.finance.tracker.app.App

import androidx.compose.ui.window.ComposeUIViewController

fun MainViewController(apiOrigin: String = getConfiguredApiOrigin()) = ComposeUIViewController { App(apiOrigin) }
