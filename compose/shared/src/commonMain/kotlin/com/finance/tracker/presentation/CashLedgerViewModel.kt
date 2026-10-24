package com.finance.tracker.presentation

import com.finance.tracker.core.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.finance.tracker.domain.CashFilters
import com.finance.tracker.domain.CashCategoryRepository
import com.finance.tracker.domain.CashLedgerRepository
import com.finance.tracker.domain.FailureCategory
import com.finance.tracker.domain.DomainFailure
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class CashLedgerViewModel(
    private val repository: CashLedgerRepository,
    private val categoryRepository: CashCategoryRepository,
    private val diagnostics: DiagnosticLogger = NoOpDiagnosticLogger,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CashLedgerUiState())
    val state: StateFlow<CashLedgerUiState> = mutableState.asStateFlow()
    val cashProjections = mutableState
        .map { it.filters to it.listRevision }
        .distinctUntilChanged()
        .flatMapLatest { (filters, revision) ->
            Pager(
                config = PagingConfig(pageSize = 20, prefetchDistance = 5, enablePlaceholders = false),
                pagingSourceFactory = {
                    CashProjectionPagingSource(repository, filters, diagnostics,
                        onSnapshotInvalidated = {
                            if (mutableState.value.filters == filters && mutableState.value.listRevision == revision)
                                mutableState.value = mutableState.value.copy(selectedIds = emptySet())
                        },
                    ) { page, firstPage ->
                        if (firstPage && mutableState.value.filters == filters && mutableState.value.listRevision == revision) {
                            mutableState.value = mutableState.value.copy(
                                selectedIds = mutableState.value.selectedIds.takeIf { mutableState.value.projectionVersion == 0L || mutableState.value.projectionVersion == page.projectionVersion }.orEmpty(),
                                page = page.copy(items = emptyList()),
                                filterOptions = page.filterOptions,
                                projectionVersion = page.projectionVersion,
                            )
                        }
                    }
                },
            ).flow
        }
        .cachedIn(viewModelScope)
    private var initialized = false
    fun initialize() {
        if (initialized) return
        initialized = true
        loadReferences()
    }

    fun loadReferences() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(referencesErrorCode = null)
            var firstError: String? = null
            try {
                val accounts = repository.fetchCashAccounts()
                mutableState.value = mutableState.value.copy(accounts = accounts)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.CASH_LEDGER, DiagnosticAction.LOAD)
                firstError = cause.safeErrorCode()
            }
            try {
                val options = repository.fetchLedgerOptions()
                mutableState.value = mutableState.value.copy(options = options)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.CASH_LEDGER, DiagnosticAction.LOAD)
                firstError = firstError ?: cause.safeErrorCode()
            }
            try {
                val categories = categoryRepository.fetchCashCategories().items
                mutableState.value = mutableState.value.copy(categories = categories)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.CASH_LEDGER, DiagnosticAction.LOAD)
                firstError = firstError ?: cause.safeErrorCode()
            }
            mutableState.value = mutableState.value.copy(referencesErrorCode = firstError)
        }
    }

    fun updateFilters(filters: CashFilters) {
        if (filters == mutableState.value.filters) return
        mutableState.value = mutableState.value.copy(
            filters = filters,
            page = null,
            projectionVersion = 0,
            selectedIds = emptySet(),
            listRevision = mutableState.value.listRevision + 1,
        )
    }

    fun reportInvalidAmountFilter() {
        diagnostics.recordInputValidationFailure(DiagnosticFeature.CASH_LEDGER, "invalid_filter")
    }

    fun clearFilters() = updateFilters(CashFilters())

    fun reload() = refreshListing(clearSelection = true)

    fun reloadAfterImport() {
        if (!initialized) {
            initialize()
            return
        }
        loadReferences()
        reload()
    }

    fun toggleSelection(id: String, selected: Boolean) {
        val selectedIds = mutableState.value.selectedIds.toMutableSet()
        if (selected) selectedIds += id else selectedIds -= id
        mutableState.value = mutableState.value.copy(selectedIds = selectedIds)
    }

    fun setSelectedCategory(categoryId: String) {
        mutableState.value = mutableState.value.copy(selectedCategory = categoryId, classifyErrorCode = null)
    }

    fun dismissClassifyError() {
        mutableState.value = mutableState.value.copy(classifyErrorCode = null)
    }

    fun dismissDeleteProjectionError() {
        mutableState.value = mutableState.value.copy(deleteProjectionErrorCode = null)
    }

    fun clearSelection() {
        mutableState.value = mutableState.value.copy(
            selectedIds = emptySet(),
            classifyErrorCode = null,
            deleteProjectionErrorCode = null,
        )
    }

    fun classifySelection(canWrite: Boolean) {
        val current = mutableState.value
        if (!canWrite || current.classifyBusy || current.selectedIds.isEmpty() || current.selectedCategory.isEmpty()) return
        val ids = current.selectedIds.toList()
        val categoryId = current.selectedCategory.takeUnless { it == UNCATEGORIZED_SELECTION }
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(classifyBusy = true, classifyErrorCode = null)
            try {
                repository.classifyCashProjections(ids, current.projectionVersion, categoryId)
                reload()
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.CASH_LEDGER, DiagnosticAction.UPDATE)
                mutableState.value = mutableState.value.copy(classifyErrorCode = cause.safeErrorCode())
            } finally {
                mutableState.value = mutableState.value.copy(classifyBusy = false)
            }
        }
    }

    fun requestDeleteSelection(canWrite: Boolean) {
        val current = mutableState.value
        if (!canWrite || current.deleteProjectionBusy || current.selectedIds.isEmpty()) return
        val ids = current.selectedIds.toList()
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(deleteProjectionErrorCode = null)
            try {
                val impact = repository.fetchCashProjectionDeleteImpact(ids, current.projectionVersion)
                mutableState.value = mutableState.value.copy(deleteProjectionImpact = impact)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.CASH_LEDGER, DiagnosticAction.LOAD_DETAILS)
                mutableState.value = mutableState.value.copy(deleteProjectionErrorCode = cause.safeErrorCode())
            }
        }
    }

    fun confirmDeleteSelection(canWrite: Boolean) {
        val current = mutableState.value
        val impact = current.deleteProjectionImpact ?: return
        if (!canWrite || current.deleteProjectionBusy || current.selectedIds.isEmpty()) return
        val ids = current.selectedIds.toList()
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(deleteProjectionBusy = true, deleteProjectionErrorCode = null)
            try {
                repository.deleteCashProjections(ids, current.projectionVersion)
                mutableState.value = mutableState.value.copy(deleteProjectionImpact = null, selectedIds = emptySet())
                reload()
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.CASH_LEDGER, DiagnosticAction.DELETE)
                mutableState.value = mutableState.value.copy(deleteProjectionErrorCode = cause.safeErrorCode())
            } finally {
                mutableState.value = mutableState.value.copy(deleteProjectionBusy = false)
            }
        }
    }

    fun dismissDeleteSelection() {
        mutableState.value = mutableState.value.copy(deleteProjectionImpact = null, deleteProjectionErrorCode = null)
    }

    private fun refreshListing(clearSelection: Boolean) {
        val current = mutableState.value
        mutableState.value = current.copy(
            page = null,
            projectionVersion = 0,
            selectedIds = if (clearSelection) emptySet() else current.selectedIds,
            listRevision = current.listRevision + 1,
        )
    }

}

const val UNCATEGORIZED_SELECTION = "__uncategorized__"

private fun Throwable.safeErrorCode(): String = when (this) {
    is DomainFailure -> if (category == FailureCategory.RECOVERABLE) code else "unknown_error"
    else -> "unknown_error"
}
