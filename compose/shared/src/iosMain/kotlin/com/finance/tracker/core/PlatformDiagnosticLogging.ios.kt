package com.finance.tracker.core

import platform.Foundation.NSLog
import platform.Foundation.NSUserDefaults
import kotlin.time.Clock

private const val DIAGNOSTIC_STORAGE_KEY = "finance-tracker:diagnostics"

actual fun createPlatformDiagnosticLogStore(): PlatformDiagnosticLogStore = object : PlatformDiagnosticLogStore {
    override fun writeDeveloperLog(line: String) {
        NSLog("Finance Tracker diagnostic: $line")
    }

    override fun readLocalLog(): String? = NSUserDefaults.standardUserDefaults.stringForKey(DIAGNOSTIC_STORAGE_KEY)

    override fun writeLocalLog(contents: String) {
        NSUserDefaults.standardUserDefaults.setObject(contents, forKey = DIAGNOSTIC_STORAGE_KEY)
    }
}

actual fun diagnosticEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
