package com.finance.tracker.core

import platform.Foundation.NSUserDefaults

actual fun createPlatformImportRecoveryStorage(): ImportRecoveryStorage = object : ImportRecoveryStorage {
    override suspend fun keys(prefix: String, limit: Int): List<String> =
        NSUserDefaults.standardUserDefaults.dictionaryRepresentation().keys.filterIsInstance<String>()
            .filter { it.startsWith(prefix) }.take(limit)
    override suspend fun read(key: String): String? = NSUserDefaults.standardUserDefaults.stringForKey(key)
    override suspend fun write(key: String, value: String) {
        NSUserDefaults.standardUserDefaults.setObject(value, forKey = key)
        check(NSUserDefaults.standardUserDefaults.synchronize()) { "import_recovery_unavailable" }
    }
    override suspend fun remove(key: String) {
        NSUserDefaults.standardUserDefaults.removeObjectForKey(key)
        check(NSUserDefaults.standardUserDefaults.synchronize()) { "import_recovery_unavailable" }
    }
}
