package com.finance.tracker

import com.finance.tracker.core.AppPage
import com.finance.tracker.presentation.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ManagementNavigationTest {
    @Test
    fun directoryKeepsWorkspaceSettingsTogetherBeforeAccessAndLedger() {
        assertEquals(listOf(ManagementSection.WORKSPACE, ManagementSection.DANGER,
            ManagementSection.MEMBERS, ManagementSection.INVITATIONS, ManagementSection.CATEGORIES), managementSections(true))
        assertEquals(listOf(ManagementSection.WORKSPACE, ManagementSection.MEMBERS,
            ManagementSection.INVITATIONS, ManagementSection.CATEGORIES), managementSections(false))
    }

    @Test
    fun directoryUsesTaskOrderAndHidesDangerForNonAdministrators() {
        assertEquals(ManagementSection.entries.toList(), managementSections(true))
        assertEquals(ManagementSection.CATEGORIES, managementSections(false).last())
        assertFalse(ManagementSection.DANGER in managementSections(false))
    }

    @Test
    fun sectionsHaveTheSameTwoLevelOwnershipForEveryRole() {
        assertEquals(listOf(ManagementGroup.WORKSPACE, ManagementGroup.ACCESS, ManagementGroup.CASH_LEDGER),
            managementSections(true).map { it.group }.distinct())
        assertEquals(listOf(ManagementSection.WORKSPACE, ManagementSection.DANGER),
            managementSections(true).filter { it.group == ManagementGroup.WORKSPACE })
        assertEquals(listOf(ManagementSection.WORKSPACE),
            managementSections(false).filter { it.group == ManagementGroup.WORKSPACE })
        assertEquals(listOf(ManagementSection.MEMBERS, ManagementSection.INVITATIONS),
            managementSections(false).filter { it.group == ManagementGroup.ACCESS })
        assertEquals(listOf(ManagementSection.CATEGORIES),
            managementSections(false).filter { it.group == ManagementGroup.CASH_LEDGER })
    }

    @Test
    fun legacyCategoryDeepLinkChoosesTheCategorySection() {
        assertEquals(ManagementSection.CATEGORIES, initialManagementSection(AppPage.CASH_CATEGORIES))
        assertEquals(ManagementSection.WORKSPACE, initialManagementSection(AppPage.WORKSPACE_MANAGEMENT))
    }

    @Test
    fun currentSectionFollowsMeasuredOffsetsIncludingGapsAndLargeCategories() {
        val offsets = mapOf(ManagementSection.WORKSPACE to 0, ManagementSection.MEMBERS to 240,
            ManagementSection.CATEGORIES to 600, ManagementSection.DANGER to 3600)
        assertEquals(ManagementSection.WORKSPACE, currentManagementSection(offsets, 0))
        assertEquals(ManagementSection.MEMBERS, currentManagementSection(offsets, 500))
        assertEquals(ManagementSection.CATEGORIES, currentManagementSection(offsets, 3000))
        assertEquals(ManagementSection.DANGER, currentManagementSection(offsets, 3600))
    }
}
