package com.finance.tracker

import com.finance.tracker.app.createDemoFinanceTrackerGraph
import com.finance.tracker.domain.*
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
}
