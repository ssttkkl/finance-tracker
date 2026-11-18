package com.finance.tracker.business.domain

import kotlin.time.Instant
import kotlin.test.*

class CashProjectionModelSnapshotTest {
    @Test fun memberIdentifiersAreUnmodifiableSnapshotsAndFactCopiesStayPrivate() {
        val at=Instant.parse("2026-10-01T00:00:00Z")
        val values=CashProjectionValues<Long,Instant>({it>0},{true},{it})
        val fact=CashProjectionFact.create(values,1L,1L,at,"-1","CNY","",null,"","manual","one")
        val projection=CashProjection("cash:1",fact,fact.amount,CashEconomicType.EXPENSE,true,null,null,
            listOf(1L),listOf(fact to listOf("root")),emptyList(),emptyList())
        val source=mutableListOf(projection)
        val build=CashProjectionBuild(source)
        val snapshot=build.memberIds
        source.clear()
        assertEquals(setOf(1L),snapshot)
        assertEquals(emptySet(),build.memberIds)
        assertFails { (snapshot as MutableSet<Long>).clear() }
        assertEquals(setOf(1L),snapshot)
        val copy=fact.javaClass.declaredMethods.single {it.name=="copy"}
        assertTrue(java.lang.reflect.Modifier.isPrivate(copy.modifiers))
    }
}
