package com.finance.tracker

import com.finance.tracker.domain.Account
import com.finance.tracker.domain.CashOrdinaryInput
import com.finance.tracker.domain.CashTransferInput
import com.finance.tracker.presentation.CashDraftAllocation
import com.finance.tracker.presentation.validateCashAllocation
import com.finance.tracker.presentation.validateCashOrdinaryCreation
import com.finance.tracker.presentation.validateCashScalarChanges
import com.finance.tracker.presentation.validateCashTransferCreation
import com.finance.tracker.presentation.validateCashTransferSides
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CashFormValidationTest {
    private val account = Account(id = 1, name = "日常", type = "cash", currencies = listOf("CNY"), active = true)

    @Test
    fun ordinaryCollectsEveryFieldErrorAtOnce() {
        val errors = validateCashOrdinaryCreation(
            CashOrdinaryInput(mapOf(
                "amount" to "abc",
                "currency" to "US",
                "account_id" to "missing",
                "occurred_at" to "",
                "record_type" to "unknown",
            )),
            listOf(account),
            "Asia/Shanghai",
        )
        assertEquals(listOf("amount", "currency", "account_id", "occurred_at", "record_type"), errors.map { it.field })
    }

    @Test
    fun transferCollectsAllSideErrors() {
        val errors = validateCashTransferCreation(
            CashTransferInput(fromAccountId = "missing", toAccountId = "", fromCurrency = "US", toCurrency = "EU",
                amount = "abc", toAmount = "abc", occurredAt = ""),
            listOf(account),
            "Asia/Shanghai",
        )
        assertEquals(setOf("transfer"), errors.map { it.field }.toSet())
        assertEquals(true, errors.size > 1)
    }

    @Test
    fun scalarAndAllocationErrorsAreSeparate() {
        assertEquals(
            setOf("amount", "currency", "occurred_at"),
            validateCashScalarChanges(mapOf("amount" to "abc", "currency" to "US", "occurred_at" to "not-a-date"), "Asia/Shanghai")
                .map { it.field }.toSet(),
        )
        assertEquals(emptyList(), validateCashScalarChanges(mapOf("amount" to "-12.5"), "Asia/Shanghai"))
        val allocation = validateCashAllocation(listOf(CashDraftAllocation(null, 0, "1", "abc", "US")))
        assertNotNull(allocation)
        assertEquals("components", allocation.field)
    }

    @Test
    fun transferSidesReportTransferSection() {
        val errors = validateCashTransferSides(
            mapOf("amount" to "abc", "account_id" to "", "currency" to "US", "occurred_at" to ""),
            mapOf("amount" to "abc", "account_id" to "", "currency" to "US", "occurred_at" to ""),
            "Asia/Shanghai",
        )
        assertEquals(setOf("transfer"), errors.map { it.field }.toSet())
    }
}
