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
    private val pollIntervalMillis: Long = 10_000L,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CashLedgerUiState())
    val state: StateFlow<CashLedgerUiState> = mutableState.asStateFlow()
    val cashProjections = mutableState
        .map { it.filters to it.listRevision }
        .distinctUntilChanged()
        .flatMapLatest { (filters, revision) ->
            Pager(
                config = PagingConfig(
                    pageSize = 20,
                    initialLoadSize = 20,
                    prefetchDistance = 5,
                    enablePlaceholders = true,
                    jumpThreshold = 20,
                ),
                pagingSourceFactory = {
                    CashProjectionPagingSource(
                        repository = repository,
                        filters = filters,
                        diagnostics = diagnostics,
                        onPageLoaded = { page, firstPage ->
                            if (firstPage && mutableState.value.filters == filters && mutableState.value.listRevision == revision) {
                                mutableState.value = mutableState.value.copy(
                                    selectedIds = mutableState.value.selectedIds.takeIf { mutableState.value.projectionVersion == 0L || mutableState.value.projectionVersion == page.projectionVersion }.orEmpty(),
                                    page = page.copy(items = emptyList()),
                                    filterOptions = page.filterOptions,
                                    projectionVersion = page.projectionVersion,
                                    displayedListRevision = revision,
                                    listLoadErrorCode = null,
                                )
                            }
                        },
                        onSnapshotInvalidated = {
                            if (mutableState.value.filters == filters && mutableState.value.listRevision == revision)
                                mutableState.value = mutableState.value.copy(selectedIds = emptySet())
                        },
                        onFirstPageError = { cause ->
                            val current = mutableState.value
                            if (current.filters == filters && current.listRevision == revision) {
                                val code = cause.safeErrorCode()
                                val retain = code == CASH_LEDGER_WORKER_FAILED
                                mutableState.value = current.copy(
                                    selectedIds = emptySet(),
                                    page = current.page.takeIf { retain },
                                    projectionVersion = if (retain) current.projectionVersion else 0,
                                    listLoadErrorCode = code,
                                )
                            }
                        }
                    )
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
        val current = mutableState.value
        if (filters == current.filters) return
        mutableState.value = current.copy(
            filters = filters,
            page = current.page.takeIf { cashLedgerUsesBackgroundQueries },
            projectionVersion = if (cashLedgerUsesBackgroundQueries) current.projectionVersion else 0,
            listLoadErrorCode = null,
            selectedIds = emptySet(),
            listRevision = mutableState.value.listRevision + 1,
        )
    }

    fun reportInvalidAmountFilter() {
        diagnostics.recordInputValidationFailure(DiagnosticFeature.CASH_LEDGER, "invalid_filter")
    }

    fun clearFilters() = updateFilters(CashFilters())

    fun reload() = refreshListing(clearSelection = true)

    private var deletePreviewSelection: Pair<Set<String>, Long>? = null

    private var pollJob: kotlinx.coroutines.Job? = null

    /** Cheap foreground-only version check: fetch facts only when the committed version changed. */
    fun setPolling(active: Boolean) {
        if (!active) { pollJob?.cancel(); pollJob = null; return }
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (true) {
                val jitter = if (pollIntervalMillis > 5) kotlin.random.Random.nextLong(-pollIntervalMillis / 5, pollIntervalMillis / 5 + 1) else 0L
                kotlinx.coroutines.delay(pollIntervalMillis + jitter)
                if (state.value.refreshingFacts || state.value.classifyBusy || state.value.deleteProjectionBusy) continue
                try { pollOnce() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Throwable) { }
            }
        }
    }

    /** One cheap version check; reloads the listing only when the committed version changed. */
    internal suspend fun pollOnce() {
        val before = repository.ledgerVersion()
        repository.refreshCashLedger()
        val after = repository.ledgerVersion()
        if (before != null && after != null && before != after) refreshListing(clearSelection = false)
    }

    fun refresh() = refreshFacts(reviewAction = false)

    fun reviewActionResult() = refreshFacts(reviewAction = true)

    private fun refreshFacts(reviewAction: Boolean) {
        val current = state.value
        if (current.refreshingFacts || current.classifyBusy || current.deleteProjectionBusy || current.loadingDeleteImpact) return
        mutableState.value = current.copy(
            refreshingFacts = true,
            listLoadErrorCode = current.listLoadErrorCode.takeIf { reviewAction },
            actionReviewErrorCode = null,
        )
        viewModelScope.launch {
            try {
                if (reviewAction) {
                    repository.refreshCashLedgerForReview()
                    mutableState.value = state.value.copy(classificationFailure = null, deleteFailure = null, deleteProjectionImpact = null)
                    deletePreviewSelection = null
                } else {
                    repository.refreshCashLedger()
                }
                refreshListing(clearSelection = true)
                loadReferences()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                failure.recordDiagnosticFailure(diagnostics, DiagnosticFeature.CASH_LEDGER, DiagnosticAction.LOAD)
                mutableState.value = if (reviewAction) {
                    state.value.copy(actionReviewErrorCode = failure.safeErrorCode())
                } else {
                    state.value.copy(page = null, projectionVersion = 0, selectedIds = emptySet(), listLoadErrorCode = failure.safeErrorCode())
                }
            } finally {
                mutableState.value = state.value.copy(refreshingFacts = false)
            }
        }
    }

    fun clearListLoadError() {
        if (mutableState.value.listLoadErrorCode != null) {
            mutableState.value = mutableState.value.copy(listLoadErrorCode = null)
        }
    }

    fun reloadAfterImport() {
        if (!initialized) {
            initialize()
            return
        }
        loadReferences()
        reload()
    }

    fun toggleSelection(id: String, selected: Boolean) {
        val current = mutableState.value
        if (current.displayingPreviousRows) return
        val selectedIds = current.selectedIds.toMutableSet()
        if (selected) selectedIds += id else selectedIds -= id
        mutableState.value = mutableState.value.copy(selectedIds = selectedIds)
    }

    fun setSelectedCategory(categoryId: String) {
        mutableState.value = mutableState.value.copy(selectedCategory = categoryId)
    }

    fun consumeActionNotice(failure: ActionFailure) {
        val current = mutableState.value
        if (!failure.short) return
        mutableState.value = current.copy(
            classificationFailure = current.classificationFailure.takeUnless { it === failure },
            deleteFailure = current.deleteFailure.takeUnless { it === failure },
        )
    }

    fun clearSelection() {
        mutableState.value = mutableState.value.copy(
            selectedIds = emptySet(),
            classificationFailure = state.value.classificationFailure?.takeUnless { it.short },
            deleteFailure = state.value.deleteFailure?.takeUnless { it.short },
        )
    }

    fun classifySelection(canWrite: Boolean) {
        val current = mutableState.value
        if (!canWrite || current.displayingPreviousRows || current.refreshingFacts || current.classifyBusy ||
            current.deleteProjectionBusy || current.loadingDeleteImpact || current.needsActionReview ||
            current.selectedIds.isEmpty() || current.selectedCategory.isEmpty()) return
        val ids = current.selectedIds.toList()
        val categoryId = current.selectedCategory.takeUnless { it == UNCATEGORIZED_SELECTION }
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(classifyBusy = true, classificationFailure = null)
            try {
                repository.classifyCashProjections(ids, current.projectionVersion, categoryId)
                reload()
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.CASH_LEDGER, DiagnosticAction.UPDATE)
                mutableState.value = mutableState.value.copy(classificationFailure = cause.writeActionFailure())
            } finally {
                mutableState.value = mutableState.value.copy(classifyBusy = false)
            }
        }
    }

    fun requestDeleteSelection(canWrite: Boolean) {
        val current = mutableState.value
        if (!canWrite || current.displayingPreviousRows || current.refreshingFacts || current.classifyBusy ||
            current.loadingDeleteImpact || current.deleteProjectionBusy || current.needsActionReview ||
            current.selectedIds.isEmpty()) return
        val ids = current.selectedIds.toList()
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loadingDeleteImpact = true, deleteFailure = null)
            try {
                val impact = repository.fetchCashProjectionDeleteImpact(ids, current.projectionVersion)
                val latest = state.value
                if (latest.selectedIds == current.selectedIds && latest.projectionVersion == current.projectionVersion) {
                    deletePreviewSelection = current.selectedIds to current.projectionVersion
                    mutableState.value = latest.copy(deleteProjectionImpact = impact)
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.CASH_LEDGER, DiagnosticAction.LOAD_DETAILS)
                val latest = state.value
                if (latest.selectedIds == current.selectedIds && latest.projectionVersion == current.projectionVersion) {
                    mutableState.value = latest.copy(deleteFailure = cause.readActionFailure())
                }
            } finally {
                mutableState.value = state.value.copy(loadingDeleteImpact = false)
            }
        }
    }

    fun confirmDeleteSelection(canWrite: Boolean) {
        val current = mutableState.value
        val selection = deletePreviewSelection ?: return
        if (current.deleteProjectionImpact == null || !canWrite || current.displayingPreviousRows || current.refreshingFacts || current.classifyBusy ||
            current.deleteProjectionBusy || current.needsActionReview || current.deleteFailure?.short == false) return
        if (selection.first != current.selectedIds || selection.second != current.projectionVersion) {
            mutableState.value = current.copy(
                deleteFailure = ActionFailure("projection.version_conflict", ActionFailurePresentation.PERSISTENT),
            )
            return
        }
        val ids = selection.first.toList()
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(deleteProjectionBusy = true, deleteFailure = null)
            try {
                repository.deleteCashProjections(ids, selection.second)
                mutableState.value = mutableState.value.copy(deleteProjectionImpact = null, selectedIds = emptySet())
                reload()
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.CASH_LEDGER, DiagnosticAction.DELETE)
                mutableState.value = mutableState.value.copy(deleteFailure = cause.writeActionFailure())
            } finally {
                mutableState.value = mutableState.value.copy(deleteProjectionBusy = false)
            }
        }
    }

    fun dismissDeleteSelection() {
        deletePreviewSelection = null
        mutableState.value = mutableState.value.copy(deleteProjectionImpact = null)
    }

    private fun refreshListing(clearSelection: Boolean) {
        val current = mutableState.value
        mutableState.value = current.copy(
            page = current.page.takeIf { cashLedgerUsesBackgroundQueries },
            projectionVersion = if (cashLedgerUsesBackgroundQueries) current.projectionVersion else 0,
            listLoadErrorCode = null,
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
