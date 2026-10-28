package com.finance.tracker

import com.finance.tracker.domain.*
import com.finance.tracker.presentation.cashDetailRelationKinds
import com.finance.tracker.presentation.cashAssociationParts
import com.finance.tracker.presentation.cashDetailAlternativeCount
import com.finance.tracker.presentation.cashRelationInitialCandidate
import com.finance.tracker.presentation.cashRelationIsJoint
import com.finance.tracker.presentation.cashRelationConflictGroups
import kotlin.test.*

class CashDetailRelationsTest {
    @Test fun suggestedOptionsDistinguishMissingDirectAndMultipleCompleteSolutions() {
        val empty = ImportPairOption("suggestion", "refund_offset", "", "defer", null, emptyList(), emptyList(), listOf("ignore"), null)
        assertEquals(0, cashDetailAlternativeCount(empty))
        assertEquals(1, cashDetailAlternativeCount(empty.copy(allowedActions = listOf("accept", "ignore"))))
        val candidates = listOf("one", "two").map { ImportPairCandidate(it, null, listOf(ImportMemberRef(null, it)), null) }
        assertEquals(2, cashDetailAlternativeCount(empty.copy(candidates = candidates, allowedActions = listOf("accept", "ignore"))))
    }
    @Test fun singlePlanPreselectsOnlyCandidateAndJointUsesAllRelations() {
        val refs = listOf(ImportMemberRef(null, "11"), ImportMemberRef(null, "12"))
        val edge = ImportPairRelation("refund_offset", "p2p_return", refs)
        val one = ImportPairCandidate("one", null, refs, null, listOf(edge))
        val group = ImportPairOption("s", "refund_offset", "p2p_return", "defer", null, listOf(one), refs, listOf("accept", "ignore"), null)
        assertEquals("one", cashRelationInitialCandidate(group)); assertFalse(cashRelationIsJoint(group))
        assertNull(cashRelationInitialCandidate(group.copy(candidates = listOf(one, one.copy(candidateId = "two")))))
        assertTrue(cashRelationIsJoint(group.copy(candidates = listOf(one.copy(relations = listOf(edge, edge.copy(kind = "payment_mirror")))))))
    }
    @Test fun conflictsBelongToAcceptedSuggestionOrExactManualEndpointsAndSubtype() {
        fun group(id: String, other: String, subtype: String = "p2p_return") = ImportPairOption(id, "refund_offset", subtype, "defer", null,
            emptyList(), listOf(ImportMemberRef(null, "11"), ImportMemberRef(null, other)), listOf("accept"), null,
            commonFieldConflicts = mapOf("note" to listOf(StructuredText("a"), StructuredText("b"))))
        val groups = listOf(group("chosen", "21"), group("unrelated", "22"), group("wrong-subtype", "21", ""))
        val accepted = CashMaintenanceRequest("maintain_relations", "11", 1, relationChoices = listOf(CashRelationDecision("chosen", "accept")))
        assertEquals(listOf("chosen"), cashRelationConflictGroups(accepted, groups).map { it.suggestionId })
        val manual = accepted.copy(relationChoices = listOf(CashRelationDecision("unrelated", "ignore")),
            relationAdditions = listOf(CashRelationAddition("21", 1, "1", "2", "refund_offset", "p2p_return")))
        assertEquals(listOf("chosen"), cashRelationConflictGroups(manual, groups).map { it.suggestionId })
        assertTrue(cashRelationConflictGroups(accepted.copy(relationChoices = listOf(CashRelationDecision("chosen", "ignore"))), groups).isEmpty())
    }
    @Test fun sourceLabelsOnlyIncludeActualConfirmedRelationsAndKeepMultipleKinds() {
        val member = CashSourceMember("11", null, "-10", "CNY", "shop", "", null, "account", emptyList(), relations = listOf(
            CashSavedRelation("1", "payment_mirror", "", "confirmed", true, 1, null),
            CashSavedRelation("2", "refund_offset", "", "confirmed", true, 1, null),
            CashSavedRelation("3", "transfer_pair", "credit_repayment", "draft", true, 1, null),
            CashSavedRelation("4", "transfer_pair", "ordinary_transfer", "confirmed", false, 1, null),
            CashSavedRelation("5", "payment_mirror", "", "confirmed", true, 1, null)))
        assertEquals(listOf("payment_mirror" to "", "refund_offset" to ""), cashDetailRelationKinds(member))
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
