package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.USERS
import com.finance.tracker.backend.persistence.generated.tables.references.WORKSPACES
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.sql.DriverManager
import java.time.Instant
import java.util.Properties
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.serialization.json.jsonPrimitive

class SchemaBootstrapPostgresTest {
    @Test
    fun `empty PostgreSQL database is initialized and validated`() {
        val configured = System.getenv("FT_TEST_POSTGRES_URL")
        org.junit.Assume.assumeTrue("FT_TEST_POSTGRES_URL is not configured", configured != null)
        val normalized = configured!!
            .replaceFirst("postgresql+psycopg://", "postgresql://")
            .replaceFirst("postgres://", "postgresql://")
        val uri = URI(normalized)
        require(uri.path.removePrefix("/").endsWith("_test")) { "PostgreSQL schema tests require an explicit _test database" }
        val credentials = uri.rawUserInfo?.split(':', limit = 2).orEmpty()
        val user = credentials.getOrNull(0)?.let { URLDecoder.decode(it.replace("+", "%2B"), StandardCharsets.UTF_8) }
        val password = credentials.getOrNull(1)?.let { URLDecoder.decode(it.replace("+", "%2B"), StandardCharsets.UTF_8) }
        val host = requireNotNull(uri.host)
        val port = if (uri.port < 0) 5432 else uri.port
        val adminUrl = "jdbc:postgresql://$host:$port${uri.path}"
        val properties = Properties().apply {
            user?.let { setProperty("user", it) }
            password?.let { setProperty("password", it) }
        }
        val databaseName = "ft_schema_${UUID.randomUUID().toString().replace("-", "")}_test"
        DriverManager.getConnection(adminUrl, properties).use { admin ->
            admin.createStatement().use { it.execute("CREATE DATABASE \"$databaseName\"") }
        }
        try {
            val database = Database("jdbc:postgresql://$host:$port/$databaseName", user, password)
            val reached = java.util.concurrent.atomic.AtomicBoolean()
            val original = java.sql.SQLException("private jdbc:postgresql://user:secret@host/private", "XX001", 7301)
            val rollback = java.sql.SQLException("private rollback diagnostic", "XX002", 7302)
            val failing = Database("jdbc:postgresql://$host:$port/$databaseName", user, password,
                decorateConnection = { failAfterThirdSchemaTable(it, reached, original, rollback) })
            val rejected = assertFailsWith<StorageFailure> { failing.bootstrapSchema() }
            kotlin.test.assertSame(original, rejected.cause)
            kotlin.test.assertSame(rollback, original.suppressed.single())
            assertEquals("finance-tracker: storage.connect (SQLState=XX001, vendorCode=7301, rollbackFailures=1)",
                storageStartupDiagnostic(rejected))
            kotlin.test.assertTrue(reached.get())
            assertEquals(0, database.read { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT count(*) FROM pg_tables WHERE schemaname='public'").use { rows ->
                        check(rows.next())
                        rows.getInt(1)
                    }
                }
            })
            database.bootstrapSchema()
            database.validateSchema()
            assertLedgerSequenceBaseline(database)
            database.bootstrapSchema()
            assertSchemaVersionRejections(database)
            val tableCount = database.read { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT count(*) FROM pg_tables WHERE schemaname='public'").use { rows ->
                        check(rows.next())
                        rows.getInt(1)
                    }
                }
            }
            assertEquals(29, tableCount)
            upgradePreservesOriginalReceipt(database)
            for (table in listOf("cash_entries", "cash_entry_members", "cash_relation_migration_audit")) {
                database.write { connection ->
                    connection.createStatement().use {
                        it.execute("DROP SCHEMA public CASCADE")
                        it.execute("CREATE SCHEMA public")
                    }
                }
                database.bootstrapSchema()
                database.validateSchema()
                database.write { connection ->
                    connection.createStatement().use { it.execute("DROP TABLE $table CASCADE") }
                }
                database.bootstrapSchema()
                assertEquals("storage.schema", assertFailsWith<StorageFailure> { database.validateSchema() }.code)
                val remaining = database.read { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT count(*) FROM pg_tables WHERE schemaname='public'").use { rows ->
                            check(rows.next())
                            rows.getInt(1)
                        }
                    }
                }
                assertEquals(28, remaining)
            }
        } finally {
            DriverManager.getConnection(adminUrl, properties).use { admin ->
                admin.createStatement().use { it.execute("DROP DATABASE IF EXISTS \"$databaseName\" WITH (FORCE)") }
            }
        }
    }

    private fun upgradePreservesOriginalReceipt(database: Database) {
        val workspace = "pre-async-workspace"
        val user = "pre-async-user"
        val key = "frozen-before-upgrade"
        val now = Instant.parse("2026-11-14T00:00:00Z")
        val original = database.write { connection ->
            connection.createStatement().use {
                it.execute("DROP TABLE cash_import_confirmations")
                it.execute("UPDATE alembic_version SET version_num='20261104_54'")
            }
            val sql = connection.jooq(false)
            sql.insertInto(WORKSPACES).set(WORKSPACES.ID, workspace).set(WORKSPACES.NAME, "Old ledger")
                .set(WORKSPACES.CREATED_AT, now).execute()
            sql.insertInto(USERS).set(USERS.ID, user).set(USERS.EMAIL, "pre-async@example.invalid")
                .set(USERS.PASSWORD_HASH, "test").set(USERS.CREATED_AT, now).execute()
            ReceiptRepository(connection, workspace, false, now = { now }).create(user, key, sourceDigest(key), "cash_write_set",
                mapOf("status" to "no_changes", "summary" to emptyMap<String, Int>()), emptyList<Any>())
        }
        database.write { connection ->
            connection.createStatement().use { it.execute("CREATE INDEX ix_cash_import_confirmation_ready ON workspaces (id)") }
        }
        assertFailsWith<StorageFailure> { database.upgradeCashImportConfirmationSchema() }
        database.read { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT to_regclass('cash_import_confirmations') IS NOT NULL, version_num FROM alembic_version").use {
                    check(it.next())
                    assertFalse(it.getBoolean(1))
                    assertEquals("20261104_54", it.getString(2))
                }
            }
        }
        database.write { connection ->
            connection.createStatement().use { it.execute("DROP INDEX ix_cash_import_confirmation_ready") }
        }
        repeat(2) { database.upgradeCashImportConfirmationSchema() }
        database.validateSchema()
        database.read { connection ->
            assertEquals(original, ReceiptRepository(connection, workspace, false).lookupKey(user, key))
            val status = CashImportConfirmationRepository(connection, false).view(CashImportOperationKey(workspace, user, key))
            assertEquals(ReceiptRepositoryView.strip(original)["receipt_id"]?.jsonPrimitive?.content, status?.receiptId)
        }
    }
}
