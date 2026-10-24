package com.finance.tracker.presentation

import com.finance.tracker.domain.*

data class CashLedgerUiState(
    val accounts: List<Account> = emptyList(),
    val categories: List<CashCategory> = emptyList(),
    val options: LedgerOptions = LedgerOptions(),
    val page: CashPage? = null,
    val filterOptions: CashFilterOptions = CashFilterOptions(),
    val projectionVersion: Long = 0,
    val listRevision: Int = 0,
    val referencesErrorCode: String? = null,
    val filters: CashFilters = CashFilters(),
    val selectedIds: Set<String> = emptySet(),
    val selectedCategory: String = "",
    val classifyBusy: Boolean = false,
    val classifyErrorCode: String? = null,
    val deleteProjectionImpact: CashProjectionDeleteImpact? = null,
    val deleteProjectionBusy: Boolean = false,
    val deleteProjectionErrorCode: String? = null,
)
