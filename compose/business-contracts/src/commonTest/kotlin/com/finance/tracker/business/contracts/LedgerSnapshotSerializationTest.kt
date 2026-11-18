package com.finance.tracker.business.contracts

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import kotlin.test.*

class LedgerSnapshotSerializationTest {
    @Test fun snapshotJsonNumbersRetainTheirLexemesOnEveryPlatform() {
        val tokens = Json.parseToJsonElement("""{"nested":[9007199254740993,1e0,-0,1.2300,1e400,18446744073709551616,true,null,"1e0"]}""")
        val value = LedgerAccountFactDto("9007199254740993", "synthetic", "cash", true, tokens, "9223372036854775807")
        val encoded = Json.encodeToString(value)
        assertEquals(tokens, Json.parseToJsonElement(encoded).jsonObject["currencies"])
        assertEquals(value, Json.decodeFromString<LedgerAccountFactDto>(encoded))
        assertEquals(value, Json.decodeFromJsonElement<LedgerAccountFactDto>(Json.encodeToJsonElement(value)))
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test fun invalidUnquotedValuesCannotEscapeIntoSnapshotJson() {
        for (bad in listOf("NaN", "Infinity", "01", "1e", "0,\"injected\":true")) {
            val value = LedgerAccountFactDto("1", "synthetic", "cash", true, JsonUnquotedLiteral(bad), "1")
            assertFailsWith<IllegalArgumentException>(bad) { Json.encodeToString(value) }
        }
        for (valid in listOf(JsonNull, JsonPrimitive(true), JsonPrimitive("NaN"), JsonArray(emptyList()), JsonObject(emptyMap()))) {
            val value = LedgerAccountFactDto("1", "synthetic", "cash", true, valid, "1")
            assertEquals(value, Json.decodeFromString<LedgerAccountFactDto>(Json.encodeToString(value)))
        }
    }
}
