package com.finance.tracker.backend

import java.time.Instant
import kotlin.test.*

class CashProjectionFastPathMigrationTest {
    @Test fun everyOriginalSingletonVectorEqualsGeneralAlgorithmWithoutGraphWork() {
        for(amount in listOf("-20.00","20.00","0","-0.00")) for(funding in listOf(null,0L,12L)) for(attention in listOf(false,true)) {
            val fact=CashProjectionFact.create(1,101,Instant.parse("2026-01-01T00:00:00Z"),amount,"CNY","",null,"","fixture","1",
                fundingRelationId=funding,needsAttention=attention)
            val expected=validateProjectionGroup(listOf(1),mapOf(1L to fact),emptyList())
            val actual=buildCashProjections(listOf(fact),emptyList(),observeGroup={_,_ ->fail("unnecessary_singleton_graph")})
            assertEquals(listOf(expected),actual.projections)
        }
    }
}
