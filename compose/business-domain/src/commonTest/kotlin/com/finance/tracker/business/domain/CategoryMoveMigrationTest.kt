package com.finance.tracker.business.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class CategoryMoveMigrationTest {
    @Test fun everyDescendantUsesTheOriginalRootDepthRegardlessOfInputOrder() {
        val root = CategoryPosition("root", 1)
        val child = CategoryPosition("child", 2, listOf("root"))
        val leaf = CategoryPosition("leaf", 3, listOf("root", "child"))
        val parent = CategoryPosition("parent", 1)
        listOf(listOf(root, child, leaf), listOf(child, root, leaf), listOf(child, leaf, root)).forEach { subtree ->
            val result = categoryMovePlan(subtree + parent, "root", "parent")
            assertEquals(null, result.violation)
            assertEquals(mapOf("root" to 2, "child" to 3, "leaf" to 4), result.depths)
        }
    }

    @Test fun completeSubtreeDepthAndCyclesAreCheckedBeforePublishingAPlan() {
        val nodes = listOf(CategoryPosition("root", 1), CategoryPosition("child", 2, listOf("root")),
            CategoryPosition("four", 4), CategoryPosition("five", 5))
        assertEquals("category.cycle", categoryMovePlan(nodes, "root", "child").violation)
        assertEquals("category.depth_limit", categoryMovePlan(nodes, "root", "four").violation)
        assertEquals("category.depth_limit", categoryMovePlan(nodes, "child", "five").violation)
        assertEquals(mapOf("child" to 1), categoryMovePlan(nodes, "child", null).depths)
    }
}
