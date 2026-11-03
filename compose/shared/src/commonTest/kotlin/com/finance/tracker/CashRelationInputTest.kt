package com.finance.tracker

import com.finance.tracker.domain.*
import com.finance.tracker.presentation.*
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
    @Test fun conflictingValuesAllowTypedExplicitClearsEvenWhenNoMemberIsEmpty() {
        val conflicts = mapOf("note" to listOf(StructuredText("甲"), StructuredText("乙")),
            "category_id" to listOf(StructuredText("food"), StructuredText("other")),
            "counterparty_account_attrs" to listOf(StructuredArray(listOf(StructuredText("full"))), StructuredArray(listOf(StructuredText("partial")))))
        val clear = StructuredObject(mapOf("note" to StructuredText(""), "category_id" to StructuredNull,
            "counterparty_account_attrs" to StructuredArray(emptyList())))
        val choice = CashRelationDecision("group", "accept", "candidate", clear)
        assertEquals(clear, cashRelationRequest(record(), listOf(choice), listOf(option().copy(commonFieldConflicts = conflicts))).relationChoices.single().commonFields)
    }
    @Test fun manualEntriesCannotOpenExternalRelationMaintenance() {
        assertFailsWith<DomainFailure> { cashRelationRequest(record().copy(recordOrigin="manual"), listOf(CashRelationDecision("group", "defer")), listOf(option())) }
    }
    @Test fun acceptingRequiresEveryActualCandidateMemberToBeLoaded() {
        val options = listOf(option().copy(commonFieldConflicts = emptyMap()))
        val choice = listOf(CashRelationDecision("group", "accept", "candidate"))
        val members = mapOf("11" to record(), "12" to record().copy(transactionId = "12"))
        assertTrue(cashRelationChoicesLoaded(choice, options, members))
        assertFalse(cashRelationChoicesLoaded(choice, options, members - "12"))
        assertFalse(cashRelationChoicesLoaded(choice, options.map { it.copy(candidates = listOf(it.candidates.single().copy(members = emptyList()))) }, members))
        assertFalse(cashRelationChoicesLoaded(choice, options.map { it.copy(candidates = listOf(it.candidates.single().copy(members = listOf(ImportMemberRef("row-only", null))))) }, members))
    }
    @Test fun identicalGroupRepeatedAcrossPlanItemsIsOneValidDecision() {
        val group = option().copy(commonFieldConflicts = emptyMap())
        assertTrue(cashRelationChoicesLoaded(listOf(CashRelationDecision("group", "accept", "candidate")), listOf(group, group),
            mapOf("11" to record(), "12" to record().copy(transactionId = "12"))))
    }
    @Test fun acceptedPublicValueCanRemainWhileOnlyTheResidualConflictIsChosenAgain() {
        val current = option().copy(commonFields = StructuredObject(mapOf("category_id" to StructuredText("food"))))
        val choice = CashRelationDecision("group", "accept", "candidate", StructuredObject(mapOf("note" to StructuredText("已有备注"), "category_id" to StructuredText("food"))))
        assertEquals(choice, cashRelationRequest(record(), listOf(choice), listOf(current)).relationChoices.single())
        assertFailsWith<DomainFailure> { cashRelationRequest(record(), listOf(choice.copy(commonFields = StructuredObject(choice.commonFields!!.fields + ("category_id" to StructuredText("invented"))))), listOf(current)) }
        assertFailsWith<DomainFailure> { cashRelationRequest(record(), listOf(choice.copy(commonFields = StructuredObject(choice.commonFields!!.fields + ("counterparty" to StructuredText("invented"))))), listOf(current)) }
    }
    private fun record() = CashCurrentRecord("import", null, "11", "posted", 3, "pending", StructuredObject(emptyMap()), emptyList(), emptyList(), emptyList(), null)
    private fun option() = ImportPairOption("group", "payment_mirror", "defer", null,
        listOf(ImportPairCandidate("candidate", null, listOf(ImportMemberRef(null, "11"), ImportMemberRef(null, "12")), null)),
        listOf(ImportMemberRef(null, "11"), ImportMemberRef(null, "12")), listOf("accept", "defer", "ignore", "remove"), null,
        commonFieldConflicts = mapOf("note" to listOf(StructuredText(""), StructuredText("已有备注"))))
}
