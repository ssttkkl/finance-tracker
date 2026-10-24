package com.finance.tracker

import com.finance.tracker.data.*
import com.finance.tracker.domain.*
import com.finance.tracker.presentation.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class DemoCashCreationTest {
    @Test fun ordinaryCreationKeepsExplicitPublicValuesIncludingAccountAttributes() = runTest {
        val finance = DemoFinanceRepository(); val accounts = finance.fetchCashAccounts()
        val input = cashOrdinaryCreation(CashOrdinaryInput(mapOf("account_id" to accounts[0].id.toString(), "currency" to "CNY",
            "amount" to "1.00", "occurred_at" to "2026-10-24T12:30", "record_type" to "income", "note" to "",
            "category_id" to "", "counterparty_account" to "1234", "counterparty_account_attrs" to "tail")), accounts, "UTC")
        DemoCashCreationRepository(finance).createRecord(input)
        val current = DemoCashMaintenanceRepository(finance).record(finance.fetchCashPage(CashFilters()).items.first().transactionId!!)
        assertEquals(StructuredArray(listOf(StructuredText("tail"))), current.entry!!.fields.fields["counterparty_account_attrs"])
        assertEquals(StructuredText(""), current.entry!!.fields.fields["note"])
        assertTrue(current.entry!!.manualFields.containsAll(listOf("note", "category_id", "counterparty_account_attrs")))
    }
    @Test fun exchangeUsesTheCurrentEconomicTypeAndDisplaysBothExactAmounts() = runTest {
        val finance = DemoFinanceRepository(); val accounts = finance.fetchCashAccounts()
        DemoCashCreationRepository(finance).createTransfer(cashTransferCreation(CashTransferInput(accounts[0].id.toString(), accounts[1].id.toString(),
            "CNY", "USD", "12.5000", "1.75000", "2026-10-24T12:30"), accounts, "UTC"))
        val item = finance.fetchCashPage(CashFilters(economicType = "internal_transfer", transferSubtype = "currency_exchange")).items.single()
        assertEquals("internal_transfer", item.economicType); assertTrue(item.statisticsEligible)
        assertEquals("12.5000 CNY → 1.75000 USD", cashProjectionAmountLabel(item))
    }
    @Test fun transferIsOneVisibleResultWithTwoReadonlyMembersAndSafeDeletion() = runTest {
        val finance = DemoFinanceRepository(); val creation = DemoCashCreationRepository(finance)
        val accounts = finance.fetchCashAccounts()
        val before = finance.fetchCashPage(CashFilters()).items
        val input = cashTransferCreation(CashTransferInput(accounts[0].id.toString(), accounts[1].id.toString(), "CNY", "CNY", "12.5000",
            occurredAt = "2026-10-24T12:30", note = "demo transfer"), accounts, "UTC")
        creation.createTransfer(input)
        val page = finance.fetchCashPage(CashFilters())
        assertEquals(before.size + 1, page.items.size)
        val item = page.items.first(); assertEquals("internal_transfer", item.economicType)
        assertTrue(item.statisticsEligible); assertEquals("-12.5000", item.transfer?.fromAmount); assertEquals("12.5000", item.transfer?.toAmount)
        assertEquals(listOf(item.projectionId), finance.fetchCashPage(CashFilters(economicType = "internal_transfer")).items.map { it.projectionId })
        val evidence = finance.fetchEvidence(item.projectionId)
        assertEquals(2, evidence.members.size); assertEquals(1, evidence.acceptedRelations.size)
        val maintenance = DemoCashMaintenanceRepository(finance)
        val current = maintenance.record(item.transactionId!!)
        assertEquals("manual_transfer", current.recordOrigin)
        assertEquals(cashPublicEntryFields, current.entry!!.editableFields.toSet())
        val member = maintenance.record(evidence.members.last().id)
        assertEquals(current.entry, member.entry)
        finance.deleteCashProjections(listOf(item.projectionId), page.projectionVersion)
        assertEquals(before.size, finance.fetchCashPage(CashFilters()).items.size)
    }
}
