package com.finance.tracker

import com.finance.tracker.app.*
import com.finance.tracker.core.*
import com.finance.tracker.data.*
import com.finance.tracker.domain.*
import com.finance.tracker.presentation.*
import androidx.paging.PagingSource
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
import kotlin.test.assertTrue

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
    fun initializeLoadsReferenceDataWithoutDuplicatingPagingRequests() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val accounts = CompletableDeferred<List<Account>>()
            val repository = FakeCashLedgerRepository(delayedAccounts = accounts)
            val viewModel = CashLedgerViewModel(repository, repository)

            viewModel.initialize()
            runCurrent()
            assertEquals(emptyList(), viewModel.state.value.accounts)

            accounts.complete(listOf(Account(1, "Cash", "cash")))
            advanceUntilIdle()

            assertEquals("Cash", viewModel.state.value.accounts.single().name)
            assertEquals(emptyList(), repository.pageCursors)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun pagingSourceLoadsAbsoluteOrdinalsAndInvalidatesOnVersionChange() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = FakeCashLedgerRepository()
            val source = CashProjectionPagingSource(repository, CashFilters()) { _, _ -> }
            val first = source.load(PagingSource.LoadParams.Refresh(key = null, loadSize = 2, placeholdersEnabled = true)) as PagingSource.LoadResult.Page<Int, CashProjection>
            val seek = source.load(PagingSource.LoadParams.Refresh(key = 3, loadSize = 2, placeholdersEnabled = true)) as PagingSource.LoadResult.Page<Int, CashProjection>

            assertEquals(listOf("row-0", "row-1"), first.data.map { it.projectionId })
            assertEquals(0, first.itemsBefore)
            assertEquals(3, first.itemsAfter)
            assertEquals(2, first.nextKey)
            assertEquals(listOf("row-3", "row-4"), seek.data.map { it.projectionId })
            assertEquals(3, seek.itemsBefore)
            assertEquals(0, seek.itemsAfter)
            assertEquals(1, seek.prevKey)
            assertEquals(listOf(0, 3), repository.pageIndexes)
            assertEquals(listOf<Long?>(null, 3L), repository.pageVersions)

            repository.totalRows = 96
            val lastPartialPage = source.load(
                PagingSource.LoadParams.Refresh(key = 85, loadSize = 20, placeholdersEnabled = true),
            ) as PagingSource.LoadResult.Page<Int, CashProjection>
            assertEquals(listOf("row-85", "row-86", "row-87", "row-88", "row-89", "row-90", "row-91", "row-92", "row-93", "row-94", "row-95"), lastPartialPage.data.map { it.projectionId })
            assertEquals(65, lastPartialPage.prevKey)

            repository.nextPageProjectionVersion = 4L
            val invalidatedSource = CashProjectionPagingSource(repository, CashFilters()) { _, _ -> }
            invalidatedSource.load(PagingSource.LoadParams.Refresh(key = null, loadSize = 2, placeholdersEnabled = true))
            val invalidated = invalidatedSource.load(PagingSource.LoadParams.Refresh(key = 2, loadSize = 2, placeholdersEnabled = true))
            assertTrue(invalidated is PagingSource.LoadResult.Invalid)
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

            assertEquals(filters, viewModel.state.value.filters)
            assertNull(viewModel.state.value.page)
            assertEquals(1, viewModel.state.value.listRevision)
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
            viewModel.openRow(CashProjection("first", "2026-09-26T09:00:00Z", amount = "-10", currency = "CNY", economicType = "expense", recordId = "record-first"))
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
) : CashLedgerRepository, CashCategoryRepository {
    val pageCursors = mutableListOf<String?>()
    val pageIndexes = mutableListOf<Int>()
    val pageVersions = mutableListOf<Long?>()
    val pageFilters = mutableListOf<CashFilters>()
    val relationPageCursors = mutableListOf<String?>()
    var classificationCalls = 0
    var createdWrite: CashRecordWrite? = null
    var nextPageProjectionVersion = 3L
    var totalRows = 5

    override suspend fun fetchCashPage(filters: CashFilters, cursor: String?, startIndex: Int?, projectionVersion: Long?, limit: Int): CashPage {
        pageCursors += cursor
        pageFilters += filters
        val index = startIndex ?: 0
        pageIndexes += index
        pageVersions += projectionVersion
        val version = if (projectionVersion == null) 3L else nextPageProjectionVersion
        val rows = (0 until totalRows).map { projection("row-$it") }.drop(index).take(limit)
        return CashPage(
            projectionVersion = version,
            items = rows,
            pageSize = rows.size,
            startIndex = index,
            totalCount = totalRows,
            itemsBefore = index,
            itemsAfter = totalRows - index - rows.size,
        )
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
