@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.finance.tracker.data

import app.cash.sqldelight.driver.worker.WebWorkerDriver
import com.finance.tracker.domain.LedgerCacheScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.await
import kotlinx.coroutines.withContext
import org.w3c.dom.Worker
import kotlin.js.JsAny
import kotlin.js.Promise

internal suspend fun createWebLedgerFactCache(scope: LedgerCacheScope): LedgerStorage {
    var opened: WebLedgerConnection? = null
    try {
        // Retain the opened resource even if the caller cancels while its Promise is pending.
        withContext(NonCancellable) { opened = openConnection(scope.apiOrigin, scope.principal, scope.workspaceId).await() }
        val connection = checkNotNull(opened)
        val driver = WebWorkerDriver(connection.worker)
        return ScopedSqlLedgerFactCache.open(scope, driver,
            closeDriver = { connection.close().await(); driver.close() },
            eraseStorage = { eraseStorage(scope.apiOrigin, scope.principal, scope.workspaceId).await() })
    } catch (failure: Throwable) {
        withContext(NonCancellable) {
            try { opened?.close()?.await() }
            catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
        }
        throw failure
    }
}

private external interface WebLedgerConnection : JsAny {
    val worker: Worker
    fun close(): Promise<JsAny?>
}

private fun openConnection(apiOrigin: String, principal: String, workspaceId: String): Promise<WebLedgerConnection> =
    js("import('/ledger-storage/connection.mjs').then(module => module.openWebLedgerConnection([apiOrigin, principal, workspaceId]))")

private fun eraseStorage(apiOrigin: String, principal: String, workspaceId: String): Promise<JsAny?> =
    js("import('/ledger-storage/connection.mjs').then(module => module.eraseWebLedgerStorage([apiOrigin, principal, workspaceId]))")
