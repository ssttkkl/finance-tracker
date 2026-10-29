package com.finance.tracker.backend

import kotlin.test.*
import kotlinx.serialization.json.*

class CategoryRevisionInputMigrationTest {
    @Test fun integerParsingDoesNotAdmitTheFourExtraStringSplitControlSeparators() {
        for (code in 0x1c..0x1f) for (text in listOf("${code.toChar()}7", "7${code.toChar()}")) {
            val failure = assertFailsWith<CategoryFailure>(text) { categoryRevisionMatches(JsonPrimitive(text), null, 7) }
            assertTrue(failure.code.startsWith("invalid literal for int() with base 10:"))
        }
        for (space in listOf(" ", "\t", "\r", "\n", "\u0085", "\u2007", "\u3000")) {
            assertTrue(categoryRevisionMatches(JsonPrimitive(space + "7" + space), null, 7))
        }
        for (text in listOf("٧", "７", "+0_7", "𝟕")) assertTrue(categoryRevisionMatches(JsonPrimitive(text), null, 7))
        assertTrue(categoryRevisionMatches(Json.parseToJsonElement("6.99999999999999999"), null, 7))
        assertFalse(categoryRevisionMatches(JsonPrimitive("99999999999999999999999999"), null, 7))
    }
}
