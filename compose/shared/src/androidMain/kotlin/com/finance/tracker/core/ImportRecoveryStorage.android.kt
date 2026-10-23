package com.finance.tracker.core

import android.content.Context

actual fun createPlatformImportRecoveryStorage(): ImportRecoveryStorage = object : ImportRecoveryStorage {
    private fun preferences() = (AndroidTokenStoreContext.applicationContext ?: error("import_recovery_unavailable"))
        .getSharedPreferences("finance-tracker-import-recovery", Context.MODE_PRIVATE)
    override suspend fun read(key: String): String? = preferences().getString(key, null)
    override suspend fun write(key: String, value: String) {
        check(preferences().edit().putString(key, value).commit()) { "import_recovery_unavailable" }
    }
    override suspend fun remove(key: String) {
        check(preferences().edit().remove(key).commit()) { "import_recovery_unavailable" }
    }
}
