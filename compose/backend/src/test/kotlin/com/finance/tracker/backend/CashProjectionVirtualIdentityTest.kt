package com.finance.tracker.backend

import java.math.BigInteger
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CashProjectionVirtualIdentityTest {
    private val virtual = BigInteger("24197857200151252747416209503149608431")
    private val virtualRelation = BigInteger("18446744073709551625")
    private val occurred = Instant.parse("2026-10-01T00:00:00Z")

    private fun fact(id: BigInteger) = CashProjectionFact.create(
        id, 1L, occurred, "-7.00", "CNY", "商户", null, "", "alipay", id.toString(),
    )

    @Test fun virtualParentIdSurvivesProjectionWithoutTruncation() {
        val projection = buildCashProjections(listOf(fact(virtual)), emptyList()).projections.single()
        assertEquals("cash:$virtual", projection.projectionId)
        assertEquals(virtual, projection.primaryRecord.id)
        assertEquals(listOf(virtual), projection.memberIds)
        assertEquals(CashAmount.parse("-7.00"), projection.netAmount)
    }

    @Test fun virtualRelationKeepsRealParentAsMirrorRoot() {
        val relation = CashProjectionRelation.create(virtualRelation, "payment_mirror", BigInteger.ONE, virtual)
        val projection = buildCashProjections(listOf(fact(BigInteger.ONE), fact(virtual)), listOf(relation))
            .projections.single()
        assertEquals("cash:1", projection.projectionId)
        assertEquals(listOf(BigInteger.ONE, virtual), projection.memberIds)
        assertEquals(virtualRelation, projection.relations.single().id)
        assertEquals(CashAmount.parse("-7.00"), projection.netAmount)
    }

    @Test fun virtualParentAndComponentSurviveRelationGraph() {
        val components = listOf(BigInteger.ONE, virtual).map { id ->
            CashRelationEndpoint(id.toString(), id.toString(), CashDecimalLiteral("-7.00"), "CNY", "1", "consumption", defaultCashRecordSubtype("consumption"))
        }
        val edge = CashRelationGraphEdge(
            virtualRelation, "payment_mirror", "1", virtual.toString(), "1", virtual.toString(), "expense", "expense",
        )
        val projection = buildCashRelationGraph(listOf(fact(BigInteger.ONE), fact(virtual)), components, listOf(edge))
            .projections.single()
        assertEquals("cash:1", projection.projectionId)
        assertEquals(listOf(BigInteger.ONE, virtual), projection.memberIds)
        assertEquals(virtualRelation, projection.relations.single().id)
    }

    @Test fun persistedIdsRequirePositiveBigintRange() {
        assertEquals(Long.MAX_VALUE, BigInteger.valueOf(Long.MAX_VALUE).storedCashId())
        assertEquals(Long.MAX_VALUE, BigInteger.valueOf(Long.MAX_VALUE).storedRelationId())
        for (id in listOf(BigInteger.ZERO, virtual)) {
            assertEquals("projection.invalid_fact", assertFailsWith<CashProjectionDomainFailure> { id.storedCashId() }.code)
            assertEquals("projection.invalid_relation", assertFailsWith<CashProjectionDomainFailure> { id.storedRelationId() }.code)
        }
    }
}
