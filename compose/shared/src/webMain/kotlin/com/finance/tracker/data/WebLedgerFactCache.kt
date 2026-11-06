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

internal suspend fun createWebLedgerFactCache(scope: LedgerCacheScope, existingOnly: Boolean = false,
    registration: LedgerSessionRegistration? = null): LedgerStorage {
    registration?.requireScope(scope)
    var opened: WebLedgerConnection? = null
    var driver: WebWorkerDriver? = null
    try {
        // Cancelling await does not stop the Worker or metadata CAS Promise.
        withContext(NonCancellable) {
            opened = acquireConnection(scope.apiOrigin, scope.principal, scope.workspaceId).await()
            registration?.beforeOpen()
            checkNotNull(opened).open(existingOnly).await()
        }
        val connection = checkNotNull(opened)
        val sql = WebWorkerDriver(connection.worker).also { driver = it }
        var erasureComplete = false // Accessed only inside the scoped cache's lifecycle mutex.
        suspend fun finish() {
            connection.close().await()
            sql.close() // SQLDelight terminates the Worker; only after final close acknowledgement.
        }
        return ScopedSqlLedgerFactCache.open(scope, sql,
            closeDriver = { withContext(NonCancellable) { connection.closeDatabase().await() } },
            eraseStorage = {
                withContext(NonCancellable) {
                    if (!erasureComplete) {
                        connection.erase().await()
                        registration?.afterErasure()
                        erasureComplete = true
                    }
                    // If final acknowledgement fails, a later retry must not erase a new owner.
                    finish()
                }
            },
            releaseStorage = { withContext(NonCancellable) { finish() } })
    } catch (failure: Throwable) {
        withContext(NonCancellable) {
            try { opened?.close()?.await(); driver?.close() }
            catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
        }
        throw failure
    }
}

internal fun createWebLedgerCacheErasure(scope: LedgerCacheScope,
    directory: LedgerSessionDirectory, ticket: LedgerSessionTicket? = null): LedgerCacheErasure {
    val check = ledgerCacheErasureCheck(scope, directory, ticket)
    return LedgerCacheErasureOwner(
        acquire = { acquireConnection(scope.apiOrigin, scope.principal, scope.workspaceId).await() },
        beforeErasure = check,
        erase = { owned -> owned.erase().await(); directory.forget(scope) },
        release = { it.close().await(); Unit },
    )
}

private external interface WebLedgerConnection : JsAny {
    val worker: Worker
    fun open(existingOnly: Boolean): Promise<JsAny?>
    fun closeDatabase(): Promise<JsAny?>
    fun erase(): Promise<JsAny?>
    fun close(): Promise<JsAny?>
}

private fun acquireConnection(apiOrigin: String, principal: String, workspaceId: String): Promise<WebLedgerConnection> =
    js("import('/ledger-storage/connection.mjs').then(module => module.acquireWebLedgerConnection([apiOrigin, principal, workspaceId]))")
