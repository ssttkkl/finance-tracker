@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.finance.tracker

import com.finance.tracker.core.*
import com.finance.tracker.domain.DomainFailure
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class BrowserImportRecoveryStorageTest {
    @Test fun actualBrowserStorageRestoresReferencesAndMapsDomExceptions() = runTest {
        val scope = ImportRecoveryScope("https://recovery-qa.test", "synthetic@test", "test-workspace")
        val storage = createPlatformImportRecoveryStorage()
        val store = ScopedImportRecoveryStore(scope, storage)
        try {
            store.save(ImportRecoveryPointer.Receipt("synthetic-receipt"))
            assertEquals(ImportRecoveryPointer.Receipt("synthetic-receipt"), ScopedImportRecoveryStore(scope, storage).load())
            for (operation in listOf("getItem", "setItem", "removeItem")) {
                injectStorageFailure(operation)
                try {
                    val failure = assertFailsWith<DomainFailure> {
                        when (operation) {
                            "getItem" -> store.load()
                            "setItem" -> store.save(ImportRecoveryPointer.Receipt("other-receipt"))
                            else -> store.clear()
                        }
                    }
                    assertEquals("import_recovery_unavailable", failure.code)
                    assertNull(failure.cause)
                } finally { restoreStorageMethod() }
            }
            assertEquals(ImportRecoveryPointer.Receipt("synthetic-receipt"), store.load())
        } finally { restoreStorageMethod(); store.clear() }
    }
}

private fun injectStorageFailure(operation: String): Unit = js("""{
    const original = Storage.prototype[operation];
    globalThis.__ftRecoveryStorageRestore = () => { Storage.prototype[operation] = original; };
    Storage.prototype[operation] = function(key, ...args) {
        if (key.startsWith('finance-tracker:cash-import:v1:')) throw new DOMException('synthetic browser error', operation === 'setItem' ? 'QuotaExceededError' : 'SecurityError');
        return original.call(this, key, ...args);
    };
}""")
private fun restoreStorageMethod(): Unit = js("""{ if (globalThis.__ftRecoveryStorageRestore) { globalThis.__ftRecoveryStorageRestore(); delete globalThis.__ftRecoveryStorageRestore; } }""")
