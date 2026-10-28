package com.finance.tracker

import com.finance.tracker.domain.*
import com.finance.tracker.presentation.cashDetailRelationKinds
import com.finance.tracker.presentation.cashAssociationParts
import com.finance.tracker.presentation.cashDetailAlternativeCount
import kotlin.test.*

class CashDetailRelationsTest {
    @Test fun suggestedOptionsDistinguishMissingDirectAndMultipleCompleteSolutions() {
        val empty = ImportPairOption("suggestion", "refund_offset", "", "defer", null, emptyList(), emptyList(), listOf("ignore"), null)
        assertEquals(0, cashDetailAlternativeCount(empty))
        assertEquals(1, cashDetailAlternativeCount(empty.copy(allowedActions = listOf("accept", "ignore"))))
        val candidates = listOf("one", "two").map { ImportPairCandidate(it, null, listOf(ImportMemberRef(null, it)), null) }
        assertEquals(2, cashDetailAlternativeCount(empty.copy(candidates = candidates, allowedActions = listOf("accept", "ignore"))))
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
