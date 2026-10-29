package com.finance.tracker.backend

import java.time.Instant
import kotlin.test.*

class CashRelationGraphMigrationTest {
    private fun fact(id: Long,amount: String,account: Long?=1,currency: String="CNY",aggregate: Boolean=false,funding: Long?=null)=CashProjectionFact.create(id,if(aggregate) null else account,
        Instant.parse("2026-01-${id.toString().padStart(2,'0')}T00:00:00Z"),amount,currency,"",null,"","fixture",id.toString(),fundingRelationId=funding,cashGranularity=if(aggregate) "aggregate" else "atomic")
    private fun component(id: Long,parent: Long,amount: String,type: String,account: Long=1,currency: String="CNY")=CashRelationEndpoint(id.toString(),parent.toString(),CashDecimalLiteral(amount),currency,account.toString(),type,defaultCashRecordSubtype(type))
    private fun edge(id: Long,kind: String,left: Long,right: Long,parents: Pair<Long,Long> = left to right,amount: String?=null,subtype: String="",roles: Pair<String,String>?=null)=CashRelationGraphEdge(id,kind,parents.first.toString(),parents.second.toString(),left.toString(),right.toString(),
        (roles ?: if(kind=="payment_mirror") "expense" to "expense" else if(kind=="transfer_pair") "outgoing" to "incoming" else "expense" to "refund").first,
        (roles ?: if(kind=="payment_mirror") "expense" to "expense" else if(kind=="transfer_pair") "outgoing" to "incoming" else "expense" to "refund").second,
        subtype,amount?.let(::CashDecimalLiteral))
    private fun rows(vararg amounts: String): Pair<List<CashProjectionFact>,List<CashRelationEndpoint>> = amounts.mapIndexed {i,a ->fact(i+1L,a)} to amounts.mapIndexed {i,a ->component(i+1L,i+1L,a,if(i==0) "consumption" else "refund")}
    private fun build(rows: Pair<List<CashProjectionFact>,List<CashRelationEndpoint>>,vararg edges: CashRelationGraphEdge)=buildCashRelationGraph(rows.first,rows.second,edges.toList())
    private fun rejected(code: String?=null,action:()->Unit) {val failure=assertFailsWith<CashRelationFailure>(block=action);if(code!=null) assertEquals(code,failure.code)}
    private fun diamond(): Triple<List<CashProjectionFact>,List<CashRelationEndpoint>,List<CashRelationGraphEdge>> {
        val amounts=listOf("-100","-100","100","100")
        return Triple(amounts.mapIndexed {i,a ->fact(i+1L,a)},amounts.mapIndexed {i,a ->component(i+1L,i+1L,a,if(i<2) "consumption" else "refund")},
            listOf(edge(1,"refund_offset",1,3,amount="100"),edge(2,"refund_offset",2,4,amount="100"),edge(3,"payment_mirror",1,2,amount="100"),edge(4,"payment_mirror",3,4,amount="100",roles="refund" to "refund")))
    }
    @Test fun refundGraphSumsIndependentRefundsExactly() {
        val result=build(rows("-100","40","60"),edge(1,"refund_offset",1,2),edge(2,"refund_offset",1,3))
        assertEquals(1,result.projections.size);val item=result.projections.single();assertEquals(CashAmount.parse("0"),item.netAmount);assertTrue(item.visible);assertNull(item.hiddenReason)
    }
    @Test fun equalRealComponentAllocationsAreAddedRatherThanDeduplicated() {
        val facts=listOf(fact(1,"-100",aggregate=true),fact(2,"40",aggregate=true))
        val parts=listOf(component(11,1,"-50","consumption"),component(12,1,"-50","consumption",2),component(21,2,"20","refund"),component(22,2,"20","refund",2))
        val result=buildCashRelationGraph(facts,parts,listOf(edge(1,"refund_offset",11,21,1L to 2L,"20"),edge(2,"refund_offset",12,22,1L to 2L,"20")))
        assertEquals(CashAmount.parse("-60"),result.projections.single().netAmount);assertEquals(2,result.projections.single().relations.size)
    }
    @Test fun completeRefundDiamondCountsOneLogicalRefund() {
        val (facts,parts,edges)=diamond();val result=buildCashRelationGraph(facts,parts,edges);val item=result.projections.single()
        assertEquals(1,result.projections.size);assertEquals(CashAmount.parse("0"),item.netAmount);assertEquals(4,item.relations.size)
        assertEquals(result,buildCashRelationGraph(facts,parts,edges.reversed()))
    }
    @Test fun partialDiamondKeepsAllocationWhenFirstEdgeIsNoncanonical() {
        val (facts,parts,edges)=diamond();val changed=listOf(edges[0].copy(id=2L,appliedAmount=CashDecimalLiteral("40")),edges[1].copy(id=1L,appliedAmount=CashDecimalLiteral("40")))+edges.drop(2)
        assertEquals(CashAmount.parse("-60"),buildCashRelationGraph(facts,parts,changed).projections.single().netAmount)
    }
    @Test fun realAllocationsAndMirroredEvidenceCoexist() {
        val facts=listOf(fact(1,"-100",aggregate=true),fact(2,"-30"),fact(3,"40",aggregate=true),fact(4,"10"))
        val parts=listOf(component(11,1,"-30","consumption"),component(12,1,"-70","consumption",2),component(2,2,"-30","consumption"),component(31,3,"10","refund"),component(32,3,"30","refund",2),component(4,4,"10","refund"))
        val edges=listOf(edge(1,"payment_mirror",11,2,1L to 2L,"30"),edge(2,"payment_mirror",31,4,3L to 4L,"10",roles="refund" to "refund"),
            edge(3,"refund_offset",11,31,1L to 3L,"10"),edge(4,"refund_offset",2,4,2L to 4L,"10"),edge(5,"refund_offset",12,32,1L to 3L,"30"))
        val result=buildCashRelationGraph(facts,parts,edges);assertEquals(1,result.projections.size);assertEquals(CashAmount.parse("-60"),result.projections.single().netAmount)
    }
    @Test fun parallelParentMirrorsKeepDistinctComponentEvidence() {
        val facts=listOf(fact(1,"-100",aggregate=true),fact(2,"-100",aggregate=true))
        val parts=(1L..2L).flatMap {parent ->listOf(component(parent*10+1,parent,"-30","consumption",1),component(parent*10+2,parent,"-70","consumption",2))}
        val result=buildCashRelationGraph(facts,parts,listOf(edge(1,"payment_mirror",11,21,1L to 2L,"30"),edge(2,"payment_mirror",12,22,1L to 2L,"70")))
        assertEquals(1,result.projections.size);assertEquals(CashAmount.parse("-100"),result.projections.single().netAmount);assertEquals(2,result.projections.single().relations.size)
    }
    @Test fun incompleteDiamondIsNotAValidFinalGraph() {for(missing in listOf(2,3)) {val (facts,parts,edges)=diamond();rejected {buildCashRelationGraph(facts,parts,edges.filterIndexed {i,_ ->i!=missing})}}}
    @Test fun singleRefundCannotConsumeTwoIndependentExpenses() {
        val facts=listOf(fact(1,"-100"),fact(2,"-100"),fact(3,"40"));val parts=listOf(component(1,1,"-100","consumption"),component(2,2,"-100","consumption"),component(3,3,"40","refund"))
        rejected {buildCashRelationGraph(facts,parts,listOf(edge(1,"refund_offset",1,3,amount="20"),edge(2,"refund_offset",2,3,amount="20")))}
    }
    @Test fun finalGraphRejectsIncompatibleOrIncompleteComponents() {
        for(change in listOf("parent","amount","inactive","draft","currency","financial_total")) {
            val (facts,parts)=rows("-100","40");var changedParts=parts;var changedFacts=facts;var edge=edge(1,"refund_offset",1,2)
            when(change) {
                "parent" ->edge=edge.copy(primaryTransactionId="2")
                "amount" ->edge=edge.copy(appliedAmount=CashDecimalLiteral("40.000000000000000001"))
                "inactive" ->changedParts=listOf(parts[0].copy(active=false),parts[1])
                "draft" ->changedParts=listOf(parts[0].copy(state="draft"),parts[1])
                "currency" ->changedParts=listOf(parts[0].copy(currency="USD"),parts[1])
                else ->changedFacts=listOf(fact(1,"-90"),facts[1])
            }
            rejected {buildCashRelationGraph(changedFacts,changedParts,listOf(edge))}
        }
    }
    @Test fun refundExceedingExpenseByMinimumPrecisionIsRejected() {rejected {build(rows("-100","40","60.000000000000000001"),edge(1,"refund_offset",1,2),edge(2,"refund_offset",1,3))}}
    @Test fun refundCannotExceedComponentEvenWithParentCapacity() {
        rejected {buildCashRelationGraph(listOf(fact(1,"-100",aggregate=true),fact(2,"60")),listOf(component(11,1,"-50","consumption"),component(12,1,"-50","consumption",2),component(2,2,"60","refund")),listOf(edge(1,"refund_offset",11,2,1L to 2L)))}
    }
    @Test fun zeroRefundKeepsEvidenceWithoutOpeningZeroTransfers() {
        val (facts,parts)=rows("0","0");val item=buildCashRelationGraph(facts,parts,listOf(edge(1,"refund_offset",1,2))).projections.single()
        assertEquals(CashAmount.parse("0"),item.netAmount);assertTrue(item.visible);assertNull(item.hiddenReason)
        val transfers=parts.mapIndexed {i,part ->part.copy(recordType=if(i==0) "transfer_out" else "transfer_in",recordSubtype=defaultCashRecordSubtype("transfer_out"))}
        rejected {buildCashRelationGraph(facts,transfers,listOf(edge(1,"transfer_pair",1,2,subtype="ordinary_transfer")))}
    }
    @Test fun zeroPartsCannotCreateRefundForNonzeroParentTotals() {
        rejected {buildCashRelationGraph(listOf(fact(1,"-100",aggregate=true),fact(2,"40",aggregate=true)),
            listOf(component(11,1,"-100","consumption"),component(12,1,"0","consumption",2),component(21,2,"40","refund"),component(22,2,"0","refund",2)),listOf(edge(1,"refund_offset",12,22,1L to 2L)))}
    }
    @Test fun explicitZeroCannotExpandToNonzeroAllocation() {
        for(kind in listOf("refund_offset","payment_mirror")) {val rows=if(kind=="refund_offset") rows("-100","40") else listOf(fact(1,"-100"),fact(2,"-100")) to listOf(component(1,1,"-100","consumption"),component(2,2,"-100","consumption"))
            rejected("relation_amount_invalid") {build(rows,edge(1,kind,1,2,amount="0"))}}
    }
    @Test fun singleZeroEndpointIsNotARefund() {for(amounts in listOf(arrayOf("-10","0"),arrayOf("0","10"))) rejected {build(rows(*amounts),edge(1,"refund_offset",1,2))}}
    @Test fun transferEndpointIsExclusiveAndRemovedEdgeNoLongerOccupiesIt() {
        val facts=listOf(fact(1,"-10"),fact(2,"10"),fact(3,"10"));val parts=listOf(component(1,1,"-10","transfer_out"),component(2,2,"10","transfer_in"),component(3,3,"10","transfer_in"))
        val old=edge(1,"transfer_pair",1,2,subtype="ordinary_transfer");val fresh=edge(2,"transfer_pair",1,3,subtype="ordinary_transfer")
        rejected("relation_endpoint_occupied") {buildCashRelationGraph(facts,parts,listOf(old,fresh))}
        assertEquals(2,buildCashRelationGraph(facts,parts,listOf(old.copy(active=false),fresh)).projections.size)
    }
    @Test fun fundingOccupationIsCheckedOnNonrootMirrorMember() {
        val facts=listOf(fact(1,"-10"),fact(2,"-10"));val parts=listOf(component(1,1,"-10","consumption"),component(2,2,"-10","consumption"))
        rejected("relation_endpoint_occupied") {buildCashRelationGraph(facts,parts,listOf(edge(1,"payment_mirror",1,2)),fundingComponentIds=listOf("2"))}
        rejected("relation_endpoint_occupied") {buildCashRelationGraph(listOf(facts[0],fact(2,"-10",funding=9)),parts,listOf(edge(1,"payment_mirror",1,2)))}
    }
    @Test fun draftRelationsHaveNoFinancialEffect() {
        val result=build(rows("-100","40"),edge(1,"refund_offset",1,2).copy(state="draft"))
        assertEquals(2,result.projections.size);assertEquals(emptyList(),result.projections.first().relations)
    }
    @Test fun largeExactValuesAreNotRoundedByDefaultContext() {
        val amount="12345678901234567890.123456789012345678";val item=build(rows("-$amount",amount),edge(1,"refund_offset",1,2,amount=amount)).projections.single()
        assertEquals(CashAmount.parse("0"),item.netAmount);assertTrue(item.visible);assertNull(item.hiddenReason)
    }
    @Test fun malformedGraphEdgesReturnStableDomainError() {
        val edge=edge(1,"refund_offset",1,2)
        for(changed in listOf(edge.copy(primaryComponentId=emptyList<Any>()),edge.copy(kind=emptyList<Any>()),edge.copy(state=emptyList<Any>()),edge.copy(secondaryTransactionId=emptyMap<String,Any>()),edge.copy(subtype=emptyList<Any>()),edge.copy(appliedAmount=0.1),edge.copy(id=true)))
            rejected {build(rows("-100","40"),changed)}
    }
    @Test fun malformedCollectionsAndComponentTypesReturnDomainErrors() {
        val (facts,parts)=rows("-100","40")
        for(field in listOf("components","relations","funding","component_type")) rejected {buildCashRelationGraph(facts,
            if(field=="components") null else if(field=="component_type") listOf(parts[0].copy(recordType=emptyList<Any>()),parts[1]) else parts,
            if(field=="relations") null else emptyList<Any>(),fundingComponentIds=if(field=="funding") listOf(listOf("1")) else emptyList<Any>())}
    }
    private fun member(id: Int,revision: Long=1)=CashJointRelationMember(id.toString(),"joint",revision)
    @Test fun jointContextRequiresAllCurrentMembersAndRevisions() {
        val expected=listOf(member(1),member(2));validateCashJointContext(expected,expected,setOf("1","2"))
        rejected("relation_group_incomplete") {validateCashJointContext(expected,expected,setOf("1"))}
    }
    @Test fun jointContextRefusesMissingChangedOrExtraMembers() {
        val expected=listOf(member(1),member(2))
        for(current in listOf(listOf(member(1)),listOf(member(1),member(2,2)),listOf(member(1),member(2).copy(active=false)),listOf(member(1),member(2).copy(state="ignored")),listOf(member(1),member(2),member(3))))
            rejected("relation_group_changed") {validateCashJointContext(expected,current,setOf("1","2"))}
    }
    @Test fun malformedJointDecisionsReturnStableDomainError() {
        for(decided in listOf(listOf(listOf("1")),null,"1",listOf(true))) rejected {validateCashJointContext(listOf(member(1)),listOf(member(1)),decided)}
    }
    @Test fun positiveRelationIdsAreNotNarrowedBeforeTheStorageBoundary() {
        val huge=java.math.BigInteger.ONE.shiftLeft(63)
        val input=edge(1,"refund_offset",1,2).copy(id=huge)
        validateCashRelationGraphEdgeInput(input)
        val result=build(rows("-100","40"),input)
        assertEquals(huge.toString(),result.projections.single().relations.single().id.toString())
    }
    @Test fun jointMembersAcceptIndependentSequencesAndIterators() {
        val expected=listOf(member(1),member(2))
        validateCashJointContext(expected.asSequence(),expected.iterator(),setOf("1","2"))
        validateCashJointContext(expected.iterator(),expected.asSequence(),setOf("1","2"))
    }
}
