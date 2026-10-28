package com.finance.tracker

import com.finance.tracker.app.createDemoFinanceTrackerGraph
import com.finance.tracker.domain.*
import com.finance.tracker.data.*
import com.finance.tracker.presentation.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class DemoCashMaintenanceTest {
    @Test fun publicEditUsesSamePageContractAndKeepsSourceAndRowRevision() = runTest {
        val graph = createDemoFinanceTrackerGraph()
        val item = graph.financeRepository.fetchCashPage(CashFilters()).items.first()
        val repo = graph.cashMaintenanceRepository
        val before = repo.record(item.recordId)
        val plan = repo.preview(CashMaintenanceRequest("edit_entry", item.recordId, before.rowRevision,
            StructuredObject(mapOf("note" to StructuredText(""), "counterparty_account_attrs" to StructuredArray(listOf(StructuredText("verified"))))),
            before.entry!!.entryId, before.entry!!.revision))
        assertEquals(emptyList(), plan.additionalEffects)
        assertEquals(before, repo.record(item.recordId))
        val command = ImportFrozenCommand(plan.planId, plan.revision, "demo-original")
        val receipt = repo.commit(command)
        assertEquals(receipt, repo.commit(command))
        val after = repo.record(item.recordId)
        val group = repo.currentGroup(after.projectionRef!!.projectionId)
        assertEquals(group.projectionVersion, after.projectionRef.projectionVersion)
        assertEquals(after.rowRevision, group.members.single().rowRevision)
        assertEquals(before.fields, after.fields)
        assertEquals(before.rowRevision, after.rowRevision)
        assertTrue(after.entry!!.revision > before.entry!!.revision)
        assertEquals(StructuredText(""), after.entry!!.fields.fields["note"])
        assertEquals(StructuredArray(listOf(StructuredText("verified"))), after.entry!!.fields.fields["counterparty_account_attrs"])
        assertEquals("", graph.financeRepository.fetchCashPage(CashFilters()).items.first { it.recordId == item.recordId }.note)
    }
    @Test fun transferEditingPreviewsBothMembersAndCommitsExactAmountsTogether() = runTest {
        val finance = DemoFinanceRepository(); val accounts = finance.fetchCashAccounts()
        DemoCashCreationRepository(finance).createTransfer(cashTransferCreation(CashTransferInput("1", "2", "CNY", "CNY", "12.5000",
            occurredAt = "2026-10-24T12:30"), accounts, "UTC"))
        val repo = DemoCashMaintenanceRepository(finance)
        val item = finance.fetchCashPage(CashFilters(economicType = "internal_transfer")).items.single()
        val group = repo.currentGroup(item.projectionId)
        val members = group.members.map { repo.record(it.transactionId) }; val root = members.first()
        val pair = assertNotNull(cashTransferEntryMembers(root, members))
        val request = cashTransferEntryRequest(root, members, mapOf("note" to "edited"),
            cashTransferEntryValues(pair.outgoing) + ("amount" to "23.75000"), cashTransferEntryValues(pair.incoming), "UTC")
        val plan = repo.preview(request)
        assertEquals(members, group.members.map { repo.record(it.transactionId) })
        val detail = repo.item(plan.planId, plan.items.single().itemId)
        assertEquals(setOf("-23.75000", "23.75000"), detail.members.map { (it.fields.fields["amount"] as StructuredText).value }.toSet())
        repo.commit(ImportFrozenCommand(plan.planId, plan.revision, "pair-save"))
        val updated = group.members.map { repo.record(it.transactionId) }
        assertEquals(setOf("-23.75000", "23.75000"), updated.map { (it.fields.fields["amount"] as StructuredText).value }.toSet())
        assertTrue(updated.all { it.rowRevision == 2L && it.entry!!.revision == 2L })
        assertTrue(updated.all { it.entry!!.fields.fields["note"] == StructuredText("edited") })
        assertEquals("23.75000", finance.fetchCashPage(CashFilters(economicType = "internal_transfer")).items.single().transfer!!.toAmount)
    }
    @Test fun invalidTransferMemberCannotChangeEitherSide() = runTest {
        val finance = DemoFinanceRepository(); val accounts = finance.fetchCashAccounts()
        DemoCashCreationRepository(finance).createTransfer(cashTransferCreation(CashTransferInput("1", "2", "CNY", "USD", "12.5000", "1.75000",
            "2026-10-24T12:30"), accounts, "UTC"))
        val repo = DemoCashMaintenanceRepository(finance)
        val item = finance.fetchCashPage(CashFilters(economicType = "internal_transfer")).items.single()
        val members = repo.currentGroup(item.projectionId).members.map { repo.record(it.transactionId) }; val root = members.first()
        val request = CashMaintenanceRequest("edit_entry", root.transactionId, root.rowRevision, StructuredObject(emptyMap()),
            root.entry!!.entryId, root.entry!!.revision, memberPatches = listOf(
                CashMemberPatch(members[0].transactionId, StructuredObject(mapOf("amount" to StructuredText("-99.0000")))),
                CashMemberPatch(members[1].transactionId, StructuredObject(mapOf("account_id" to StructuredText("9999"))))))
        assertFailsWith<DomainFailure> { repo.preview(request) }
        assertEquals(members, members.map { repo.record(it.transactionId) })
        val pair = assertNotNull(cashTransferEntryMembers(root, members))
        val valid = cashTransferEntryRequest(root, members, emptyMap(), cashTransferEntryValues(pair.outgoing),
            cashTransferEntryValues(pair.incoming) + ("amount" to "2.25000"), "UTC")
        val plan = repo.preview(valid); repo.commit(ImportFrozenCommand(plan.planId, plan.revision, "fx-save"))
        assertEquals("2.25000", finance.fetchCashPage(CashFilters(economicType = "internal_transfer")).items.single().transfer!!.toAmount)
        assertEquals("-12.5000", repo.record(pair.outgoing.transactionId).fields.fields["amount"].let { (it as StructuredText).value })
    }

    @Test fun convertingToExchangeUpdatesTheDerivedTypeAndRevisionOfBothMembers() = runTest {
        val finance = DemoFinanceRepository(); val accounts = finance.fetchCashAccounts()
        DemoCashCreationRepository(finance).createTransfer(cashTransferCreation(CashTransferInput("1", "2", "CNY", "CNY", "12.5000",
            occurredAt = "2026-10-24T12:30"), accounts, "UTC"))
        val repo = DemoCashMaintenanceRepository(finance)
        val item = finance.fetchCashPage(CashFilters(economicType = "internal_transfer")).items.single()
        val members = repo.currentGroup(item.projectionId).members.map { repo.record(it.transactionId) }; val root = members.first()
        val pair = assertNotNull(cashTransferEntryMembers(root, members))
        val request = cashTransferEntryRequest(root, members, emptyMap(), cashTransferEntryValues(pair.outgoing),
            cashTransferEntryValues(pair.incoming) + mapOf("currency" to "USD", "amount" to "1.75000"), "UTC")
        assertEquals(emptyMap(), request.memberPatches.first().patch.fields)
        val plan = repo.preview(request); repo.commit(ImportFrozenCommand(plan.planId, plan.revision, "convert-fx"))
        val out = repo.record(pair.outgoing.transactionId); val incoming = repo.record(pair.incoming.transactionId)
        assertEquals(StructuredText("fx_out"), out.fields.fields["record_type"])
        assertEquals(StructuredText("fx_in"), incoming.fields.fields["record_type"])
        assertEquals(2L, out.rowRevision); assertEquals(2L, incoming.rowRevision)
    }

}
