package com.finance.tracker.presentation

enum class AuthFocusControl {
    EMAIL,
    PASSWORD,
    CONFIRM_PASSWORD,
    SUBMIT,
    TOGGLE_MODE,
    BACK,
}

fun nextAuthFocusControl(current: AuthFocusControl?, backwards: Boolean, registering: Boolean = false): AuthFocusControl {
    val controls = if (registering) {
        listOf(AuthFocusControl.BACK, AuthFocusControl.EMAIL, AuthFocusControl.PASSWORD, AuthFocusControl.CONFIRM_PASSWORD, AuthFocusControl.SUBMIT)
    } else {
        listOf(AuthFocusControl.EMAIL, AuthFocusControl.PASSWORD, AuthFocusControl.SUBMIT, AuthFocusControl.TOGGLE_MODE)
    }
    val currentIndex = current?.let(controls::indexOf) ?: if (backwards) 0 else -1
    val nextIndex = if (backwards) {
        (currentIndex - 1 + controls.size) % controls.size
    } else {
        (currentIndex + 1) % controls.size
    }
    return controls[nextIndex]
}
