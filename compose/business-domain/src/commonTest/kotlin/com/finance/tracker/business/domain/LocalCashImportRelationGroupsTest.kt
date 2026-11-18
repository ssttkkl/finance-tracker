package com.finance.tracker.business.domain

import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

class LocalCashImportRelationGroupsTest {
    private fun snapshot(): CashImportPreviewSnapshot<CashImportPreviewInteger, Instant> {
        val base = LocalCashRelationContextTest().snapshot()
        val source = base.copy(facts = base.facts.copy(transactionRelations = emptyList()))
        return buildLocalCashImportPreviewSnapshot(source, readLocalCashRelationContext(source, setOf(1L)),
            LocalCashImportPreviewSnapshotTest().operation, JsonArray(emptyList())) { Instant.parse("2026-10-01T09:00:00Z") }
    }

    @Test fun realLocalFactsBuildStableStrongGroupsWithExactNumericEdgesWithoutMutatingState() {
        val preview = snapshot()
        val before = encodeLocalCashValue(preview.values)
        val proposals = listOf(RelationProposal("payment_mirror", "11", "232800787442169502504161063218564809373",
            status = "accepted", confidence = "strong", ruleId = "fixture"))
        val result = buildLocalCashImportRelationGroups(preview, proposals)
        val group = result.groups.single()
        assertEquals("accept", group["action"])
        assertEquals(listOf("1", "34038115944072999195166050254573576354"), group["parent_ids"])
        val edge = (group["edges"] as List<*>).single() as Map<*, *>
        assertEquals("35435814355376358695398610309847823312", edge["id"].toString())
        assertTrue(edge["id"] is CashImportPreviewInteger)
        assertFalse(encodeLocalCashValue(edge["id"]).jsonPrimitive.isString)
        assertEquals(before, encodeLocalCashValue(preview.values))
        assertTrue(preview.edges.isEmpty())
        assertTrue(result.failures.isEmpty())
    }

    @Test fun originalExpenseStatusRetainsTheSourceTypeDirectionAndNegativeAmountGates() {
        fun value(status: String, amount: Any? = CashAmount.parse("-1"), source: String = "wechat", direction: Any? = "支出") =
            mapOf("source_type" to source, "record_type" to "refund", "amount" to amount,
                "source_explanation" to mapOf("_wechat_direction" to direction, "direction" to "收入", "platform_status" to status))
        for (status in listOf("已全额退款", "对方已退还", "已退款(¥18.00)", " 已退款¥" + "9".repeat(100)))
            assertTrue(localCashImportOriginalExpense(value(status)), status)
        assertFalse(localCashImportOriginalExpense(value("已全额退款", CashAmount.parse("-0.00"))))
        assertFalse(localCashImportOriginalExpense(value("已全额退款", "-1")))
        assertFalse(localCashImportOriginalExpense(value("已全额退款", source = "alipay")))
        assertFalse(localCashImportOriginalExpense(value("已全额退款", direction = "收入")))
        assertFalse(localCashImportOriginalExpense(value("已全额退款", direction = true)))
        assertFalse(localCashImportOriginalExpense(value("退款处理中")))
    }

    @Test fun proposalRefundAmountsUseComputationPrecisionAndInvalidProgramValuesPropagate() {
        val preview = snapshot()
        val part = "232800787442169502504161063218564809373"
        val raw = "1234567890123456789012345678901234567890.00"
        fun proposal(amount: String) = RelationProposal("refund_offset", part, null,
            evidence = RelationEvidence(extras = mapOf("refund_amount" to amount)))
        val result = buildLocalCashImportRelationGroups(preview, listOf(proposal(raw)))
        val edge = ((result.groups.single()["edges"] as List<*>).single() as Map<*, *>)
        assertEquals(raw, (edge["applied_amount"] as CashDecimalLiteral).text)
        assertFailsWith<IllegalArgumentException> { buildLocalCashImportRelationGroups(preview, listOf(proposal("not a decimal"))) }
    }
}
