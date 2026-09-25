package com.finance.tracker

data class AppDestination(
    val page: AppPage,
    val title: String,
    val semanticId: String,
)

val appDestinations = listOf(
    AppDestination(AppPage.CASH_LEDGER, "收支账本", "navigation-item-cash-ledger"),
    AppDestination(AppPage.CASH_IMPORT, "账单导入", "navigation-item-cash-import"),
    AppDestination(AppPage.CASH_CATEGORIES, "收支分类", "navigation-item-cash-categories"),
    AppDestination(AppPage.INVESTMENT_HOLDINGS, "当前持仓", "navigation-item-investment-holdings"),
    AppDestination(AppPage.INVESTMENT_EVENTS, "投资事件", "navigation-item-investment-events"),
    AppDestination(AppPage.WORKSPACE_MANAGEMENT, "工作区管理", "navigation-item-workspace-management"),
)

fun titleFor(page: AppPage): String = when (page) {
    AppPage.INVITATION -> "接受邀请"
    AppPage.NOT_FOUND -> "页面不存在"
    else -> appDestinations.first { it.page == page }.title
}
