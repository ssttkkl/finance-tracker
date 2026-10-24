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
    AppDestination(AppPage.CASH_CATEGORIES, "navigation_cash_categories", "navigation-item-cash-categories"),
    AppDestination(AppPage.INVESTMENT_HOLDINGS, "navigation_investment_holdings", "navigation-item-investment-holdings"),
    AppDestination(AppPage.INVESTMENT_EVENTS, "navigation_investment_events", "navigation-item-investment-events"),
    AppDestination(AppPage.WORKSPACE_MANAGEMENT, "navigation_workspace_management", "navigation-item-workspace-management"),
)

internal fun primaryPageFor(page: AppPage): AppPage =
    if (page == AppPage.CASH_IMPORT) AppPage.CASH_LEDGER else page

fun titleKeyFor(page: AppPage): String = when (page) {
    AppPage.INVITATION -> "navigation_invitation"
    AppPage.NOT_FOUND -> "navigation_not_found"
    AppPage.CASH_IMPORT -> "cash_import_title"
    else -> appDestinations.first { it.page == page }.titleKey
}
