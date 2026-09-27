package com.finance.tracker.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.finance.tracker.core.DiagnosticAction
import com.finance.tracker.core.DiagnosticFeature
import com.finance.tracker.core.DiagnosticLogger
import com.finance.tracker.core.NoOpDiagnosticLogger
import com.finance.tracker.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class InvestmentHoldingsViewModel(
    private val repository: InvestmentRepository,
    private val diagnostics: DiagnosticLogger = NoOpDiagnosticLogger,
) : ViewModel() {
    private val initialOptions = loadInvestmentDisplayOptions()
    private val mutableState = MutableStateFlow(
        InvestmentHoldingsUiState(displayOptions = initialOptions, currencyDraft = initialOptions.currency),
    )
    val state: StateFlow<InvestmentHoldingsUiState> = mutableState.asStateFlow()
    private var initialized = false
    private var portfolioRequestId = 0
    private var accountsRequestId = 0
    private var streamJob: Job? = null
    private var refreshTimeoutJob: Job? = null

    fun initialize() {
        if (initialized) return
        initialized = true
        loadAccounts()
        loadPortfolio()
        restartStream()
    }

    fun reload() {
        loadAccounts()
        loadPortfolio()
    }

    fun updateDisplayOptions(options: InvestmentDisplayOptions) {
        val current = mutableState.value.displayOptions
        val validated = validateInvestmentDisplayOptions(options)
        if (validated == current) return
        mutableState.value = mutableState.value.copy(displayOptions = validated)
        saveInvestmentDisplayOptions(validated)
        if (validated.currency != current.currency || validated.period != current.period) {
            loadPortfolio()
            restartStream()
        }
    }

    fun updateCurrencyDraft(value: String) {
        val next = value.uppercase()
        mutableState.value = mutableState.value.copy(currencyDraft = next)
        if (next.isEmpty() || Regex("^[A-Z]{3}$").matches(next)) {
            updateDisplayOptions(mutableState.value.displayOptions.copy(currency = next))
        }
    }

    fun resetInvalidCurrencyDraft() {
        mutableState.value = mutableState.value.copy(currencyDraft = mutableState.value.displayOptions.currency)
    }

    fun reportInvalidCurrencyDraft() {
        diagnostics.recordInputValidationFailure(
            DiagnosticFeature.INVESTMENT_HOLDINGS,
            "valuation.invalid_display_currency",
        )
        resetInvalidCurrencyDraft()
    }

    fun updateTickerFilter(value: String) {
        mutableState.value = mutableState.value.copy(tickerFilter = value)
    }

    fun showPosition(row: InvestmentHoldingRow) {
        mutableState.value = mutableState.value.copy(expandedPosition = row)
    }

    fun closePosition() {
        mutableState.value = mutableState.value.copy(expandedPosition = null)
    }

    fun refresh() {
        val options = mutableState.value.displayOptions
        if (mutableState.value.refreshing) return
        mutableState.value = mutableState.value.copy(refreshing = true, refreshMessageCode = null)
        refreshTimeoutJob?.cancel()
        refreshTimeoutJob = viewModelScope.launch {
            delay(20_000)
            if (mutableState.value.refreshing) {
                diagnostics.record(
                    DiagnosticFeature.INVESTMENT_HOLDINGS,
                    DiagnosticAction.LOAD,
                    "investment_refresh_timeout",
                    null,
                    "StreamingRefreshTimeout",
                )
                mutableState.value = mutableState.value.copy(
                    refreshing = false,
                    refreshMessageCode = "investment_refresh_timeout",
                )
            }
        }
        viewModelScope.launch {
            try {
                repository.refreshInvestmentPortfolio(options.currency.ifBlank { null }, options.period)
                if (mutableState.value.refreshing) {
                    mutableState.value = mutableState.value.copy(refreshMessageCode = "investment_refresh_started")
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.INVESTMENT_HOLDINGS, DiagnosticAction.LOAD)
                refreshTimeoutJob?.cancel()
                mutableState.value = mutableState.value.copy(
                    refreshing = false,
                    refreshMessageCode = cause.safeErrorCode(),
                )
            }
        }
    }

    private fun loadAccounts() {
        val requestId = ++accountsRequestId
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(accountsErrorCode = null)
            try {
                val accounts = repository.fetchInvestmentAccounts()
                if (requestId != accountsRequestId) return@launch
                val options = mutableState.value.displayOptions
                mutableState.value = mutableState.value.copy(
                    accounts = accounts,
                    accountsErrorCode = null,
                    displayOptions = options.copy(accountId = options.accountId.takeIf { id -> accounts.any { it.id.toString() == id } }.orEmpty()),
                )
                saveInvestmentDisplayOptions(mutableState.value.displayOptions)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.INVESTMENT_HOLDINGS, DiagnosticAction.LOAD)
                if (requestId == accountsRequestId) mutableState.value = mutableState.value.copy(accountsErrorCode = cause.safeErrorCode())
            }
        }
    }

    private fun loadPortfolio() {
        val requestId = ++portfolioRequestId
        val options = mutableState.value.displayOptions
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, errorCode = null)
            try {
                val portfolio = repository.fetchInvestmentPortfolio(options.currency.ifBlank { null }, options.period)
                if (requestId == portfolioRequestId) {
                    mutableState.value = mutableState.value.copy(portfolio = portfolio, loading = false, errorCode = null)
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.INVESTMENT_HOLDINGS, DiagnosticAction.LOAD)
                if (requestId == portfolioRequestId) {
                    mutableState.value = mutableState.value.copy(loading = false, errorCode = cause.safeErrorCode())
                }
            }
        }
    }

    private fun restartStream() {
        val options = mutableState.value.displayOptions
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            repository.streamInvestmentPortfolio(
                displayCurrency = options.currency.ifBlank { null },
                period = options.period,
                onPortfolio = { incoming ->
                    val previous = mutableState.value.portfolio
                    mutableState.value = mutableState.value.copy(
                        portfolio = previous?.let { retainKnownInvestmentValuation(it, incoming) } ?: incoming,
                        refreshing = false,
                        refreshMessageCode = null,
                        errorCode = null,
                    )
                    refreshTimeoutJob?.cancel()
                },
                onRefreshError = {
                    if (mutableState.value.refreshing) {
                        diagnostics.record(
                            DiagnosticFeature.INVESTMENT_HOLDINGS,
                            DiagnosticAction.LOAD,
                            "investment_refresh_unavailable",
                            null,
                            "StreamingRefreshFailure",
                        )
                        refreshTimeoutJob?.cancel()
                        mutableState.value = mutableState.value.copy(
                            refreshing = false,
                            refreshMessageCode = "investment_refresh_unavailable",
                        )
                    }
                },
            )
        }
    }
}

class InvestmentEventsViewModel(
    private val repository: InvestmentRepository,
    private val diagnostics: DiagnosticLogger = NoOpDiagnosticLogger,
) : ViewModel() {
    private val mutableState = MutableStateFlow(InvestmentEventsUiState())
    val state: StateFlow<InvestmentEventsUiState> = mutableState.asStateFlow()
    private var initialized = false
    private var pageRequestId = 0
    private var accountsRequestId = 0
    private var evidenceRequestId = 0

    fun initialize() {
        if (initialized) return
        initialized = true
        loadAccounts()
        loadPage(clearSelection = true)
    }

    fun reload() {
        loadAccounts()
        loadPage(clearSelection = true)
    }

    fun updateFilters(filters: InvestmentFilters) {
        if (filters == mutableState.value.filters) return
        mutableState.value = mutableState.value.copy(filters = filters)
        loadPage(clearSelection = true)
    }

    fun loadMore(retry: Boolean = false) {
        val current = mutableState.value
        val cursor = current.nextCursor ?: return
        if (current.loadingMore || current.loading || (!retry && current.appendErrorCode != null)) return
        val requestId = pageRequestId
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loadingMore = true, appendErrorCode = null)
            try {
                val page = repository.fetchInvestmentPage(current.filters, cursor)
                if (requestId != pageRequestId) return@launch
                if (page.dataVersion != mutableState.value.page?.dataVersion) {
                    loadPage(clearSelection = true)
                } else {
                    val existingIds = mutableState.value.events.mapTo(mutableSetOf(), InvestmentEvent::eventId)
                    mutableState.value = mutableState.value.copy(
                        events = mutableState.value.events + page.items.filter { it.eventId !in existingIds },
                        page = page,
                        nextCursor = page.nextCursor,
                    )
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.INVESTMENT_EVENTS, DiagnosticAction.LOAD_NEXT_PAGE)
                if (requestId == pageRequestId) mutableState.value = mutableState.value.copy(appendErrorCode = cause.safeErrorCode())
            } finally {
                if (requestId == pageRequestId) mutableState.value = mutableState.value.copy(loadingMore = false)
            }
        }
    }

    fun selectEvent(event: InvestmentEvent) {
        val requestId = ++evidenceRequestId
        mutableState.value = mutableState.value.copy(
            selected = event,
            evidence = null,
            evidenceErrorCode = null,
            evidenceLoading = true,
        )
        loadEvidence(event, requestId)
    }

    fun retryEvidence() {
        mutableState.value.selected?.let { event -> loadEvidence(event, ++evidenceRequestId) }
    }

    fun closeEvidence() {
        evidenceRequestId++
        mutableState.value = mutableState.value.copy(selected = null, evidence = null, evidenceLoading = false, evidenceErrorCode = null)
    }

    private fun loadAccounts() {
        val requestId = ++accountsRequestId
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(accountsErrorCode = null)
            try {
                val accounts = repository.fetchInvestmentAccounts()
                if (requestId == accountsRequestId) mutableState.value = mutableState.value.copy(accounts = accounts)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.INVESTMENT_EVENTS, DiagnosticAction.LOAD)
                if (requestId == accountsRequestId) mutableState.value = mutableState.value.copy(accountsErrorCode = cause.safeErrorCode())
            }
        }
    }

    private fun loadPage(clearSelection: Boolean) {
        val requestId = ++pageRequestId
        val filters = mutableState.value.filters
        if (clearSelection) evidenceRequestId++
        mutableState.value = mutableState.value.copy(
            loading = true,
            loadingMore = false,
            errorCode = null,
            appendErrorCode = null,
            page = null,
            events = emptyList(),
            nextCursor = null,
            selected = if (clearSelection) null else mutableState.value.selected,
            evidence = if (clearSelection) null else mutableState.value.evidence,
        )
        viewModelScope.launch {
            try {
                val page = repository.fetchInvestmentPage(filters)
                if (requestId == pageRequestId) {
                    mutableState.value = mutableState.value.copy(page = page, events = page.items, nextCursor = page.nextCursor, loading = false)
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.INVESTMENT_EVENTS, DiagnosticAction.LOAD)
                if (requestId == pageRequestId) mutableState.value = mutableState.value.copy(loading = false, errorCode = cause.safeErrorCode())
            }
        }
    }

    private fun loadEvidence(event: InvestmentEvent, requestId: Int) {
        viewModelScope.launch {
            try {
                val evidence = repository.fetchInvestmentEvidence(event.eventId)
                if (requestId == evidenceRequestId) mutableState.value = mutableState.value.copy(evidence = evidence, evidenceLoading = false)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.INVESTMENT_EVENTS, DiagnosticAction.LOAD_EVIDENCE)
                if (requestId == evidenceRequestId) mutableState.value = mutableState.value.copy(evidenceLoading = false, evidenceErrorCode = cause.safeErrorCode())
            }
        }
    }
}

private fun Throwable.safeErrorCode(): String = when (this) {
    is DomainFailure -> if (category == FailureCategory.RECOVERABLE) code else "unknown_error"
    else -> "unknown_error"
}
