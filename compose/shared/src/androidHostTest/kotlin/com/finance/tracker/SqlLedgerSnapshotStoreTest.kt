package com.finance.tracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.finance.tracker.business.contracts.*
import com.finance.tracker.data.SqlLedgerSnapshotStore
import com.finance.tracker.domain.LedgerCacheScope
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class SqlLedgerSnapshotStoreTest {
    private val scope = LedgerCacheScope("https://one.test", "person@one.test", "workspace")
    private fun snapshot() = Json.decodeFromJsonElement<LedgerSnapshotDto>(
        Json.parseToJsonElement(File(requireNotNull(System.getProperty("ft.ledgerSnapshotFixture"))).readText())
            .jsonObject.getValue("expected")
    ).copy(workspaceId = scope.workspaceId, ledgerSeq = "9223372036854775807")

    private fun snapshotWithRawNumbers(): LedgerSnapshotDto {
        val rawFields = setOf("currencies", "source_payload", "relation_metadata", "manual_overrides",
            "counterparty_account_attrs", "metadata_json", "coverage", "evidence", "pending_source_payload",
            "pending_fields", "origin", "manual_fields", "standalone_values", "standalone_manual_fields")
        val tokens = Json.parseToJsonElement("""[9007199254740993,1e0,-0,1.2300,1e400,18446744073709551616,true,null,"1e0"]""")
        fun inject(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.mapValues { (key, child) ->
                if (key in rawFields) JsonObject(mapOf("original" to child, "tokens" to tokens)) else inject(child) })
            is JsonArray -> JsonArray(value.map(::inject))
            else -> value
        }
        val expected = Json.parseToJsonElement(File(requireNotNull(System.getProperty("ft.ledgerSnapshotFixture"))).readText())
            .jsonObject.getValue("expected")
        return Json.decodeFromJsonElement<LedgerSnapshotDto>(inject(expected))
            .copy(workspaceId = scope.workspaceId, ledgerSeq = "9223372036854775807")
    }

    @Test fun fullPublicSnapshotSurvivesDatabaseCloseAndReopenWithoutNumericCoercion() = runTest {
        val file = Files.createTempFile("ledger-snapshot-synthetic-", ".db").toFile()
        try {
            val expected = snapshotWithRawNumbers()
            val facts = Json.encodeToJsonElement(snapshot().facts).jsonObject.values.filterIsInstance<JsonArray>()
            assertEquals(15, facts.size); assertTrue(facts.all { it.isNotEmpty() })
            JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
                val store = SqlLedgerSnapshotStore.open(driver)
                assertNull(store.read(scope))
                store.replace(scope, expected)
                assertEquals(expected, store.read(scope))
            }
            JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
                assertEquals(expected, SqlLedgerSnapshotStore.open(driver).read(scope))
            }
        } finally { file.delete() }
    }

    @Test fun scopeCompositeKeyIsExactAndClearingOneScopePreservesOthers() = runTest {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            val store = SqlLedgerSnapshotStore.open(driver)
            val scopes = listOf(scope, scope.copy(apiOrigin = "https://two.test"),
                scope.copy(principal = "other@one.test"), scope.copy(workspaceId = "other-workspace"),
                scope.copy(principal = "'); DROP TABLE ledger_cache_snapshots; --"))
            val snapshots = scopes.mapIndexed { i, s -> snapshot().copy(workspaceId = s.workspaceId, ledgerSeq = "$i") }
            scopes.zip(snapshots).forEach { (s, snap) -> store.replace(s, snap) }
            scopes.zip(snapshots).forEach { (s, snap) -> assertEquals(snap, store.read(s)) }
            store.remove(scope); store.remove(scope)
            assertNull(store.read(scope))
            scopes.drop(1).zip(snapshots.drop(1)).forEach { (s, snap) -> assertEquals(snap, store.read(s)) }
        }
    }

    @Test fun actualSqlFailureKeepsThePreviousCompleteSnapshot() = runTest {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            val store = SqlLedgerSnapshotStore.open(driver)
            val original = snapshot().copy(ledgerSeq = "17")
            store.replace(scope, original)
            driver.execute(null, "CREATE TRIGGER fail_snapshot_update BEFORE UPDATE ON ledger_cache_snapshots BEGIN SELECT RAISE(ABORT, 'synthetic disk failure'); END", 0).await()
            assertFails { store.replace(scope, snapshot()) }
            assertEquals(original, store.read(scope))
        }
    }

    @Test fun mismatchedOrInvalidSnapshotsCannotOverwriteValidFacts() = runTest {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            val store = SqlLedgerSnapshotStore.open(driver)
            val original = snapshot(); store.replace(scope, original)
            assertFailsWith<IllegalArgumentException> { store.replace(scope, original.copy(workspaceId = "foreign")) }
            assertFailsWith<IllegalArgumentException> { store.replace(scope, original.copy(ledgerSeq = "-1")) }
            assertEquals(original, store.read(scope))
        }
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @Test fun invalidRawNumbersCannotInjectJsonOrDestroyThePreviousSnapshot() = runTest {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            val store = SqlLedgerSnapshotStore.open(driver); val original = snapshot()
            store.replace(scope, original)
            for (bad in listOf("NaN", "Infinity", "01", "1e", "0,\"injected\":true")) {
                val invalid = original.copy(facts = original.facts.copy(accounts = original.facts.accounts.map {
                    it.copy(currencies = JsonUnquotedLiteral(bad))
                }))
                assertFailsWith<IllegalArgumentException>(bad) { store.replace(scope, invalid) }
                assertEquals(original, store.read(scope))
            }
        }
    }

    @Test fun corruptPayloadOrMismatchedPersistedScopeIsAnErrorRatherThanAnEmptyCache() = runTest {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            val store = SqlLedgerSnapshotStore.open(driver); store.replace(scope, snapshot())
            driver.execute(null, "UPDATE ledger_cache_snapshots SET snapshot_json = ?", 1) { bindString(0, "{") }.await()
            assertFails { store.read(scope) }
            val foreign = Json.encodeToString(snapshot().copy(workspaceId = "foreign"))
            driver.execute(null, "UPDATE ledger_cache_snapshots SET snapshot_json = ?", 1) { bindString(0, foreign) }.await()
            assertFailsWith<IllegalArgumentException> { store.read(scope) }
        }
    }
}
