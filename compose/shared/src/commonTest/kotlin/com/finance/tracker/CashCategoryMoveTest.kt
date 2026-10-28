package com.finance.tracker

import com.finance.tracker.domain.*
import com.finance.tracker.presentation.*
import kotlin.test.*

class CashCategoryMoveTest {
    @Test
    fun categoryMovesStayWithinTheirSiblingGroup() {
        val items = listOf(
            CashCategory("root-a", name = "甲", depth = 1),
            CashCategory("child-a", parentId = "root-a", name = "甲一", depth = 2),
            CashCategory("root-b", name = "乙", depth = 1),
            CashCategory("child-b", parentId = "root-b", name = "乙一", depth = 2),
            CashCategory("child-b2", parentId = "root-b", name = "乙二", depth = 2),
        )

        assertFalse(categoryCanMove(items, "child-a", "before"))
        assertFalse(categoryCanMove(items, "child-a", "after"))
        assertTrue(categoryCanMove(items, "child-b", "after"))
        assertFalse(categoryCanMove(items, "child-b2", "after"))
        assertFalse(categoryCanMove(items, "root-b", "sideways"))
    }
}
