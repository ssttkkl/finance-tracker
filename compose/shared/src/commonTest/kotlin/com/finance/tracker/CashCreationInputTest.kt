package com.finance.tracker

import com.finance.tracker.domain.*
import com.finance.tracker.presentation.*
import kotlin.test.*

class CashCreationInputTest {
    private val accounts = listOf(Account(1, "Daily", "cash", currencies = listOf("CNY")),
        Account(2, "Bank", "cash", currencies = listOf("CNY", "USD")),
        Account(3, "Inactive", "cash", active = false, currencies = listOf("CNY")))
    private val ordinary = CashOrdinaryInput(mapOf("account_id" to "1", "currency" to "CNY", "amount" to "12345678901234567890.0100",
        "occurred_at" to "2026-10-24T12:30", "record_type" to "consumption", "note" to "", "category_id" to ""))
    private val transfer = CashTransferInput("1", "2", "CNY", "CNY", "12345678901234567890.0100", "999", "2026-10-24T12:30", "transfer")
    @Test fun ordinaryDirectionPreservesExactAmountAndLocalTime() {
        val fields = cashOrdinaryCreation(ordinary, accounts, "Asia/Shanghai").fields
        assertEquals(StructuredText("-12345678901234567890.0100"), fields["amount"])
        assertEquals(StructuredText("2026-10-24T04:30:00Z"), fields["occurred_at"])
        assertEquals(StructuredText("Daily"), fields["account_name"])
        assertFalse("account_id" in fields); assertFalse("state" in fields); assertFalse("projection_version" in fields)
        val income = cashOrdinaryCreation(ordinary.copy(values = ordinary.values + ("record_type" to "income") + ("amount" to "0.0000")), accounts, "UTC")
        assertEquals(StructuredText("0.0000"), income.fields["amount"])
        assertEquals(StructuredText("income"), income.fields["record_type"])
    }
    @Test fun ordinaryRejectsNegativeMagnitudeInvalidDirectionAndHiddenFields() {
        for ((key, value) in listOf("amount" to "-1", "record_type" to "transfer", "source_type" to "forged"))
            assertFailsWith<DomainFailure> { cashOrdinaryCreation(ordinary.copy(values = ordinary.values + (key to value)), accounts, "UTC") }
    }
    @Test fun sameCurrencyUsesOneAmountAndExchangeUsesExactSecondAmount() {
        val single = cashTransferCreation(transfer, accounts, "Asia/Shanghai").fields
        assertEquals(StructuredText(transfer.amount), single["amount"]); assertFalse("to_amount" in single)
        assertEquals(StructuredText("2026-10-24"), single["date"]); assertEquals(StructuredText("04:30:00"), single["time"])
        assertEquals(StructuredText("Bank"), single["to_account"])
        val exchange = cashTransferCreation(transfer.copy(toCurrency = "USD", toAmount = "12.345600"), accounts, "UTC")
        assertEquals(StructuredText("12.345600"), exchange.fields["to_amount"])
        assertEquals(StructuredText("USD"), exchange.fields["to_currency"])
    }
    @Test fun invalidAccountCurrencyTimeAndTransferAmountsNeverBuildCommand() {
        for (value in listOf(transfer.copy(toAccountId = "1"), transfer.copy(fromAccountId = "unknown"), transfer.copy(fromAccountId = "3"),
            transfer.copy(fromCurrency = "USD"), transfer.copy(amount = "0.00"), transfer.copy(amount = "-1"),
            transfer.copy(toCurrency = "USD", toAmount = ""), transfer.copy(occurredAt = "bad")))
            assertFailsWith<DomainFailure> { cashTransferCreation(value, accounts, "UTC") }
    }
}
