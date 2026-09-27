package com.finance.tracker.domain

class SaveCashCategoryUseCase(private val repository: CashCategoryRepository) {
    suspend operator fun invoke(
        id: String?,
        name: String,
        parentId: String?,
        description: String,
        expectedRevision: Long,
    ): CashCategory {
        val normalizedName = name.trim()
        val normalizedDescription = description.trim()
        if (normalizedName.isEmpty()) {
            throw DomainFailure("category.invalid_name", 400, FailureCategory.RECOVERABLE)
        }
        if (normalizedName.length > CASH_CATEGORY_NAME_MAX_LENGTH) {
            throw DomainFailure("category.invalid_name", 400, FailureCategory.RECOVERABLE)
        }
        if (normalizedDescription.length > CASH_CATEGORY_DESCRIPTION_MAX_LENGTH) {
            throw DomainFailure("category.invalid_description", 400, FailureCategory.RECOVERABLE)
        }

        return if (id == null) {
            repository.createCashCategory(normalizedName, parentId, normalizedDescription, expectedRevision)
        } else {
            repository.updateCashCategory(id, normalizedName, parentId, normalizedDescription, expectedRevision)
        }
    }
}

class DeleteCashCategoryUseCase(private val repository: CashCategoryRepository) {
    suspend operator fun invoke(category: CashCategory, impact: CashCategoryDeleteImpact): CashCategoryDeleteResult {
        if (category.id != impact.categoryId) {
            throw DomainFailure("category.revision_conflict", 409, FailureCategory.RECOVERABLE)
        }
        if (impact.childCount > 0) {
            throw DomainFailure("category.has_children", 409, FailureCategory.RECOVERABLE)
        }
        return repository.deleteCashCategory(category.id, impact)
    }
}

const val CASH_CATEGORY_NAME_MAX_LENGTH = 40
const val CASH_CATEGORY_DESCRIPTION_MAX_LENGTH = 500
