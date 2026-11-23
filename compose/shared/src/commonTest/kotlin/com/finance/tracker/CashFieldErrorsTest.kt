package com.finance.tracker

import com.finance.tracker.presentation.cashFieldErrorKeyFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CashFieldErrorsTest {
    @Test
    fun ordinaryErrorsMapToTheirFields() {
        assertEquals("amount", cashFieldErrorKeyFor("invalid_amount")?.field)
        assertEquals("amount", cashFieldErrorKeyFor("cash_amount_out_of_range")?.field)
        assertEquals("currency", cashFieldErrorKeyFor("invalid_currency")?.field)
        assertEquals("account_id", cashFieldErrorKeyFor("invalid_account")?.field)
        assertEquals("account_id", cashFieldErrorKeyFor("account_changed")?.field)
        assertEquals("occurred_at", cashFieldErrorKeyFor("invalid_datetime")?.field)
        assertEquals("components", cashFieldErrorKeyFor("invalid_allocation")?.field)
    }

    @Test
    fun splitErrorsStayAtTheAllocationSection() {
        assertEquals("components", cashFieldErrorKeyFor("invalid_amount", split = true)?.field)
        assertEquals("components", cashFieldErrorKeyFor("invalid_currency", split = true)?.field)
        assertEquals("components", cashFieldErrorKeyFor("cash_amount_out_of_range", split = true)?.field)
        assertEquals("components", cashFieldErrorKeyFor("invalid_account", split = true)?.field)
        assertEquals("components", cashFieldErrorKeyFor("account_changed", split = true)?.field)
    }

    @Test
    fun transferErrorsStayAtTheTransferSection() {
        for (code in listOf("invalid_amount", "cash_amount_out_of_range", "invalid_currency", "invalid_account", "invalid_datetime")) {
            assertEquals("transfer", cashFieldErrorKeyFor(code, transfer = true)?.field, code)
        }
    }

    @Test
    fun unknownErrorsStayFormLevel() {
        assertNull(cashFieldErrorKeyFor(null))
        assertNull(cashFieldErrorKeyFor("request_failed"))
        assertNull(cashFieldErrorKeyFor("permission_denied"))
    }
}
