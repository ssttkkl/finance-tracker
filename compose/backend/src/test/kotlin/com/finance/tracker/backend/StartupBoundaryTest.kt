package com.finance.tracker.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.sql.SQLException
import java.sql.DriverManager
import java.nio.file.Files

class StartupBoundaryTest {
    @Test fun missingAuthenticationDoesNotAttemptDatabaseAccess() {
        val services = ReadServices(Database("jdbc:sqlite:/nonexistent/anonymous_test.db"))
        assertFailsWith<AccessFailure> { services.state(null) }
        assertFailsWith<AccessFailure> { services.requireContext(null) }
    }

    @Test fun webOriginAcceptsConfiguredHttpsAndOnlyExplicitLocalHttp() {
        assertEquals("https://example.invalid", validateWebOrigin("https://example.invalid/"))
        assertEquals("http://localhost:18888", validateWebOrigin("http://localhost:18888"))
        for (invalid in listOf("http://example.invalid", "http://localhost", "https://user:password@example.invalid",
            "https://example.invalid/path", "https://example.invalid?q=1", "https://example.invalid#fragment")) {
            assertFailsWith<IllegalArgumentException>(invalid) { validateWebOrigin(invalid) }
        }
    }

    @Test fun runtimeStorageErrorsPreserveNativeCodeMappingAndNeverExposeDriverText() {
        assertEquals("storage.busy", storageErrorCode(SQLException("private", "55P03")))
        assertEquals("storage.readonly", storageErrorCode(SQLException("private", "25006")))
        assertEquals("storage.connect", storageErrorCode(SQLException("private", "42P01")))
        assertEquals("storage.busy", storageErrorCode(SQLException("private", null, 517)))
        assertEquals("storage.readonly", storageErrorCode(SQLException("private", null, 264)))
    }

    @Test fun schemaValidationFailsClosedForAbsentEmptyAndWrongRevision() {
        for (revision in listOf("absent-table", "empty-table", "wrong-revision")) {
            val directory = Files.createTempDirectory("finance-kotlin-schema-test-")
            val path = Files.createFile(directory.resolve("schema_test.db"))
            try {
                if (revision != "absent-table") DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute("CREATE TABLE alembic_version(version_num VARCHAR(32) NOT NULL)")
                        if (revision == "wrong-revision") statement.execute("INSERT INTO alembic_version VALUES('unsupported')")
                    }
                }
                val error = assertFailsWith<StorageFailure> { Database("jdbc:sqlite:$path").validateSchema() }
                assertEquals("storage.schema", error.code, revision)
            } finally {
                Files.deleteIfExists(path)
                Files.deleteIfExists(directory)
            }
        }
    }
}
