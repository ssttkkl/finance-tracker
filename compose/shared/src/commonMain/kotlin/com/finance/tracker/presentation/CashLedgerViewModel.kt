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
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private val saveCashRecord: SaveCashRecordUseCase = SaveCashRecordUseCase(repository),
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
    private var detailRequestId = 0
    private var relationRequestId = 0
    private var relationRemovalInFlight = false
    private var relationSearchJob: Job? = null

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

    fun dismissDetailActionError() {
        mutableState.value = mutableState.value.copy(detailActionErrorCode = null)
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

    fun openRow(item: CashProjection) {
        if (mutableState.value.recordBusy) return
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
            relationBusy = relationRemovalInFlight,
            pendingRelationRemovalId = null,
            pendingRelationDissolveAll = false,
            detailActionErrorCode = null,
            deleteRecordConfirm = false,
            deleteRecordMode = "delete_current_dissolve",
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
            recordDraftBaseline = null,
            recordSaveErrorCode = null,
            confirmRecordDiscard = false,
            confirmRelationImpact = false,
            relationComposerOpen = false,
            relationCandidates = emptyList(),
            relationTarget = null,
            relationBusy = relationRemovalInFlight,
            pendingRelationRemovalId = null,
            pendingRelationDissolveAll = false,
            editingRelationId = null,
            editingRelationKind = null,
            detailActionErrorCode = null,
            deleteRecordConfirm = false,
            deleteRecordMode = "delete_current_dissolve",
            deleteRecordBusy = false,
        )
    }

    fun dismissRecordPanel() {
        val current = mutableState.value
        if (current.recordBusy) {
            closeSelectedRow()
        } else if (current.recordDraft != null && current.recordDraft != current.recordDraftBaseline) {
            mutableState.value = current.copy(confirmRecordDiscard = true)
        } else if (current.recordDraft != null) {
            discardRecordEdit()
        } else {
            closeSelectedRow()
        }
    }

    fun dismissRecordDiscardConfirmation() {
        mutableState.value = mutableState.value.copy(confirmRecordDiscard = false)
    }

    fun confirmRecordDiscard() {
        if (!mutableState.value.confirmRecordDiscard) return
        discardRecordEdit()
    }

    private fun discardRecordEdit() {
        val current = mutableState.value
        if (current.selectedRow == null) {
            closeSelectedRow()
        } else {
            mutableState.value = current.copy(
                recordDraft = null,
                recordDraftBaseline = null,
                recordSaveErrorCode = null,
                confirmRecordDiscard = false,
                confirmRelationImpact = false,
            )
        }
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
        if (!canWrite || current.relationBusy || relationRemovalInFlight) return
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
        if (!canWrite || current.relationBusy || relationRemovalInFlight) return
        if (current.recordDetail?.relations?.none { it.id == relationId && it.status == "accepted" } != false) return
        mutableState.value = current.copy(
            pendingRelationRemovalId = relationId,
            pendingRelationDissolveAll = false,
            detailActionErrorCode = null,
        )
    }

    fun dissolveRelations(canWrite: Boolean) {
        val current = mutableState.value
        if (!canWrite || current.relationBusy || relationRemovalInFlight) return
        if (current.recordDetail?.relations?.none { it.status == "accepted" } != false) return
        mutableState.value = current.copy(
            pendingRelationRemovalId = null,
            pendingRelationDissolveAll = true,
            detailActionErrorCode = null,
        )
    }

    fun dismissPendingRelationRemoval() {
        mutableState.value = mutableState.value.copy(
            pendingRelationRemovalId = null,
            pendingRelationDissolveAll = false,
            detailActionErrorCode = null,
        )
    }

    fun confirmPendingRelationRemoval(canWrite: Boolean) {
        val current = mutableState.value
        val recordId = current.recordDetail?.record?.id ?: return
        val relationId = current.pendingRelationRemovalId
        val dissolveAll = current.pendingRelationDissolveAll
        if (!canWrite || current.relationBusy || relationRemovalInFlight || (relationId == null && !dissolveAll)) return
        relationRemovalInFlight = true
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(relationBusy = true, detailActionErrorCode = null)
            try {
                val updated = if (dissolveAll) {
                    repository.dissolveCashRelations(recordId)
                } else {
                    repository.cancelCashRelation(requireNotNull(relationId))
                    repository.fetchCashRecord(recordId)
                }
                if (mutableState.value.selectedRow?.recordId == recordId) {
                    mutableState.value = mutableState.value.copy(
                        recordDetail = updated,
                        pendingRelationRemovalId = null,
                        pendingRelationDissolveAll = false,
                    )
                }
                refreshSelectedEvidence()
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.CASH_LEDGER, DiagnosticAction.DELETE)
                mutableState.value = mutableState.value.copy(detailActionErrorCode = cause.safeErrorCode())
            } finally {
                relationRemovalInFlight = false
                mutableState.value = mutableState.value.copy(relationBusy = false)
            }
        }
    }

    fun createRelation(canWrite: Boolean) {
        val current = mutableState.value
        val recordId = current.recordDetail?.record?.id ?: return
        val targetId = current.relationTarget ?: return
        if (!canWrite || current.relationBusy || relationRemovalInFlight) return
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

    fun requestDeleteRecord(canWrite: Boolean) {
        if (canWrite && mutableState.value.recordDetail != null) {
            mutableState.value = mutableState.value.copy(
                deleteRecordConfirm = true,
                deleteRecordMode = "delete_current_dissolve",
                detailActionErrorCode = null,
            )
        }
    }

    fun selectDeleteRecordMode(mode: String) {
        val current = mutableState.value
        val hasAcceptedRelations = current.recordDetail?.relations?.any { it.status == "accepted" } == true
        if (!current.deleteRecordConfirm || mode !in setOf("delete_current_dissolve", "delete_all")) return
        if (mode == "delete_all" && !hasAcceptedRelations) return
        mutableState.value = current.copy(deleteRecordMode = mode)
    }

    fun dismissDeleteRecord() {
        mutableState.value = mutableState.value.copy(
            deleteRecordConfirm = false,
            deleteRecordMode = "delete_current_dissolve",
            detailActionErrorCode = null,
        )
    }

    fun deleteRecord(mode: String, canWrite: Boolean) {
        val current = mutableState.value
        val recordId = current.recordDetail?.record?.id ?: return
        if (!canWrite || current.deleteRecordBusy) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(deleteRecordBusy = true, detailActionErrorCode = null)
            try {
                repository.deleteCashRecord(recordId, mode)
                mutableState.value = mutableState.value.copy(
                    deleteRecordConfirm = false,
                    deleteRecordMode = "delete_current_dissolve",
                )
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
        if (current.recordBusy) return
        val firstAccount = current.accounts.firstOrNull()
        val firstType = current.options.recordTypes.firstOrNull()
        val draft = CashRecordDraft(
            occurredAt = occurredAt,
            accountName = firstAccount?.name.orEmpty(),
            currency = firstAccount?.currencies?.firstOrNull() ?: "CNY",
            recordType = firstType?.value.orEmpty(),
            recordSubtype = firstType?.subtypes?.firstOrNull()?.value ?: "not_applicable",
        )
        mutableState.value = current.copy(
            recordDraft = draft,
            recordDraftBaseline = draft,
            recordSaveErrorCode = null,
            confirmRecordDiscard = false,
            confirmRelationImpact = false,
        )
    }

    fun editRecord(recordOverride: CashRecord? = null) {
        val current = mutableState.value
        if (current.recordBusy) return
        val record = recordOverride ?: current.recordDetail?.record ?: return
        val categoryId = record.categoryId ?: record.category?.id
        val draft = CashRecordDraft(
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
        )
        mutableState.value = current.copy(
            recordDraft = draft,
            recordDraftBaseline = draft,
            recordSaveErrorCode = null,
            confirmRecordDiscard = false,
            confirmRelationImpact = false,
        )
    }

    fun updateRecordDraft(draft: CashRecordDraft) {
        mutableState.value = mutableState.value.copy(recordDraft = draft, recordSaveErrorCode = null)
    }

    fun dismissRecordSaveError() {
        mutableState.value = mutableState.value.copy(recordSaveErrorCode = null)
    }

    fun cancelRecordEdit() {
        dismissRecordPanel()
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
            )
            try {
                val updated = saveCashRecord(draft.id, draft.toWrite(confirmImpact))
                val selected = mutableState.value.selectedRow
                mutableState.value = mutableState.value.copy(
                    recordDraft = null,
                    recordDraftBaseline = null,
                    recordDetail = updated,
                    recordBusy = false,
                    recordSaveErrorCode = null,
                    confirmRecordDiscard = false,
                    confirmRelationImpact = false,
                )
                reload()
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

    private fun refreshListing(clearSelection: Boolean) {
        val current = mutableState.value
        mutableState.value = current.copy(
            page = null,
            projectionVersion = 0,
            selectedIds = if (clearSelection) emptySet() else current.selectedIds,
            listRevision = current.listRevision + 1,
        )
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
