package com.finance.tracker

import com.finance.tracker.app.createDemoFinanceTrackerGraph
import com.finance.tracker.domain.CashRecordWrite
import com.finance.tracker.domain.CashFilters
import com.finance.tracker.domain.DomainFailure
import com.finance.tracker.domain.ImportMappingDecision
import com.finance.tracker.domain.WorkspaceRole
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComposeDemoModeTest {
    @Test
    fun demoGraphStartsWithFixedSessionWithoutProductionClient() = runTest {
        val graph = createDemoFinanceTrackerGraph()

        assertNull(graph.apiClient)
        assertTrue(graph.sessionRepository.hasStoredSessionToken())
        assertEquals("demo@example.test", graph.sessionRepository.restoreSession().user.email)
        assertEquals("demo-workspace", graph.sessionRepository.restoreSession().activeWorkspaceId)
        assertNotNull(graph.demoStatement)
    }

    @Test
    fun financeMutationsAreVisibleInTheCurrentGraphAndResetWithANewGraph() = runTest {
        val graph = createDemoFinanceTrackerGraph()
        val write = CashRecordWrite(
            occurredAt = "2026-10-12T10:00:00Z",
            amount = "-12.50",
            currency = "CNY",
            accountName = "日常账户",
            recordType = "consumption",
            counterparty = "演示测试",
        )

        val created = graph.financeRepository.createCashRecord(write)
        val currentRows = graph.financeRepository.fetchCashRecords().items
        val restartedRows = createDemoFinanceTrackerGraph().financeRepository.fetchCashRecords().items

        assertTrue(currentRows.any { it.id == created.record.id && it.amount == "-12.50" })
        assertFalse(restartedRows.any { it.amount == "-12.50" && it.counterparty == "演示测试" })
    }

    @Test
    fun demoCashRecordsSupportCreateUpdateAndDeleteInMemory() = runTest {
        val repository = createDemoFinanceTrackerGraph().financeRepository
        val write = CashRecordWrite(
            occurredAt = "2026-10-12T10:00:00Z",
            amount = "-12.50",
            currency = "CNY",
            accountName = "日常账户",
            recordType = "consumption",
            counterparty = "演示测试",
        )

        val created = repository.createCashRecord(write)
        val updated = repository.updateCashRecord(created.record.id, write.copy(amount = "-15.00", counterparty = "演示编辑"))

        assertEquals("-15.00", updated.record.amount)
        assertEquals("演示编辑", updated.record.counterparty)
        assertTrue(repository.fetchCashRecords().items.any { it.id == created.record.id && it.amount == "-15.00" })

        repository.deleteCashRecord(created.record.id, mode = "record")

        assertFalse(repository.fetchCashRecords().items.any { it.id == created.record.id })
    }

    @Test
    fun demoLedgerAppliesExactSignedAmountFiltersAndRejectsInvalidAmounts() = runTest {
        val repository = createDemoFinanceTrackerGraph().financeRepository

        val betweenNegativeBounds = repository.fetchCashPage(CashFilters(amountMin = "-20", amountMax = "-10"))
        val minimumOnly = repository.fetchCashPage(CashFilters(amountMin = "10000.01"))
        val invalidFilter = runCatching { repository.fetchCashPage(CashFilters(amountMin = "not-a-number")) }.exceptionOrNull()

        assertEquals(listOf("demo-record-1"), betweenNegativeBounds.items.map { it.recordId })
        assertEquals(listOf("demo-record-3"), minimumOnly.items.map { it.recordId })
        assertEquals("invalid_filter", (invalidFilter as? DomainFailure)?.code)
    }

    @Test
    fun demoCategoryAndWorkspaceActionsMutateOnlyTheCurrentGraph() = runTest {
        val graph = createDemoFinanceTrackerGraph()
        val members = graph.sessionRepository.members()
        assertTrue(members.members.any { it.userId == "demo-editor" })
        graph.sessionRepository.updateMember("demo-editor", WorkspaceRole.VIEWER)
        assertEquals(WorkspaceRole.VIEWER, graph.sessionRepository.members().members.first { it.userId == "demo-editor" }.role)
        graph.sessionRepository.removeMember("demo-editor")
        assertFalse(graph.sessionRepository.members().members.any { it.userId == "demo-editor" })

        val initialCategories = graph.financeRepository.fetchCashCategories()
        val createdCategory = graph.financeRepository.createCashCategory("旅行", null, "", initialCategories.revision.toLong())
        val updatedCategory = graph.financeRepository.updateCashCategory(
            createdCategory.id,
            "出行",
            null,
            "",
            initialCategories.revision.toLong(),
        )
        val deleteImpact = graph.financeRepository.fetchCashCategoryDeletionImpact(updatedCategory.id)
        graph.financeRepository.deleteCashCategory(updatedCategory.id, deleteImpact)
        assertFalse(graph.financeRepository.fetchCashCategories().items.any { it.id == updatedCategory.id })

        val createdSession = graph.sessionRepository.createWorkspace("短途旅行")
        val createdWorkspaceId = assertNotNull(createdSession.activeWorkspaceId)
        assertEquals(WorkspaceRole.ADMIN, createdSession.workspaces.first { it.id == createdWorkspaceId }.role)
        graph.sessionRepository.updateWorkspace("周末旅行")
        assertEquals("周末旅行", graph.sessionRepository.workspaceDetails().workspace.name)
        val invitation = graph.sessionRepository.invite(WorkspaceRole.EDITOR)
        assertTrue(graph.sessionRepository.invitationPreview(invitation).valid)
        val accepted = graph.sessionRepository.acceptInvitation(invitation)
        assertEquals(WorkspaceRole.EDITOR, accepted.workspaces.first { it.id == accepted.activeWorkspaceId }.role)

        val restartedSession = createDemoFinanceTrackerGraph().sessionRepository.restoreSession()
        assertEquals("demo-workspace", restartedSession.activeWorkspaceId)
        assertFalse(restartedSession.workspaces.any { it.id == createdWorkspaceId })
    }

    @Test
    fun builtInStatementCompletesTheLocalImportContract() = runTest {
        val graph = createDemoFinanceTrackerGraph()
        val statement = assertNotNull(graph.demoStatement)
        val detection = graph.financeRepository.detectCashImport(statement)
        val scan = graph.financeRepository.scanCashImport(listOf(statement))
        val token = assertNotNull(scan.importToken)
        val preview = graph.financeRepository.previewCashImport(token)
        val decisions = preview.mapping.map { ImportMappingDecision(it.groupId, accountId = it.accountId) }
        val result = graph.financeRepository.commitCashImport(
            importToken = token,
            previewDigest = preview.file.digest,
            previewRelationDigest = preview.relationDigest,
            mapping = decisions,
        )

        assertTrue(detection.rowCount > 0)
        assertTrue(scan.ready == true)
        assertTrue(preview.summary.new > 0)
        assertTrue(result.newRows > 0)
        assertTrue(graph.financeRepository.fetchCashRecords().items.any { it.sourceType == "statement_import" })
        assertFalse(createDemoFinanceTrackerGraph().financeRepository.fetchCashRecords().items.any { it.sourceType == "statement_import" })
    }
}
