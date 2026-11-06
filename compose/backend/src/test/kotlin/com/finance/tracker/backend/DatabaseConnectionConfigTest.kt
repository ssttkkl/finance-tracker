package com.finance.tracker.backend

import java.util.Properties
import kotlin.test.*
import org.postgresql.Driver

class DatabaseConnectionConfigTest {
    private val base = "postgresql+psycopg://fixture%2Buser:synthetic%40pass@fixture.neon.tech/finance_test"
    private fun config(value: String) = databaseConnectionConfig(mapOf("FT_DATABASE_URL" to value))
    private fun driver(value: String): Properties = config(value).let { setting ->
        Driver.parseURL(setting.jdbcUrl, Properties().apply {
            setting.user?.let { setProperty("user", it) }
            setting.password?.let { setProperty("password", it) }
        })!!
    }

    @Test fun neonUrlKeepsBothExplicitSecurityRequirementsInTheRealDriver() {
        val settings = config("$base?sslmode=require&channel_binding=require")
        val properties = driver("$base?sslmode=require&channel_binding=require")
        assertEquals("require", properties.getProperty("sslmode"))
        assertEquals("require", properties.getProperty("channelBinding"))
        assertNull(properties.getProperty("channel_binding"))
        assertEquals("fixture+user", properties.getProperty("user"))
        assertEquals("synthetic@pass", properties.getProperty("password"))
        assertFalse(settings.jdbcUrl.contains("fixture%2Buser"))
        assertFalse(settings.jdbcUrl.contains("synthetic"))
        assertFalse(settings.toString().contains("synthetic"))
    }

    @Test fun eachSecurityModeAndEncodedInputKeepTheirDriverMeaning() {
        for (mode in listOf("disable", "allow", "prefer", "require", "verify-ca", "verify-full"))
            assertEquals(mode, driver("$base?sslmode=$mode").getProperty("sslmode"))
        for (binding in listOf("disable", "prefer", "require"))
            assertEquals(binding, driver("$base?channel_binding=$binding").getProperty("channelBinding"))
        assertEquals("verify-full", driver("$base?channel_binding=require&%73slmode=verify%2Dfull").getProperty("sslmode"))
        assertEquals("require", driver("$base?channel_binding=%72equire").getProperty("channelBinding"))
        assertEquals("verify-full", driver("$base?sslmode=VERIFY-FULL").getProperty("sslmode"))
    }

    @Test fun aliasesPortsCredentialsAndUnspecifiedOptionsStayCompatible() {
        for (scheme in listOf("postgresql", "postgresql+psycopg", "postgres")) {
            val setting = config("$scheme://literal+user:pass%3Aword%252B@localhost:55432/finance_test")
            assertEquals("jdbc:postgresql://localhost:55432/finance_test", setting.jdbcUrl)
            assertEquals("literal+user", setting.user)
            assertEquals("pass:word%2B", setting.password)
        }
        val defaults = driver(base)
        assertNull(defaults.getProperty("sslmode"))
        assertNull(defaults.getProperty("channelBinding"))
        assertEquals("5432", defaults.getProperty("PGPORT"))
        assertEquals("jdbc:postgresql://[::1]:5432/finance_test", config("postgresql://[::1]/finance_test").jdbcUrl)
    }

    @Test fun illegalOrAmbiguousOptionsFailClosedWithoutLeakingConfiguration() {
        val invalid = listOf("?sslmode=", "?channel_binding=", "?sslmode", "?", "?sslmode=required",
            "?channel_binding=REQUIRE", "?sslMode=require", "?ssl=true", "?channelBinding=require",
            "?user=other", "?password=override", "?connectTimeout=0", "?sslfactory=arbitrary.Class",
            "?sslmode=require&sslmode=require", "?sslmode=require&sslmode=disable", "?sslmode=require&%73slmode=require",
            "?channel_binding=require&channel_binding=disable", "?channel_binding=require&channel%5Fbinding=require",
            "?sslmode=require&", "?sslmode=%", "?sslmode=%FF", "?sslmode=require%00", "?sslmode=%2572equire", "#fragment")
        for (suffix in invalid) {
            val failure = assertFailsWith<StorageFailure>(suffix) { config(base + suffix) }
            assertEquals("storage.config", failure.code)
            assertEquals("storage.config", failure.message)
            assertNull(failure.cause)
        }
    }

    @Test fun environmentPrecedenceAndSqliteSelectionDoNotFallBack() {
        assertEquals("jdbc:sqlite:/tmp/finance_test.db", config("sqlite+pysqlite:////tmp/finance_test.db").jdbcUrl)
        assertEquals("jdbc:postgresql://fixture.neon.tech:5432/finance_test",
            databaseConnectionConfig(mapOf("DATABASE_URL" to base)).jdbcUrl)
        for (invalid in listOf("", "https://invalid", "sqlite+pysqlite:////tmp/finance_test.db?sslmode=require",
            "sqlite+pysqlite:////tmp/finance_test.db#fragment"))
            assertEquals("storage.config", assertFailsWith<StorageFailure> {
                databaseConnectionConfig(mapOf("FT_DATABASE_URL" to invalid, "DATABASE_URL" to base))
            }.code)
        assertEquals("storage.config", assertFailsWith<StorageFailure> { databaseConnectionConfig(emptyMap()) }.code)
    }
}
