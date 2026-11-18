package com.finance.tracker.business.domain

import kotlin.test.*
import kotlinx.serialization.json.*

class CashRelationReferencesTest {
    private val expected=Json.parseToJsonElement("""{"human":[true,false,true],"refs":["alipay:ALI-1","9","missing"],"keys":["proposal:6761669421011d17cb60a26448a73f7c","proposal:d2a4fc3ce36f8f09b4a454c889a9fece"]}""").jsonObject
    private fun fact(id:String,source:String="",record:String="",parent:String?=null,ordinal:Int?=null)=RelationFact(
        id,CashAmount.parse("-1"),"CNY","a",billSource=source,recordId=record,parentId=parent,componentOrdinal=ordinal)
    @Test fun stableReferencesAndProposalKeysMatchExistingGolden() {
        val refs=cashRelationReferences
        assertEquals(expected.getValue("human"),JsonArray(listOf(
            refs.isHumanDecision(mapOf("created_by" to "system","decided_by" to "user")),
            refs.isHumanDecision(mapOf("created_by" to "system","decided_by" to "system")),
            refs.isHumanDecision(mapOf("created_by" to "admin"))).map(::JsonPrimitive)))
        assertEquals(expected.getValue("refs"),JsonArray(listOf(refs.stableFactRef("1",mapOf("1" to fact("1","alipay","ALI-1"))),
            refs.stableFactRef("preview:9"),refs.stableFactRef("missing")).map(::JsonPrimitive)))
        val facts=listOf(fact("1","alipay","ALI-1"),fact("2","wechat"),fact("3"),fact("7","alipay"))
        val proposal=RelationProposal("refund_offset","1","2",ruleId="scan.rule",anchorFactId="1",
            evidence=RelationEvidence(candidateFactIds=listOf("2","preview:9")))
        val openLeg=RelationProposal("transfer_pair","preview:7",null,ruleId="scan.rule2",anchorFactId="preview:7",
            evidence=RelationEvidence(openLeg=true,anchorRole="out",candidateFactIds=listOf("1")))
        assertEquals(expected.getValue("keys"),JsonArray(listOf(refs.proposalKey(proposal,facts),refs.proposalKey(openLeg,facts)).map(::JsonPrimitive)))
        assertEquals("alipay:ALI-1#component:3",refs.stableFactRef("x",mapOf("x" to fact("x","alipay","ALI-1","p",3))))
    }
    @Test fun stableInputsIgnorePreviewIdsOrderAndMutableConclusionFields() {
        val refs=cashRelationReferences
        val facts=listOf(fact("1","alipay","A"),fact("2","bank","B"))
        val p=RelationProposal("payment_mirror","1","2",ruleId="rule",evidence=RelationEvidence(candidateFactIds=listOf("2","1")))
        val renamed=listOf(facts[0].copy(id="new-a"),facts[1].copy(id="new-b"))
        assertEquals(refs.proposalKey(p,facts),refs.proposalKey(p.copy(primaryFactId="new-a",secondaryFactId="new-b",
            status="accepted",evidence=p.evidence.copy(candidateFactIds=listOf("new-a","new-b"))),renamed))
        assertNotEquals(refs.proposalKey(p,facts),refs.proposalKey(p.copy(evidence=p.evidence.copy(candidateFactIds=listOf("1","2","2"))),facts))
    }

    @Test fun unicodeOrderingAndInvalidUtf16KeepTheLegacyDigest() {
        val broken=0xd800.toChar().toString()
        val p=RelationProposal("payment_mirror",broken,null,ruleId="rule",
            evidence=RelationEvidence(candidateFactIds=listOf("\uE000","😀",broken)))
        assertEquals("proposal:1b656f1f1204a8cc7a715a99e76db16d",cashRelationReferences.proposalKey(p))
    }
}
