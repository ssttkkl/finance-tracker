@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.finance.tracker.core

actual fun createPlatformImportRecoveryStorage(): ImportRecoveryStorage = object : ImportRecoveryStorage {
    override suspend fun read(key: String): String? {
        val result = readRecovery(key)
        return when (result.firstOrNull()) {
            'n' -> null
            'v' -> result.substring(1)
            else -> error("import_recovery_unavailable")
        }
    }
    override suspend fun write(key: String, value: String) {
        check(writeRecovery(key, value)) { "import_recovery_unavailable" }
    }
    override suspend fun remove(key: String) {
        check(removeRecovery(key)) { "import_recovery_unavailable" }
    }
}

private fun readRecovery(key: String): String = js("(function() { try { const value = window.localStorage.getItem(key); return value === null ? 'n' : 'v' + value; } catch (_) { return 'e'; } })()")
private fun writeRecovery(key: String, value: String): Boolean = js("(function() { try { window.localStorage.setItem(key, value); return true; } catch (_) { return false; } })()")
private fun removeRecovery(key: String): Boolean = js("(function() { try { window.localStorage.removeItem(key); return true; } catch (_) { return false; } })()")
