package com.finance.tracker.app

import com.finance.tracker.core.*
import com.finance.tracker.domain.*
import com.finance.tracker.presentation.*

data class AppDestination(
    val page: AppPage,
    val titleKey: String,
    val semanticId: String,
)

val appDestinations = listOf(
    AppDestination(AppPage.CASH_LEDGER, "navigation_cash_ledger", "navigation-item-cash-ledger"),
    AppDestination(AppPage.INVESTMENT_HOLDINGS, "navigation_investment_holdings", "navigation-item-investment-holdings"),
    AppDestination(AppPage.INVESTMENT_EVENTS, "navigation_investment_events", "navigation-item-investment-events"),
    AppDestination(AppPage.WORKSPACE_MANAGEMENT, "navigation_workspace_management", "navigation-item-workspace-management"),
)

internal fun primaryPageFor(page: AppPage): AppPage = when (page) {
    AppPage.CASH_IMPORT -> AppPage.CASH_LEDGER
    AppPage.CASH_CATEGORIES -> AppPage.WORKSPACE_MANAGEMENT
    else -> page
}

fun titleKeyFor(page: AppPage): String = when (page) {
    AppPage.INVITATION -> "navigation_invitation"
    AppPage.NOT_FOUND -> "navigation_not_found"
    AppPage.CASH_IMPORT -> "navigation_cash_ledger"
    AppPage.CASH_CATEGORIES -> "navigation_workspace_management"
    else -> appDestinations.first { it.page == page }.titleKey
}
