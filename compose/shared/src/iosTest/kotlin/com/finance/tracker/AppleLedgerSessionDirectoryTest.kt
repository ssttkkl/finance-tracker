package com.finance.tracker

import com.finance.tracker.business.domain.sha256Hex
import com.finance.tracker.data.*
import com.finance.tracker.domain.LedgerCacheScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSUserDefaults
import kotlin.random.Random
import kotlin.test.*

class AppleLedgerSessionDirectoryTest {
    @Test fun actualDefaultsPreserveReferencesAcrossIndependentInstances() = runTest {
        val origin = "https://synthetic-${Random.nextLong().toString().removePrefix("-")}.example.test"
        val key = "ledger-session-current-v1:" + sha256Hex(origin.encodeToByteArray())
        val scope = LedgerCacheScope(origin, ledgerOfflinePrincipal("synthetic@example.test"), "workspace")
        try {
            val directory = LedgerSessionDirectory(AppleLedgerSessionLocatorStorage())
            val ticket = directory.begin(origin)
            directory.register(ticket, scope)
            assertNull(directory.current(ticket))
            directory.publish(ticket, scope)
            val reopened = LedgerSessionDirectory(AppleLedgerSessionLocatorStorage())
            val next = reopened.begin(origin)
            assertEquals(scope, reopened.current(next))
            reopened.forget(scope)
            assertTrue(directory.scopes(origin).isEmpty())
            assertNull(directory.current(next))
        } finally {
            NSUserDefaults.standardUserDefaults.removeObjectForKey(key)
            check(NSUserDefaults.standardUserDefaults.synchronize())
        }
    }

    @Test fun actualDefaultsWritersOnlyAcceptOneOriginalValue() = runTest {
        val key = "ledger-session-current-v1:" + sha256Hex(Random.nextLong().toString().encodeToByteArray())
        val left = AppleLedgerSessionLocatorStorage()
        val right = AppleLedgerSessionLocatorStorage()
        try {
            assertTrue(left.compareAndSet(key, null, "original"))
            val accepted = listOf(async { left.compareAndSet(key, "original", "left") },
                async { right.compareAndSet(key, "original", "right") }).awaitAll()
            assertEquals(1, accepted.count { it })
            assertFalse(left.compareAndSet(key, "original", "late"))
            assertTrue(left.read(key) in listOf("left", "right"))
        } finally {
            NSUserDefaults.standardUserDefaults.removeObjectForKey(key)
            check(NSUserDefaults.standardUserDefaults.synchronize())
        }
    }
}
