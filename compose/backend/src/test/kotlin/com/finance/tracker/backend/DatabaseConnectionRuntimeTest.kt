package com.finance.tracker.backend

import java.net.URI
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*
import kotlinx.serialization.json.*

class DatabaseConnectionRuntimeTest {
    private fun database(url: String): Database = databaseConnectionConfig(mapOf("FT_DATABASE_URL" to url)).let {
        Database(it.jdbcUrl, it.user, it.password)
    }

    private fun accountContract(database: Database) {
        database.bootstrapSchema()
        database.validateSchema()
        val token = AuthenticationService(database).register("runtime@example.invalid", "synthetic runtime password").accessToken
        WorkspaceService(database).create(token, "Connection contract")
        val account = AccountService(database).create(token, buildJsonObject {
            put("name", JsonPrimitive("Synthetic account"))
            put("type", JsonPrimitive("cash"))
            put("currencies", JsonArray(listOf(JsonPrimitive("CNY"))))
        }, "connection-account")
        val id = (account.getValue("account_id") as JsonPrimitive).content
        assertEquals("Synthetic account", AccountService(database).get(token, id).name)
        database.bootstrapSchema()
        database.validateSchema()
    }

    @Test fun sqliteFactoryStillInitializesAndRunsTheSameAccountService() {
        val directory = Files.createTempDirectory("ft-runtime-config-")
        try { accountContract(database("sqlite+pysqlite:///${directory.resolve("runtime_test.db")}")) }
        finally { Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }

    @Test fun explicitPostgresFactoryPreservesOptionsThroughBootstrapAndLaterConnections() {
        val configured = System.getenv("FT_TEST_POSTGRES_URL")
        org.junit.Assume.assumeTrue("FT_TEST_POSTGRES_URL is not configured", configured != null)
        val uri = URI(configured!!.replaceFirst("postgresql+psycopg://", "postgresql://").replaceFirst("postgres://", "postgresql://"))
        require(uri.path.removePrefix("/").endsWith("_test")) { "An explicit _test database is required" }
        val settings = databaseConnectionConfig(mapOf("FT_DATABASE_URL" to configured))
        fun administer(sql: String) = java.sql.DriverManager.getConnection(settings.jdbcUrl, settings.user, settings.password).use { c ->
            c.createStatement().use { it.execute(sql) }
        }
        val name = "ft_runtime_${UUID.randomUUID().toString().replace("-", "")}_test"
        administer("CREATE DATABASE \"$name\"")
        try {
            val prefix = configured.substringBefore('?').substringBeforeLast('/') + "/$name"
            val child = database(prefix + (uri.rawQuery?.let { "?$it" } ?: ""))
            accountContract(child)
            val sslMode = uri.rawQuery?.split('&')?.firstOrNull { it.startsWith("sslmode=") }?.substringAfter('=')
            if (sslMode in setOf("require", "verify-ca", "verify-full")) child.read { c ->
                c.createStatement().use { s -> s.executeQuery("SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()").use {
                    assertTrue(it.next()); assertTrue(it.getBoolean(1), "Explicit TLS mode must reach the actual read connection")
                } }
            }
            val withoutTls = database("$prefix?sslmode=disable&channel_binding=require")
            assertEquals("storage.connect", assertFailsWith<StorageFailure> { withoutTls.read { } }.code,
                "A required channel binding must not disappear or fall back on a plaintext connection")
        } finally {
            administer("DROP DATABASE \"$name\" WITH (FORCE)")
        }
    }
}
