package com.finance.tracker.backend

import java.net.URI
import java.nio.file.Files
import java.util.UUID

internal fun withCashWriteSetTestWorkspace(backend: String, action: (Database, String, Long) -> Unit) =
    withCashWriteSetTestWorkspace(backend, { it }, action)

internal fun withCashWriteSetTestWorkspace(backend: String, decorateConnection: (java.sql.Connection) -> java.sql.Connection,
    action: (Database, String, Long) -> Unit) {
    val directory = if (backend == "sqlite") Files.createTempDirectory("ft-write-set-sources-") else null
    val db = if (directory != null) Database("jdbc:sqlite:${directory.resolve("source_test.db")}", decorateConnection = decorateConnection).also { it.bootstrapSchema() }
    else {
        val url = System.getenv("FT_TEST_POSTGRES_URL")
        org.junit.Assume.assumeTrue("Explicit PostgreSQL test database required", url != null)
        require(URI(url!!.replaceFirst("postgresql+psycopg://", "postgresql://")).path.endsWith("_test"))
        databaseConnectionConfig(mapOf("FT_DATABASE_URL" to url)).let { Database(it.jdbcUrl, it.user, it.password, decorateConnection) }
    }
    val workspace = UUID.randomUUID().toString()
    try {
        db.validateSchema()
        db.write { c -> deletionInsert(c, db, "workspaces", linkedMapOf("id" to workspace, "name" to "Write set sources", "created_at" to deletionTime)) }
        action(db, workspace, deletionAccount(db, workspace))
    } finally {
        db.write { c -> c.prepareStatement("DELETE FROM workspaces WHERE id=?").use { it.setString(1, workspace); it.executeUpdate() } }
        directory?.let { Files.walk(it).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
}
