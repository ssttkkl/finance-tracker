package com.finance.tracker

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.finance.tracker.data.*
import com.finance.tracker.domain.LedgerCacheScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.io.File
import kotlin.test.*

@RunWith(AndroidJUnit4::class)
class AndroidLedgerSessionDirectoryTest {
    @Test fun actualPreferencesPreserveReferencesAcrossIndependentDirectoryInstances() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "ledger-locator-test-${UUID.randomUUID()}"
        val origin = "https://synthetic.example.test"
        val scope = LedgerCacheScope(origin, ledgerOfflinePrincipal("synthetic@example.test"), "workspace")
        try {
            val directory = LedgerSessionDirectory(AndroidLedgerSessionLocatorStorage(context, name))
            val ticket = directory.begin(origin)
            directory.register(ticket, scope)
            assertNull(directory.current(ticket))
            directory.publish(ticket, scope)
            val persisted = File(context.applicationInfo.dataDir, "shared_prefs/$name.xml").readText()
            assertTrue(persisted.contains("workspace"))
            assertFalse(persisted.contains("synthetic@example.test"))
            val reopened = LedgerSessionDirectory(AndroidLedgerSessionLocatorStorage(context, name))
            val next = reopened.begin(origin)
            assertEquals(scope, reopened.current(next))
            assertEquals(listOf(scope), reopened.scopes(origin))
            reopened.forget(scope)
            assertNull(directory.current(next))
            assertTrue(directory.scopes(origin).isEmpty())
        } finally { assertTrue(context.getSharedPreferences(name, 0).edit().clear().commit()) }
    }

    @Test fun actualIndependentPreferencesWritersRespectCompareAndReplace() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "ledger-locator-test-${UUID.randomUUID()}"
        val left = AndroidLedgerSessionLocatorStorage(context, name)
        val right = AndroidLedgerSessionLocatorStorage(context, name)
        val key = "ledger-session-current-v1:" + "a".repeat(64)
        try {
            assertTrue(left.compareAndSet(key, null, "original"))
            val accepted = listOf(async { left.compareAndSet(key, "original", "left") },
                async { right.compareAndSet(key, "original", "right") }).awaitAll()
            assertEquals(1, accepted.count { it })
            assertTrue(checkNotNull(left.read(key)) in listOf("left", "right"))
            assertFalse(right.compareAndSet(key, "original", "late"))
        } finally { assertTrue(context.getSharedPreferences(name, 0).edit().clear().commit()) }
    }
}
