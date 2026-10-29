package com.finance.tracker.backend

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.*

class StorageContractTest {
    private val requiredTables = listOf(
        "workspaces", "accounts", "cash_transactions", "cash_transaction_components", "investment_events",
        "ledger_snapshots", "transaction_relations", "account_aliases", "sync_cursors",
        "cash_projection_states", "cash_projection_datasets", "cash_projections", "cash_projection_members",
        "cash_projection_relations", "cash_investment_funding_relations", "users", "workspace_memberships",
        "user_sessions", "workspace_invitations", "cash_categories", "cash_category_states",
        "statement_account_mappings", "cash_source_bindings", "cash_source_empty_pages",
        "cash_transaction_issues", "cash_issue_candidates", "cash_mutation_receipts",
        "cash_mutation_results", "cash_relation_candidates",
    )

    @Test fun sqliteConnectionsUseWalNormalAndPrivateFileNotices() {
        val directory = Files.createTempDirectory("finance-kotlin-storage-contract-")
        val path = Files.createFile(directory.resolve("storage_test.db"))
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
            val database = Database("jdbc:sqlite:$path")
            assertTrue(database.runtimeNotices.isEmpty())
            val mode = database.read { connection -> connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA journal_mode").use { rows ->
                    assertTrue(rows.next()); rows.getString(1)
                }
            } }
            assertEquals("wal", mode.lowercase())
            val synchronous = database.read { connection -> connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA synchronous").use { rows ->
                    assertTrue(rows.next()); rows.getInt(1)
                }
            } }
            assertEquals(1, synchronous)
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"))
            assertEquals(1, Database("jdbc:sqlite:$path").runtimeNotices.size)
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
            val sidecar = directory.resolve("storage_test.db-wal")
            Files.deleteIfExists(sidecar)
            Files.createFile(sidecar)
            Files.setPosixFilePermissions(sidecar, PosixFilePermissions.fromString("rw-r--r--"))
            assertEquals(1, Database("jdbc:sqlite:$path").runtimeNotices.size)
        } finally {
            for (name in listOf("storage_test.db", "storage_test.db-wal", "storage_test.db-shm"))
                Files.deleteIfExists(directory.resolve(name))
            Files.deleteIfExists(directory)
        }
    }

    @Test fun schemaValidationRequiresEveryTableAtTheCurrentRevision() {
        val directory = Files.createTempDirectory("finance-kotlin-storage-schema-")
        val path = directory.resolve("schema_test.db")
        try {
            java.sql.DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
                connection.createStatement().use { statement ->
                    for (table in requiredTables) statement.execute("CREATE TABLE $table (id INTEGER)")
                    statement.execute("CREATE TABLE alembic_version(version_num VARCHAR(32) NOT NULL)")
                    statement.execute("INSERT INTO alembic_version VALUES('20261028_51')")
                }
            }
            Database("jdbc:sqlite:$path").validateSchema()
            java.sql.DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
                connection.createStatement().use { it.execute("DROP TABLE sync_cursors") }
            }
            assertEquals("storage.schema", assertFailsWith<StorageFailure> {
                Database("jdbc:sqlite:$path").validateSchema()
            }.code)
        } finally {
            for (name in listOf("schema_test.db", "schema_test.db-wal", "schema_test.db-shm"))
                Files.deleteIfExists(directory.resolve(name))
            Files.deleteIfExists(directory)
        }
    }
}
