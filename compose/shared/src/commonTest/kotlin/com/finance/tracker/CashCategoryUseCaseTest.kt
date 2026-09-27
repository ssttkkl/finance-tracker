package com.finance.tracker

import com.finance.tracker.app.*
import com.finance.tracker.core.*
import com.finance.tracker.data.*
import com.finance.tracker.domain.*
import com.finance.tracker.presentation.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CashCategoryUseCaseTest {
    @Test
    fun saveNormalizesInputAndPreservesExpectedRevision() = runTest {
        val repository = RecordingCashCategoryRepository()

        SaveCashCategoryUseCase(repository)(
            id = null,
            name = "  Dining  ",
            parentId = "food",
            description = "  Everyday meals  ",
            expectedRevision = 17,
        )

        assertEquals("Dining", repository.savedName)
        assertEquals("Everyday meals", repository.savedDescription)
        assertEquals(17, repository.expectedRevision)
    }

    @Test
    fun saveRejectsEmptyNameBeforeCallingRepository() = runTest {
        val repository = RecordingCashCategoryRepository()

        val failure = assertFailsWith<DomainFailure> {
            SaveCashCategoryUseCase(repository)(null, "  ", null, "", 1)
        }

        assertEquals("category.invalid_name", failure.code)
        assertEquals(null, repository.savedName)
    }

    @Test
    fun deleteRequiresMatchingImpactAndLeafCategory() = runTest {
        val repository = RecordingCashCategoryRepository()
        val category = CashCategory("food", name = "Food")

        val childrenFailure = assertFailsWith<DomainFailure> {
            DeleteCashCategoryUseCase(repository)(
                category,
                CashCategoryDeleteImpact("food", 1, 1, childCount = 1, directUsageCount = 0),
            )
        }
        val mismatchFailure = assertFailsWith<DomainFailure> {
            DeleteCashCategoryUseCase(repository)(
                category,
                CashCategoryDeleteImpact("other", 1, 1, childCount = 0, directUsageCount = 0),
            )
        }

        assertEquals("category.has_children", childrenFailure.code)
        assertEquals("category.revision_conflict", mismatchFailure.code)
        assertEquals(null, repository.deletedId)
    }
}

private class RecordingCashCategoryRepository : CashCategoryRepository {
    var savedName: String? = null
    var savedDescription: String? = null
    var expectedRevision: Long? = null
    var deletedId: String? = null

    override suspend fun fetchCashCategories() = CashCategoryDirectory(1, emptyList())
    override suspend fun createCashCategory(name: String, parentId: String?, description: String, expectedRevision: Long): CashCategory {
        savedName = name
        savedDescription = description
        this.expectedRevision = expectedRevision
        return CashCategory("new", parentId, name, description)
    }
    override suspend fun updateCashCategory(id: String, name: String, parentId: String?, description: String, expectedRevision: Long) =
        createCashCategory(name, parentId, description, expectedRevision).copy(id = id)
    override suspend fun reorderCashCategory(id: String, direction: String, expectedRevision: Long) = CashCategory(id, name = "Category")
    override suspend fun fetchCashCategoryDeletionImpact(id: String) = CashCategoryDeleteImpact(id, 1, 1, 0, 0)
    override suspend fun deleteCashCategory(id: String, impact: CashCategoryDeleteImpact): CashCategoryDeleteResult {
        deletedId = id
        return CashCategoryDeleteResult(id, 0, impact.revision)
    }
}
