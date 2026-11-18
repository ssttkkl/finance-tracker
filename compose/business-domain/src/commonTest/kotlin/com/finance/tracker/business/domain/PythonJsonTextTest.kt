package com.finance.tracker.business.domain

import kotlinx.serialization.json.*
import kotlin.test.*

class PythonJsonTextTest {
    @Test fun jsonFloatParsingKeepsTheIndependentBinary64BitsAtRoundingBoundaries() {
        // CPython float -> struct.pack('>d', value).hex(); independent of platform parsing.
        val vectors = listOf(
            "-2.796741545048726e-258" to "8a758020ea211271",
            "2.796741545048726e-258" to "0a758020ea211271",
            "2.4703282292062327e-324" to "0000000000000000",
            "2.4703282292062328e-324" to "0000000000000001",
            "1.7976931348623157e308" to "7fefffffffffffff",
            "1.7976931348623159e308" to "7ff0000000000000",
            "-1e-999" to "8000000000000000",
            "1e999" to "7ff0000000000000",
            "0.1000000000000000055511151231257827021181583404541015625" to "3fb999999999999a",
            "0.1000000000000000055511151231257827021181583404541015626" to "3fb999999999999a",
        )
        for ((text, bits) in vectors) assertEquals(bits.toULong(16).toLong(), parsePythonJsonBinary64(text).toBits(), text)
        for ((text, bits) in originalPythonJsonFloatBoundaries)
            assertEquals(bits.toULong(16).toLong(), parsePythonJsonBinary64(text).toBits(), text)
    }

    @Test fun aPoorOrMissingPlatformApproximationCannotChangeTheCorrectRounding() {
        for ((text, bits) in listOf("0.1" to "3fb999999999999a", "1e-325" to "0000000000000000",
            "-2.796741545048726e-258" to "8a758020ea211271", "1.7976931348623157e308" to "7fefffffffffffff")) {
            for (candidate in listOf(null, 0.0, Double.MIN_VALUE, Double.MAX_VALUE, Double.POSITIVE_INFINITY, Double.NaN))
                assertEquals(bits.toULong(16).toLong(), parsePythonJsonBinary64(text) { candidate }.toBits(), "$text / $candidate")
        }
    }

    @Test fun hugeExponentAndZeroInputsDoNotNeedAnApproximationOrExponentExpansion() {
        val huge = "9".repeat(1000)
        val values = listOf("1e$huge" to Double.POSITIVE_INFINITY, "-1e$huge" to Double.NEGATIVE_INFINITY,
            "1e-$huge" to 0.0, "-1e-$huge" to -0.0, "-0.0e$huge" to -0.0, "0e-$huge" to 0.0,
            "0.1e-9223372036854775808" to 0.0, "10e9223372036854775807" to Double.POSITIVE_INFINITY)
        for ((text, expected) in values) assertEquals(expected.toBits(),
            parsePythonJsonBinary64(text) { error("magnitude must be decided before approximation") }.toBits())
    }

    @Test fun legacyNonfiniteValuesAndMalformedNumbersKeepTheirOriginalBoundary() {
        assertTrue(parsePythonJsonBinary64("NaN").isNaN())
        assertEquals(Double.POSITIVE_INFINITY, parsePythonJsonBinary64("Infinity"))
        assertEquals(Double.NEGATIVE_INFINITY, parsePythonJsonBinary64("-Infinity"))
        for (text in listOf("", "one", "true", "+", ".", "1e", "1.2.3")) assertFailsWith<NumberFormatException>(text) {
            parsePythonJsonBinary64(text)
        }
    }

    @Test fun allOriginalPythonJsonTextVectorsRemainUnchanged() {
        for (value in Json.parseToJsonElement(pythonStringGoldens).jsonArray) {
            val case = value.jsonObject
            val raw = case.getValue("json").jsonPrimitive.content
            assertEquals(case.getValue("expected").jsonPrimitive.content, pythonJsonText(Json.parseToJsonElement(raw)), raw)
        }
    }

    @Test fun explicitSurrogateBoundariesPreserveTheOriginalUtf16Contract() {
        val pair = charArrayOf(0xd801.toChar(), 0xdc28.toChar()).concatToString()
        assertEquals(listOf(0xd801, 0xdc28), pair.map { it.code })
        assertEquals("'$pair'", pythonJsonStringRepr(pair))
        for (index in listOf(0, 1)) assertEquals("'\\ud801\\udc28'", pythonJsonStringRepr(pair, setOf(index)))
        val lone = charArrayOf(0xd801.toChar()).concatToString()
        assertEquals(0xd801, lone.single().code)
        assertEquals("'\\ud801'", pythonJsonStringRepr(lone))
        assertEquals("'a\\n\\t\\r\\\\b'", pythonJsonStringRepr("a\n\t\r\\b"))
        assertEquals("\"'\"", pythonJsonStringRepr("'"))
    }
}
