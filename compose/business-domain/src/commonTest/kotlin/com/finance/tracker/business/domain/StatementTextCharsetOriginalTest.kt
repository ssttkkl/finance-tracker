package com.finance.tracker.business.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.test.Test
import kotlin.test.assertEquals

class StatementTextCharsetOriginalTest {
    @Test fun originalStrictDecodingSamplesMatchAcrossPlatforms() {
        val cases = Json.parseToJsonElement(StatementTextCharsetOriginalVectors.json).jsonArray
        assertEquals(5_221, cases.size)
        for ((index, item) in cases.withIndex()) {
            val row = item.jsonArray
            val hex = row[0].jsonPrimitive.content
            val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            assertEquals(row[1].jsonPrimitive.contentOrNull, decodeCashStatementText(bytes), "case $index $hex")
        }
    }
}
