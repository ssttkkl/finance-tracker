package com.finance.tracker

fun main() {
    startWebApp(::dismissStartupScreen)
}

private fun dismissStartupScreen(): Unit = js(
    """requestAnimationFrame(() => {
        const screen = document.getElementById('startup-screen');
        if (screen !== null) screen.remove();
    })"""
)
