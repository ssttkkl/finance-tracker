package com.finance.tracker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CashRelationLogicTest {
    @Test
    fun relationSearchUsesThreeLocalCalendarDaysOnEachSide() {
        assertEquals(
            CashRelationDateRange("2024-02-27", "2024-03-04"),
            cashRelationDateRange("2024-03-01"),
        )
        assertEquals(
            CashRelationDateRange("2023-12-29", "2024-01-04"),
            cashRelationDateRange("2024-01-01"),
        )
        assertNull(cashRelationDateRange("2024-02-30"))
    }

    @Test
    fun relationDateRangeCanBeClearedLikeTheWebFilters() {
        assertTrue(isValidCashRelationDateFilter("", ""))
        assertTrue(isValidCashRelationDateFilter("2024-02-29", ""))
        assertFalse(isValidCashRelationDateFilter("2024-02-30", ""))
        assertFalse(isValidCashRelationDateFilter("2024-03-02", "2024-03-01"))
    }

    @Test
    fun categoryMovesStayWithinTheirSiblingGroup() {
        val items = listOf(
            CashCategoryDto("root-a", name = "甲", depth = 1),
            CashCategoryDto("child-a", parentId = "root-a", name = "甲一", depth = 2),
            CashCategoryDto("root-b", name = "乙", depth = 1),
            CashCategoryDto("child-b", parentId = "root-b", name = "乙一", depth = 2),
            CashCategoryDto("child-b2", parentId = "root-b", name = "乙二", depth = 2),
        )

        assertFalse(categoryCanMove(items, "child-a", "before"))
        assertFalse(categoryCanMove(items, "child-a", "after"))
        assertTrue(categoryCanMove(items, "child-b", "after"))
        assertFalse(categoryCanMove(items, "child-b2", "after"))
        assertFalse(categoryCanMove(items, "root-b", "sideways"))
    }
}
