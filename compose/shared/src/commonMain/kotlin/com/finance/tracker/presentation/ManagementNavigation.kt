package com.finance.tracker.presentation

import com.finance.tracker.core.AppPage

internal enum class ManagementGroup(val key: String, val titleKey: String) {
    WORKSPACE("workspace", "management_workspace"),
    ACCESS("access", "management_access"),
    CASH_LEDGER("cash-ledger", "navigation_cash_ledger"),
}

internal enum class ManagementSection(val key: String, val titleKey: String, val group: ManagementGroup) {
    WORKSPACE("workspace", "management_basic_info", ManagementGroup.WORKSPACE),
    DANGER("danger", "management_danger", ManagementGroup.WORKSPACE),
    MEMBERS("members", "management_members", ManagementGroup.ACCESS),
    INVITATIONS("invitations", "copy_37f99c470a", ManagementGroup.ACCESS),
    CATEGORIES("categories", "navigation_cash_categories", ManagementGroup.CASH_LEDGER),
}

internal fun managementSections(isAdmin: Boolean): List<ManagementSection> =
    ManagementSection.entries.filter { isAdmin || it != ManagementSection.DANGER }

internal fun initialManagementSection(page: AppPage): ManagementSection =
    if (page == AppPage.CASH_CATEGORIES) ManagementSection.CATEGORIES else ManagementSection.WORKSPACE

internal fun currentManagementSection(offsets: Map<ManagementSection, Int>, scrollPosition: Int): ManagementSection =
    offsets.entries.filter { it.value <= scrollPosition }.maxByOrNull { it.value }?.key ?: ManagementSection.WORKSPACE
