package com.finance.tracker

import com.finance.tracker.domain.*
import com.finance.tracker.presentation.cashEffectTargets
import kotlin.test.*

class CashEffectTargetsTest {
    @Test fun relationEffectUsesWholeGroupAndKeepsDifferentObjects() {
        val first = detail("a", "11", "12", "sg_one", "71")
        val second = detail("b", "13", "14", "sg_two", "72")
        val input = CashMaintenanceRequest("edit_record", "11", 1)
        assertEquals(setOf("11", "12"), cashEffectTargets(CashAdditionalEffect("relation_changed", "sg_one"), listOf(first, second), input))
        assertEquals(setOf("13", "14"), cashEffectTargets(CashAdditionalEffect("relation_changed", "72"), listOf(first, second), input))
        assertTrue(cashEffectTargets(CashAdditionalEffect("relation_changed", "unknown"), listOf(first, second), input).isEmpty())
    }
    @Test fun fieldAndAttentionPointToExactMemberAndFundingToRequestedRecord() {
        val detail = detail("a", "11", "9007199254740993", "sg", "71")
        val input = CashMaintenanceRequest("edit_record", "11", 1)
        assertEquals(setOf("9007199254740993"), cashEffectTargets(CashAdditionalEffect("field_value_changed", "9007199254740993", "note"), listOf(detail), input))
        assertEquals(setOf("11"), cashEffectTargets(CashAdditionalEffect("funding_relation_removed", "funding"), listOf(detail), input))
    }
    private fun detail(id: String, first: String, second: String, group: String, relation: String): ImportItemDetail {
        val refs = listOf(ImportMemberRef(null, first), ImportMemberRef(null, second))
        return ImportItemDetail(id, refs.map { ImportMemberDetail(null, it.transactionId, "duplicate", StructuredObject(emptyMap()), emptyList(), emptyList(), 1) },
            emptyList(), emptyList(), listOf(ImportPairOption(group, "payment_mirror", null, "defer", null, emptyList(), refs, listOf("accept"), null)),
            listOf(ImportPairEffect(relation, "payment_mirror", null, "remove", refs, null)))
    }
}
