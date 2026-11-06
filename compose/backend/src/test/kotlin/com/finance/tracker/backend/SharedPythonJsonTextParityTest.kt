package com.finance.tracker.backend

import com.finance.tracker.business.domain.pythonJsonStringRepr
import com.finance.tracker.business.domain.pythonJsonText
import java.security.MessageDigest
import kotlinx.serialization.json.*
import kotlin.test.*

class SharedPythonJsonTextParityTest {
    @Test fun everyUnicodeCodePointMatchesTheFrozenOriginalNativeRepresentationDigest() {
        val digest = MessageDigest.getInstance("SHA-256")
        for (code in 0..0x10ffff) {
            digest.update(pythonJsonStringRepr(String(Character.toChars(code))).toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
        }
        // Captured from the original backend implementation before its shared delegation.
        assertEquals("98f10605ea58fdbfcfc9eda665a3c9047583e020957c1d847f2f3d69e55ccf95",
            digest.digest().joinToString("") { "%02x".format(it) })
    }
    @Test fun sharedTextMatchesTheUnmodifiedOriginalPythonGolden() {
        for (value in Json.parseToJsonElement(pythonStringGoldens).jsonArray) {
            val raw = Json.parseToJsonElement(value.jsonObject.getValue("json").jsonPrimitive.content)
            assertEquals(value.jsonObject.getValue("expected").jsonPrimitive.content, pythonJsonText(raw))
        }
    }

    @Test fun explicitSurrogateSplitsAndLargeIntegerNormalizationKeepTheirOriginalResults() {
        val text = "a𐐨b𐀀c"
        val expected = listOf("'a𐐨b𐀀c'", "'a\\ud801\\udc28b𐀀c'", "'a\\ud801\\udc28b𐀀c'",
            "'a𐐨b𐀀c'", "'a𐐨b\\ud800\\udc00c'", "'a𐐨b\\ud800\\udc00c'", "'a𐐨b𐀀c'")
        for (index in text.indices) assertEquals(expected[index], pythonJsonStringRepr(text, setOf(index)))
        for (raw in listOf("-0", "0001", "-0001", "9".repeat(5000))) {
            val value = Json.parseToJsonElement(raw)
            val expectedInteger = when (raw) { "-0" -> "0"; "0001" -> "1"; "-0001" -> "-1"; else -> raw }
            assertEquals(expectedInteger, pythonJsonText(value))
        }
    }
}
