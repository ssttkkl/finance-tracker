package com.finance.tracker.presentation

import androidx.compose.runtime.Composable

/** True while the app is foregrounded and the page is visible; false for background or hidden tabs. */
@Composable
internal expect fun rememberPageVisible(): Boolean
