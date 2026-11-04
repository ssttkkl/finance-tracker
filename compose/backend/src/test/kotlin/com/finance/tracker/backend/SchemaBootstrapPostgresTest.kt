package com.finance.tracker.backend

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.sql.DriverManager
import java.util.Properties
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class SchemaBootstrapPostgresTest {
    @Test
    fun `empty PostgreSQL database is initialized and validated`() {
        val configured = System.getenv("FT_TEST_POSTGRES_URL")
        org.junit.Assume.assumeTrue("FT_TEST_POSTGRES_URL is not configured", configured != null)
        val normalized = configured!!
            .replaceFirst("postgresql+psycopg://", "postgresql://")
            .replaceFirst("postgres://", "postgresql://")
        val uri = URI(normalized)
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
            database.bootstrapSchema()
            database.validateSchema()
            database.bootstrapSchema()
            val tableCount = database.read { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT count(*) FROM pg_tables WHERE schemaname='public'").use { rows ->
                        check(rows.next())
                        rows.getInt(1)
                    }
                }
            }
            assertEquals(33, tableCount)
        } finally {
            DriverManager.getConnection(adminUrl, properties).use { admin ->
                admin.createStatement().use { it.execute("DROP DATABASE IF EXISTS \"$databaseName\" WITH (FORCE)") }
            }
        }
    }
}
