package com.finance.tracker

import com.finance.tracker.core.*
import com.finance.tracker.domain.DomainFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ImportAccountRecoveryTest {
    private val scope = ImportRecoveryScope("https://account.test", "synthetic@test", "workspace")

    @Test fun accountKeysSurviveMainImportCancellationAndRemainScopeBound() = runTest {
        val storage = RecoveryMemoryStorage()
        val accounts = ScopedImportAccountRecovery(scope, storage)
        val main = ScopedImportRecoveryStore(scope, storage)
        accounts.add("first-key"); accounts.add("second-key")
        main.save(ImportRecoveryPointer.Session("a".repeat(43)))
        main.clear()
        assertEquals(setOf("first-key", "second-key"), accounts.keys().toSet())
        for (other in listOf(scope.copy(apiOrigin = "https://other.test"), scope.copy(principal = "other@test"), scope.copy(workspaceId = "other"))) {
            assertTrue(ScopedImportAccountRecovery(other, storage).keys().isEmpty())
        }
        accounts.complete("first-key")
        assertEquals(listOf("second-key"), accounts.keys())
        assertTrue(storage.values.keys.none { scope.principal in it })
        assertTrue(storage.values.values.all { it == "1" })
    }

    @Test fun capacityRejectsNewCommandWithoutDroppingUnresolvedKeys() = runTest {
        val storage = RecoveryMemoryStorage()
        val accounts = ScopedImportAccountRecovery(scope, storage)
        repeat(64) { accounts.add("key-$it") }
        accounts.add("key-0")
        assertEquals("account_recovery_limit", assertFailsWith<DomainFailure> { accounts.add("extra") }.code)
        assertEquals(64, accounts.keys().size)
        accounts.complete("key-0")
        accounts.add("extra")
        assertEquals(64, accounts.keys().size)
        assertTrue("extra" in accounts.keys())
    }

    @Test fun invalidKeysAndInvalidStoredMarkersNeverBecomeMissingOrGetDeleted() = runTest {
        val storage = RecoveryMemoryStorage()
        val accounts = ScopedImportAccountRecovery(scope, storage)
        for (key in listOf("", "../key", "key\nother", "x".repeat(256))) assertFailsWith<DomainFailure> { accounts.add(key) }
        assertTrue(storage.values.isEmpty())
        accounts.add("original")
        val slot = storage.values.keys.single()
        storage.values[slot] = "private account body"
        assertEquals("account_recovery_invalid", assertFailsWith<DomainFailure> { accounts.keys() }.code)
        assertEquals("private account body", storage.values[slot])
    }

    @Test fun concurrentWritersCannotMakeOverflowKeysUnrecoverable() = runTest {
        val storage = RecoveryMemoryStorage()
        val accounts = ScopedImportAccountRecovery(scope, storage)
        accounts.add("seed")
        val prefix = storage.values.keys.single().removeSuffix("seed")
        // 模拟其他标签在容量检查后完成写入，所有已保存操作都必须仍可核实。
        repeat(66) { storage.values[prefix + "racing-$it"] = "1" }
        assertEquals(64, accounts.keys().size)
        assertEquals("account_recovery_limit", assertFailsWith<DomainFailure> { accounts.add("new") }.code)
        val recovered = mutableSetOf<String>()
        while (accounts.keys().isNotEmpty()) {
            val batch = accounts.keys()
            recovered.addAll(batch)
            batch.forEach { accounts.complete(it) }
        }
        assertEquals(67, recovered.size)
        assertTrue(storage.values.isEmpty())
    }

    @Test fun twoInstancesRacingAtCapacityKeepEverySavedKeyReachable() = runTest {
        val memory = RecoveryMemoryStorage()
        var racing = false; var reads = 0
        val bothRead = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val storage = object : ImportRecoveryStorage by memory {
            override suspend fun keys(prefix: String, limit: Int): List<String> {
                val snapshot = memory.keys(prefix, limit)
                if (racing) {
                    if (++reads == 2) bothRead.complete(Unit)
                    release.await()
                }
                return snapshot
            }
        }
        val first = ScopedImportAccountRecovery(scope, storage)
        val second = ScopedImportAccountRecovery(scope, storage)
        repeat(63) { first.add("key-$it") }
        racing = true
        val left = async { first.add("left") }; val right = async { second.add("right") }
        bothRead.await(); release.complete(Unit); left.await(); right.await(); racing = false
        assertEquals(65, memory.values.size)
        val reachable = mutableSetOf<String>()
        while (first.keys().isNotEmpty()) {
            first.keys().forEach { reachable += it; first.complete(it) }
        }
        assertEquals(65, reachable.size)
        assertTrue("left" in reachable && "right" in reachable)
    }

    @Test fun storageFailureDoesNotPretendKeyWasSavedAndCancellationPropagates() = runTest {
        val storage = object : ImportRecoveryStorage {
            override suspend fun read(key: String): String? = throw CancellationException("cancelled")
            override suspend fun write(key: String, value: String) { error("private storage") }
            override suspend fun remove(key: String) { error("private storage") }
            override suspend fun keys(prefix: String, limit: Int): List<String> = emptyList()
        }
        val accounts = ScopedImportAccountRecovery(scope, storage)
        assertEquals("account_recovery_unavailable", assertFailsWith<DomainFailure> { accounts.add("original") }.code)
        assertFailsWith<DomainFailure> { accounts.complete("original") }
        assertFailsWith<CancellationException> { accounts.verify("original") }
    }
}
