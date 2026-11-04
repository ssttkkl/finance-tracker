package com.finance.tracker.business.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CashNfcOriginalTest {
    @Test fun originalJvmNfcSamplesAreEquivalentAcrossPlatforms() {
        val cases = Json.parseToJsonElement(CashNfcOriginalVectors.json).jsonArray
        assertEquals(20_113, cases.size)
        var sawUnpaired = false
        for ((index, item) in cases.withIndex()) {
            val row = item.jsonArray
            val text = row[0].jsonPrimitive.content
            if (text.pythonCodePoints().any { it in 0xd800..0xdfff }) sawUnpaired = true
            assertEquals(row[1].jsonPrimitive.content, normalizeCashNfc(text), "case $index")
        }
        assertTrue(sawUnpaired)
    }
}
