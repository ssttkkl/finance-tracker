package com.finance.tracker.business.domain

import kotlin.test.*

class CashProjectionTopologyTest {
    @Test fun componentsUseTheSmallestIdAndDeterministicGroupOrdering() {
        val large=9007199254740993L
        val components=CashProjectionComponents(listOf(large,10L,2L,1L))
        components.union(large,10L);components.union(2L,large)
        assertEquals(2L,components.find(large))
        assertEquals(listOf(listOf(1L),listOf(2L,10L,large)),components.groups())
        assertEquals(1L,cashProjectionRoot(listOf(large,10L,2L,1L),listOf(1L to 2L,2L to 10L,10L to large)))
    }
    @Test fun rootedTreeRejectsDisconnectedCyclesDuplicateAndMultipleParentPaths() {
        fun invalid(nodes:List<Int>,edges:List<Pair<Int,Int>>) {
            assertEquals("projection.invalid_relation",assertFailsWith<CashProjectionDomainFailure> {cashProjectionRoot(nodes,edges)}.code)
        }
        invalid(emptyList(),emptyList())
        invalid(listOf(1,2),emptyList())
        invalid(listOf(1,2),listOf(1 to 2,2 to 1))
        invalid(listOf(1,2),listOf(1 to 2,1 to 2))
        invalid(listOf(1,2,3,4),listOf(1 to 2,1 to 3,2 to 4,3 to 4))
        assertEquals(7,cashProjectionRoot(listOf(7),emptyList()))
    }
    @Test fun sourceAndTransferLabelsKeepExistingPriority() {
        val documentProvider=listOf("source","document","key","provider_id")
        val accountWeak=listOf("source","account","key","position")
        val accountProvider=listOf("source","account","key","provider_id")
        assertEquals(documentProvider,cashSourceOrderKey("ignored","ignored",listOf(accountWeak,documentProvider)))
        assertEquals(accountProvider,cashSourceOrderKey(null,"",listOf(documentProvider,accountProvider)))
        assertEquals(listOf("manual_transfer","transfer","outgoing"),cashSourceOrderKey(null,"",recordOrigin="manual_transfer",manualTransferId="transfer",amountSign=-1))
        assertEquals(listOf("manual","legacy_source_record","record"),cashSourceOrderKey(null,"record"))
        assertEquals(emptyList(),cashSourceOrderKey("x","import-v1:old"))
        assertEquals("credit_repayment",cashTransferBusinessLabel(setOf("repayment","fx_in"),listOf("cross_border_remittance")))
        assertEquals("currency_exchange",cashTransferBusinessLabel(setOf("fx_out"),listOf("cross_border_remittance")))
        assertEquals("cross_currency_remittance",cashTransferBusinessLabel(setOf("transfer_out"),listOf("cross_border_remittance")))
        assertEquals("ordinary_transfer",cashTransferBusinessLabel(emptySet(),emptyList()))
    }
}
