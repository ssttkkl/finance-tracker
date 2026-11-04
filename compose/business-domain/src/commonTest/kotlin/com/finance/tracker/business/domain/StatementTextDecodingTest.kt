package com.finance.tracker.business.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StatementTextDecodingTest {
    private fun bytes(vararg values: Int) = values.map(Int::toByte).toByteArray()

    @Test fun utf8TakesPrecedenceAndOnlyOneLeadingBomIsRemoved() {
        assertEquals("账单😀", decodeCashStatementText("账单😀".encodeToByteArray()))
        assertEquals("£", decodeCashStatementText(bytes(0xc2, 0xa3)))
        assertEquals("\ufeff原文", decodeCashStatementText("\ufeff\ufeff原文".encodeToByteArray()))
        assertEquals("", decodeCashStatementText(byteArrayOf()))
        assertEquals("a\u0000b", decodeCashStatementText(bytes(0x61, 0, 0x62)))
    }

    @Test fun gb18030SupportsTwoAndFourByteSequencesWithoutReplacement() {
        assertEquals("中文", decodeCashStatementText(bytes(0xd6, 0xd0, 0xce, 0xc4)))
        assertEquals("𐀀", decodeCashStatementText(bytes(0x90, 0x30, 0x81, 0x30)))
        for (input in listOf(bytes(0x80), bytes(0xff), bytes(0x81), bytes(0x81, 0x30),
            bytes(0x81, 0x30, 0x81), bytes(0x81, 0x7f), bytes(0x81, 0x30, 0x80, 0x30))) {
            assertNull(decodeCashStatementText(input), input.joinToString())
        }
    }
}
