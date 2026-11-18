package com.finance.tracker.business.domain

import java.security.MessageDigest
import java.util.Random
import kotlin.test.*

class CashRelationReferencesParityTest {
    @Test fun proposalKeysRetainLegacyJavaUtf8AndCompletePayload() {
        val random=Random(123749)
        val symbols=listOf("a","中文",0xd800.toChar().toString(),0xdc00.toChar().toString(),"😀","\uE000","preview:","\u0000","\"","\\")
        fun text()=buildString {repeat(6) {append(symbols[random.nextInt(symbols.size)])}}
        repeat(1000) {
            val proposal=RelationProposal(text(),text(),if(random.nextBoolean()) text() else null,
                ruleId=text(),anchorFactId=text(),openLeg=random.nextBoolean(),evidence=RelationEvidence(candidateFactIds=List(4) {text()}))
            fun ref(id:String?):String=id.orEmpty().removePrefix("preview:")
            val payload=linkedMapOf<String,Any?>("kind" to proposal.kind,"primary" to ref(proposal.primaryFactId),
                "secondary" to ref(proposal.secondaryFactId),"anchor" to ref(proposal.effectiveAnchorFactId),"rule_id" to proposal.ruleId,
                "candidates" to proposal.evidence.candidateFactIds.map(::ref).sortedWith(Comparator(::pythonStringOrder)))
            val encoded=sourceJson(payload,{null},::pythonFloatText).toString()
            val expected="proposal:"+MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8))
                .joinToString("") {"%02x".format(it)}.take(32)
            assertEquals(expected,cashRelationReferences.proposalKey(proposal))
        }
    }
}
