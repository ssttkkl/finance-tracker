package com.finance.tracker

import com.finance.tracker.app.*
import com.finance.tracker.core.*
import com.finance.tracker.data.*
import com.finance.tracker.domain.*
import com.finance.tracker.presentation.*

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CashLedgerDisplayTest {
    @Test fun internalTransferIsLabeledAsTransferRatherThanItsMergeState() {
        for (subtype in listOf("ordinary_transfer", "currency_exchange", "credit_repayment")) {
            assertEquals("ledger_quick_transfer", cashProjectionEconomicTypeResourceKey(
                projection(amount = "0", economicType = "internal_transfer", businessLabel = subtype)))
        }
    }
    @Test
    fun quickEconomicTypesOnlyIncludeExistingFilterOptions() {
        val partial = listOf(CashEconomicTypeFilterOption("expense"), CashEconomicTypeFilterOption("income"))
        assertEquals(listOf("expense", "income"), cashQuickEconomicTypes(partial))
        assertEquals(listOf("expense", "income", "internal_transfer"),
            cashQuickEconomicTypes(partial + CashEconomicTypeFilterOption("internal_transfer", listOf("ordinary_transfer"))))
        assertTrue(cashQuickEconomicTypes(emptyList()).isEmpty())
    }

    @Test
    fun scrollbarProgressMapsToClampedTransactionOrdinals() {
        assertEquals(0, cashLedgerOrdinalForProgress(0f, 5))
        assertEquals(2, cashLedgerOrdinalForProgress(0.5f, 5))
        assertEquals(4, cashLedgerOrdinalForProgress(1f, 5))
        assertEquals(0, cashLedgerOrdinalForProgress(-2f, 1))
        assertEquals(null, cashLedgerOrdinalForProgress(0.5f, 0))
        assertEquals(0, cashLedgerOrdinalForProgress(0.5f, 1))
        assertEquals(5_000, cashLedgerOrdinalForProgress(0.5f, 10_000))
    }

    @Test
    fun scrollbarOrdinalMapsToCachedMonthRangesWithoutLoadedRows() {
        val positions = listOf(
            CashMonthPosition("2026-07", 0, 3),
            CashMonthPosition("2026-06", 3, 5),
        )

        assertEquals("2026-07", cashLedgerMonthForOrdinal(positions, 0))
        assertEquals("2026-07", cashLedgerMonthForOrdinal(positions, 2))
        assertEquals("2026-06", cashLedgerMonthForOrdinal(positions, 3))
        assertEquals("2026-06", cashLedgerMonthForOrdinal(positions, 4))
        assertEquals(null, cashLedgerMonthForOrdinal(positions, -1))
        assertEquals(null, cashLedgerMonthForOrdinal(positions, 5))
    }

    @Test
    fun projectionRowsKeepFullCategoryPathAndBusinessLabels() {
        val category = CashCategory(
            id = "work-meal",
            name = "工作餐",
            path = listOf(CashCategoryPathItem("food", "餐饮")),
            depth = 2,
        )
        val row = projection(amount = "-12.50", category = category)

        assertEquals("餐饮 / 工作餐", cashCategoryDisplayPathValue(category))
        assertEquals("cash_type_expense", cashProjectionEconomicTypeResourceKey(row))
        assertEquals("-12.50 CNY", cashProjectionAmountLabel(row))
        assertNull(cashProjectionSourceResourceKey(row))
    }

    @Test
    fun bankSecurityTransferShowsBothAccountsAndAmounts() {
        val row = projection(
            amount = "0",
            economicType = "internal_transfer",
            businessLabel = "bank_security_transfer",
            transfer = CashTransfer(
                fromAccount = Account(1, "银行卡", "cash"),
                fromAmount = "-100",
                fromCurrency = "USD",
                toAccount = Account(2, "证券账户", "investment"),
                toAmount = "650",
                toCurrency = "CNY",
            ),
        )

        assertEquals("cash_source_bank_security_transfer", cashProjectionEconomicTypeResourceKey(row))
        assertEquals("银行卡 → 证券账户", cashProjectionAccountValue(row))
        assertEquals("100 USD → 650 CNY", cashProjectionAmountLabel(row))
        assertEquals("cash_source_bank_security_transfer", cashProjectionSourceResourceKey(row))
    }

    @Test
    fun evidenceMemberUsesBusinessRoleForTypeAndImpact() {
        val member = EvidenceMember(
            id = "refund-1",
            amount = "-12.50",
            roles = listOf("refund"),
        )

        assertEquals("copy_b82ef83b7f", cashEvidenceMemberResourceKey(member))
        assertEquals("cash_impact_refund", cashEvidenceMemberImpactResourceKey(member))
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
            CashMonthlySummary("2026-04", listOf(CashMonthlyCurrencySummary("CNY", "0", "2.1"))),
            CashMonthlySummary("2026-05", listOf(CashMonthlyCurrencySummary("CNY", "12.3", "0"))),
        )

        val groups = cashProjectionMonthGroups(listOf(april, may), summaries)

        assertEquals(listOf("2026-05", "2026-04"), groups.map { it.month })
        assertEquals(listOf("may"), groups.first().items.map { it.projectionId })
        assertEquals("12.3", groups.first().summary?.currencies?.single()?.income)
        assertEquals(LocalDateDisplayParts(2026, 4, 16), localDateTimeDisplayParts(april.occurredAt)?.date)
    }

    @Test
    fun nativeDateAndTimePickerValuesUseValidatedIsoCalendarValues() {
        assertEquals(0L, isoDateToUtcMillis("1970-01-01"))
        assertEquals("1969-12-31", isoDateFromUtcMillis(-1L))
        assertEquals("2000-02-29", isoDateFromUtcMillis(requireNotNull(isoDateToUtcMillis("2000-02-29"))))
        assertEquals(null, isoDateToUtcMillis("1900-02-29"))
        assertEquals(LocalDateDisplayParts(2026, 4, 15), localDateDisplayParts("2026-04-15"))
        assertEquals(true, isValidLocalDateTime("2026-04-15T23:59"))
        assertEquals(false, isValidLocalDateTime("2026-04-15T24:00"))
        assertEquals(false, isValidLocalDateTime("2026-02-29T12:00"))
    }

    @Test
    fun mergedDetailUsesRepresentativeDateInsteadOfOpenedMemberDate() {
        val member = CashCurrentRecord(recordOrigin = "import", entry = null, transactionId = "bank",
            state = "posted", rowRevision = 1, confirmationStatus = "confirmed",
            fields = StructuredObject(mapOf("occurred_at" to StructuredText("2026-09-25T10:00:00Z"))),
            editableFields = emptyList(), issues = emptyList(), relations = emptyList(), projectionRef = null)
        val merged = projection("-100").copy(occurredAt = "2026-09-24T09:00:00Z")
        assertEquals("2026-09-24T09:00:00Z", cashCurrentRecordOccurredAt(member, merged))
        assertNull(cashCurrentRecordOccurredAt(member, merged.copy(occurredAt = null)))
        assertEquals("2026-09-25T10:00:00Z", cashCurrentRecordOccurredAt(member, null))
    }

    private fun projection(
        amount: String,
        category: CashCategory? = null,
        economicType: String = "expense",
        businessLabel: String? = null,
        transfer: CashTransfer? = null,
    ) = CashProjection(
        projectionId = "projection-1",
        occurredAt = "2026-09-25T09:00:00Z",
        account = Account(1, "银行卡", "cash"),
        category = category,
        amount = amount,
        currency = "CNY",
        economicType = economicType,
        businessLabel = businessLabel,
        recordId = "record-1",
            transactionId = "11",
        transfer = transfer,
    )
}
