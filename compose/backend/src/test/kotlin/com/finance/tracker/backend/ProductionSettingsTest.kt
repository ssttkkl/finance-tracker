package com.finance.tracker.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProductionSettingsTest {
    @Test
    fun `port is required and invalid platform values cannot fall back`() {
        for (value in listOf(null, "", " ", "abc", "0", "1023", "65536")) {
            val environment = mutableMapOf("FT_KOTLIN_PORT" to "18240")
            if (value == null) environment.clear() else environment["PORT"] = value
            assertFailsWith<IllegalArgumentException>(value ?: "missing") { requiredServerPort(environment) }
        }
        assertEquals(8080, requiredServerPort(mapOf("PORT" to "8080", "FT_KOTLIN_PORT" to "18240")))
        assertEquals(18240, requiredServerPort(mapOf("FT_KOTLIN_PORT" to "18240")))
    }

    @Test
    fun `web origin is required`() {
        assertFailsWith<IllegalArgumentException> { requiredWebOrigin(emptyMap()) }
        for (value in listOf("", " ", "invalid", "https://example.com/path")) {
            assertFailsWith<IllegalArgumentException> { requiredWebOrigin(mapOf("FT_WEB_ORIGIN" to value)) }
        }
        assertEquals("https://example.com", requiredWebOrigin(mapOf("FT_WEB_ORIGIN" to "https://example.com")))
    }
}
