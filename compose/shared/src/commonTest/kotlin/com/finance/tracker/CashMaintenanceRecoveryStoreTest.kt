package com.finance.tracker

import com.finance.tracker.core.*
import com.finance.tracker.domain.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CashMaintenanceRecoveryStoreTest {
    @Test fun storesOnlyOriginalCommandAndIsolatesOriginUserWorkspaceAndImport() = runTest {
        val storage = MaintenanceStorage()
        val scope = ImportRecoveryScope("https://api.example.test", "user@example.test", "workspace-one")
        val store = ScopedCashMaintenanceRecoveryStore(scope, storage)
        val command = ImportFrozenCommand("mp_" + "a".repeat(43) + "." + "b".repeat(16), 3, "original")
        val pointer = CashMaintenanceRecoveryPointer("11", command)
        store.save(pointer)
        assertEquals(pointer, store.load())
        assertEquals(1, storage.values.size)
        assertFalse(storage.values.values.single().contains("note"))
        for (other in listOf(scope.copy(apiOrigin="https://other.example.test"), scope.copy(principal="other@example.test"), scope.copy(workspaceId="workspace-two"))) {
            assertNull(ScopedCashMaintenanceRecoveryStore(other, storage).load())
        }
        assertNull(ScopedImportRecoveryStore(scope, storage).load())
        store.clear(); assertNull(store.load())
    }
    @Test fun corruptOrOversizedPointersFailClosed() = runTest {
        val storage = MaintenanceStorage()
        val store = ScopedCashMaintenanceRecoveryStore(ImportRecoveryScope("https://api.example.test", "user", "workspace"), storage)
        store.save(CashMaintenanceRecoveryPointer("11", ImportFrozenCommand("mp_"+"a".repeat(43)+"."+"b".repeat(16), 1, "key")))
        val key = storage.values.keys.single()
        for (text in listOf("x".repeat(2049), "{}", "{\"version\":99}", "{\"version\":1,\"note\":\"private\"}")) {
            storage.values[key] = text
            assertFailsWith<DomainFailure> { store.load() }
        }
    }
}
private class MaintenanceStorage : ImportRecoveryStorage {
    val values = mutableMapOf<String,String>()
    override suspend fun read(key: String) = values[key]
    override suspend fun write(key: String, value: String) { values[key] = value }
    override suspend fun remove(key: String) { values.remove(key) }
}
