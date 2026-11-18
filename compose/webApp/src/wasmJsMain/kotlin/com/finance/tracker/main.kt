@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.finance.tracker

import kotlin.js.js

fun main() {
    startWebApp(::dismissStartupScreen)
}

private fun dismissStartupScreen(): Unit = js(
    """requestAnimationFrame(() => document.getElementById('startup-screen')?.remove())"""
)
