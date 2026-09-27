package com.finance.tracker.presentation

import com.finance.tracker.domain.*

data class InvestmentHoldingsUiState(
    val portfolio: Portfolio? = null,
    val accounts: List<Account> = emptyList(),
    val accountsErrorCode: String? = null,
    val displayOptions: InvestmentDisplayOptions = InvestmentDisplayOptions(),
    val currencyDraft: String = "",
    val tickerFilter: String = "",
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val errorCode: String? = null,
    val refreshMessageCode: String? = null,
    val expandedPosition: InvestmentHoldingRow? = null,
)

data class InvestmentEventsUiState(
    val accounts: List<Account> = emptyList(),
    val filters: InvestmentFilters = InvestmentFilters(),
    val page: InvestmentPage? = null,
    val events: List<InvestmentEvent> = emptyList(),
    val selected: InvestmentEvent? = null,
    val evidence: InvestmentEvidence? = null,
    val evidenceLoading: Boolean = false,
    val evidenceErrorCode: String? = null,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val appendErrorCode: String? = null,
    val accountsErrorCode: String? = null,
    val errorCode: String? = null,
    val nextCursor: String? = null,
)
