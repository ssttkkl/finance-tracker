package com.finance.tracker.business.domain

import kotlin.test.*
import kotlin.time.Instant

class CashProjectionRulesSnapshotTest {
    @Test fun observerCannotMutateGroupsOrEdgesAndResultListsRemainReadOnly() {
        val at=Instant.parse("2026-10-01T00:00:00Z")
        val values=CashProjectionValues<Long,Instant>({it>0},{true},{it})
        fun fact(id:Long)=CashProjectionFact.create(values,id,1L,at,"-10","CNY","",null,"","alipay","r$id")
        val relation=CashProjectionRelation.create(values,1L,"payment_mirror",1L,2L)
        var observed=0
        val result=CashProjectionRules(values) {it.toString()}.build(listOf(fact(1),fact(2)),listOf(relation)) {ids,edges ->
            observed++
            assertEquals(listOf(1L,2L),ids)
            assertEquals(listOf(relation),edges)
            assertFails {(ids as MutableList<Long>).clear()}
            assertFails {(edges as MutableList<CashProjectionRelation<Long>>).clear()}
        }
        assertEquals(1,observed)
        assertEquals(setOf(1L,2L),result.memberIds)
        val row=result.projections.single()
        assertFails {(result.projections as MutableList<CashProjection<Long,Instant>>).clear()}
        assertFails {(row.memberIds as MutableList<Long>).clear()}
        assertFails {(row.members as MutableList<Pair<CashProjectionFact<Long,Instant>,List<String>>>).clear()}
        assertFails {(row.relations as MutableList<CashProjectionRelation<Long>>).clear()}
        assertFails {(row.members.first().second as MutableList<String>).clear()}
    }
}
