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
        assertEquals("20261030_53", database.read { connection ->
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
        database.write { connection ->
            connection.createStatement().use { it.executeUpdate("UPDATE alembic_version SET version_num='20260101_00'") }
        }
        assertFailsWith<StorageFailure> { database.validateSchema() }
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
