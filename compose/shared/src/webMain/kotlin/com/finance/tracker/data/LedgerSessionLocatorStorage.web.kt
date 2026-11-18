@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.finance.tracker.data

import kotlinx.coroutines.await
import kotlin.js.Promise
import kotlin.js.JsString
import kotlin.js.JsBoolean
import kotlin.js.toBoolean

internal actual fun createPlatformLedgerSessionLocatorStorage(): LedgerSessionLocatorStorage = object : LedgerSessionLocatorStorage {
    override suspend fun read(key: String): String? = locatorRead(key).await()?.toString()
    override suspend fun compareAndSet(key: String, expected: String?, replacement: String): Boolean =
        locatorCompareAndSet(key, expected, replacement).await().toBoolean()
}

private fun locatorRead(key: String): Promise<JsString?> =
    js("import('/ledger-storage/session-locator.mjs').then(module => module.readLocator(key))")

private fun locatorCompareAndSet(key: String, expected: String?, replacement: String): Promise<JsBoolean> =
    js("import('/ledger-storage/session-locator.mjs').then(module => module.compareAndSetLocator(key, expected, replacement))")
