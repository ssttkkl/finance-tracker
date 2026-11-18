@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.finance.tracker

import com.finance.tracker.business.domain.sha256Hex
import com.finance.tracker.data.*
import com.finance.tracker.domain.LedgerCacheScope
import kotlinx.coroutines.async
import kotlinx.coroutines.await
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.random.Random
import kotlin.test.*

class WebLedgerSessionDirectoryTest {
    private fun origin() = "https://synthetic-${Random.nextLong().toString().removePrefix("-")}.example.test"
    private fun key(origin: String) = "ledger-session-current-v1:" + sha256Hex(origin.encodeToByteArray())

    @Test fun actualBrowserStorageReopensOnlyThePublishedScopeReference() = runTest {
        val origin = origin()
        val scope = LedgerCacheScope(origin, ledgerOfflinePrincipal("synthetic@example.test"), "workspace")
        try {
            val directory = LedgerSessionDirectory(createPlatformLedgerSessionLocatorStorage())
            val ticket = directory.begin(origin)
            directory.register(ticket, scope)
            assertNull(directory.current(ticket))
            directory.publish(ticket, scope)
            val reopened = LedgerSessionDirectory(createPlatformLedgerSessionLocatorStorage())
            val next = reopened.begin(origin)
            assertEquals(scope, reopened.current(next))
            reopened.forget(scope)
            assertNull(directory.current(next))
            assertTrue(directory.scopes(origin).isEmpty())
        } finally { removeTestRecord(key(origin)) }
    }

    @Test fun actualBrowserStorageSerializesIndependentWriters() = runTest {
        val key = key(origin())
        val left = createPlatformLedgerSessionLocatorStorage()
        val right = createPlatformLedgerSessionLocatorStorage()
        try {
            assertTrue(left.compareAndSet(key, null, "original"))
            val accepted = listOf(async { left.compareAndSet(key, "original", "left") },
                async { right.compareAndSet(key, "original", "right") }).awaitAll()
            assertEquals(1, accepted.count { it })
            assertFalse(right.compareAndSet(key, "original", "late"))
            assertTrue(left.read(key) in listOf("left", "right"))
        } finally { removeTestRecord(key) }
    }

    @Test fun anIndependentBrowsingContextWaitsForTheSameMetadataLock() = runTest {
        assertEquals("blocked-then-updated", probeMetadataLock(key(origin())).await().toString())
    }
}

private fun removeTestRecord(key: String) { js("globalThis.localStorage.removeItem(key)") }
private fun probeMetadataLock(key: String): Promise<JsString> =
    js("import('/ledger-storage/ledger-session-locator-probe.mjs').then(module => module.probeMetadataLock(key))")
