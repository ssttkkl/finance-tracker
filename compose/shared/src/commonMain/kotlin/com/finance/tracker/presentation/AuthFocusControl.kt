package com.finance.tracker.presentation

enum class AuthFocusControl {
    EMAIL,
    PASSWORD,
    SUBMIT,
    TOGGLE_MODE,
}

fun nextAuthFocusControl(current: AuthFocusControl?, backwards: Boolean): AuthFocusControl {
    val controls = AuthFocusControl.entries
    val currentIndex = current?.let(controls::indexOf) ?: if (backwards) 0 else -1
    val nextIndex = if (backwards) {
        (currentIndex - 1 + controls.size) % controls.size
    } else {
        (currentIndex + 1) % controls.size
    }
    return controls[nextIndex]
}
