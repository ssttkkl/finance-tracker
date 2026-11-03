package com.finance.tracker

import com.finance.tracker.app.createDemoFinanceTrackerGraph
import com.finance.tracker.domain.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class DemoCashAuditScenariosTest {
    @Test fun defaultDemoHasTwentyVisibleRowsAndConsistentIndexedPagination() = runTest {
        val repo = createDemoFinanceTrackerGraph().financeRepository
        val first = repo.fetchCashPage(CashFilters(), startIndex = 0, limit = 20)
        assertEquals(24, assertNotNull(first.totalCount))
        assertEquals(20, first.items.size)
        val rest = repo.fetchCashPage(CashFilters(), startIndex = 20, projectionVersion = first.projectionVersion)
        assertEquals(first.totalCount, (first.items + rest.items).map { it.projectionId }.distinct().size)
        assertEquals(first.totalCount, first.monthPositions.sumOf { it.endIndex - it.startIndex })
        assertTrue(first.items.any { it.attentionCount > 0 })
        assertTrue(first.monthlySummaries.first { it.month == "2026-10" }.currencies.size >= 3)
    }

    @Test fun unknownDraftAndExactFilteredSummaryNeverInventAmounts() = runTest {
        val repo = createDemoFinanceTrackerGraph().financeRepository
        val unknown = repo.fetchCashPage(CashFilters(state = "draft")).items.first { it.counterparty == "待补齐账单" }
        assertNull(unknown.amount); assertNull(unknown.currency); assertNull(unknown.occurredAt)
        assertFalse(unknown.statisticsEligible)
        assertFalse(repo.fetchCashPage(CashFilters(amountMin = "0")).items.any { it.transactionId == unknown.transactionId })
        val salary = repo.fetchCashPage(CashFilters(counterparty = "示例公司"))
        assertEquals("12000.00", salary.monthlySummaries.single().currencies.single().income)
        assertEquals("0", salary.monthlySummaries.single().currencies.single().expense)
    }

    @Test fun suggestionsHaveCompleteMembersAndCommitOnlyAfterReview() = runTest {
        val graph = createDemoFinanceTrackerGraph(); val repo = graph.cashMaintenanceRepository
        for ((id, candidates, joint) in listOf(Triple("audit-single", 1, false), Triple("audit-multiple", 2, false), Triple("audit-joint", 1, true))) {
            val before = repo.record(id)
            assertEquals("pending", before.confirmationStatus)
            assertTrue(before.relations.any { it.active && it.state == "draft" })
            val plan = repo.preview(CashMaintenanceRequest("maintain_relations", id, before.rowRevision))
            val group = repo.item(plan.planId, plan.items.single().itemId).relationChoices.single()
            assertEquals(candidates, group.candidates.size)
            assertEquals(joint, group.candidates.any { it.relations.size > 1 })
            (group.members + group.candidates.flatMap { it.members }).forEach { assertNotNull(repo.record(it.transactionId!!)) }
            assertEquals(before, repo.record(id))
            val accepted = repo.preview(CashMaintenanceRequest("maintain_relations", id, before.rowRevision,
                relationChoices = listOf(CashRelationDecision(group.suggestionId, "accept", group.candidates.first().candidateId))))
            assertEquals(before, repo.record(id))
            if (joint) assertEquals("0.00", accepted.items.single().display.amount)
            val command = ImportFrozenCommand(accepted.planId, accepted.revision, "accept-$id")
            assertEquals(repo.commit(command), repo.commit(command))
            assertEquals("confirmed", repo.record(id).confirmationStatus)
            assertTrue(repo.sources("projection-$id").members.size > 1)
            if (joint) assertEquals("0.00", graph.financeRepository.fetchCashPage(CashFilters(hideZeroAmount = false)).items.first { it.transactionId == id }.amount)
        }
    }

    @Test fun associationSearchIncludesOnlyEligibleRowsAndExactDayAmount() = runTest {
        val repo = createDemoFinanceTrackerGraph().cashMaintenanceRepository
        val page = repo.associationTargets("audit-single", CashAssociationSearch(query = "山海咖啡", kind = "payment_mirror"))
        assertTrue(page.items.any { it.unavailableReason == null && it.componentChoices.isNotEmpty() })
        assertTrue(page.items.all { it.unavailableReason == null && it.componentChoices.isNotEmpty() })
        val exact = repo.associationTargets("audit-single", CashAssociationSearch(query = "山海咖啡", amount = "-28.0000", dateFrom = "2026-10-29", dateTo = "2026-10-29", kind = "payment_mirror"))
        assertTrue(exact.items.isNotEmpty())
        assertTrue(exact.items.all { (it.fields.fields["amount"] as StructuredText).value == "-28.00" })
    }

    @Test fun builtInImportHasMultipleSourceGroupsAndReadOnlyDraftAndCurrencyScenarios() = runTest {
        val graph = createDemoFinanceTrackerGraph(); val repo = graph.cashImportRepository
        val context = repo.upload(listOf(ImportUpload("audit-file", graph.demoStatement!!))).context!!
        assertTrue(context.sourceAccounts.size >= 3)
        assertEquals(context.files.sumOf { it.recognizedRowCount ?: 0 }, context.sourceAccounts.sumOf { it.rowCount })
        val plan = repo.preview(context.sessionId, context.revision!!, null, context.lastChoices!!)
        assertTrue(plan.items.any { it.anticipatedState == "draft" && !it.statisticsEligible })
        assertTrue(plan.items.mapNotNull { it.display.currency }.distinct().size >= 3)
        assertTrue(plan.monthlySummaries["all"].orEmpty().any { month -> month.currencies.size >= 3 })
        assertTrue(plan.monthlySummaries["pending"].orEmpty().all { month -> month.currencies.all { it.income == "0" && it.expense == "0" } })
        assertEquals(context.files.sumOf { it.recognizedRowCount ?: 0 }.toLong(), plan.itemCounts["all"])
        assertTrue(repo.item(plan.planId, plan.items.first().itemId).members.first().fields.fields["source_payload"] is StructuredObject)
    }
}
