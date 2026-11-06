package com.finance.tracker

import com.finance.tracker.data.AppleLedgerSessionLocatorStorage
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSUserDefaults
import kotlin.random.Random
import kotlin.test.*

class AppleLedgerSessionLocatorStorageTest {
    @Test fun nonTextDefaultsAreRejectedAndPreservedInsteadOfBeingReplaced() = runTest {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = "ledger-session-corruption-test-${Random.nextLong()}"
        val storage = AppleLedgerSessionLocatorStorage(defaults)
        try {
            for (value in listOf(listOf("synthetic"), mapOf("synthetic" to "value"))) {
                defaults.setObject(value, forKey = key)
                check(defaults.synchronize())
                val before = defaults.objectForKey(key)
                assertFailsWith<IllegalStateException> { storage.read(key) }
                assertFailsWith<IllegalStateException> { storage.compareAndSet(key, null, "replacement") }
                assertEquals(before, defaults.objectForKey(key))
            }
        } finally {
            defaults.removeObjectForKey(key)
            check(defaults.synchronize())
        }
    }
}
