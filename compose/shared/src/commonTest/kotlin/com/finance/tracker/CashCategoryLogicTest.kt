package com.finance.tracker

import com.finance.tracker.app.*
import com.finance.tracker.core.*
import com.finance.tracker.data.*
import com.finance.tracker.domain.*
import com.finance.tracker.presentation.*

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CashCategoryLogicTest {
    @Test
    fun parentChoicesExcludeSelfDescendantsAndMovesThatWouldExceedDepthLimit() {
        val items = listOf(
            CashCategory("root", name = "餐饮", depth = 1),
            CashCategory("work", parentId = "root", name = "工作餐", path = listOf(CashCategoryPathItem("root", "餐饮")), depth = 2),
            CashCategory("deep", parentId = "work", name = "深层", path = listOf(CashCategoryPathItem("root", "餐饮"), CashCategoryPathItem("work", "工作餐")), depth = 3),
            CashCategory("other", name = "工作", depth = 1),
            CashCategory("level4", name = "第四层", depth = 4),
            CashCategory("level5", name = "第五层", depth = 5),
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
        val items = listOf(CashCategory("last", name = "最后一项", depth = 1))

        assertEquals(listOf("last"), items.map { it.id })
        assertTrue(canDeleteCashCategory(CashCategoryDeleteImpact("leaf", 1, 1, childCount = 0, directUsageCount = 12)))
        assertFalse(canDeleteCashCategory(CashCategoryDeleteImpact("parent", 1, 1, childCount = 2, directUsageCount = 0)))
    }

    @Test
    fun categorySearchMatchesTheAncestorPathLikeTheWebPage() {
        val items = listOf(
            CashCategory("food", name = "餐饮", depth = 1),
            CashCategory("coffee", parentId = "food", name = "咖啡", path = listOf(CashCategoryPathItem("food", "餐饮")), depth = 2),
            CashCategory("latte", parentId = "coffee", name = "拿铁", path = listOf(
                CashCategoryPathItem("food", "餐饮"),
                CashCategoryPathItem("coffee", "咖啡"),
            ), depth = 3),
        )

        assertEquals(listOf("coffee", "latte"), filterCashCategoriesByAncestorPath(items, "餐饮").map { it.id })
        assertEquals(listOf("latte"), filterCashCategoriesByAncestorPath(items, "咖啡").map { it.id })
        assertEquals(emptyList(), filterCashCategoriesByAncestorPath(items, "拿铁").map { it.id })
        assertEquals(items, filterCashCategoriesByAncestorPath(items, " "))
    }
}
