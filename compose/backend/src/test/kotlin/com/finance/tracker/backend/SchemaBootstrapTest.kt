package com.finance.tracker.backend

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SchemaBootstrapTest {
    private fun database(name: String): Database {
        val path: Path = Files.createTempDirectory("finance-schema").resolve("$name.db")
        return Database("jdbc:sqlite:$path")
    }

    @Test
    fun `empty SQLite database is initialized and validated`() {
        val database = database("empty")
        database.bootstrapSchema()
        database.validateSchema()
        assertLedgerSequenceBaseline(database)
        assertEquals("20261104_54", database.read { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT version_num FROM alembic_version").use { rows ->
                    check(rows.next())
                    rows.getString(1)
                }
            }
        })
    }

    @Test
    fun `bootstrap is idempotent on an initialized SQLite database`() {
        val database = database("idempotent")
        database.bootstrapSchema()
        database.bootstrapSchema()
        database.validateSchema()
    }

    @Test
    fun `partial SQLite database fails closed without creating tables`() {
        val path = Files.createTempDirectory("finance-schema").resolve("partial.db")
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().use { it.execute("CREATE TABLE workspaces (id TEXT)") }
        }
        val database = Database("jdbc:sqlite:$path")
        database.bootstrapSchema()
        assertFailsWith<StorageFailure> { database.validateSchema() }
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='alembic_version'").use { rows ->
                    assertFalse(rows.next())
                }
            }
        }
    }

    @Test
    fun `old SQLite schema version fails closed`() {
        val database = database("old-version")
        database.bootstrapSchema()
        assertSchemaVersionRejections(database)
    }

    @Test
    fun `SQLite failure after actual DDL rolls back every baseline table`() {
        val path = Files.createTempDirectory("finance-schema-rollback").resolve("failed.db")
        val reached = java.util.concurrent.atomic.AtomicBoolean()
        val original = java.sql.SQLException("private jdbc:postgresql://user:secret@host/private", "XX001", 7301)
        val rollback = java.sql.SQLException("private rollback diagnostic", "XX002", 7302)
        val failing = Database("jdbc:sqlite:$path", decorateConnection = { failAfterThirdSchemaTable(it, reached, original, rollback) })
        val rejected = assertFailsWith<StorageFailure> { failing.bootstrapSchema() }
        kotlin.test.assertSame(original, rejected.cause)
        kotlin.test.assertSame(rollback, original.suppressed.single())
        assertEquals("finance-tracker: storage.connect (SQLState=XX001, vendorCode=7301, rollbackFailures=1)",
            storageStartupDiagnostic(rejected))
        kotlin.test.assertTrue(reached.get())
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table'").use { rows ->
                    kotlin.test.assertTrue(rows.next())
                    assertEquals(0, rows.getInt(1))
                }
            }
        }
        Database("jdbc:sqlite:$path").apply { bootstrapSchema(); validateSchema() }
    }

    @Test
    fun `SQLite initialization waits for the existing writer before inspecting schema`() {
        val path = Files.createTempDirectory("finance-schema-lock").resolve("locked.db")
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            DriverManager.getConnection("jdbc:sqlite:$path").use { writer ->
                writer.createStatement().use { it.execute("BEGIN IMMEDIATE") }
                val started = java.util.concurrent.CountDownLatch(1)
                val initialization = executor.submit {
                    started.countDown()
                    Database("jdbc:sqlite:$path").bootstrapSchema()
                }
                kotlin.test.assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
                assertFailsWith<java.util.concurrent.TimeoutException> {
                    initialization.get(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
                writer.createStatement().use { it.execute("COMMIT") }
                initialization.get(10, java.util.concurrent.TimeUnit.SECONDS)
            }
            Database("jdbc:sqlite:$path").validateSchema()
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `every baseline business table is required on subsequent startup`() {
        for (table in listOf("cash_entries", "cash_entry_members", "cash_relation_migration_audit")) {
            val database = database("missing-$table")
            database.bootstrapSchema()
            database.write { connection ->
                connection.createStatement().use { it.execute("DROP TABLE $table") }
            }
            assertEquals("storage.schema", assertFailsWith<StorageFailure> { database.validateSchema() }.code, table)
        }
    }

    @Test
    fun `SQLite baseline excludes the legacy migration artifact`() {
        val database = database("artifacts")
        database.bootstrapSchema()
        val tables = database.read { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table'").use { rows ->
                    buildSet { while (rows.next()) add(rows.getString(1)) }
                }
            }
        }
        assertFalse("account_lifecycle_events__new" in tables)
    }
}
