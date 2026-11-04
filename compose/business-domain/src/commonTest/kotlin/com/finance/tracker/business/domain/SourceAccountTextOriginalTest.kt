package com.finance.tracker.business.domain

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals

class SourceAccountTextOriginalTest {
    @Test fun originalSourcePatternResultsMatchAcrossPlatforms() {
        val cases = Json.parseToJsonElement(SourceAccountTextOriginalVectors.json).jsonArray
        assertEquals(8_604, cases.size)
        val installment = CashSourceAccountTextPatterns.regex("(?U)分期\\s*[（(]\\s*\\d+\\s*期\\s*[）)]")
        val remaining = CashSourceAccountTextPatterns.regex("(?U)\\s*[（(]?\\d+\\s*期[）)]?")
        val separators = CashSourceAccountTextPatterns.regex("[\\s\\-－—()（）]")
        val identifier = CashSourceAccountTextPatterns.regex("(?U)\\d{12,19}")
        val masked = CashSourceAccountTextPatterns.regex("(?U)\\d{4,6}[*＊]{2,}\\d{4}")
        for ((index, item) in cases.withIndex()) {
            val row = item.jsonArray
            val text = row[0].jsonPrimitive.content
            assertEquals(row[1].jsonPrimitive.contentOrNull, CashSourceAccountTextPatterns.explicitAmount(text), "amount $index")
            assertEquals(row[2].jsonPrimitive.content, installment.replace(text, ""), "installment $index")
            assertEquals(row[3].jsonPrimitive.content, remaining.replace(text, ""), "remaining $index")
            assertEquals(row[4].jsonPrimitive.content, separators.replace(text, ""), "separators $index")
            assertEquals(row[5].jsonPrimitive.boolean, identifier.matches(text), "identifier $index")
            assertEquals(row[6].jsonPrimitive.boolean, masked.matches(text), "masked $index")
        }
    }
}
