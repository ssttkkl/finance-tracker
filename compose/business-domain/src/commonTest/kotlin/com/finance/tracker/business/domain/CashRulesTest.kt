package com.finance.tracker.business.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class CashRulesTest {
    @Test fun exactAllocationDoesNotLoseMinorUnitsAtLargeMagnitudes() {
        assertEquals(AllocationBalance("complete", "0.000000000000000000", "99999999999999999999.999999999999999999"),
            allocationBalance("-99999999999999999999.999999999999999999", listOf("99999999999999999999.999999999999999998", "0.000000000000000001")))
        assertEquals("incomplete", allocationBalance("1.00", listOf("1.00", "")).state)
        assertEquals("invalid", allocationBalance("1.00", listOf("-1", "2")).state)
    }

    @Test fun categoryParentsExcludeCyclesAndSubtreesThatExceedFiveLevels() {
        val nodes = listOf(
            CategoryPosition("root", 1),
            CategoryPosition("child", 2, listOf("root")),
            CategoryPosition("leaf", 3, listOf("root", "child")),
            CategoryPosition("other", 1),
            CategoryPosition("four", 4),
            CategoryPosition("five", 5),
        )
        assertEquals(listOf("root", "other"), availableCategoryParentIds(nodes, "child"))
        assertEquals(listOf("root", "child", "leaf", "other", "four"), availableCategoryParentIds(nodes, null))
    }
}
