package com.finance.tracker.business.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

class LocalCashMerchantTest {
    @Test fun allOriginalInputExpectedPairsRemainUnchanged() {
        assertEquals("1315eef086cab30c18af4cbffe6b62c48914525c5122bb00b0fc36a2d7ea14e3", sha256Hex(MerchantTextOriginalVectors.json))
        val rows = Json.parseToJsonElement(MerchantTextOriginalVectors.json).jsonArray
        assertEquals(21309, rows.size)
        for ((index, raw) in rows.withIndex()) {
            val row = raw.jsonArray
            assertEquals(row[1].jsonPrimitive.content, normalizeCashMerchant(row[0].jsonPrimitive.content), "original vector $index")
        }
        assertEquals("", normalizeCashMerchant(null))
    }

    @Test fun fullCodePointDigestKeepsFilteringAndScalarMapping() {
        val text = buildString {
            for (point in 0..0x10ffff) {
                val input = buildString { appendPythonCodePoint(point) }
                append(normalizeCashMerchant(input))
                append('\u0000')
            }
        }
        assertEquals(MerchantTextOriginalVectors.scalarHash, sha256Hex(text))
    }

    @Test fun sigmaUsesLegacyCasedContextAndOriginalUtf16Coordinates() {
        for ((input, expected) in listOf(
            "A1Σ" to "a1ς", "AΣ‿A" to "aσ‿a", "AΣª" to "aςª", "AΣʰ" to "aσʰ",
            "AΣ\u0345" to "aσ\u0345", "AΣⅠ" to "aσⅰ", "AΣ𐀀A" to "aς𐀀a",
            "AΣ𐐀A" to "aσ𐐨a", "AΣ𠀀A" to "aς𠀀a", "A\ud800Σ" to "aς",
            "İAΣ" to "i\u0307aς", "A_Σ!A" to "aσa", "AΣⒶ" to "aςⓐ",
        )) assertEquals(expected, normalizeCashMerchant(input), input)
    }

    @Test fun queryHistoryCanChangeBoundaryAndZeroDoesNotMoveTheCursor() {
        val cursor = LocalCashMerchantBoundary("AΣ‿𐅀A")
        for ((query, expected, position) in listOf(
            Triple(1, false, 2), Triple(0, true, 2), Triple(2, true, 2),
            Triple(3, true, 3), Triple(5, false, 6),
        )) {
            assertEquals(expected, cursor.isBoundary(query), "query $query")
            assertEquals(position, cursor.current, "position after $query")
        }
        val fresh = LocalCashMerchantBoundary("AΣ‿𐅀A")
        assertEquals(true, fresh.isBoundary(5))
        assertEquals(5, fresh.current)
    }

    @Test fun longMarksConnectorsAndMultipleSigmasKeepEveryCharacter() {
        val size = 16384
        val marks = "\u0301".repeat(size)
        assertEquals("a${marks}ς", normalizeCashMerchant("A${marks}Σ"))
        assertEquals("aς$marks", normalizeCashMerchant("AΣ$marks"))
        assertEquals("aσ".repeat(size - 1) + "aς", normalizeCashMerchant("AΣ".repeat(size)))
        assertEquals("a‿".repeat(size) + "ς", normalizeCashMerchant("A‿".repeat(size) + "Σ"))
        assertEquals("σ\u0301".repeat(size - 1) + "ς\u0301", normalizeCashMerchant("Σ\u0301".repeat(size)))
        assertEquals("aς𐀀a".repeat(size), normalizeCashMerchant("AΣ𐀀A".repeat(size)))
    }

    @Test fun queryingImmediatelyAfterAnArtificialBoundaryStillRelocates() {
        val cursor = LocalCashMerchantBoundary("AΣ𐀀‿A")
        assertEquals(true, cursor.isBoundary(4))
        assertEquals(4, cursor.current)
        assertEquals(false, cursor.isBoundary(5))
        assertEquals(6, cursor.current)
    }
}
