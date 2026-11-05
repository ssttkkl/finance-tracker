package com.finance.tracker.core

import androidx.compose.runtime.Composable

internal sealed interface ControlAccessibilityState {
    data class Button(val enabled: Boolean) : ControlAccessibilityState
    data class Radio(val selected: Boolean) : ControlAccessibilityState
}

@Composable
internal expect fun PlatformControlAccessibility(tag: String, state: ControlAccessibilityState)
