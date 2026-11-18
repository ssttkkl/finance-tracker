package com.finance.tracker.business.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class CashNfcTest {
    @Test fun canonicalCompositionReordersAndKeepsCompatibilityCharacters() {
        assertEquals("é", normalizeCashNfc("e\u0301"))
        assertEquals("Å", normalizeCashNfc("\u212b"))
        assertEquals("Á\u0327", normalizeCashNfc("A\u0301\u0327"))
        assertEquals("A\u0305\u0301", normalizeCashNfc("A\u0305\u0301"))
        assertEquals("\u0308\u0301", normalizeCashNfc("\u0344"))
        assertEquals("각", normalizeCashNfc("\u1100\u1161\u11a8"))
        assertEquals("ﬁＡ", normalizeCashNfc("ﬁＡ"))
    }
    @Test fun nicknameTextAndUnpairedUtf16ArePreserved() {
        val unpaired = buildString { appendPythonCodePoint(0xd800) }
        assertEquals("  é😀$unpaired  ", normalizeCashNfc("  e\u0301😀$unpaired  "))
        assertEquals("\r\n\u0000", normalizeCashNfc("\r\n\u0000"))
    }
    @Test fun equalClassOrderAndLongCombiningRunsUseTheSameCanonicalResult() {
        for (count in listOf(16, 17, 128)) {
            val input = "A" + "\u0315\u0300".repeat(count)
            val expected = "À" + "\u0300".repeat(count - 1) + "\u0315".repeat(count)
            assertEquals(expected, normalizeCashNfc(input), "count $count")
        }
    }

}
