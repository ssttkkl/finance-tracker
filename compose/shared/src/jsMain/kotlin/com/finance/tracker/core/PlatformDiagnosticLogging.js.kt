package com.finance.tracker.core

import kotlin.js.js

actual fun createPlatformDiagnosticLogStore(): PlatformDiagnosticLogStore = object : PlatformDiagnosticLogStore {
    override fun writeDeveloperLog(line: String) {
        writeJsDeveloperLog(line)
    }

    override fun readLocalLog(): String? = readJsDiagnosticLog()

    override fun writeLocalLog(contents: String) {
        writeJsDiagnosticLog(contents)
    }
}

actual fun diagnosticEpochMillis(): Long = jsDiagnosticEpochMillis().toLong()

private fun writeJsDeveloperLog(line: String): Unit = js("console.error('[Finance Tracker diagnostic]', line)")

private fun readJsDiagnosticLog(): String? = js("window.localStorage.getItem('finance-tracker:diagnostics')")

private fun writeJsDiagnosticLog(contents: String): Unit = js("window.localStorage.setItem('finance-tracker:diagnostics', contents)")

private fun jsDiagnosticEpochMillis(): String = js("Date.now().toString()")
