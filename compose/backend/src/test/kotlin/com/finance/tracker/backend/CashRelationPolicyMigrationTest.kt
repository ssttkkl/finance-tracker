package com.finance.tracker.backend

import kotlin.test.*

class CashRelationPolicyMigrationTest {
    private fun endpoint(ref: String,type: String="consumption",amount: String?="-100")=CashRelationEndpoint(ref,"t-$ref",amount?.let(::CashDecimalLiteral),"CNY","account",type,defaultCashRecordSubtype(type))
    private fun roles(kind: String,a: CashRelationEndpoint,b: CashRelationEndpoint,subtype: String="",evidence: CashRelationEvidence=CashRelationEvidence())=
        discoverCashRelationRoles(kind,subtype,a,b,evidence)
    private fun validate(kind: String,a: CashRelationEndpoint,b: CashRelationEndpoint,roles: Pair<String,String>,subtype: String="")=validateCashRelationRoles(kind,subtype,a,b,roles.first,roles.second)
    private fun rejected(code: String?=null,action:()->Unit) {val failure=assertFailsWith<CashRelationFailure>(block=action);if(code!=null) assertEquals(code,failure.code)}
    @Test fun existingExpenseRoleSurvivesSourceHintRemovalAndTypeChanges() {
        val a=endpoint("expense");val b=endpoint("refund","refund","100");val roles=roles("refund_offset",a,b)
        assertEquals("expense" to "refund",roles)
        for(type in listOf("consumption","refund","investment_out")) validate("refund_offset",a.copy(recordType=type),b,roles)
    }
    @Test fun newNegativeRefundNeedsItsOwnEvidence() {
        val negative=endpoint("negative","refund");val expense=endpoint("expense")
        rejected("relation_role_unproven") {roles("payment_mirror",expense,negative)}
        assertEquals("expense" to "expense",roles("payment_mirror",expense,negative,evidence=CashRelationEvidence(secondaryOriginalExpense=true)))
        rejected("relation_role_unproven") {roles("payment_mirror",negative,negative.copy(componentId="another",transactionId="t-another"),evidence=CashRelationEvidence(primaryOriginalExpense=true))}
    }
    @Test fun newInvestmentRefundUsesOnlyHardKeyPath() {
        val a=endpoint("investment","investment_out");val b=endpoint("refund","refund","100")
        rejected("relation_role_unproven") {roles("refund_offset",a,b)}
        val roles=roles("refund_offset",a,b,evidence=CashRelationEvidence(investmentRefundHardKey=true));validate("refund_offset",a,b,roles)
        rejected {roles("payment_mirror",a,a.copy(componentId="other",transactionId="other"),evidence=CashRelationEvidence(investmentRefundHardKey=true))}
    }
    @Test fun p2pReturnKeepsDistinctRolesAndHardKeyEvidence() {
        val a=endpoint("out","transfer_reversal");val b=endpoint("in","transfer_reversal","100")
        rejected("relation_role_unproven") {roles("refund_offset",a,b,"p2p_return")}
        val roles=roles("refund_offset",a,b,"p2p_return",CashRelationEvidence(p2pReturnHardKey=true));assertEquals("p2p_out" to "p2p_in",roles)
        validate("refund_offset",a,b,roles,"p2p_return");rejected {validate("refund_offset",a,b.copy(recordType="income"),roles,"p2p_return")}
    }
    @Test fun standardTransferRolesKeepWithdrawalAndRepayment() {
        for((out,incoming,subtype) in listOf(Triple("transfer_out","transfer_in","ordinary_transfer"),Triple("withdrawal_out","withdrawal_in","ordinary_transfer"),Triple("repayment","repayment","credit_repayment"))) {
            val a=endpoint("out",out);val b=endpoint("in",incoming,"100");val expected="outgoing" to "incoming"
            assertEquals(expected,roles("transfer_pair",a,b,subtype));validate("transfer_pair",a,b,expected,subtype)
        }
    }
    @Test fun transferRecordSubtypesNeedNotMatch() {
        for(subtype in listOf("cross_border_remittance","internal_account_transfer")) validate("transfer_pair",endpoint("out","transfer_out").copy(recordSubtype=subtype),endpoint("in","transfer_in","100"),"outgoing" to "incoming","ordinary_transfer")
    }
    @Test fun currencyExchangeRetainsBothExistingShapes() {
        for(kind in listOf("fx","internal")) {
            val a=endpoint("out",if(kind=="fx") "fx_out" else "transfer_out");val b=endpoint("in",if(kind=="fx") "fx_in" else "transfer_in","10").copy(currency="USD")
            assertEquals("outgoing" to "incoming",roles("transfer_pair",a,b,"currency_exchange"));validate("transfer_pair",a,b,"outgoing" to "incoming","currency_exchange")
        }
    }
    @Test fun crossCurrencyRemittanceAllowsDifferentIncomingSubtype() {
        validate("transfer_pair",endpoint("out","transfer_out").copy(recordSubtype="cross_border_remittance"),endpoint("in","transfer_in","10").copy(currency="USD",accountId="other"),"outgoing" to "incoming","cross_currency_remittance")
    }
    @Test fun invalidRefundEndpointIsRejected() {
        val refund=endpoint("refund","refund","100")
        for(b in listOf(refund.copy(recordType="income"),refund.copy(recordSubtype="currency_exchange"),refund.copy(state="draft"),refund.copy(active=false),refund.copy(amount=null),
            refund.copy(amount=CashDecimalLiteral("0")),refund.copy(amount=CashDecimalLiteral("-1")),refund.copy(currency=null),refund.copy(accountId=null)))
            rejected {validate("refund_offset",endpoint("expense"),b,"expense" to "refund")}
    }
    @Test fun roleCheckDoesNotReplaceFinancialFinalGraphCheck() {
        val a=endpoint("expense");val b=endpoint("refund","refund","101")
        validate("refund_offset",a,b,"expense" to "refund");rejected("relation_endpoint_incompatible") {validate("refund_offset",a,b.copy(accountId="other"),"expense" to "refund")}
    }
    @Test fun mirrorRolesCannotMixRefundAndExpenseOrAcceptNewIncomeType() {
        val a=endpoint("expense");val b=endpoint("refund","refund","100");val other=b.copy(componentId="another",transactionId="another")
        rejected {validate("payment_mirror",a,b,"expense" to "refund")};validate("payment_mirror",b,other,"refund" to "refund")
        rejected {validate("payment_mirror",b.copy(recordType="income"),other,"refund" to "refund")}
    }
    @Test fun roleGateRejectsEveryOriginalNonexactFinancialInput() {
        for(amount in listOf(Double.NaN,0.1,CashDecimalLiteral("NaN"),CashDecimalLiteral("1e21")))
            rejected {validate("refund_offset",endpoint("expense"),endpoint("refund","refund","100").copy(amount=amount),"expense" to "refund")}
    }
    @Test fun closureIncludesDissolvedOldMembersAndNewMembersOnly() {
        val old=listOf(CashRelationLink("old","a","b",state="confirmed"),CashRelationLink("unrelated","x","y",state="confirmed"))
        val fresh=listOf(CashRelationLink("new","b","c",state="confirmed"))
        assertEquals(setOf("a","b","c"),affectedCashRelationClosure(setOf("a"),old,fresh));assertEquals(setOf("z"),affectedCashRelationClosure(setOf("z"),old,fresh))
    }
    @Test fun draftIgnoredAndInactiveEdgesDoNotMergeClosure() {
        val links=listOf(CashRelationLink("draft","a","b"),CashRelationLink("ignored","a","c",state="ignored"),CashRelationLink("inactive","a","d",state="confirmed",active=false))
        assertEquals(setOf("a"),affectedCashRelationClosure(setOf("a"),links,emptyList<Any>()))
    }
    @Test fun refundDiamondMirrorChangesShareOneAtomicGroup() {
        val old=listOf(CashRelationLink("refund-platform","pa","pb",state="confirmed"),CashRelationLink("refund-bank","ba","bb",state="confirmed"))
        val changes=listOf(CashRelationLink("expense-mirror","pa","ba"),CashRelationLink("refund-mirror","pb","bb"),CashRelationLink("independent","x","y"))
        assertEquals(listOf(listOf("expense-mirror","refund-mirror"),listOf("independent")),cashRelationCommitGroups(old,changes))
    }
    @Test fun sharedEndpointsOldGroupsAndExplicitDraftDependenciesAreAtomic() {
        val old=listOf(CashRelationLink("old","a","b",state="confirmed"))
        val changes=listOf(CashRelationLink("delete","a",null),CashRelationLink("new","b","c"),CashRelationLink("other","c","d"),CashRelationLink("draft-1","x",null,decisionGroupId="together"),CashRelationLink("draft-2","y",null,decisionGroupId="together"))
        assertEquals(listOf(listOf("delete","new","other"),listOf("draft-1","draft-2")),cashRelationCommitGroups(old,changes))
    }
    @Test fun commitGroupingIsStableWithoutUnchosenDraftEdges() {
        val old=listOf(CashRelationLink("not-confirmed","a","b"));val changes=listOf(CashRelationLink("z","a","c"),CashRelationLink("a","b","d"))
        val expected=listOf(listOf("a"),listOf("z"));assertEquals(expected,cashRelationCommitGroups(old,changes));assertEquals(expected,cashRelationCommitGroups(old.reversed(),changes.reversed()))
    }
    @Test fun duplicateOperationsAreNotSilentlyLost() {
        rejected("relation_change_duplicate") {cashRelationCommitGroups(emptyList<Any>(),listOf(CashRelationLink("same","a","b"),CashRelationLink("same","c","d")))}
    }
    @Test fun candidateTypePrefilterContainsEveryValidRolePair() {
        data class Case(val kind:String,val subtype:String,val roles:Pair<String,String>,val a:String,val b:String,val currency:String="CNY",val account:String="1",val cross:Boolean=false)
        val cases=listOf(Case("payment_mirror","","expense" to "expense","-1","-1"),Case("payment_mirror","","refund" to "refund","1","1"),
            Case("refund_offset","","expense" to "refund","-1","1"),Case("refund_offset","","expense" to "refund","0","0"),Case("refund_offset","p2p_return","p2p_out" to "p2p_in","-1","1"),
            Case("transfer_pair","ordinary_transfer","outgoing" to "incoming","-1","1",account="2"),Case("transfer_pair","credit_repayment","outgoing" to "incoming","-1","1",account="2"),
            Case("transfer_pair","currency_exchange","outgoing" to "incoming","-1","1","USD"),Case("transfer_pair","cross_currency_remittance","outgoing" to "incoming","-1","1","USD","2",true))
        var accepted=0
        for(case in cases) for(first in cashRecordTypes) for(second in cashRecordTypes) {
            val a=CashRelationEndpoint("1","1",CashDecimalLiteral(case.a),"CNY","1",first,if(case.cross && first=="transfer_out") "cross_border_remittance" else defaultCashRecordSubtype(first))
            val b=CashRelationEndpoint("2","2",CashDecimalLiteral(case.b),case.currency,case.account,second,defaultCashRecordSubtype(second))
            try {validate(case.kind,a,b,case.roles,case.subtype)} catch (_: CashRelationFailure) {continue}
            accepted++;assertTrue(second in cashRelationCandidateTypes(setOf(first)));assertTrue(first in cashRelationCandidateTypes(setOf(second)))
        }
        assertTrue(accepted>0)
    }
    @Test fun fundingRoleKeepsFiniteFloatAcceptanceWithoutRelaxingEndpointDecimalType() {
        for(amount in listOf<Any>(-0.1,-0.1f)) assertTrue(cashFundingRoleCompatible("cash_to_investment",amount,"investment_out"))
        for(amount in listOf<Any>(0.1,0.1f)) assertTrue(cashFundingRoleCompatible("investment_to_cash",amount,"investment_in"))
        for(amount in listOf(Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,Float.NaN,Float.POSITIVE_INFINITY,true,false))
            assertFalse(cashFundingRoleCompatible("investment_to_cash",amount,"investment_in"))
        for(amount in listOf<Any>(0.1,0.1f)) rejected("relation_endpoint_incomplete") {
            validateCashRelationEndpoint(endpoint("refund","refund","100").copy(amount=amount))
        }
    }
}
