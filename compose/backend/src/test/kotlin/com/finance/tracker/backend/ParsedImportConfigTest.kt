package com.finance.tracker.backend

import kotlin.test.*

class ParsedImportConfigTest {
    private val memory = mapOf("FT_IMPORT_STAGING_BACKEND" to "memory", "FT_IMPORT_STAGING_ALLOW_MEMORY" to "1")

    @Test fun backendAndLocalMemorySwitchMustBeExplicit() {
        assertTrue(assertFailsWith<ParsedStorageConfigurationError> { ParsedImportSettings.load(emptyMap()) }.message!!.contains("FT_IMPORT_STAGING_BACKEND"))
        assertTrue(assertFailsWith<ParsedStorageConfigurationError> {
            ParsedImportSettings.load(mapOf("FT_IMPORT_STAGING_BACKEND" to "memory"))
        }.message!!.contains("FT_IMPORT_STAGING_ALLOW_MEMORY"))
        val settings = ParsedImportSettings.load(memory + ("FT_IMPORT_STAGING_TTL_SECONDS" to "900"))
        assertEquals("memory", settings.backend); assertEquals(900, settings.ttlSeconds)
        settings.build().use { runtime ->
            assertIs<InMemoryParsedImportStore>(runtime.store)
            assertNotNull(runtime.cleanup); assertFalse(runtime.cleanup.running)
        }
    }

    @Test fun invalidBackendTtlAndCleanupSettingsNeverConstructRuntime() {
        for ((key, value) in listOf("FT_IMPORT_STAGING_BACKEND" to "filesystem", "FT_IMPORT_STAGING_TTL_SECONDS" to "1801",
            "FT_IMPORT_STAGING_TTL_SECONDS" to "nan", "FT_IMPORT_STAGING_TTL_SECONDS" to "0")) {
            assertTrue(assertFailsWith<ParsedStorageConfigurationError> { ParsedImportSettings.load(memory + (key to value)) }.message!!.contains(key))
        }
        for ((key, value) in listOf("FT_IMPORT_STAGING_SWEEP_SECONDS" to "nan", "FT_IMPORT_STAGING_SWEEP_SECONDS" to "61",
            "FT_IMPORT_STAGING_SWEEP_PAGE_SIZE" to "0")) {
            assertEquals("import_session_storage_config_invalid", assertFailsWith<ParsedStorageConfigurationError> {
                ParsedImportSettings.load(memory + (key to value)).build()
            }.message)
        }
        assertEquals(900, ParsedImportSettings.load(memory + ("FT_IMPORT_STAGING_TTL_SECONDS" to " +９_００ ")).ttlSeconds)
    }

    @Test fun r2RequiresExplicitCredentialsAndHttpsWithoutExposingSecretValues() {
        val r2 = mapOf("FT_IMPORT_STAGING_BACKEND" to "r2", "FT_R2_ENDPOINT" to "https://example.invalid",
            "FT_R2_BUCKET" to "private-imports", "FT_R2_ACCESS_KEY_ID" to "synthetic-access", "FT_R2_SECRET_ACCESS_KEY" to "synthetic-secret")
        for (key in listOf("FT_R2_ENDPOINT", "FT_R2_BUCKET", "FT_R2_ACCESS_KEY_ID", "FT_R2_SECRET_ACCESS_KEY")) {
            val error = assertFailsWith<ParsedStorageConfigurationError> { ParsedImportSettings.load(r2 - key) }
            assertTrue(error.message!!.contains(key)); assertFalse(error.message!!.contains("synthetic-secret"))
        }
        for (url in listOf("http://example.invalid", "https://example.invalid/path", "https://example.invalid?q=1", "https://example.invalid/#fragment")) {
            assertEquals("FT_R2_ENDPOINT is invalid", assertFailsWith<ParsedStorageConfigurationError> {
                ParsedImportSettings.load(r2 + ("FT_R2_ENDPOINT" to url))
            }.message)
        }
        val settings = ParsedImportSettings.load(r2)
        assertFalse(settings.toString().contains("synthetic-secret"))
        settings.build().use { assertIs<R2ParsedImportStore>(it.store); assertFalse(it.cleanup.running) }
    }
}
