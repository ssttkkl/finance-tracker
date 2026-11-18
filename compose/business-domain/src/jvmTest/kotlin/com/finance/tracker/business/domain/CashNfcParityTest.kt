package com.finance.tracker.business.domain

import java.text.Normalizer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class CashNfcParityTest {
    @Test fun everyCodePointAndLongCombiningRunsMatchOriginalJvm() {
        fun compare(value: String) {
            val expected = Normalizer.normalize(value, Normalizer.Form.NFC)
            val actual = normalizeCashNfc(value)
            if (expected != actual) assertEquals(expected, actual, value.codePoints().toArray().joinToString { it.toString(16) })
        }
        for (code in 0..0x10ffff) compare(String(Character.toChars(code)))
        val marks = listOf(0x300, 0x301, 0x327, 0x315, 0x345, 0x3099, 0x5b0, 0x93c, 0x110ba, 0x1e4ec)
        for (length in listOf(31, 32, 33, 128, 4096, 65_536)) compare(buildString {
            append('A'); repeat(length) { appendCodePoint(marks[it % marks.size]) }
        })
        val random = Random(0x4e4643)
        repeat(4096) {
            compare(buildString { repeat(random.nextInt(64)) { append(random.nextInt(0x10000).toChar()) } })
        }
    }
}
