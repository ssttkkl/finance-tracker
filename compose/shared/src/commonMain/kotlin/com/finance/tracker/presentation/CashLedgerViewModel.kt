package com.finance.tracker.presentation

import com.finance.tracker.core.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.finance.tracker.domain.Account
import com.finance.tracker.domain.CashCategory
import com.finance.tracker.domain.CashCategoryDirectory
import com.finance.tracker.domain.CashFilters
import com.finance.tracker.domain.CashFilterOptions
import com.finance.tracker.domain.CashCategoryRepository
import com.finance.tracker.domain.CashLedgerRepository
import com.finance.tracker.domain.CashPage
import com.finance.tracker.domain.CashProjection
import com.finance.tracker.domain.CashProjectionDeleteImpact
import com.finance.tracker.domain.CashRecord
import com.finance.tracker.domain.CashRecordDetail
import com.finance.tracker.domain.CashRecordWrite
import com.finance.tracker.domain.Evidence
import com.finance.tracker.domain.FailureCategory
import com.finance.tracker.domain.LedgerOptions
import com.finance.tracker.domain.SaveCashRecordUseCase
import com.finance.tracker.domain.DomainFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CashLedgerViewModel(
    private val repository: CashLedgerRepository,
    private val categoryRepository: CashCategoryRepository,
    private val saveCashRecord: SaveCashRecordUseCase = SaveCashRecordUseCase(repository),
    private val diagnostics: DiagnosticLogger = NoOpDiagnosticLogger,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CashLedgerUiState())
    val state: StateFlow<CashLedgerUiState> = mutableState.asStateFlow()
    private var initialized = false
    private var pageRequestId = 0
    private var detailRequestId = 0
    private var relationRequestId = 0
    private var relationSearchJob: Job? = null

    fun initialize() {
        if (initialized) return
        initialized = true
        loadReferences()
        loadPage(mutableState.value.filters, clearSelection = true)
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
        mutableState.value = mutableState.value.copy(filters = filters)
        loadPage(filters, clearSelection = true)
    }

    fun reportInvalidRecordAmount() {
        diagnostics.recordInputValidationFailure(DiagnosticFeature.CASH_LEDGER, "invalid_record")
    }

    fun reportInvalidRecordDateTime() {
        diagnostics.recordInputValidationFailure(DiagnosticFeature.CASH_LEDGER, "invalid_record")
    }

    fun reportInvalidAmountFilter() {
        diagnostics.recordInputValidationFailure(DiagnosticFeature.CASH_LEDGER, "invalid_filter")
    }

    fun clearFilters() = updateFilters(CashFilters())

    fun reload() = loadPage(mutableState.value.filters, clearSelection = true)

    fun loadMore() {
        val current = mutableState.value
        val cursor = current.nextCursor ?: return
        if (current.loadingMore || current.loading) return
        val requestId = pageRequestId
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loadingMore = true, appendErrorCode = null)
            try {
                val result = repository.fetchCashPage(current.filters, cursor)
                if (requestId != pageRequestId) return@launch
                if (result.projectionVersion != current.projectionVersion) {
                    loadPage(current.filters, clearSelection = true)
                } else {
                    val existingIds = mutableState.value.rows.mapTo(mutableSetOf(), CashProjection::projectionId)
                    val currentPage = mutableState.value.page
                    mutableState.value = mutableState.value.copy(
                        rows = mutableState.value.rows + result.items.filter { it.projectionId !in existingIds },
                        nextCursor = result.nextCursor,
                        page = currentPage?.copy(monthlySummaries = result.monthlySummaries),
                    )
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.CASH_LEDGER, DiagnosticAction.LOAD_NEXT_PAGE)
                if (requestId == pageRequestId) {
                    mutableState.value = mutableState.value.copy(appendErrorCode = cause.safeErrorCode())
                }
            } finally {
                if (requestId == pageRequestId) mutableState.value = mutableState.value.copy(loadingMore = false)
            }
        }
    }

    fun toggleSelection(id: String, selected: Boolean) {
        val selectedIds = mutableState.value.selectedIds.toMutableSet()
        if (selected) selectedIds += id else selectedIds -= id
        mutableState.value = mutableState.value.copy(selectedIds = selectedIds)
    }

    fun selectAllLoaded(selected: Boolean) {
        val state = mutableState.value
        val ids = state.rows.mapTo(mutableSetOf(), CashProjection::projectionId)
        mutableState.value = state.copy(selectedIds = if (selected) state.selectedIds + ids else state.selectedIds - ids)
    }

    fun setSelectedCategory(categoryId: String) {
        mutableState.value = mutableState.value.copy(selectedCategory = categoryId, classifyErrorCode = null)
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
                loadPage(mutableState.value.filters, clearSelection = true)
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
                loadPage(mutableState.value.filters, clearSelection = true)
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

    fun openRow(item: CashProjection) {
        val requestId = ++detailRequestId
        relationRequestId++
        relationSearchJob?.cancel()
        mutableState.value = mutableState.value.copy(
            selectedRow = item,
            evidence = null,
            recordDetail = null,
            recordErrorCode = null,
            recordLoading = true,
            relationComposerOpen = false,
            relationCandidates = emptyList(),
            relationTarget = null,
            detailActionErrorCode = null,
            deleteRecordConfirm = false,
        )
        viewModelScope.launch { loadSelectedRow(item, requestId) }
    }

    fun retrySelectedRow() {
        mutableState.value.selectedRow?.let { openRow(it) }
    }

    fun closeSelectedRow() {
        detailRequestId++
        relationRequestId++
        relationSearchJob?.cancel()
        mutableState.value = mutableState.value.copy(
            selectedRow = null,
            evidence = null,
            recordDetail = null,
            recordLoading = false,
            recordErrorCode = null,
            recordDraft = null,
            recordSaveErrorCode = null,
            confirmRelationImpact = false,
            relationComposerOpen = false,
            relationCandidates = emptyList(),
            relationTarget = null,
            relationBusy = false,
            editingRelationId = null,
            editingRelationKind = null,
            detailActionErrorCode = null,
            deleteRecordConfirm = false,
            deleteRecordBusy = false,
        )
    }

    fun updateRecordDetail(detail: CashRecordDetail?) {
        mutableState.value = mutableState.value.copy(recordDetail = detail)
    }

    fun openRelationComposer() {
        val current = mutableState.value
        if (current.recordDetail == null) return
        relationSearchJob?.cancel()
        mutableState.value = current.copy(
            relationComposerOpen = true,
            relationQuery = "",
            relationType = current.options.relationTypes.firstOrNull()?.value ?: "payment_mirror",
            relationTarget = null,
            relationCandidates = emptyList(),
            relationNextCursor = null,
            relationPageNumber = 1,
            relationPageStarts = listOf(null),
            relationLoadError = false,
            relationErrorPage = null,
            relationErrorCursor = null,
            detailActionErrorCode = null,
        )
        scheduleRelationSearch(debounce = false)
    }

    fun closeRelationComposer() {
        relationRequestId++
        relationSearchJob?.cancel()
        mutableState.value = mutableState.value.copy(
            relationComposerOpen = false,
            relationCandidates = emptyList(),
            relationTarget = null,
            relationNextCursor = null,
            relationLoadError = false,
        )
    }

    fun updateRelationQuery(value: String) {
        mutableState.value = mutableState.value.copy(
            relationQuery = value,
            relationTarget = null,
            relationPageNumber = 1,
            relationPageStarts = listOf(null),
        )
        scheduleRelationSearch(debounce = value.isNotBlank())
    }

    fun updateRelationDateFrom(value: String) {
        mutableState.value = mutableState.value.copy(
            relationDateFrom = value,
            relationTarget = null,
            relationPageNumber = 1,
            relationPageStarts = listOf(null),
        )
        scheduleRelationSearch(debounce = false)
    }

    fun updateRelationDateTo(value: String) {
        mutableState.value = mutableState.value.copy(
            relationDateTo = value,
            relationTarget = null,
            relationPageNumber = 1,
            relationPageStarts = listOf(null),
        )
        scheduleRelationSearch(debounce = false)
    }

    fun updateRelationType(value: String) {
        mutableState.value = mutableState.value.copy(relationType = value)
    }

    fun selectRelationTarget(id: String?) {
        mutableState.value = mutableState.value.copy(relationTarget = id)
    }

    fun loadRelationPage(targetPage: Int, cursor: String?) {
        relationSearchJob?.cancel()
        val requestId = ++relationRequestId
        relationSearchJob = viewModelScope.launch { loadRelationPageNow(targetPage, cursor, requestId) }
    }

    fun retryRelationPage() {
        val current = mutableState.value
        loadRelationPage(current.relationErrorPage ?: 1, current.relationErrorCursor)
    }

    fun previousRelationPage() {
        val current = mutableState.value
        if (current.relationPageNumber <= 1 || current.relationBusy) return
        val targetPage = current.relationPageNumber - 1
        loadRelationPage(targetPage, current.relationPageStarts.getOrNull(targetPage - 1))
    }

    fun nextRelationPage() {
        val current = mutableState.value
        val cursor = current.relationNextCursor ?: return
        if (current.relationBusy) return
        loadRelationPage(current.relationPageNumber + 1, cursor)
    }

    fun beginEditingRelation(id: String, kind: String) {
        mutableState.value = mutableState.value.copy(
            editingRelationId = id,
            editingRelationKind = kind,
            detailActionErrorCode = null,
        )
    }

    fun updateEditingRelationKind(kind: String) {
        mutableState.value = mutableState.value.copy(editingRelationKind = kind)
    }

    fun cancelEditingRelation() {
        mutableState.value = mutableState.value.copy(editingRelationId = null, editingRelationKind = null)
    }

    fun saveRelationEdit(canWrite: Boolean) {
        val current = mutableState.value
        val relationId = current.editingRelationId ?: return
        val kind = current.editingRelationKind ?: return
        if (!canWrite || current.relationBusy) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(relationBusy = true, detailActionErrorCode = null)
            try {
                val updated = repository.updateCashRelation(relationId, kind)
                mutableState.value = mutableState.value.copy(
                    recordDetail = updated,
                    editingRelationId = null,
                    editingRelationKind = null,
                )
                refreshSelectedEvidence()
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.CASH_LEDGER, DiagnosticAction.UPDATE)
                mutableState.value = mutableState.value.copy(detailActionErrorCode = cause.safeErrorCode())
            } finally {
                mutableState.value = mutableState.value.copy(relationBusy = false)
            }
        }
    }

    fun cancelRelation(relationId: String, canWrite: Boolean) {
        val current = mutableState.value
        val recordId = current.recordDetail?.record?.id ?: return
        if (!canWrite || current.relationBusy) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(relationBusy = true, detailActionErrorCode = null)
            try {
                repository.cancelCashRelation(relationId)
                mutableState.value = mutableState.value.copy(recordDetail = repository.fetchCashRecord(recordId))
                refreshSelectedEvidence()
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.CASH_LEDGER, DiagnosticAction.DELETE)
                mutableState.value = mutableState.value.copy(detailActionErrorCode = cause.safeErrorCode())
            } finally {
                mutableState.value = mutableState.value.copy(relationBusy = false)
            }
        }
    }

    fun createRelation(canWrite: Boolean) {
        val current = mutableState.value
        val recordId = current.recordDetail?.record?.id ?: return
        val targetId = current.relationTarget ?: return
        if (!canWrite || current.relationBusy) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(relationBusy = true, detailActionErrorCode = null)
            try {
                val updated = repository.createCashRelation(recordId, targetId, current.relationType)
                mutableState.value = mutableState.value.copy(
                    recordDetail = updated,
                    relationComposerOpen = false,
                    relationCandidates = emptyList(),
                    relationTarget = null,
                )
                refreshSelectedEvidence()
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.CASH_LEDGER, DiagnosticAction.CREATE)
                mutableState.value = mutableState.value.copy(detailActionErrorCode = cause.safeErrorCode())
            } finally {
                mutableState.value = mutableState.value.copy(relationBusy = false)
            }
        }
    }

    fun dissolveRelations(canWrite: Boolean) {
        val current = mutableState.value
        val recordId = current.recordDetail?.record?.id ?: return
        if (!canWrite || current.relationBusy) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(relationBusy = true, detailActionErrorCode = null)
            try {
                val updated = repository.dissolveCashRelations(recordId)
                mutableState.value = mutableState.value.copy(recordDetail = updated)
                refreshSelectedEvidence()
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.CASH_LEDGER, DiagnosticAction.DELETE)
                mutableState.value = mutableState.value.copy(detailActionErrorCode = cause.safeErrorCode())
            } finally {
                mutableState.value = mutableState.value.copy(relationBusy = false)
            }
        }
    }

    fun requestDeleteRecord(canWrite: Boolean) {
        if (canWrite && mutableState.value.recordDetail != null) {
            mutableState.value = mutableState.value.copy(deleteRecordConfirm = true, detailActionErrorCode = null)
        }
    }

    fun dismissDeleteRecord() {
        if (!mutableState.value.deleteRecordBusy) {
            mutableState.value = mutableState.value.copy(deleteRecordConfirm = false, detailActionErrorCode = null)
        }
    }

    fun deleteRecord(mode: String, canWrite: Boolean) {
        val current = mutableState.value
        val recordId = current.recordDetail?.record?.id ?: return
        if (!canWrite || current.deleteRecordBusy) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(deleteRecordBusy = true, detailActionErrorCode = null)
            try {
                repository.deleteCashRecord(recordId, mode)
                mutableState.value = mutableState.value.copy(deleteRecordConfirm = false)
                closeSelectedRow()
                reload()
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.CASH_LEDGER, DiagnosticAction.DELETE)
                mutableState.value = mutableState.value.copy(detailActionErrorCode = cause.safeErrorCode())
            } finally {
                mutableState.value = mutableState.value.copy(deleteRecordBusy = false)
            }
        }
    }

    fun startNewRecord(occurredAt: String) {
        val current = mutableState.value
        val firstAccount = current.accounts.firstOrNull()
        val firstType = current.options.recordTypes.firstOrNull()
        mutableState.value = current.copy(
            recordDraft = CashRecordDraft(
                occurredAt = occurredAt,
                accountName = firstAccount?.name.orEmpty(),
                currency = firstAccount?.currencies?.firstOrNull() ?: "CNY",
                recordType = firstType?.value.orEmpty(),
                recordSubtype = firstType?.subtypes?.firstOrNull()?.value ?: "not_applicable",
            ),
            recordSaveErrorCode = null,
            confirmRelationImpact = false,
        )
    }

    fun editRecord(recordOverride: CashRecord? = null) {
        val current = mutableState.value
        val record = recordOverride ?: current.recordDetail?.record ?: return
        val categoryId = record.categoryId ?: record.category?.id
        mutableState.value = current.copy(
            recordDraft = CashRecordDraft(
                id = record.id,
                originalCategoryId = categoryId,
                occurredAt = record.occurredAt.take(16),
                amount = record.amount,
                currency = record.currency,
                counterparty = record.counterparty,
                counterpartyAccount = record.counterpartyAccount,
                note = record.note,
                accountName = record.accountName,
                recordType = record.recordType,
                recordSubtype = record.recordSubtype,
                categoryId = categoryId,
                projectionVersion = current.evidence?.projectionVersion,
            ),
            recordSaveErrorCode = null,
            confirmRelationImpact = false,
        )
    }

    fun updateRecordDraft(draft: CashRecordDraft) {
        mutableState.value = mutableState.value.copy(recordDraft = draft, recordSaveErrorCode = null)
    }

    fun cancelRecordEdit() {
        mutableState.value = mutableState.value.copy(
            recordDraft = null,
            recordSaveErrorCode = null,
            confirmRelationImpact = false,
        )
    }

    fun dismissRelationImpactConfirmation() {
        mutableState.value = mutableState.value.copy(confirmRelationImpact = false)
    }

    fun saveRecord(canWrite: Boolean, confirmImpact: Boolean = false) {
        val current = mutableState.value
        val draft = current.recordDraft ?: return
        if (!canWrite || current.recordBusy || !isValidLocalDateTime(draft.occurredAt)) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                recordBusy = true,
                recordSaveErrorCode = null,
                confirmRelationImpact = false,
            )
            try {
                val updated = saveCashRecord(draft.id, draft.toWrite(confirmImpact))
                val selected = mutableState.value.selectedRow
                mutableState.value = mutableState.value.copy(
                    recordDraft = null,
                    recordDetail = updated,
                    recordBusy = false,
                    recordSaveErrorCode = null,
                    confirmRelationImpact = false,
                )
                loadPage(mutableState.value.filters, clearSelection = true)
                if (selected != null) loadSelectedRow(selected, detailRequestId)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.CASH_LEDGER, DiagnosticAction.UPDATE)
                val code = cause.safeErrorCode()
                if (!confirmImpact && code == "relation_impact_required") {
                    mutableState.value = mutableState.value.copy(confirmRelationImpact = true)
                } else {
                    mutableState.value = mutableState.value.copy(recordSaveErrorCode = code)
                }
            } finally {
                mutableState.value = mutableState.value.copy(recordBusy = false)
            }
        }
    }

    private fun loadPage(filters: CashFilters, clearSelection: Boolean) {
        val requestId = ++pageRequestId
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                loading = true,
                errorCode = null,
                appendErrorCode = null,
                loadingMore = false,
                rows = emptyList(),
                page = null,
                nextCursor = null,
                projectionVersion = 0,
                selectedIds = if (clearSelection) emptySet() else mutableState.value.selectedIds,
            )
            try {
                val result = repository.fetchCashPage(filters)
                if (requestId != pageRequestId) return@launch
                mutableState.value = mutableState.value.copy(
                    page = result,
                    filterOptions = result.filterOptions,
                    rows = result.items,
                    nextCursor = result.nextCursor,
                    projectionVersion = result.projectionVersion,
                    loading = false,
                )
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.CASH_LEDGER, DiagnosticAction.LOAD)
                if (requestId == pageRequestId) {
                    mutableState.value = mutableState.value.copy(loading = false, page = null, errorCode = cause.safeErrorCode())
                }
            }
        }
    }

    private fun scheduleRelationSearch(debounce: Boolean) {
        relationSearchJob?.cancel()
        val searchRequestId = ++relationRequestId
        val current = mutableState.value
        if (!current.relationComposerOpen) return
        if (!isValidCashRelationDateFilter(current.relationDateFrom, current.relationDateTo)) {
            mutableState.value = current.copy(
                relationCandidates = emptyList(),
                relationNextCursor = null,
                relationLoadError = false,
                relationErrorPage = null,
                relationErrorCursor = null,
            )
            return
        }
        val requestId = detailRequestId
        relationSearchJob = viewModelScope.launch {
            if (debounce) delay(250)
            if (requestId != detailRequestId || searchRequestId != relationRequestId) return@launch
            loadRelationPageNow(targetPage = 1, cursor = null, searchRequestId = searchRequestId)
        }
    }

    private suspend fun loadRelationPageNow(targetPage: Int, cursor: String?, searchRequestId: Int) {
        val current = mutableState.value
        val record = current.recordDetail?.record ?: return
        if (!current.relationComposerOpen) return
        val requestId = detailRequestId
        if (searchRequestId != relationRequestId) return
        mutableState.value = current.copy(
            relationBusy = true,
            relationLoadError = false,
            relationErrorPage = null,
            relationErrorCursor = null,
            detailActionErrorCode = null,
        )
        try {
            val result = repository.fetchCashRecords(
                query = current.relationQuery.trim(),
                excludeId = record.id,
                dateFrom = current.relationDateFrom,
                dateTo = current.relationDateTo,
                cursor = cursor,
                limit = 20,
            )
            if (requestId != detailRequestId || searchRequestId != relationRequestId || mutableState.value.recordDetail?.record?.id != record.id) return
            mutableState.value = mutableState.value.copy(
                relationCandidates = result.items,
                relationNextCursor = result.nextCursor,
                relationPageNumber = targetPage,
                relationPageStarts = current.relationPageStarts.take(targetPage) + result.nextCursor,
                relationTarget = mutableState.value.relationTarget?.takeIf { target -> result.items.any { it.id == target } },
            )
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.CASH_LEDGER, DiagnosticAction.LOAD_NEXT_PAGE)
            if (requestId == detailRequestId && searchRequestId == relationRequestId) {
                mutableState.value = mutableState.value.copy(
                    relationCandidates = emptyList(),
                    relationNextCursor = null,
                    relationLoadError = true,
                    relationErrorPage = targetPage,
                    relationErrorCursor = cursor,
                    detailActionErrorCode = cause.safeErrorCode(),
                )
            }
        } finally {
            if (requestId == detailRequestId && searchRequestId == relationRequestId) {
                mutableState.value = mutableState.value.copy(relationBusy = false)
            }
        }
    }

    private suspend fun refreshSelectedEvidence() {
        val current = mutableState.value
        val row = current.selectedRow ?: return
        val requestId = detailRequestId
        try {
            val evidence = repository.fetchEvidence(row.projectionId)
            if (requestId == detailRequestId) mutableState.value = mutableState.value.copy(evidence = evidence)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.CASH_LEDGER, DiagnosticAction.LOAD_EVIDENCE)
            if (requestId == detailRequestId) mutableState.value = mutableState.value.copy(recordErrorCode = cause.safeErrorCode())
        }
    }

    private suspend fun loadSelectedRow(item: CashProjection, requestId: Int) {
        try {
            val evidence = repository.fetchEvidence(item.projectionId)
            if (requestId != detailRequestId) return
            val detail = repository.fetchCashRecord(evidence.rootRecord.id)
            if (requestId != detailRequestId) return
            val range = cashRelationDateRange(getLocalDateForInstant(detail.record.occurredAt))
            mutableState.value = mutableState.value.copy(
                evidence = evidence,
                recordDetail = detail,
                recordLoading = false,
                relationType = mutableState.value.options.relationTypes.firstOrNull()?.value ?: "payment_mirror",
                relationDateFrom = range?.from.orEmpty(),
                relationDateTo = range?.to.orEmpty(),
            )
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.CASH_LEDGER, DiagnosticAction.LOAD_DETAILS)
            if (requestId == detailRequestId) {
                mutableState.value = mutableState.value.copy(recordLoading = false, recordErrorCode = cause.safeErrorCode())
            }
        }
    }
}

const val UNCATEGORIZED_SELECTION = "__uncategorized__"

private fun Throwable.safeErrorCode(): String = when (this) {
    is DomainFailure -> if (category == FailureCategory.RECOVERABLE) code else "unknown_error"
    else -> "unknown_error"
}
