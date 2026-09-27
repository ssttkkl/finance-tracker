@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.finance.tracker.core

import kotlin.js.js

actual fun createPlatformDiagnosticLogStore(): PlatformDiagnosticLogStore = object : PlatformDiagnosticLogStore {
    override fun writeDeveloperLog(line: String) {
        writeWasmDeveloperLog(line)
    }

    override fun readLocalLog(): String? = readWasmDiagnosticLog()

    override fun writeLocalLog(contents: String) {
        writeWasmDiagnosticLog(contents)
    }
}

actual fun diagnosticEpochMillis(): Long = wasmDiagnosticEpochMillis().toLong()

private fun writeWasmDeveloperLog(line: String): Unit = js("console.error('[Finance Tracker diagnostic]', line)")

private fun readWasmDiagnosticLog(): String? = js("window.localStorage.getItem('finance-tracker:diagnostics')")

private fun writeWasmDiagnosticLog(contents: String): Unit = js("window.localStorage.setItem('finance-tracker:diagnostics', contents)")

private fun wasmDiagnosticEpochMillis(): String = js("Date.now().toString()")
