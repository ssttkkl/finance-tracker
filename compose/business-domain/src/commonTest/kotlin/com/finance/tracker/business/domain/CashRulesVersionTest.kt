package com.finance.tracker.business.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CashRulesVersionTest {
    @Test
    fun rulesVersionIsStableAndObservable() {
        assertTrue(CASH_RULES_VERSION.isNotBlank())
        // 端侧据此判断是否需要整体重派生；语义变化时必须显式修改此值。
        assertEquals("cash-rules-v1", CASH_RULES_VERSION)
    }
}
