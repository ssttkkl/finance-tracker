package com.finance.tracker.business.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class CashWriteSetEncodingTest {
    @Test
    fun checksTheSameUtf8BoundaryAsThePlatformEncoder() {
        for (body in listOf("", "{\"value\":\"abc\"}", "\u007f\u0080\u07ff\u0800\uffff", "😀𐐷账é",
            "\ud800A", "A\udfff", "界\ud800\ud800\udc00", "\udbff\udfff".repeat(50))) {
            for (budget in 0..maxOf(body.length, body.encodeToByteArray().size) + 1) {
                val expected = body.length <= budget && body.encodeToByteArray().size <= budget
                assertEquals(expected, cashWriteSetFitsUtf8Budget(body, budget), "limit=$budget")
            }
        }
    }
}
