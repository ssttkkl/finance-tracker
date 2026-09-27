package com.finance.tracker

import com.finance.tracker.app.*
import com.finance.tracker.core.*
import com.finance.tracker.data.*
import com.finance.tracker.domain.*
import com.finance.tracker.presentation.*

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CashCategoryViewModelTest {
    @Test
    fun createsCategoryThroughRepositoryAndRefreshesDirectoryState() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = FakeCashCategoryRepository()
            val viewModel = CashCategoryViewModel(repository)
            viewModel.load()
            advanceUntilIdle()
            viewModel.startCreate()
            viewModel.updateDraft(viewModel.state.value.draft!!.copy(name = "Dining"))
            viewModel.save(canWrite = true)
            advanceUntilIdle()

            assertEquals(listOf("Dining"), repository.createdNames)
            assertNull(viewModel.state.value.draft)
            assertFalse(viewModel.state.value.loading)
            assertEquals("Dining", viewModel.state.value.directory?.items?.last()?.name)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun requiresDeleteImpactBeforeConfirmingDeletion() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = FakeCashCategoryRepository()
            val viewModel = CashCategoryViewModel(repository)
            viewModel.load()
            advanceUntilIdle()
            val item = viewModel.state.value.directory!!.items.single()
            viewModel.requestDelete(item, canWrite = true)
            advanceUntilIdle()

            assertNotNull(viewModel.state.value.deleteImpact)
            assertEquals(0, repository.deletedIds.size)
            viewModel.confirmDelete(canWrite = true)
            advanceUntilIdle()

            assertEquals(listOf(item.id), repository.deletedIds)
            assertNull(viewModel.state.value.deleting)
        } finally {
            Dispatchers.resetMain()
        }
    }
}

private class FakeCashCategoryRepository : CashCategoryRepository {
    val createdNames = mutableListOf<String>()
    val deletedIds = mutableListOf<String>()
    private var items = listOf(CashCategory("food", name = "Food"))

    override suspend fun fetchCashCategories(): CashCategoryDirectory = CashCategoryDirectory(1, items)
    override suspend fun createCashCategory(name: String, parentId: String?, description: String, expectedRevision: Long): CashCategory {
        createdNames += name
        return CashCategory("new-${createdNames.size}", parentId, name, description)
            .also { items = items + it }
    }
    override suspend fun updateCashCategory(id: String, name: String, parentId: String?, description: String, expectedRevision: Long): CashCategory =
        CashCategory(id, parentId, name, description)
    override suspend fun reorderCashCategory(id: String, direction: String, expectedRevision: Long): CashCategory = items.first { it.id == id }
    override suspend fun fetchCashCategoryDeletionImpact(id: String): CashCategoryDeleteImpact =
        CashCategoryDeleteImpact(id, 1, 1, childCount = 0, directUsageCount = 0)
    override suspend fun deleteCashCategory(id: String, impact: CashCategoryDeleteImpact): CashCategoryDeleteResult {
        deletedIds += id
        items = items.filterNot { it.id == id }
        return CashCategoryDeleteResult(id, clearedTransactionCount = 0, revision = 2)
    }
}
