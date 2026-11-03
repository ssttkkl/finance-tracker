package com.finance.tracker

import com.finance.tracker.domain.*
import com.finance.tracker.presentation.cashDetailRelationKinds
import com.finance.tracker.presentation.cashAssociationParts
import com.finance.tracker.presentation.cashDetailAlternativeCount
import com.finance.tracker.presentation.cashRelationInitialCandidate
import com.finance.tracker.presentation.cashRelationIsJoint
import com.finance.tracker.presentation.cashRelationConflictGroups
import com.finance.tracker.presentation.cashAssociationChoice
import com.finance.tracker.presentation.cashAssociationDisplayParts
import com.finance.tracker.presentation.cashRelationConstraintError
import kotlin.test.*

class CashDetailRelationsTest {
    @Test fun suggestedOptionsDistinguishMissingDirectAndMultipleCompleteSolutions() {
        val empty = ImportPairOption("suggestion", "refund_offset", "defer", null, emptyList(), emptyList(), listOf("ignore"), null)
        assertEquals(0, cashDetailAlternativeCount(empty))
        assertEquals(1, cashDetailAlternativeCount(empty.copy(allowedActions = listOf("accept", "ignore"))))
        val candidates = listOf("one", "two").map { ImportPairCandidate(it, null, listOf(ImportMemberRef(null, it)), null) }
        assertEquals(2, cashDetailAlternativeCount(empty.copy(candidates = candidates, allowedActions = listOf("accept", "ignore"))))
    }
    @Test fun singlePlanPreselectsOnlyCandidateAndJointUsesAllRelations() {
        val refs = listOf(ImportMemberRef(null, "11"), ImportMemberRef(null, "12"))
        val edge = ImportPairRelation("refund_offset", refs)
        val one = ImportPairCandidate("one", null, refs, null, listOf(edge))
        val group = ImportPairOption("s", "refund_offset", "defer", null, listOf(one), refs, listOf("accept", "ignore"), null)
        assertEquals("one", cashRelationInitialCandidate(group)); assertFalse(cashRelationIsJoint(group))
        assertNull(cashRelationInitialCandidate(group.copy(candidates = listOf(one, one.copy(candidateId = "two")))))
        assertTrue(cashRelationIsJoint(group.copy(candidates = listOf(one.copy(relations = listOf(edge, edge.copy(kind = "payment_mirror")))))))
    }
    @Test fun conflictsBelongToAcceptedSuggestionOrExactManualEndpointsAndKind() {
        fun group(id: String, other: String, kind: String = "refund_offset") = ImportPairOption(id, kind, "defer", null,
            emptyList(), listOf(ImportMemberRef(null, "11"), ImportMemberRef(null, other)), listOf("accept"), null,
            commonFieldConflicts = mapOf("note" to listOf(StructuredText("a"), StructuredText("b"))))
        val groups = listOf(group("chosen", "21"), group("unrelated", "22"), group("wrong-kind", "21", "payment_mirror"))
        val accepted = CashMaintenanceRequest("maintain_relations", "11", 1, relationChoices = listOf(CashRelationDecision("chosen", "accept")))
        assertEquals(listOf("chosen"), cashRelationConflictGroups(accepted, groups).map { it.suggestionId })
        val manual = accepted.copy(relationChoices = listOf(CashRelationDecision("unrelated", "ignore")),
            relationAdditions = listOf(CashRelationAddition("21", 1, "1", "2", "refund_offset")))
        assertEquals(listOf("chosen"), cashRelationConflictGroups(manual, groups).map { it.suggestionId })
        assertTrue(cashRelationConflictGroups(accepted.copy(relationChoices = listOf(CashRelationDecision("chosen", "ignore"))), groups).isEmpty())
    }
    @Test fun sourceLabelsOnlyIncludeActualConfirmedRelationsAndKeepMultipleKinds() {
        val member = CashSourceMember("11", null, "-10", "CNY", "shop", "", null, "account", emptyList(), relations = listOf(
            CashSavedRelation("1", "payment_mirror", "confirmed", true, 1, null),
            CashSavedRelation("2", "refund_offset", "confirmed", true, 1, null),
            CashSavedRelation("3", "transfer_pair", "draft", true, 1, null),
            CashSavedRelation("4", "transfer_pair", "confirmed", false, 1, null),
            CashSavedRelation("5", "payment_mirror", "confirmed", true, 1, null)))
        assertEquals(listOf("payment_mirror", "refund_offset"), cashDetailRelationKinds(member))
    }
    @Test fun manualSelectionUsesCurrentServerPairsAndRejectsMissingOrInvalidReferences() {
        fun record(id: String, parts: List<String>) = CashCurrentRecord("import", null, id, "posted", 1, "confirmed",
            StructuredObject(mapOf("components" to StructuredArray(parts.map { StructuredObject(mapOf("component_id" to StructuredText(it))) }))), emptyList(), emptyList(), emptyList(), null)
        val anchor = record("11", listOf("a1", "a2"))
        val target = record("12", listOf("b1", "b2"))
        assertNull(cashAssociationChoice(anchor, target, "a1", "b1"))
        val eligible = target.copy(componentChoices = listOf(CashAssociationComponentChoice("a1", "b2"), CashAssociationComponentChoice("a2", "b1")))
        assertNull(cashAssociationChoice(anchor, eligible, null, null))
        assertEquals(CashAssociationComponentChoice("a1", "b2"), cashAssociationChoice(anchor, eligible, "a1", null))
        assertNull(cashAssociationChoice(anchor, eligible, "a1", "b1"))
        assertNull(cashAssociationChoice(anchor, eligible.copy(unavailableReason = "relation_endpoint_occupied"), "a1", "b2"))
        assertNull(cashAssociationChoice(anchor, eligible.copy(state = "draft"), "a1", "b2"))
        assertNull(cashAssociationChoice(anchor, target.copy(componentChoices = listOf(CashAssociationComponentChoice("missing", "b2"))), null, null))
    }

    @Test fun singleLegalCombinationIsVisibleAsSelectedAndUnavailableRowsExplainWhy() {
        fun record(id: String, parts: List<String>, choices: List<CashAssociationComponentChoice> = emptyList(), unavailable: String? = null) =
            CashCurrentRecord("import", null, id, "posted", 1, "confirmed",
                StructuredObject(mapOf("components" to StructuredArray(parts.map { StructuredObject(mapOf("component_id" to StructuredText(it))) }))),
                emptyList(), emptyList(), emptyList(), null, choices, unavailable)
        val anchor = record("11", listOf("a1", "a2"))
        val target = record("12", listOf("b1", "b2"), listOf(CashAssociationComponentChoice("a1", "b2"), CashAssociationComponentChoice("a2", "b1")))
        assertEquals(null to null, cashAssociationDisplayParts(anchor, target, null, null))
        assertEquals("a1" to "b2", cashAssociationDisplayParts(anchor, target, "a1", null))
        assertEquals("a1" to "b2", cashAssociationDisplayParts(anchor, record("12", listOf("b1", "b2"), listOf(CashAssociationComponentChoice("a1", "b2"))), null, null))
    }

    @Test fun structuralRelationFailuresUseNeutralRefreshMessage() {
        for (code in listOf("relation_endpoint_incomplete", "relation_group_incomplete", "relation_group_changed",
            "relation_data_invalid", "relation_mirror_component_duplicate", "relation_mirror_incomplete", "relation_graph_invalid")) {
            assertEquals("cash_relation_state_invalid",
                com.finance.tracker.presentation.userErrorResourceKey(DomainFailure(code, 422, FailureCategory.RECOVERABLE)))
        }
    }

    @Test fun permanentRelationConstraintErrorsDoNotOfferRetry() {
        for (code in listOf("relation_account_mismatch", "relation_currency_mismatch", "relation_amount_invalid", "relation_role_incompatible",
            "relation_refund_exceeds_remaining", "relation_refund_incomplete", "relation_endpoint_occupied",
            "relation_aggregate_unsupported", "relation_endpoint_incompatible", "relation_mirror_incomplete", "relation_graph_invalid",
            "relation_endpoint_incomplete", "relation_group_incomplete", "relation_group_changed", "relation_data_invalid",
            "relation_mirror_component_duplicate")) {
            assertTrue(cashRelationConstraintError(code), code)
        }
        assertFalse(cashRelationConstraintError("record_changed"))
        assertFalse(cashRelationConstraintError("network_unavailable"))
    }

    @Test fun hardConstraintErrorsHaveSpecificSafeMessages() {
        for (code in listOf("relation_account_mismatch", "relation_currency_mismatch", "relation_amount_invalid", "relation_role_incompatible",
            "relation_refund_exceeds_remaining", "relation_refund_incomplete", "relation_endpoint_occupied", "relation_aggregate_unsupported")) {
            assertEquals("cash_$code", com.finance.tracker.presentation.userErrorResourceKey(DomainFailure(code, 422, FailureCategory.RECOVERABLE)))
        }
    }

    @Test fun componentsKeepExplicitIdentityAndDoNotGuessMissingPartIds() {
        val parts = StructuredArray(listOf(
            StructuredObject(mapOf("component_id" to StructuredText("9007199254740993"), "amount" to StructuredText("-1.000000000000000001"))),
            StructuredObject(mapOf("amount" to StructuredText("-2")))))
        val record = CashCurrentRecord("import", null, "11", "posted", 1, "confirmed", StructuredObject(mapOf("components" to parts)), emptyList(), emptyList(), emptyList(), null)
        assertEquals(listOf("9007199254740993"), cashAssociationParts(record).map { it.first })
        assertEquals(StructuredText("-1.000000000000000001"), cashAssociationParts(record).single().second.fields["amount"])
    }
}
