package com.finance.tracker.backend

import java.nio.file.Path
import com.finance.tracker.backend.persistence.generated.tables.references.WORKSPACES
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class LedgerVersionContractTest {
    private fun database(): Database {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_LEDGER_CONTRACT_DIR")))
        val config = Json.parseToJsonElement(root.resolve("database.json").readText()).jsonObject
        val jdbc = config.getValue("jdbc_url").jsonPrimitive.content
        if (jdbc.startsWith("jdbc:postgresql:")) {
            val explicit = java.net.URI(requireNotNull(System.getenv("FT_TEST_POSTGRES_URL")).replace("postgresql+psycopg://", "postgresql://"))
            val target = java.net.URI(jdbc.removePrefix("jdbc:"))
            require(explicit.scheme == "postgresql" && explicit.path.endsWith("_test") &&
                explicit.host == target.host && explicit.port == target.port && explicit.path == target.path)
        }
        return Database(jdbc, System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
    }
    private fun version(c: Connection, workspace: String): Long = c.prepareStatement("SELECT ledger_seq FROM workspaces WHERE id=?").use {
        it.setString(1, workspace)
        it.executeQuery().use { rows -> check(rows.next()); rows.getLong(1) }
    }
    private fun change(c: Connection, workspace: String, value: String) {
        c.prepareStatement("UPDATE workspaces SET name=? WHERE id=?").use {
            it.setString(1, value); it.setString(2, workspace); check(it.executeUpdate() == 1)
        }
    }
    private fun <T> fixture(action: (Database, String) -> T): T {
        val db = database()
        val workspace = "ledger-version-" + UUID.randomUUID()
        db.write { c -> c.jooq(db.sqlite).insertInto(WORKSPACES).set(WORKSPACES.ID, workspace)
            .set(WORKSPACES.NAME, "original").set(WORKSPACES.CREATED_AT, Instant.parse("2026-11-04T00:00:00Z")).execute() }
        try { return action(db, workspace) }
        finally { db.write { c -> c.prepareStatement("DELETE FROM workspaces WHERE id=?").use { it.setString(1, workspace); it.executeUpdate() } } }
    }

    @Test
    fun oneTransactionAdvancesOnceAndReadOnlyDoesNotAdvance() = fixture { db, workspace ->
        assertEquals(0L, db.read { version(it, workspace) })
        db.write { c ->
            assertEquals(1L, c.markLedgerChanged(workspace))
            change(c, workspace, "first")
            assertEquals(1L, c.markLedgerChanged(workspace))
            change(c, workspace, "second")
            assertEquals(1L, version(c, workspace))
        }
        assertEquals(1L, db.read { version(it, workspace) })
        db.read { version(it, workspace) }
        db.write { c -> assertEquals(2L, c.markLedgerChanged(workspace)); change(c, workspace, "third") }
        assertEquals(2L, db.read { version(it, workspace) })
    }

    @Test
    fun outerRollbackRestoresFactAndVersionTogether() = fixture { db, workspace ->
        class Rejected : RuntimeException()
        assertFailsWith<Rejected> { db.write { c ->
            c.markLedgerChanged(workspace); change(c, workspace, "rejected"); throw Rejected()
        } }
        db.read { c ->
            assertEquals(0L, version(c, workspace))
            c.prepareStatement("SELECT name FROM workspaces WHERE id=?").use {
                it.setString(1, workspace); it.executeQuery().use { rows -> check(rows.next()); assertEquals("original", rows.getString(1)) }
            }
        }
    }

    @Test
    fun savepointRollbackBeforeFirstMarkAllowsExactlyOneLaterAdvance() = fixture { db, workspace ->
        db.write { c ->
            c.createStatement().use { it.execute("SAVEPOINT before_mark") }
            assertEquals(1L, c.markLedgerChanged(workspace))
            change(c, workspace, "rolled back")
            c.createStatement().use { it.execute("ROLLBACK TO SAVEPOINT before_mark"); it.execute("RELEASE SAVEPOINT before_mark") }
            assertEquals(0L, version(c, workspace))
            assertEquals(1L, c.markLedgerChanged(workspace))
            change(c, workspace, "kept")
        }
        assertEquals(1L, db.read { version(it, workspace) })
    }

    @Test
    fun postgresRollbackReleasesFirstMarkAndAnotherWriterMustNotBeMistakenForOurs() = fixture { db, workspace ->
        if (!db.sqlite) {
            db.write { first ->
                first.createStatement().use { it.execute("SAVEPOINT before_first_mark") }
                assertEquals(1L, first.markLedgerChanged(workspace))
                first.createStatement().use { it.execute("ROLLBACK TO before_first_mark") }
                db.write { second ->
                    assertEquals(1L, second.markLedgerChanged(workspace))
                    change(second, workspace, "another writer")
                }
                assertEquals(2L, first.markLedgerChanged(workspace))
                change(first, workspace, "our later fact")
                first.createStatement().use { it.execute("RELEASE before_first_mark") }
            }
            assertEquals(2L, db.read { version(it, workspace) })
        }
    }

    @Test
    fun savepointRollbackAfterFirstMarkDoesNotDoubleAdvance() = fixture { db, workspace ->
        db.write { c ->
            assertEquals(1L, c.markLedgerChanged(workspace))
            c.createStatement().use { it.execute("SAVEPOINT after_mark") }
            c.markLedgerChanged(workspace); change(c, workspace, "rolled back")
            c.createStatement().use { it.execute("ROLLBACK TO SAVEPOINT after_mark"); it.execute("RELEASE SAVEPOINT after_mark") }
            assertEquals(1L, c.markLedgerChanged(workspace))
            change(c, workspace, "kept")
        }
        assertEquals(1L, db.read { version(it, workspace) })
    }

    @Test
    fun nestedQuotedAndRepeatedSavepointsRestoreTheirOwnMarks() = fixture { db, workspace ->
        db.write { c ->
            c.createStatement().use { sql ->
                sql.execute("SAVEPOINT \"Outer;\"\"name\"")
                assertEquals(1L, c.markLedgerChanged(workspace))
                sql.execute("SAVEPOINT repeated")
                sql.execute("SAVEPOINT repeated")
                sql.execute("ROLLBACK TO repeated")
                assertEquals(1L, c.markLedgerChanged(workspace))
                sql.execute("RELEASE repeated")
                sql.execute("ROLLBACK TO repeated")
                assertEquals(1L, c.markLedgerChanged(workspace))
                sql.execute("ROLLBACK TO \"Outer;\"\"name\"")
                assertEquals(0L, version(c, workspace))
                assertEquals(1L, c.markLedgerChanged(workspace))
                sql.execute("RELEASE \"Outer;\"\"name\"")
            }
        }
        assertEquals(1L, db.read { version(it, workspace) })
    }

    @Test
    fun unicodeSavepointNamesDoNotFoldDistinctDatabaseIdentifiers() = fixture { db, workspace ->
        db.write { c ->
            c.createStatement().use { sql ->
                sql.execute("SAVEPOINT Ä")
                c.markLedgerChanged(workspace)
                sql.execute("SAVEPOINT ä")
                sql.execute("ROLLBACK TO Ä")
                assertEquals(0L, version(c, workspace))
                assertEquals(1L, c.markLedgerChanged(workspace))
                sql.execute("RELEASE Ä")
            }
        }
        assertEquals(1L, db.read { version(it, workspace) })
    }

    @Test
    fun postgresUnicodeQuotedSavepointsKeepDistinctDecodedIdentities() = fixture { db, workspace ->
        if (!db.sqlite) {
            db.write { c ->
                c.createStatement().use { sql ->
                    sql.execute("SAVEPOINT U&\"s\"")
                    c.markLedgerChanged(workspace)
                    sql.execute("SAVEPOINT U&\"t\"")
                    sql.execute("ROLLBACK TO U&\"s\"")
                    assertEquals(0L, version(c, workspace))
                    assertEquals(1L, c.markLedgerChanged(workspace))
                    assertEquals(1L, version(c, workspace))
                    sql.execute("RELEASE U&\"s\"")
                }
            }
            assertEquals(1L, db.read { version(it, workspace) })
        }
    }

    @Test
    fun postgresUnicodeEscapesReferToTheSameOrdinaryQuotedSavepoint() = fixture { db, workspace ->
        if (!db.sqlite) {
            val cases = listOf(
                "U&\"\\0073\"" to "s",
                "U&\"!0073\" UESCAPE '!'" to "s",
                "U&\"!0073\" UESCAPE E'!'" to "s",
                "U&\"\\+01F600\"" to "😀",
                "U&\"\\D83D\\DE00\"" to "😀",
                "U&\"a!!b\" UESCAPE '!'" to "a!b",
                "U&\"a\"\"b\"" to "a\"b",
            )
            db.write { c ->
                c.createStatement().use { sql ->
                    for ((encoded, decoded) in cases) {
                        val quoted = "\"" + decoded.replace("\"", "\"\"") + "\""
                        sql.execute("SAVEPOINT initial_state")
                        sql.execute("SAVEPOINT $encoded")
                        c.markLedgerChanged(workspace)
                        sql.execute("SAVEPOINT inner_state")
                        sql.execute("ROLLBACK TO $quoted")
                        assertEquals(0L, version(c, workspace), encoded)
                        assertEquals(1L, c.markLedgerChanged(workspace), encoded)
                        assertEquals(1L, version(c, workspace), encoded)
                        sql.execute("RELEASE $encoded")
                        sql.execute("ROLLBACK TO initial_state")
                        sql.execute("RELEASE initial_state")
                    }
                }
            }
            assertEquals(0L, db.read { version(it, workspace) })
        }
    }

    @Test
    fun postgresTruncatedIdentifiersRestoreTheDatabaseSavepoint() = fixture { db, workspace ->
        if (!db.sqlite) {
            db.write { c ->
                val prefix = "层".repeat(21)
                c.createStatement().use { sql ->
                    sql.execute("SAVEPOINT \"${prefix}first\"")
                    c.markLedgerChanged(workspace)
                    sql.execute("SAVEPOINT \"${prefix}second\"")
                    sql.execute("ROLLBACK TO \"${prefix}first\"")
                    assertEquals(1L, version(c, workspace))
                    assertEquals(1L, c.markLedgerChanged(workspace))
                    sql.execute("RELEASE \"${prefix}second\"")
                    sql.execute("ROLLBACK TO \"${prefix}first\"")
                    assertEquals(0L, version(c, workspace))
                    assertEquals(1L, c.markLedgerChanged(workspace))
                    sql.execute("RELEASE \"${prefix}first\"")
                }
            }
            assertEquals(1L, db.read { version(it, workspace) })
        }
    }

    @Test
    fun postgresJdbcSavepointsAndNamedSqlRollbackShareVersionState() = fixture { db, workspace ->
        if (!db.sqlite) {
            db.write { c ->
                val named = c.setSavepoint("Named;Point")
                c.markLedgerChanged(workspace)
                c.createStatement().use { it.execute("ROLLBACK TO \"Named;Point\"") }
                assertEquals(0L, version(c, workspace))
                assertEquals(1L, c.markLedgerChanged(workspace))
                val unnamed = c.setSavepoint()
                c.markLedgerChanged(workspace)
                c.rollback(unnamed)
                c.createStatement().use { it.execute("ROLLBACK TO JDBC_SAVEPOINT_${unnamed.savepointId}") }
                c.releaseSavepoint(unnamed)
                assertEquals(1L, c.markLedgerChanged(workspace))
                c.releaseSavepoint(named)
            }
            assertEquals(1L, db.read { version(it, workspace) })
        }
    }

    @Test
    fun workspacesHaveIndependentCountersAndVersionsCannotWrap() = fixture { db, first ->
        fixture { _, second ->
            db.write { c -> assertEquals(1L, c.markLedgerChanged(first)); assertEquals(1L, c.markLedgerChanged(second)) }
            assertEquals(1L, db.read { version(it, first) })
            db.write { c -> c.prepareStatement("UPDATE workspaces SET ledger_seq=? WHERE id=?").use {
                it.setLong(1, Long.MAX_VALUE); it.setString(2, first); it.executeUpdate()
            } }
            assertFailsWith<ArithmeticException> { db.write { it.markLedgerChanged(first) } }
            assertEquals(Long.MAX_VALUE, db.read { version(it, first) })
            assertEquals(1L, db.read { version(it, second) })
        }
    }

    @Test
    fun newPhysicalTransactionOnSameConnectionGetsANewMark() = fixture { db, workspace ->
        db.write { c ->
            assertEquals(1L, c.markLedgerChanged(workspace))
            if (db.sqlite) c.createStatement().use { it.execute("COMMIT"); it.execute("BEGIN IMMEDIATE") }
            else c.commit()
            assertEquals(2L, c.markLedgerChanged(workspace))
            if (db.sqlite) c.createStatement().use { it.execute("ROLLBACK"); it.execute("BEGIN IMMEDIATE") }
            else c.rollback()
            assertEquals(1L, version(c, workspace))
            assertEquals(2L, c.markLedgerChanged(workspace))
        }
        assertEquals(2L, db.read { version(it, workspace) })
    }

    @Test
    fun concurrentWritersAdvanceDistinctVersionsWithoutLosingAnUpdate() = fixture { db, workspace ->
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        val start = java.util.concurrent.CountDownLatch(1)
        val ready = java.util.concurrent.CountDownLatch(2)
        try {
            val writes = (1..2).map {
                executor.submit<Long> {
                    ready.countDown()
                    check(start.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    db.write { c ->
                        val next = c.markLedgerChanged(workspace)
                        change(c, workspace, "version-$next")
                        next
                    }
                }
            }
            check(ready.await(5, java.util.concurrent.TimeUnit.SECONDS))
            start.countDown()
            assertEquals(listOf(1L, 2L), writes.map { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }.sorted())
            assertEquals(2L, db.read { version(it, workspace) })
        } finally { executor.shutdownNow() }
    }

    @Test
    fun versionMarkRequiresAnActiveManagedTransaction() = fixture { db, workspace ->
        var finished: Connection? = null
        db.write { c -> finished = c; assertTrue(!c.isClosed) }
        assertFailsWith<IllegalStateException> { finished!!.markLedgerChanged(workspace) }
        Unit
    }
}
