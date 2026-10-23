package com.finance.tracker

import com.finance.tracker.core.*
import com.finance.tracker.domain.DomainFailure
import com.finance.tracker.domain.ImportFrozenCommand
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlin.test.*

class ImportRecoveryStoreTest {
    private val session = "a".repeat(43)
    private val plan = "ip_$session.${"b".repeat(16)}"
    private val scope = ImportRecoveryScope("https://one.test", "person@one.test", "workspace1")

    @Test fun cancellationInAnyStorageOperationIsNeverConvertedToFailure() = runTest {
        val storage = object : ImportRecoveryStorage {
            override suspend fun read(key: String): String? = throw CancellationException("cancelled")
            override suspend fun write(key: String, value: String): Unit = throw CancellationException("cancelled")
            override suspend fun remove(key: String): Unit = throw CancellationException("cancelled")
        }
        val store = ScopedImportRecoveryStore(scope, storage)
        assertFailsWith<CancellationException> { store.load() }
        assertFailsWith<CancellationException> { store.save(ImportRecoveryPointer.Session(session)) }
        assertFailsWith<CancellationException> { store.clear() }
    }

    @Test fun publicPlanIdentifierRoundTripsAndBelongsToTheSameSession() = runTest {
        val storage = RecoveryMemoryStorage()
        val store = ScopedImportRecoveryStore(scope, storage)
        val sessionId = "a".repeat(43)
        val pointer = ImportRecoveryPointer.Submission(sessionId, ImportFrozenCommand("ip_$sessionId.${"b".repeat(16)}", 2, "original"))
        store.save(pointer)
        assertEquals(pointer, store.load())
        assertFailsWith<DomainFailure> { store.save(pointer.copy(sessionId = "c".repeat(43))) }
    }

    @Test fun savesOnlyReferencesAndRestoresFrozenRequestExactly() = runTest {
        val storage = RecoveryMemoryStorage()
        val store = ScopedImportRecoveryStore(scope, storage)
        val pointer = ImportRecoveryPointer.Submission(session, ImportFrozenCommand(plan, 9, "original:key"))
        store.save(pointer)
        assertEquals(pointer, ScopedImportRecoveryStore(scope, storage).load())
        val body = storage.values.values.single()
        assertFalse(body.contains(scope.principal))
        assertFalse(body.contains(scope.workspaceId))
        assertFalse(storage.values.keys.single().contains(scope.principal))
        assertFalse(body.contains("password"))
        assertFalse(body.contains("choices"))
        store.save(ImportRecoveryPointer.Receipt("receipt1"))
        assertEquals(ImportRecoveryPointer.Receipt("receipt1"), store.load())
        assertFalse(storage.values.values.single().contains("original:key"))
        store.clear()
        assertNull(store.load())
    }

    @Test fun serverPrincipalAndWorkspaceEachIsolateRecovery() = runTest {
        val storage = RecoveryMemoryStorage()
        val scopes = listOf(scope, scope.copy(apiOrigin = "https://two.test"), scope.copy(principal = "other@one.test"), scope.copy(workspaceId = "workspace2"))
        scopes.forEachIndexed { index, value -> ScopedImportRecoveryStore(value, storage).save(ImportRecoveryPointer.Session("a".repeat(42) + index)) }
        scopes.forEachIndexed { index, value -> assertEquals(ImportRecoveryPointer.Session("a".repeat(42) + index), ScopedImportRecoveryStore(value, storage).load()) }
        ScopedImportRecoveryStore(scopes[1], storage).clear()
        assertEquals(ImportRecoveryPointer.Session("a".repeat(42) + 0), ScopedImportRecoveryStore(scope, storage).load())
        assertNull(ScopedImportRecoveryStore(scopes[1], storage).load())
    }

    @Test fun malformedOrExtendedPointerNeverSilentlyStartsNewTask() = runTest {
        val storage = RecoveryMemoryStorage()
        val store = ScopedImportRecoveryStore(scope, storage)
        store.save(ImportRecoveryPointer.Session(session))
        val key = storage.values.keys.single()
        for (body in listOf("broken", "{}", "{\"version\":2,\"kind\":\"session\",\"sessionId\":\"session1\"}",
            "{\"version\":1,\"kind\":\"session\",\"sessionId\":\"session1\",\"password\":\"secret\"}",
            "{\"version\":1,\"kind\":\"submission\",\"sessionId\":\"session1\",\"planId\":\"ip_one\",\"revision\":0,\"key\":\"original\"}",
            "{\"version\":1,\"kind\":\"receipt\",\"receiptId\":\"receipt1\",\"sessionId\":\"session1\"}", "x".repeat(4097))) {
            storage.values[key] = body
            assertEquals("import_recovery_invalid", assertFailsWith<DomainFailure> { store.load() }.code)
            assertEquals(body, storage.values[key])
        }
    }

    @Test fun storageFailureIsSafeAndDoesNotReturnMissing() = runTest {
        val storage = object : ImportRecoveryStorage {
            override suspend fun read(key: String): String? = error("private filesystem")
            override suspend fun write(key: String, value: String) { error("private filesystem") }
            override suspend fun remove(key: String) { error("private filesystem") }
        }
        val store = ScopedImportRecoveryStore(scope, storage)
        val failure = assertFailsWith<DomainFailure> { store.load() }
        assertEquals("import_recovery_unavailable", failure.code)
        assertNull(failure.cause)
        assertFailsWith<DomainFailure> { store.save(ImportRecoveryPointer.Session(session)) }
        assertFailsWith<DomainFailure> { store.clear() }
    }

    @Test fun repeatedKeysAndCoercedPrimitivesAreRejected() = runTest {
        val storage = RecoveryMemoryStorage()
        val store = ScopedImportRecoveryStore(scope, storage)
        store.save(ImportRecoveryPointer.Receipt("receipt1"))
        val key = storage.values.keys.single()
        for (body in listOf(
            "{\"version\":1,\"kind\":\"receipt\",\"receiptId\":\"original\",\"receiptId\":\"other\"}",
            "{\"version\":1,\"version\":1,\"kind\":\"receipt\",\"receiptId\":\"receipt1\"}",
            "{\"version\":1,\"kind\":\"session\",\"kind\":\"receipt\",\"receiptId\":\"receipt1\"}",
            "{\"version\":1,\"kind\":\"receipt\",\"receiptId\":\"original\",\"receipt\\u0049d\":\"other\"}",
            "{\"version\":\"1\",\"kind\":\"receipt\",\"receiptId\":\"receipt1\"}",
            "{\"version\":1,\"kind\":\"receipt\",\"receiptId\":123}")) {
            storage.values[key] = body
            assertEquals("import_recovery_invalid", assertFailsWith<DomainFailure> { store.load() }.code)
        }
    }

    @Test fun invalidReferencesAreRejectedBeforeStorage() = runTest {
        val storage = RecoveryMemoryStorage()
        val store = ScopedImportRecoveryStore(scope, storage)
        for (pointer in listOf(ImportRecoveryPointer.Session("../private"), ImportRecoveryPointer.Receipt(""),
            ImportRecoveryPointer.Submission(session, ImportFrozenCommand(plan, -1, "key")),
            ImportRecoveryPointer.Submission(session, ImportFrozenCommand(plan, 1, "x".repeat(256))))) {
            assertFailsWith<DomainFailure> { store.save(pointer) }
        }
        assertTrue(storage.values.isEmpty())
    }
}

internal class RecoveryMemoryStorage : ImportRecoveryStorage {
    val values = mutableMapOf<String, String>()
    override suspend fun read(key: String): String? = values[key]
    override suspend fun write(key: String, value: String) { values[key] = value }
    override suspend fun remove(key: String) { values.remove(key) }
}
