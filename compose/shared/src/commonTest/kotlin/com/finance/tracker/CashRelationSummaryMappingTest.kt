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
                    {"kind":"payment_mirror","member_refs":[{"transaction_id":"11"},{"transaction_id":"12"}]},
                    {"kind":"refund_offset","member_refs":[{"transaction_id":"11"},{"transaction_id":"13"}]}],
                "candidates":[{"candidate_id":"alternative","member_refs":[],"relations":[
                    {"kind":"payment_mirror","member_refs":[{"transaction_id":"11"},{"transaction_id":"21"}]},
                    {"kind":"refund_offset","member_refs":[{"transaction_id":"11"},{"row_ref":"row-22"}]}]}]}]
        }""").toDomain()
        val group = detail.relationChoices.single()
        assertEquals(listOf("payment_mirror", "refund_offset"), group.relations.map { it.kind })
        assertEquals(listOf("11", "12"), group.relations.first().members.map { it.transactionId })
        val candidate = group.candidates.single()
        assertEquals(listOf("payment_mirror", "refund_offset"), candidate.relations.map { it.kind })
        assertEquals(listOf("11", "21"), candidate.relations.first().members.map { it.transactionId })
        assertEquals("row-22", candidate.relations.last().members.last().rowRef)
    }

    @Test fun businessLabelsKeepStableEntryIdentityAndExactAmounts() {
        val page = apiJson.decodeFromString<CashPageDto>("""{
            "projection_version":9,"items":[{"projection_id":"cash-entry:stable","entry_id":"stable",
                "transaction_id":"changed-representative","record_id":"source-reference","occurred_at":null,
                "amount":"-12345678901234567890.0001","currency":"CNY","economic_type":"internal_transfer",
                "business_label":"credit_repayment","accepted_relation_summary":[{"kind":"transfer_pair","count":1}]}],
            "filter_options":{"economic_types":[{"economic_type":"internal_transfer",
                "business_labels":["credit_repayment","currency_exchange","cross_currency_remittance"]}]}
        }""").toDomain()
        assertEquals("cash-entry:stable", page.items.single().projectionId)
        assertEquals("changed-representative", page.items.single().transactionId)
        assertEquals("-12345678901234567890.0001", page.items.single().amount)
        assertEquals("credit_repayment", page.items.single().businessLabel)
        assertEquals(listOf("credit_repayment", "currency_exchange", "cross_currency_remittance"), page.filterOptions.economicTypes.single().businessLabels)
        assertEquals("transfer_pair", page.items.single().acceptedRelationSummary.single().kind)
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
