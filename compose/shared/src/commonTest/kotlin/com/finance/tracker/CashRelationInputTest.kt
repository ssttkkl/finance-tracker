package com.finance.tracker

import com.finance.tracker.domain.*
import com.finance.tracker.presentation.cashRelationRequest
import kotlin.test.*

class CashRelationInputTest {
    @Test fun wholeGroupChoiceAndExplicitFalseArePreserved() {
        val choice = CashRelationDecision("group", "remove", inheritCommonFields = false)
        assertEquals(listOf(choice), cashRelationRequest(record(), listOf(choice), listOf(option())).relationChoices)
    }
    @Test fun cannotInventGroupCandidateActionOrConflictValue() {
        for (choice in listOf(CashRelationDecision("invented", "defer"), CashRelationDecision("group", "fake"),
            CashRelationDecision("group", "accept", "invented"), CashRelationDecision("group", "accept", "candidate", StructuredObject(mapOf("note" to StructuredText("invented")))))) {
            assertFailsWith<DomainFailure> { cashRelationRequest(record(), listOf(choice), listOf(option())) }
        }
    }
    @Test fun conflictRequiresExplicitSelectionIncludingEmptyValue() {
        assertFailsWith<DomainFailure> { cashRelationRequest(record(), listOf(CashRelationDecision("group", "accept", "candidate")), listOf(option())) }
        val choice = CashRelationDecision("group", "accept", "candidate", StructuredObject(mapOf("note" to StructuredText(""))))
        assertEquals(choice, cashRelationRequest(record(), listOf(choice), listOf(option())).relationChoices.single())
    }
    @Test fun manualEntriesCannotOpenExternalRelationMaintenance() {
        assertFailsWith<DomainFailure> { cashRelationRequest(record().copy(recordOrigin="manual"), listOf(CashRelationDecision("group", "defer")), listOf(option())) }
    }
    private fun record() = CashCurrentRecord("import", null, "11", "posted", 3, "pending", StructuredObject(emptyMap()), emptyList(), emptyList(), emptyList(), null)
    private fun option() = ImportPairOption("group", "payment_mirror", null, "defer", null,
        listOf(ImportPairCandidate("candidate", null, listOf(ImportMemberRef(null, "11"), ImportMemberRef(null, "12")), null)),
        listOf(ImportMemberRef(null, "11"), ImportMemberRef(null, "12")), listOf("accept", "defer", "ignore", "remove"), null,
        commonFieldConflicts = mapOf("note" to listOf(StructuredText(""), StructuredText("已有备注"))))
}
