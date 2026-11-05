package com.finance.tracker.business.domain

import kotlin.test.*
import kotlin.time.Instant

class CashImportPreviewSnapshotTest {
    private val time = Instant.parse("2026-10-02T10:00:00Z")
    private val values = CashProjectionValues<Long, Instant>({ it > 0 }, { true }, { it })
    private val projections = CashProjectionRules(values, Instant::toString)
    private val graph = CashRelationGraphRules(values, projections, { it as? Long }, String::toLong)
    private fun fact(id: Long, state: String = "posted") = CashProjectionFact.create(values, id, 10L,
        if (state == "posted") time else null, if (state == "posted") "-5.00" else null, "CNY", "对方", null,
        "备注", "ccb_debit", "record-$id", state = state, createdAt = time, recordType = "consumption")
    private fun edge(id: Long, first: String, second: String?) = CashRelationGraphEdge(id, "payment_mirror",
        first, second, null, null, null, null, state = "draft")
    private fun snapshot(): CashImportPreviewSnapshot<Long, Instant> {
        val context = CashRelationReadContext<Long, Instant>(emptySet(), emptyList(), emptySet(), emptyList(), emptyList(),
            emptyList(), emptyList(), emptyList(), emptySet(), emptyList(), emptyList(), emptyList(), emptyList(),
            emptyList(), emptyList(), emptySet())
        return CashImportPreviewSnapshot(context,
            linkedMapOf("1" to linkedMapOf("components" to listOf(mapOf("component_id" to "11"))),
                "2" to linkedMapOf("components" to emptyList<Any>())),
            linkedMapOf("1" to fact(1L), "2" to fact(2L, "draft")),
            linkedMapOf("11" to CashRelationEndpoint("11", "1", CashDecimalLiteral("-5.00"), "CNY", "10", "consumption", "not_applicable")),
            linkedMapOf(), linkedMapOf(), linkedMapOf(), linkedMapOf(), linkedSetOf(), linkedSetOf(), mutableListOf(), mutableListOf(),
            projectionValues = values, integer = { it as? Long }, graphBuilder = graph::build,
            projectionBuilder = { facts, relations -> projections.build(facts, relations) })
    }

    @Test fun formalGraphAndDraftsComposeWithoutChangingTheSourceFacts() {
        val input = snapshot()
        input.edges[9007199254740993L] = edge(9007199254740993L, "1", "2")
        val result = input.build()
        assertEquals(setOf(1L, 2L), result.memberIds)
        assertEquals(listOf("posted", "draft"), result.projections.map { it.state })
        assertTrue(result.projections.first().primaryRecord.needsAttention)
        assertFalse(input.facts.getValue("1").needsAttention)
        assertEquals(CashAmount.parseOrNull("-5.00")!!.representation, input.facts.getValue("1").amount!!.representation)
        assertEquals(listOf("draft"), input.build(setOf("2")).projections.map { it.state })
    }

    @Test fun scopedEdgesAndListedComponentsKeepOriginalFundingAndOverrideOrder() {
        val input = snapshot()
        input.edges[9007199254740993L] = edge(9007199254740993L, "1", "2")
        input.edges[9007199254740994L] = edge(9007199254740994L, "unrelated", "other")
        input.components["12"] = input.components.getValue("11").copy(componentId = "12")
        input.funding[9007199254740993L] = CashRelationFundingVersion(9007199254740993L, 11L, 50L, "cash_to_investment")
        input.funding[9007199254740994L] = CashRelationFundingVersion(9007199254740994L, 12L, 51L, "cash_to_investment")
        input.funding[99L] = CashRelationFundingVersion(99L, 999L, 52L, "cash_to_investment")
        val replacement = edge(9007199254740993L, "1", null).copy(active = false)
        val addition = edge(9007199254740995L, "outside", "scope")
        var called = false
        val preview = input.copy(graphBuilder = { rawFacts, rawParts, rawEdges, rawFunding ->
            called = true
            @Suppress("UNCHECKED_CAST") val facts = rawFacts as List<CashProjectionFact<Long, Instant>>
            assertEquals(listOf(1L), facts.map { it.id })
            assertEquals(9007199254740994L, facts.single().fundingRelationId)
            assertFalse(facts.single().needsAttention)
            assertEquals(listOf(input.components.getValue("11")), rawParts)
            assertEquals(listOf(replacement, addition), rawEdges)
            assertEquals(listOf("11"), rawFunding)
            CashProjectionBuild(emptyList())
        })
        assertTrue(preview.build(setOf("1"), listOf(replacement, addition)).projections.isEmpty())
        assertTrue(called)
        assertEquals(2, input.edges.size)
        assertNull(input.facts.getValue("1").fundingRelationId)
    }

    @Test fun graphFailuresPropagateAndCopyKeepsTheOverridableBuildBoundary() {
        val input = snapshot()
        val failure = IllegalStateException("graph program error")
        val preview = input.copy(graphBuilder = { _, _, _, _ -> throw failure })
        assertSame(failure, assertFailsWith<IllegalStateException> { preview.build() })
        assertEquals(setOf(1L, 2L), input.build().memberIds)
        assertTrue(input.build(emptySet()).projections.isEmpty())
    }
}
