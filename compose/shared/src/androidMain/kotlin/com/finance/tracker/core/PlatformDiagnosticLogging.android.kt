package com.finance.tracker.core

import android.content.Context
import android.util.Log

private const val DIAGNOSTIC_FILE = "finance-tracker-diagnostics.log"

actual fun createPlatformDiagnosticLogStore(): PlatformDiagnosticLogStore = object : PlatformDiagnosticLogStore {
    override fun writeDeveloperLog(line: String) {
        Log.e("FinanceTracker", line)
    }

    override fun readLocalLog(): String? = runCatching {
        AndroidTokenStoreContext.applicationContext?.openFileInput(DIAGNOSTIC_FILE)?.bufferedReader()?.use { it.readText() }
    }.getOrNull()

    override fun writeLocalLog(contents: String) {
        val context = AndroidTokenStoreContext.applicationContext ?: error("platform_log_storage_unavailable")
        context.openFileOutput(DIAGNOSTIC_FILE, Context.MODE_PRIVATE).bufferedWriter().use { it.write(contents) }
    }
}

actual fun diagnosticEpochMillis(): Long = System.currentTimeMillis()
