package com.finance.tracker

import com.finance.tracker.app.*
import com.finance.tracker.core.*
import com.finance.tracker.data.*
import com.finance.tracker.domain.*
import com.finance.tracker.presentation.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class CashLedgerViewModelTest {
    @Test
    fun reportsVisibleRecordAndFilterValidationErrorsWithFixedFields() {
        val logger = RecordingDiagnosticLogger()
        val repository = FakeCashLedgerRepository()
        val viewModel = CashLedgerViewModel(repository, repository, diagnostics = logger)

        viewModel.reportInvalidRecordAmount()
        viewModel.reportInvalidRecordDateTime()
        viewModel.reportInvalidAmountFilter()

        assertEquals(
            listOf(
                RecordedDiagnostic(
                    DiagnosticFeature.CASH_LEDGER,
                    DiagnosticAction.VALIDATE_INPUT,
                    "invalid_record",
                    null,
                    "InputValidationFailure",
                ),
                RecordedDiagnostic(
                    DiagnosticFeature.CASH_LEDGER,
                    DiagnosticAction.VALIDATE_INPUT,
                    "invalid_record",
                    null,
                    "InputValidationFailure",
                ),
                RecordedDiagnostic(
                    DiagnosticFeature.CASH_LEDGER,
                    DiagnosticAction.VALIDATE_INPUT,
                    "invalid_filter",
                    null,
                    "InputValidationFailure",
                ),
            ),
            logger.entries,
        )
    }

    @Test
    fun delayedReferenceLoadCannotReplaceTheLoadedPageState() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val accounts = CompletableDeferred<List<Account>>()
            val page = CompletableDeferred<CashPage>()
            val repository = FakeCashLedgerRepository(delayedAccounts = accounts, delayedPage = page)
            val viewModel = CashLedgerViewModel(repository, repository)

            viewModel.initialize()
            runCurrent()
            page.complete(
                CashPage(
                    projectionVersion = 3,
                    items = listOf(
                        CashProjection(
                            projectionId = "first",
                            occurredAt = "2026-09-26T09:00:00Z",
                            amount = "-10",
                            currency = "CNY",
                            economicType = "expense",
                            recordId = "record-first",
                        ),
                    ),
                    pageSize = 1,
                    monthlySummaries = listOf(CashMonthlySummary("2026-09", listOf(CashMonthlyCurrencySummary("CNY", "0", "10")))),
                ),
            )
            runCurrent()
            assertEquals(listOf("first"), viewModel.state.value.rows.map { it.projectionId })
            assertFalse(viewModel.state.value.loading)

            accounts.complete(listOf(Account(1, "Cash", "cash")))
            advanceUntilIdle()

            assertEquals(listOf("first"), viewModel.state.value.rows.map { it.projectionId })
            assertEquals("2026-09", viewModel.state.value.page?.monthlySummaries?.single()?.month)
            assertFalse(viewModel.state.value.loading)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun loadsReferencesAndAppendsOnlyNewRows() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = FakeCashLedgerRepository()
            val viewModel = CashLedgerViewModel(repository, repository)

            viewModel.initialize()
            advanceUntilIdle()

            assertEquals(listOf("first"), viewModel.state.value.rows.map { it.projectionId })
            assertEquals("next", viewModel.state.value.nextCursor)
            assertEquals("Cash", viewModel.state.value.accounts.single().name)

            viewModel.loadMore()
            advanceUntilIdle()

            assertEquals(listOf("first", "second"), viewModel.state.value.rows.map { it.projectionId })
            assertEquals(null, viewModel.state.value.nextCursor)
            assertEquals(listOf(null, "next"), repository.pageCursors)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun filterChangesReplaceRowsAndReadOnlyUserCannotClassify() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = FakeCashLedgerRepository()
            val viewModel = CashLedgerViewModel(repository, repository)
            viewModel.initialize()
            advanceUntilIdle()

            val filters = CashFilters(counterparty = "Market")
            viewModel.updateFilters(filters)
            advanceUntilIdle()
            viewModel.setSelectedCategory("food")
            viewModel.toggleSelection("first", selected = true)
            viewModel.classifySelection(canWrite = false)
            advanceUntilIdle()

            assertEquals(filters, repository.pageFilters.last())
            assertEquals(listOf("first"), viewModel.state.value.rows.map { it.projectionId })
            assertEquals(0, repository.classificationCalls)
            assertFalse(viewModel.state.value.classifyBusy)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun savesRecordThroughDomainRepositoryAndKeepsWriteFieldsTyped() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = FakeCashLedgerRepository()
            val viewModel = CashLedgerViewModel(repository, repository)
            viewModel.initialize()
            advanceUntilIdle()
            viewModel.startNewRecord("2026-09-26T10:30")
            viewModel.updateRecordDraft(viewModel.state.value.recordDraft!!.copy(amount = "12.3400"))
            viewModel.saveRecord(canWrite = true)
            advanceUntilIdle()

            assertEquals("12.3400", repository.createdWrite?.amount)
            assertEquals("Cash", repository.createdWrite?.accountName)
            assertNull(viewModel.state.value.recordDraft)
            assertFalse(viewModel.state.value.recordBusy)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun detailRelationSearchKeepsCursorPagingInViewModelState() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = FakeCashLedgerRepository()
            val viewModel = CashLedgerViewModel(repository, repository)
            viewModel.initialize()
            advanceUntilIdle()
            viewModel.openRow(viewModel.state.value.rows.single())
            advanceUntilIdle()

            viewModel.openRelationComposer()
            advanceUntilIdle()
            assertEquals(listOf("candidate-1"), viewModel.state.value.relationCandidates.map { it.id })
            assertEquals("relation-next", viewModel.state.value.relationNextCursor)

            viewModel.nextRelationPage()
            advanceUntilIdle()

            assertEquals(2, viewModel.state.value.relationPageNumber)
            assertEquals(listOf("candidate-2"), viewModel.state.value.relationCandidates.map { it.id })
            assertEquals(listOf(null, "relation-next"), repository.relationPageCursors)
        } finally {
            Dispatchers.resetMain()
        }
    }
}

private class FakeCashLedgerRepository(
    private val delayedAccounts: CompletableDeferred<List<Account>>? = null,
    private val delayedPage: CompletableDeferred<CashPage>? = null,
) : CashLedgerRepository, CashCategoryRepository {
    val pageCursors = mutableListOf<String?>()
    val pageFilters = mutableListOf<CashFilters>()
    val relationPageCursors = mutableListOf<String?>()
    var classificationCalls = 0
    var createdWrite: CashRecordWrite? = null

    override suspend fun fetchCashPage(filters: CashFilters, cursor: String?): CashPage {
        pageCursors += cursor
        pageFilters += filters
        if (cursor == null && delayedPage != null) return delayedPage.await()
        return when (cursor) {
            null -> CashPage(
                projectionVersion = 3,
                items = listOf(projection("first")),
                nextCursor = "next",
                pageSize = 1,
            )
            else -> CashPage(
                projectionVersion = 3,
                items = listOf(projection("first"), projection("second")),
                nextCursor = null,
                pageSize = 2,
            )
        }
    }

    override suspend fun fetchCashAccounts() = delayedAccounts?.await() ?: listOf(Account(1, "Cash", "cash"))
    override suspend fun fetchCashCategories() = CashCategoryDirectory(1, listOf(CashCategory("food", name = "Food")))
    override suspend fun createCashCategory(name: String, parentId: String?, description: String, expectedRevision: Long) = CashCategory("new", parentId, name, description)
    override suspend fun updateCashCategory(id: String, name: String, parentId: String?, description: String, expectedRevision: Long) = CashCategory(id, parentId, name, description)
    override suspend fun reorderCashCategory(id: String, direction: String, expectedRevision: Long) = CashCategory(id, name = "Food")
    override suspend fun fetchCashCategoryDeletionImpact(id: String) = CashCategoryDeleteImpact(id, 1, 1, 0, 0)
    override suspend fun deleteCashCategory(id: String, impact: CashCategoryDeleteImpact) = CashCategoryDeleteResult(id, 0, impact.revision)
    override suspend fun fetchEvidence(id: String): Evidence = Evidence(
        projectionVersion = 3,
        projection = projection(id.removePrefix("projection-").ifBlank { "first" }),
        rootRecord = EvidenceRecord(
            id = "record-$id",
            occurredAt = "2026-09-26T09:00:00Z",
            amount = "-10",
            currency = "CNY",
        ),
    )
    override suspend fun fetchLedgerOptions() = LedgerOptions(
        recordTypes = listOf(RecordTypeOption("consumption", "消费")),
    )
    override suspend fun classifyCashProjections(ids: List<String>, projectionVersion: Long, categoryId: String?): CashClassificationResult {
        classificationCalls++
        return CashClassificationResult(projectionVersion, ids.size, ids.size, categoryId)
    }
    override suspend fun fetchCashProjectionDeleteImpact(ids: List<String>, projectionVersion: Long) =
        CashProjectionDeleteImpact(ids.size, ids.size, 0)
    override suspend fun deleteCashProjections(ids: List<String>, projectionVersion: Long) =
        CashProjectionDeleteResult(ids.size, ids.size, 0, true, projectionVersion)
    override suspend fun fetchCashRecord(id: String) = CashRecordDetail(cashRecord(id))
    override suspend fun fetchCashRecords(query: String?, excludeId: String?, dateFrom: String?, dateTo: String?, cursor: String?, limit: Int): CashRecordPage {
        relationPageCursors += cursor
        return if (cursor == null) {
            CashRecordPage(listOf(cashRecord("candidate-1")), "relation-next")
        } else {
            CashRecordPage(listOf(cashRecord("candidate-2")), null)
        }
    }
    override suspend fun createCashRecord(write: CashRecordWrite): CashRecordDetail {
        createdWrite = write
        return CashRecordDetail(
            CashRecord(
                id = "created",
                occurredAt = write.occurredAt,
                amount = write.amount,
                currency = write.currency,
                recordType = write.recordType,
                accountName = write.accountName,
            ),
        )
    }
    override suspend fun updateCashRecord(id: String, write: CashRecordWrite): CashRecordDetail = error("Not used by this test")
    override suspend fun deleteCashRecord(id: String, mode: String) = CashRecordDeleteResult(true, 0)
    override suspend fun createCashRelation(primaryFactId: String, secondaryFactId: String, kind: String): CashRecordDetail = error("Not used by this test")
    override suspend fun updateCashRelation(id: String, kind: String): CashRecordDetail = error("Not used by this test")
    override suspend fun cancelCashRelation(id: String) = Unit
    override suspend fun dissolveCashRelations(factId: String): CashRecordDetail = error("Not used by this test")

    private fun projection(id: String) = CashProjection(
        projectionId = id,
        occurredAt = "2026-09-26T09:00:00Z",
        amount = "-10",
        currency = "CNY",
        economicType = "expense",
        recordId = "record-$id",
    )

    private fun cashRecord(id: String) = CashRecord(
        id = id,
        occurredAt = "2026-09-26T09:00:00Z",
        amount = "-10",
        currency = "CNY",
        accountName = "Cash",
        recordType = "consumption",
    )
}
