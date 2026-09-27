package com.finance.tracker.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.finance.tracker.core.DiagnosticAction
import com.finance.tracker.core.DiagnosticFeature
import com.finance.tracker.core.DiagnosticLogger
import com.finance.tracker.core.NoOpDiagnosticLogger
import com.finance.tracker.domain.CashCategory
import com.finance.tracker.domain.CashCategoryDeleteImpact
import com.finance.tracker.domain.CashCategoryDirectory
import com.finance.tracker.domain.CashCategoryRepository
import com.finance.tracker.domain.DeleteCashCategoryUseCase
import com.finance.tracker.domain.DomainFailure
import com.finance.tracker.domain.FailureCategory
import com.finance.tracker.domain.SaveCashCategoryUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CashCategoryDraft(
    val id: String?,
    val name: String,
    val description: String,
    val parentId: String?,
)

data class CashCategoryUiState(
    val directory: CashCategoryDirectory? = null,
    val loading: Boolean = true,
    val loadingDeleteImpact: Boolean = false,
    val errorCode: String? = null,
    val search: String = "",
    val draft: CashCategoryDraft? = null,
    val busy: Boolean = false,
    val editorErrorCode: String? = null,
    val deleting: CashCategory? = null,
    val deleteImpact: CashCategoryDeleteImpact? = null,
    val deleteErrorCode: String? = null,
)

class CashCategoryViewModel(
    private val repository: CashCategoryRepository,
    private val saveCategory: SaveCashCategoryUseCase = SaveCashCategoryUseCase(repository),
    private val deleteCategory: DeleteCashCategoryUseCase = DeleteCashCategoryUseCase(repository),
    private val diagnostics: DiagnosticLogger = NoOpDiagnosticLogger,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CashCategoryUiState())
    val state: StateFlow<CashCategoryUiState> = mutableState.asStateFlow()

    fun load() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, errorCode = null)
            try {
                val directory = repository.fetchCashCategories()
                mutableState.value = mutableState.value.copy(directory = directory, loading = false)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.CASH_CATEGORIES, DiagnosticAction.LOAD)
                mutableState.value = mutableState.value.copy(loading = false, errorCode = cause.safeErrorCode())
            }
        }
    }

    fun setSearch(value: String) {
        mutableState.value = mutableState.value.copy(search = value)
    }

    fun startCreate() {
        mutableState.value = mutableState.value.copy(
            draft = CashCategoryDraft(id = null, name = "", description = "", parentId = null),
            editorErrorCode = null,
        )
    }

    fun startChild(parentId: String) {
        mutableState.value = mutableState.value.copy(
            draft = CashCategoryDraft(id = null, name = "", description = "", parentId = parentId),
            editorErrorCode = null,
        )
    }

    fun edit(category: CashCategory) {
        mutableState.value = mutableState.value.copy(
            draft = CashCategoryDraft(category.id, category.name, category.description.orEmpty(), category.parentId),
            editorErrorCode = null,
        )
    }

    fun updateDraft(draft: CashCategoryDraft) {
        mutableState.value = mutableState.value.copy(draft = draft, editorErrorCode = null)
    }

    fun cancelEditor() {
        mutableState.value = mutableState.value.copy(draft = null, editorErrorCode = null)
    }

    fun save(canWrite: Boolean) {
        val current = mutableState.value
        val draft = current.draft ?: return
        if (!canWrite || current.busy || current.loadingDeleteImpact) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(busy = true, editorErrorCode = null, errorCode = null)
            try {
                val directory = current.directory ?: repository.fetchCashCategories()
                saveCategory(
                    id = draft.id,
                    name = draft.name,
                    parentId = draft.parentId,
                    description = draft.description,
                    expectedRevision = directory.revision.toLong(),
                )
                val refreshed = repository.fetchCashCategories()
                mutableState.value = mutableState.value.copy(
                    directory = refreshed,
                    loading = false,
                    draft = null,
                    editorErrorCode = null,
                )
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.CASH_CATEGORIES, DiagnosticAction.UPDATE)
                mutableState.value = mutableState.value.copy(editorErrorCode = cause.safeErrorCode())
            } finally {
                mutableState.value = mutableState.value.copy(busy = false)
            }
        }
    }

    fun move(category: CashCategory, direction: String, canWrite: Boolean) {
        val current = mutableState.value
        if (!canWrite || current.busy || current.loadingDeleteImpact) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(busy = true, errorCode = null)
            try {
                val directory = current.directory ?: repository.fetchCashCategories()
                repository.reorderCashCategory(category.id, direction, directory.revision.toLong())
                mutableState.value = mutableState.value.copy(directory = repository.fetchCashCategories(), loading = false)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.CASH_CATEGORIES, DiagnosticAction.UPDATE)
                mutableState.value = mutableState.value.copy(errorCode = cause.safeErrorCode())
            } finally {
                mutableState.value = mutableState.value.copy(busy = false)
            }
        }
    }

    fun requestDelete(category: CashCategory, canWrite: Boolean) {
        val current = mutableState.value
        if (!canWrite || current.busy || current.loadingDeleteImpact) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                loadingDeleteImpact = true,
                deleteErrorCode = null,
                errorCode = null,
            )
            try {
                val impact = repository.fetchCashCategoryDeletionImpact(category.id)
                mutableState.value = mutableState.value.copy(
                    deleting = category,
                    deleteImpact = impact,
                    loadingDeleteImpact = false,
                )
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.CASH_CATEGORIES, DiagnosticAction.LOAD_DETAILS)
                val code = cause.safeErrorCode()
                mutableState.value = mutableState.value.copy(
                    loadingDeleteImpact = false,
                    deleteErrorCode = code,
                    errorCode = code,
                )
            }
        }
    }

    fun confirmDelete(canWrite: Boolean) {
        val current = mutableState.value
        val category = current.deleting ?: return
        val impact = current.deleteImpact ?: return
        if (!canWrite || current.busy || current.loadingDeleteImpact || impact.childCount > 0) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(busy = true, deleteErrorCode = null)
            try {
                deleteCategory(category, impact)
                val refreshed = repository.fetchCashCategories()
                mutableState.value = mutableState.value.copy(
                    directory = refreshed,
                    loading = false,
                    draft = null,
                    deleting = null,
                    deleteImpact = null,
                    deleteErrorCode = null,
                )
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.CASH_CATEGORIES, DiagnosticAction.DELETE)
                mutableState.value = mutableState.value.copy(deleteErrorCode = cause.safeErrorCode())
            } finally {
                mutableState.value = mutableState.value.copy(busy = false)
            }
        }
    }

    fun dismissDelete() {
        mutableState.value = mutableState.value.copy(deleting = null, deleteImpact = null, deleteErrorCode = null)
    }
}

private fun Throwable.safeErrorCode(): String = when (this) {
    is DomainFailure -> if (category == FailureCategory.RECOVERABLE) code else "unknown_error"
    else -> "unknown_error"
}
