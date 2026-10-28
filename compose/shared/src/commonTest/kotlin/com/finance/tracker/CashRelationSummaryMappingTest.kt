package com.finance.tracker

import com.finance.tracker.data.*
import kotlin.test.*

class CashRelationSummaryMappingTest {
    @Test fun mixedGroupAndEachAlternativeKeepTheirOwnKindsAndEndpoints() {
        val detail = apiJson.decodeFromString<CashPlanItemDetailDto>("""{
            "preview_item_id":"item","members":[],"field_diffs":[],"issues":[],"relation_effects":[],
            "relation_choices":[{"suggestion_id":"group","kind":"payment_mirror","action":"defer",
                "member_refs":[],"allowed_actions":["accept"],
                "relations":[
                    {"kind":"payment_mirror","subtype":"","member_refs":[{"transaction_id":"11"},{"transaction_id":"12"}]},
                    {"kind":"refund_offset","subtype":"p2p_return","member_refs":[{"transaction_id":"11"},{"transaction_id":"13"}]}],
                "candidates":[{"candidate_id":"alternative","member_refs":[],"relations":[
                    {"kind":"payment_mirror","subtype":"","member_refs":[{"transaction_id":"11"},{"transaction_id":"21"}]},
                    {"kind":"refund_offset","subtype":"p2p_return","member_refs":[{"transaction_id":"11"},{"row_ref":"row-22"}]}]}]}]
        }""").toDomain()
        val group = detail.relationChoices.single()
        assertEquals(listOf("payment_mirror", "refund_offset"), group.relations.map { it.kind })
        assertEquals("p2p_return", group.relations.last().subtype)
        assertEquals(listOf("11", "12"), group.relations.first().members.map { it.transactionId })
        val candidate = group.candidates.single()
        assertEquals(listOf("payment_mirror", "refund_offset"), candidate.relations.map { it.kind })
        assertEquals(listOf("11", "21"), candidate.relations.first().members.map { it.transactionId })
        assertEquals("row-22", candidate.relations.last().members.last().rowRef)
    }

    @Test fun omittedSummariesRemainCompatibleWithExistingDetails() {
        val detail = apiJson.decodeFromString<CashPlanItemDetailDto>("""{
            "preview_item_id":"item","members":[],"field_diffs":[],"issues":[],"relation_effects":[],
            "relation_choices":[{"suggestion_id":"group","action":"defer","member_refs":[],"allowed_actions":[],
                "candidates":[{"candidate_id":"alternative"}]}]
        }""").toDomain()
        assertTrue(detail.relationChoices.single().relations.isEmpty())
        assertTrue(detail.relationChoices.single().candidates.single().relations.isEmpty())
    }
}
