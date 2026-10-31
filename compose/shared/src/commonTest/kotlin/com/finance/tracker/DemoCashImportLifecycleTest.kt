package com.finance.tracker

import com.finance.tracker.app.createDemoFinanceTrackerGraph
import com.finance.tracker.domain.*
import com.finance.tracker.data.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class DemoCashImportLifecycleTest {
    private fun minimalImportGraph(): com.finance.tracker.app.FinanceTrackerGraph {
        val finance = DemoFinanceRepository()
        return createDemoFinanceTrackerGraph().copy(financeRepository = finance,
            cashMaintenanceRepository = DemoCashMaintenanceRepository(finance), cashCreationRepository = DemoCashCreationRepository(finance),
            cashImportRepository = DemoCashImportLifecycleRepository(finance), demoStatement = DemoStatementFileSource())
    }
    @Test fun previewItemsKeepNewestTransactionsFirst() = runTest {
        val graph = createDemoFinanceTrackerGraph()
        val repo = graph.cashImportRepository
        val context = repo.upload(listOf(ImportUpload("demo-file", graph.demoStatement!!))).context!!
        val plan = repo.preview(context.sessionId, context.revision!!, null, context.lastChoices ?: ImportChoiceSet())
        val dates = plan.items.map { it.display.occurredAt.orEmpty() }
        assertEquals(dates.sortedDescending(), dates)
        assertEquals(plan.items.map { it.itemId }, repo.items(plan.planId, null, 200, "all", 0).items.map { it.itemId })
    }
    @Test fun currentImportEntryEditsAndOriginalSubmissionAreVisibleInTheSameDemoLedger() = runTest {
        val graph = minimalImportGraph()
        val repo = graph.cashImportRepository
        val context = repo.upload(listOf(ImportUpload("demo-file", graph.demoStatement!!))).context!!
        val first = repo.preview(context.sessionId, context.revision!!, null, context.lastChoices ?: ImportChoiceSet())
        val detail = repo.item(first.planId, first.items.first().itemId)
        val choices = ImportChoiceSet(accountSelections = context.lastChoices!!.accountSelections,
            entries = listOf(ImportEntryChoice(detail.entry!!.editRef!!, StructuredObject(mapOf("note" to StructuredText("edited import"))))))
        val plan = repo.preview(context.sessionId, first.sessionRevision, first.planId, choices)
        val receipt = repo.confirm(plan.planId, plan.sessionRevision, "original-import")
        assertEquals(receipt, repo.confirm(plan.planId, plan.sessionRevision, "original-import"))
        assertEquals(receipt, repo.resolve(plan.planId, "original-import").receipt)
        val rows = graph.financeRepository.fetchCashPage(CashFilters()).items.filter { it.sourceType == "statement_import" }
        assertEquals(2, rows.size)
        assertTrue(rows.any { it.note == "edited import" })
        val edited = graph.cashMaintenanceRepository.record(rows.first { it.note == "edited import" }.transactionId!!)
        assertEquals("import", edited.recordOrigin)
        assertNotEquals(StructuredText("edited import"), edited.fields.fields["note"])
        assertEquals(StructuredText("edited import"), edited.entry!!.fields.fields["note"])
    }

    @Test fun unmappedImportRemainsPendingDraftAndDoesNotEnterStatistics() = runTest {
        val graph = minimalImportGraph(); val repo = graph.cashImportRepository
        val context = repo.upload(listOf(ImportUpload("demo-file", graph.demoStatement!!))).context!!
        val choices = ImportChoiceSet(accountSelections = context.sourceAccounts.map { ImportAccountSelection(it.sourceAccountRef, ImportAccountTarget(null, null)) })
        val plan = repo.preview(context.sessionId, context.revision!!, null, choices)
        assertEquals(2L, plan.itemCounts["pending"])
        val receipt = repo.confirm(plan.planId, plan.sessionRevision, "draft-import")
        assertEquals(2L, receipt.pendingItemCount)
        val rows = graph.financeRepository.fetchCashPage(CashFilters(state = "draft")).items
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.state == "draft" && !it.statisticsEligible && it.account == null })
    }

    @Test fun accountCommandsAreIndependentAndRejectStaleRevisions() = runTest {
        val graph = minimalImportGraph(); val repo = graph.cashImportRepository
        val created = repo.createAccount("Independent account", "cash", listOf("CNY"), "account-create")
        assertEquals(created, repo.createAccount("Independent account", "cash", listOf("CNY"), "account-create"))
        val updated = repo.updateAccount(created.accountId, created.revision, StructuredObject(mapOf("name" to StructuredText("Renamed account"))), "account-update")
        assertEquals(updated, repo.receiptByKey("account-update").account)
        assertFailsWith<DomainFailure> { repo.updateAccount(created.accountId, created.revision, StructuredObject(mapOf("name" to StructuredText("Old"))), "stale-update") }
        assertTrue(graph.financeRepository.fetchCashAccounts().any { it.name == "Renamed account" })
    }
    @Test fun importedDraftKeepsItsIdentityDuringOrdinarySaveAndExplicitFinalization() = runTest {
        val graph = minimalImportGraph(); val importer = graph.cashImportRepository
        val context = importer.upload(listOf(ImportUpload("file", graph.demoStatement!!))).context!!
        val plan = importer.preview(context.sessionId, context.revision!!, null, ImportChoiceSet())
        importer.confirm(plan.planId, plan.sessionRevision, "draft-seed")
        val row = graph.financeRepository.fetchCashPage(CashFilters(state = "draft")).items.first()
        val repo = graph.cashMaintenanceRepository; val before = repo.record(row.transactionId!!)
        assertTrue(before.issues.all { it.nextAction == "edit" })
        val source = before.fields.fields["source_payload"]
        val savedPlan = repo.preview(CashMaintenanceRequest("save_draft", before.transactionId, before.rowRevision,
            StructuredObject(mapOf("account_id" to StructuredText("1")))))
        repo.commit(ImportFrozenCommand(savedPlan.planId, savedPlan.revision, "ordinary-draft-save"))
        val saved = repo.record(before.transactionId)
        assertEquals("draft", saved.state)
        assertEquals(StructuredText("1"), saved.fields.fields["account_id"])
        assertFalse(graph.financeRepository.fetchCashPage(CashFilters(state = "draft")).items.first { it.transactionId == saved.transactionId }.statisticsEligible)
        val finalize = repo.preview(CashMaintenanceRequest("finalize_draft", saved.transactionId, saved.rowRevision))
        repo.commit(ImportFrozenCommand(finalize.planId, finalize.revision, "finalize-draft"))
        val after = repo.record(before.transactionId)
        assertEquals("posted", after.state)
        assertEquals(source, after.fields.fields["source_payload"])
        assertTrue(graph.financeRepository.fetchCashPage(CashFilters()).items.first { it.transactionId == after.transactionId }.statisticsEligible)
    }

    @Test fun importedPublicEditingDoesNotChangeSourceFieldsInTheDemo() = runTest {
        val graph = minimalImportGraph(); val importer = graph.cashImportRepository
        val context = importer.upload(listOf(ImportUpload("file", graph.demoStatement!!))).context!!
        val plan = importer.preview(context.sessionId, context.revision!!, null, context.lastChoices!!)
        importer.confirm(plan.planId, plan.sessionRevision, "posted-seed")
        val row = graph.financeRepository.fetchCashPage(CashFilters()).items.first { it.sourceType == "statement_import" }
        val repo = graph.cashMaintenanceRepository; val before = repo.record(row.transactionId!!)
        val edit = repo.preview(CashMaintenanceRequest("edit_entry", before.transactionId, before.rowRevision,
            StructuredObject(mapOf("note" to StructuredText("public only"))), before.entry!!.entryId, before.entry!!.revision))
        repo.commit(ImportFrozenCommand(edit.planId, edit.revision, "public-edit"))
        val after = repo.record(before.transactionId)
        assertEquals(before.fields, after.fields)
        assertEquals(StructuredText("public only"), after.entry!!.fields.fields["note"])
    }

    @Test fun clearingAnImportAmountNeverCreatesACompleteDemoRecord() = runTest {
        val graph = minimalImportGraph(); val repo = graph.cashImportRepository
        val context = repo.upload(listOf(ImportUpload("file", graph.demoStatement!!))).context!!
        val initial = repo.preview(context.sessionId, context.revision!!, null, ImportChoiceSet())
        val row = initial.items.first().members.single().rowRef!!
        val choices = ImportChoiceSet(rows = listOf(ImportRecordChoice(row, ImportRowDecision.APPLY,
            StructuredObject(mapOf("account_id" to StructuredText("1"), "amount" to StructuredNull)))))
        val plan = repo.preview(context.sessionId, initial.sessionRevision, initial.planId, choices)
        assertEquals("draft", plan.items.first { it.itemId == row }.anticipatedState)
        assertFalse(plan.items.first { it.itemId == row }.statisticsEligible)
        repo.confirm(plan.planId, plan.sessionRevision, "empty-amount")
        assertTrue(graph.financeRepository.fetchCashPage(CashFilters()).items.filter { it.sourceType == "statement_import" }.all { !it.statisticsEligible })
    }

    @Test fun anImportAccountThatCannotHoldTheCurrencyDoesNotPostInTheDemo() = runTest {
        val graph = minimalImportGraph(); val repo = graph.cashImportRepository
        val context = repo.upload(listOf(ImportUpload("file", graph.demoStatement!!))).context!!
        val initial = repo.preview(context.sessionId, context.revision!!, null, context.lastChoices!!)
        val row = initial.items.first().members.single().rowRef!!
        val choices = ImportChoiceSet(accountSelections = context.lastChoices!!.accountSelections,
            rows = listOf(ImportRecordChoice(row, ImportRowDecision.APPLY, StructuredObject(mapOf("currency" to StructuredText("USD"))))))
        val plan = repo.preview(context.sessionId, initial.sessionRevision, initial.planId, choices)
        assertEquals("draft", plan.items.first { it.itemId == row }.anticipatedState)
        assertFalse(plan.items.first { it.itemId == row }.statisticsEligible)
    }

    @Test fun unknownImportedFieldsRemainNullInTheDemoLedger() = runTest {
        val graph = minimalImportGraph(); val repo = graph.cashImportRepository
        val context = repo.upload(listOf(ImportUpload("file", graph.demoStatement!!))).context!!
        val initial = repo.preview(context.sessionId, context.revision!!, null, ImportChoiceSet())
        val row = initial.items.first().members.single().rowRef!!
        val choices = ImportChoiceSet(rows = listOf(ImportRecordChoice(row, ImportRowDecision.APPLY,
            StructuredObject(mapOf("amount" to StructuredNull, "occurred_at" to StructuredNull, "currency" to StructuredNull)))))
        val plan = repo.preview(context.sessionId, initial.sessionRevision, initial.planId, choices)
        repo.confirm(plan.planId, plan.sessionRevision, "unknown-fields")
        val pending = graph.financeRepository.fetchCashPage(CashFilters(state = "draft")).items.first { it.counterparty == plan.items.first { it.itemId == row }.display.counterparty }
        assertNull(pending.amount); assertNull(pending.occurredAt); assertNull(pending.currency)
    }

    @Test fun anInvestmentAccountCannotPostOrFinalizeACashImportInTheDemo() = runTest {
        val graph = minimalImportGraph(); val repo = graph.cashImportRepository
        val context = repo.upload(listOf(ImportUpload("file", graph.demoStatement!!))).context!!
        val initial = repo.preview(context.sessionId, context.revision!!, null, ImportChoiceSet())
        val row = initial.items.first().members.single().rowRef!!
        val choices = ImportChoiceSet(rows = listOf(ImportRecordChoice(row, ImportRowDecision.APPLY,
            StructuredObject(mapOf("account_id" to StructuredText("3"), "currency" to StructuredText("USD"))))))
        val plan = repo.preview(context.sessionId, initial.sessionRevision, initial.planId, choices)
        assertEquals("draft", plan.items.first { it.itemId == row }.anticipatedState)
        repo.confirm(plan.planId, plan.sessionRevision, "investment-account")
        val saved = graph.financeRepository.fetchCashPage(CashFilters(state = "draft")).items.first { it.counterparty == plan.items.first { it.itemId == row }.display.counterparty }
        val current = graph.cashMaintenanceRepository.record(saved.transactionId!!)
        assertFailsWith<DomainFailure> { graph.cashMaintenanceRepository.preview(CashMaintenanceRequest("finalize_draft", current.transactionId, current.rowRevision)) }
        assertTrue(repo.accounts().all { it.type in setOf("cash", "loan", "lend") })
    }

}
