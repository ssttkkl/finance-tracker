package com.finance.tracker.business.domain

import kotlin.test.*
import kotlin.time.Instant

class CashRelationCoreTest {
    private fun fact(id:String="1",amount:String="-10",type:String="consumption",source:String="alipay")=
        RelationFact(id,CashAmount.parse(amount),"CNY","a",occurredAt="2026-10-01T10:00:00Z",recordType=type,
            billSource=source,source=source,recordId="record")
    @Test fun signedPredicatesAndEvidenceUseTheSameFactView() {
        val expense=fact();assertTrue(cashRelationCore.isRefundExpenseCandidate(expense))
        assertTrue(cashRelationCore.isRefundIn(fact(amount="10",type="refund")))
        val negative=fact(type="refund",amount="-0.000000000000000001")
        assertFalse(cashRelationCore.isOriginalRefundExpense(negative))
        assertTrue(cashRelationCore.isOriginalRefundExpense(negative.copy(rawPayload=mapOf("offset_role" to "expense"))))
        assertFalse(cashRelationCore.isOriginalRefundExpense(negative.copy(rawPayload=mapOf("offset_role" to "expense"),relationMetadata=mapOf("other" to "value"))))
        assertFalse(cashRelationCore.isRefundIn(fact(amount="-0E+999999999999999999",type="refund")))
        assertTrue(cashRelationCore.isTransferOutRecord(fact(type="transfer_out")))
        assertTrue(cashRelationCore.isFxInRecord(fact(amount="1",type="fx_in")))
    }
    @Test fun channelPrecedenceScopeAndStableComponentReferencesArePreserved() {
        val value=fact(source="unknown").copy(note="支付宝",parentId="p",componentOrdinal=2)
        assertEquals("other",cashRelationCore.sourceGroup(value))
        assertEquals("platform",cashRelationCore.sourceGroup(value.copy(billSource="",source="")))
        assertEquals("unknown:record#component:2",cashRelationCore.stableFactReference(value))
        assertFalse(cashRelationCore.compatiblePlatformScope(fact().copy(sourceIdentity="a"),fact().copy(sourceIdentity="b")))
        assertTrue(cashRelationCore.compatiblePlatformScope(fact().copy(sourceIdentity="a"),fact(source="wechat").copy(sourceIdentity="b")))
    }
    @Test fun arbitraryIntegerIdentifiersKeepNumericOrderingAndOpenLegSentinel() {
        val n="9".repeat(300)
        assertEquals("2" to n,cashRelationCore.orderedFactPair(n,"0002"))
        assertEquals("-"+n to "-2",cashRelationCore.orderedFactPair("-02","-"+n))
        assertEquals("0" to "0",cashRelationCore.orderedFactPair("-0","+00"))
        assertEquals("5" to 0,cashRelationCore.orderedFactPair("5",null))
        assertEquals(listOf("w","payment_mirror","2","10"),cashRelationCore.relationBusinessKey("w","payment_mirror","010","2"))
        assertEquals(listOf("1","2"),cashRelationCore.topKCandidateIds(listOf("3","","1","3","2"),2))
    }
    @Test fun stableTimestampFormattingUsesUtcMicrosecondsAndRetainsInvalidText() {
        assertEquals("2026-10-01T10:00:00.123456+00:00",cashRelationCore.stableTimestamp("2026-10-01T18:00:00.123456999+08:00"))
        assertEquals("2026-10-01T10:00:00+00:00",cashRelationCore.stableTimestamp("2026-10-01 10:00:00"))
        assertEquals("bad+00:00",cashRelationCore.stableTimestamp(" badZ "))
        for(text in listOf("0001-01-01T00:00:00Z","1600-02-29T23:59:59.123456Z","1900-03-01T00:00:00Z","1969-12-31T23:59:59.999999Z","2000-02-29T00:00:00Z","9999-12-31T23:59:59.999999Z"))
            assertEquals(text.removeSuffix("Z")+"+00:00",formatPythonCashTimestamp(Instant.parse(text)))
    }
    @Test fun factOrderingUsesCanonicalTimestampAndCodePointOrder() {
        val a=fact("a").copy(occurredAt="2026-10-01T18:00:00+08:00")
        val b=fact("b")
        assertEquals(cashRelationCore.stableFactOrderKey(a).first(),cashRelationCore.stableFactOrderKey(b).first())
        assertTrue(cashRelationCore.relationOrderKey(a).compare(a,b)<0)
        val supplementary=charArrayOf(0xd800.toChar(),0xdc00.toChar()).concatToString()
        val bmp=0xe000.toChar().toString()
        assertEquals(bmp to supplementary,cashRelationCore.orderedFactPair(supplementary,bmp))
    }
}
