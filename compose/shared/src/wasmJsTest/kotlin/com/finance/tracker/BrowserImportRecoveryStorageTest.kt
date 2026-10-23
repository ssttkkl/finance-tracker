@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.finance.tracker

import com.finance.tracker.core.*
import com.finance.tracker.domain.DomainFailure
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class BrowserImportRecoveryStorageTest {
    @Test fun accountKeysUseActualBrowserStorageAndEnumerationErrorsStayRecoverable() = runTest {
        val scope = ImportRecoveryScope("https://account-recovery-qa.test", "synthetic@test", "test-workspace")
        val store = ScopedImportAccountRecovery(scope, createPlatformImportRecoveryStorage())
        try {
            store.add("synthetic-account-key")
            assertEquals(listOf("synthetic-account-key"), store.keys())
            for (operation in listOf("key", "length")) {
                injectEnumerationFailure(operation)
                try {
                    assertEquals("account_recovery_unavailable", assertFailsWith<DomainFailure> { store.keys() }.code)
                } finally { restoreStorageMethod() }
            }
            assertEquals(listOf("synthetic-account-key"), store.keys())
        } finally { restoreStorageMethod(); store.complete("synthetic-account-key") }
    }

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

private fun injectEnumerationFailure(operation: String): Unit = js("""{
    const original = Object.getOwnPropertyDescriptor(Storage.prototype, operation);
    globalThis.__ftRecoveryStorageRestore = () => { Object.defineProperty(Storage.prototype, operation, original); };
    const fail = function() { throw new DOMException('synthetic browser error', 'SecurityError'); };
    Object.defineProperty(Storage.prototype, operation, operation === 'length'
        ? { configurable: true, get: fail }
        : { configurable: true, writable: true, value: fail });
}""")
