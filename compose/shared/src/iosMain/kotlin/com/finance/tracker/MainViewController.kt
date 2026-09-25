package com.finance.tracker

import androidx.compose.ui.window.ComposeUIViewController

fun MainViewController(apiOrigin: String = getConfiguredApiOrigin()) = ComposeUIViewController { App(apiOrigin) }
