package com.finance.tracker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CashLedgerDisplayTest {
    @Test
    fun projectionRowsKeepFullCategoryPathAndBusinessLabels() {
        val category = CashCategoryDto(
            id = "work-meal",
            name = "工作餐",
            path = listOf(CashCategoryPathItemDto("food", "餐饮")),
            depth = 2,
        )
        val row = projection(amount = "-12.50", category = category)

        assertEquals("餐饮 / 工作餐", cashCategoryDisplayPath(category))
        assertEquals("消费", cashProjectionEconomicTypeLabel(row))
        assertEquals("-12.50 CNY", cashProjectionAmountLabel(row))
        assertNull(cashProjectionSourceLabel(row))
    }

    @Test
    fun bankSecurityTransferShowsBothAccountsAndAmounts() {
        val row = projection(
            amount = "0",
            economicType = "internal_transfer",
            transferSubtype = "bank_security_transfer",
            transfer = CashTransferDto(
                fromAccount = AccountDto(1, "银行卡", "cash"),
                fromAmount = "-100",
                fromCurrency = "USD",
                toAccount = AccountDto(2, "证券账户", "investment"),
                toAmount = "650",
                toCurrency = "CNY",
            ),
        )

        assertEquals("银证转账", cashProjectionEconomicTypeLabel(row))
        assertEquals("银行卡 → 证券账户", cashProjectionAccountLabel(row))
        assertEquals("100 USD → 650 CNY", cashProjectionAmountLabel(row))
        assertEquals("银证转账", cashProjectionSourceLabel(row))
    }

    @Test
    fun evidenceMemberUsesBusinessRoleForTypeAndImpact() {
        val member = EvidenceMemberDto(
            id = "refund-1",
            amount = "-12.50",
            roles = listOf("refund"),
        )

        assertEquals("退款", cashEvidenceMemberLabel(member, LedgerOptionsDto()))
        assertEquals("已计入退款进度。", cashEvidenceMemberImpactLabel(member))
    }

    @Test
    fun projectionMonthGroupsUseLocalTimeAndKeepTheMatchingSummaryWithRows() {
        val april = projection(amount = "-2.10").copy(
            projectionId = "april",
            occurredAt = "2026-04-16T12:00:00Z",
        )
        val may = projection(amount = "+12.30").copy(
            projectionId = "may",
            occurredAt = "2026-05-01T12:00:00Z",
        )
        val summaries = listOf(
            CashMonthlySummaryDto("2026-04", listOf(CashMonthlyCurrencySummaryDto("CNY", "0", "2.1"))),
            CashMonthlySummaryDto("2026-05", listOf(CashMonthlyCurrencySummaryDto("CNY", "12.3", "0"))),
        )

        val groups = cashProjectionMonthGroups(listOf(april, may), summaries)

        assertEquals(listOf("2026-05", "2026-04"), groups.map { it.month })
        assertEquals(listOf("may"), groups.first().items.map { it.projectionId })
        assertEquals("12.3", groups.first().summary?.currencies?.single()?.income)
        assertEquals("2026年4月16日", localDateTimeDisplayLabel(april.occurredAt).substringBeforeLast(' '))
    }

    @Test
    fun nativeDateAndTimePickerValuesUseValidatedIsoCalendarValues() {
        assertEquals(0L, isoDateToUtcMillis("1970-01-01"))
        assertEquals("1969-12-31", isoDateFromUtcMillis(-1L))
        assertEquals("2000-02-29", isoDateFromUtcMillis(requireNotNull(isoDateToUtcMillis("2000-02-29"))))
        assertEquals(null, isoDateToUtcMillis("1900-02-29"))
        assertEquals("2026年4月15日", isoDateDisplayLabel("2026-04-15"))
        assertEquals(true, isValidLocalDateTime("2026-04-15T23:59"))
        assertEquals(false, isValidLocalDateTime("2026-04-15T24:00"))
        assertEquals(false, isValidLocalDateTime("2026-02-29T12:00"))
    }

    private fun projection(
        amount: String,
        category: CashCategoryDto? = null,
        economicType: String = "expense",
        transferSubtype: String? = null,
        transfer: CashTransferDto? = null,
    ) = CashProjectionDto(
        projectionId = "projection-1",
        occurredAt = "2026-09-25T09:00:00Z",
        account = AccountDto(1, "银行卡", "cash"),
        category = category,
        amount = amount,
        currency = "CNY",
        economicType = economicType,
        transferSubtype = transferSubtype,
        recordId = "record-1",
        transfer = transfer,
    )
}
