package com.finance.tracker

import kotlin.test.Test
import kotlin.test.assertEquals

class CashImportDisplayTest {
    @Test
    fun previewAmountLabelsAndDirectionsMatchLedgerSemantics() {
        val expense = previewItem("out", "-12.50", "CNY", "consumption")
        val income = previewItem("in", "+7", "USD", "income")
        val zero = previewItem("zero", "-0.00", "CNY", "expense")

        assertEquals("-12.50 CNY", importPreviewAmountLabel(expense))
        assertEquals("expense", importPreviewDirection(expense))
        assertEquals("+7 USD", importPreviewAmountLabel(income))
        assertEquals("income", importPreviewDirection(income))
        assertEquals("0.00 CNY", importPreviewAmountLabel(zero))
        assertEquals("unknown", importPreviewDirection(zero))
        assertEquals("2026年4月15日", importPreviewDateTimeLabel("2026-04-15T12:00:00Z").takeWhile { it != ' ' })
    }

    @Test
    fun previewMonthlySummariesUseLocalCalendarMonthAndExactAmounts() {
        val items = listOf(
            previewItem("income", "+12.30", "CNY", "income", occurredAt = "2026-04-15T12:00:00Z"),
            previewItem("expense", "-2.10", "CNY", "expense", occurredAt = "2026-04-16T12:00:00Z"),
            previewItem("other-month", "3", "CNY", "income", occurredAt = "2026-05-01T12:00:00Z"),
        )

        val april = importPreviewMonthlySummaries(items).first { it.month.endsWith("-04") }

        assertEquals("12.3", april.currencies.single().income)
        assertEquals("2.1", april.currencies.single().expense)
    }

    private fun previewItem(
        id: String,
        amount: String,
        currency: String,
        recordType: String,
        occurredAt: String = "2026-04-01T00:00:00Z",
    ) = ImportPreviewItemDto(
        recordId = id,
        occurredAt = occurredAt,
        amount = amount,
        currency = currency,
        accountName = "账户",
        recordType = recordType,
        recordSubtype = recordType,
        status = "new",
        category = "测试分类",
    )
}
