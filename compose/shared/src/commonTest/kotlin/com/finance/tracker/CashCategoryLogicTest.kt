package com.finance.tracker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CashCategoryLogicTest {
    @Test
    fun parentChoicesExcludeSelfDescendantsAndMovesThatWouldExceedDepthLimit() {
        val items = listOf(
            CashCategoryDto("root", name = "餐饮", depth = 1),
            CashCategoryDto("work", parentId = "root", name = "工作餐", path = listOf(CashCategoryPathItemDto("root", "餐饮")), depth = 2),
            CashCategoryDto("deep", parentId = "work", name = "深层", path = listOf(CashCategoryPathItemDto("root", "餐饮"), CashCategoryPathItemDto("work", "工作餐")), depth = 3),
            CashCategoryDto("other", name = "工作", depth = 1),
            CashCategoryDto("level4", name = "第四层", depth = 4),
            CashCategoryDto("level5", name = "第五层", depth = 5),
        )

        val choices = availableCategoryParents(items, editingCategoryId = "work")

        assertEquals(listOf("root", "other"), choices.map { it.id })
        assertFalse("work" in choices.map { it.id })
        assertFalse("deep" in choices.map { it.id })
        assertFalse("level4" in choices.map { it.id })
        assertFalse("level5" in choices.map { it.id })
    }

    @Test
    fun rootCategoryCanBeCreatedAsTheFinalDirectoryAction() {
        val items = listOf(CashCategoryDto("last", name = "最后一项", depth = 1))

        assertEquals(listOf("last"), items.map { it.id })
        assertTrue(canDeleteCashCategory(CashCategoryDeleteImpactDto("leaf", 1, 1, childCount = 0, directUsageCount = 12)))
        assertFalse(canDeleteCashCategory(CashCategoryDeleteImpactDto("parent", 1, 1, childCount = 2, directUsageCount = 0)))
    }

    @Test
    fun categorySearchMatchesTheAncestorPathLikeTheWebPage() {
        val items = listOf(
            CashCategoryDto("food", name = "餐饮", depth = 1),
            CashCategoryDto("coffee", parentId = "food", name = "咖啡", path = listOf(CashCategoryPathItemDto("food", "餐饮")), depth = 2),
            CashCategoryDto("latte", parentId = "coffee", name = "拿铁", path = listOf(
                CashCategoryPathItemDto("food", "餐饮"),
                CashCategoryPathItemDto("coffee", "咖啡"),
            ), depth = 3),
        )

        assertEquals(listOf("coffee", "latte"), filterCashCategoriesByAncestorPath(items, "餐饮").map { it.id })
        assertEquals(listOf("latte"), filterCashCategoriesByAncestorPath(items, "咖啡").map { it.id })
        assertEquals(emptyList(), filterCashCategoriesByAncestorPath(items, "拿铁").map { it.id })
        assertEquals(items, filterCashCategoriesByAncestorPath(items, " "))
    }
}
