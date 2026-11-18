package com.finance.tracker.business.domain

import kotlin.test.*
import kotlin.time.Instant

class CashImportRelationGroupRulesTest {
    private fun rules(refundAmount: (Any?) -> String = { it.toString() }) = CashImportRelationGroupRules<Long, Instant>(
        { it as? Long }, { _: String, _: List<Any?> -> 9007199254740993L }, ::localCashImportDigest,
        { it.toLong() > 0 }, refundAmount, { LedgerArithmeticValue.fromCashAmount(it).signum < 0 }, { false })
    private fun snapshot(): CashImportPreviewSnapshot<Long, Instant> {
        val initial = CashImportPreviewOverlayTest().snapshot()
        initial.edges.clear()
        val preview = initial.copy(context = initial.context.copy(relations = emptyList()))
        preview.parentOps["1"] = linkedMapOf("planned_action" to "create_posted", "row_refs" to listOf("file:r1"),
            "values" to preview.values.getValue("1"))
        return preview
    }
    private val strong = RelationProposal("payment_mirror", "11", "22", status = "accepted", confidence = "strong", ruleId = "fixture")

    @Test fun strongEvidenceBuildsAStableGroupAndRetainsExactVirtualIds() {
        val preview = snapshot()
        val result = rules().build(preview, listOf(strong))
        val group = result.groups.single()
        assertEquals("accept", group["action"])
        assertEquals(listOf("1", "2"), group["parent_ids"])
        assertEquals(listOf(mapOf("row_ref" to "file:r1"), mapOf("transaction_id" to "2")), group["member_refs"])
        assertEquals(9007199254740993L, ((group["edges"] as List<*>).single() as Map<*, *>)["id"])
        // Independent CPython json.dumps(ensure_ascii=False, sort_keys=True, separators=(",", ":")) fixture digest.
        assertEquals("sg_8a151c2e19af875e9644df5cdf3ff9c7a3b40983", group["suggestion_id"])
        assertEquals(group["suggestion_id"], rules().build(preview, listOf(strong)).groups.single()["suggestion_id"])
        assertTrue(result.failures.isEmpty())
        assertTrue(preview.edges.isEmpty())
    }

    @Test fun existingActivePairsAndImportsWithoutNewFactsDoNotCreateSuggestions() {
        val initial = CashImportPreviewOverlayTest().snapshot()
        initial.parentOps["1"] = linkedMapOf("planned_action" to "create_posted")
        assertTrue(rules().build(initial, listOf(strong)).groups.isEmpty())
        val unchanged = snapshot()
        unchanged.parentOps.getValue("1")["planned_action"] = "update"
        assertTrue(rules().build(unchanged, listOf(strong)).groups.isEmpty())
    }

    @Test fun invalidProposalEndpointsAreIgnoredButProgramFailuresPropagate() {
        val preview = snapshot()
        assertTrue(rules().build(preview, listOf(strong.copy(secondaryFactId = "missing"))).groups.isEmpty())
        val failure = IllegalStateException("amount program error")
        val refund = RelationProposal("refund_offset", "11", null, evidence = RelationEvidence(extras = mapOf("refund_amount" to "1")))
        assertSame(failure, assertFailsWith<IllegalStateException> { rules { throw failure }.build(preview, listOf(refund)) })
    }
}
