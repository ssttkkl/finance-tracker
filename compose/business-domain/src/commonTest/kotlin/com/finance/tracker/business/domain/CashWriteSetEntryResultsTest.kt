package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*
import kotlin.test.*

class CashWriteSetEntryResultsTest {
    private val array = JsonArray(emptyList())
    private val attrs = JsonArray(listOf(JsonPrimitive("bank"), JsonPrimitive("person")))
    private fun ref(id: String) = ExistingCashWriteReferenceDto(id)
    private fun target(id: String) = CashWriteVersionedReferenceDto(ref(id), "1")
    private fun fresh(id: String) = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto(id), null)
    private fun standalone(note: String) = buildJsonObject {
        put("category_id", "removed-category"); put("note", note); put("counterparty", "source-party")
        put("counterparty_account", "source-account"); put("counterparty_account_attrs", attrs)
    }
    private fun actual() = LocalCashRelationContextTest().snapshot().facts.copy(
        cashEntries = listOf(LedgerCashEntryFactDto("actual-uuid", "2", "1", "9007199254740993", null,
            "D", "B-party", "B-account", attrs, JsonArray(listOf(JsonPrimitive("note"), JsonPrimitive("category_id"))), null)),
        cashEntryMembers = listOf(LedgerCashEntryMemberFactDto("1", "actual-uuid", standalone("C"), JsonArray(listOf(JsonPrimitive("note")))),
            LedgerCashEntryMemberFactDto("2", "actual-uuid", standalone("source-note"), array)),
    )
    private fun result() = CashEntryWriteDto(fresh("root"), null, "D", "B-party", "B-account", attrs,
        listOf("category_id", "note"), listOf(CashEntryMemberWriteDto(target("1"), standalone("C"), listOf("note")),
            CashEntryMemberWriteDto(fresh("root"), standalone("source-note"), emptyList())), listOf("note"))
    private fun resolve(reference: CashWriteReferenceDto): String = when (reference) {
        is ExistingCashWriteReferenceDto -> reference.id
        is NewCashWriteReferenceDto -> mapOf("root" to "2", "alias" to "1").getValue(reference.ref)
    }
    private fun check(rows: List<CashEntryWriteDto> = listOf(result()), source: LedgerFactsDto = actual()) =
        validateCashWriteSetEntryResults(CashWriteSetDto(entries = rows), source, ::resolve)
    private fun rejected(rows: List<CashEntryWriteDto>, path: String, source: LedgerFactsDto = actual()) {
        val failure = assertFailsWith<CashPolicyError> { check(rows, source) }
        assertEquals("entry_result_invalid", failure.code)
        assertEquals(listOf(path), failure.fields)
    }

    @Test fun exactBusinessResultsUseServerIdentitiesAndPreserveAllInputs() {
        val source = actual()
        val expected = result()
        val before = source.copy()
        check(listOf(expected), source)
        assertEquals(before, source)
        assertEquals(result(), expected)
        val entry = source.cashEntries.single().copy(id = "different-uuid", identityTransactionId = "2", revision = "9223372036854775807")
        check(source = source.copy(cashEntries = listOf(entry), cashEntryMembers = source.cashEntryMembers.map { it.copy(entryId = entry.id) }))
    }

    @Test fun eachPublicFieldIsComparedWithTheActualPersistedValue() {
        val row = result()
        for ((changed, field) in listOf(row.copy(categoryId = "forged") to "category_id", row.copy(note = "forged") to "note",
            row.copy(counterparty = "forged") to "counterparty", row.copy(counterpartyAccount = "forged") to "counterparty_account",
            row.copy(counterpartyAccountAttrs = JsonArray(attrs.reversed())) to "counterparty_account_attrs")) {
            rejected(listOf(changed), "entries[0].$field")
        }
    }

    @Test fun aDifferentRootCannotBeHiddenByTheSameOwnerAndPublicValues() {
        rejected(listOf(result().copy(rootTransaction = target("1"))), "entries[0].root_transaction")
    }

    @Test fun theCompleteActualOwnerMemberSetMustMatchIncludingUnrequestedMembers() {
        val row = result()
        rejected(listOf(row.copy(members = row.members.drop(1))), "entries[0].members")
        rejected(listOf(row.copy(members = row.members + row.members.first().copy(transaction = target("3")))), "entries[0].members")
        rejected(listOf(row), "entries[0].members", actual().copy(cashEntryMembers = actual().cashEntryMembers +
            LedgerCashEntryMemberFactDto("3", "actual-uuid", standalone("outside"), array)))
    }

    @Test fun publicAndStandaloneManualFlagsAreSetsWhilePublicArraysRemainOrdered() {
        val row = result()
        check(listOf(row.copy(manualFields = listOf("note", "category_id", "note"))))
        rejected(listOf(row.copy(manualFields = listOf("note"))), "entries[0].manual_fields")
        rejected(listOf(row.copy(members = row.members.map { it.copy(standaloneManualFields = emptyList()) })),
            "entries[0].members[0].standalone_manual_fields")
    }

    @Test fun sourceValuesAndFinalPublicValuesCannotStandInForStandaloneValues() {
        val row = result()
        for (note in listOf("D", "source-note")) {
            rejected(listOf(row.copy(members = listOf(row.members.first().copy(standaloneValues = standalone(note))) + row.members.drop(1))),
                "entries[0].members[0].standalone_values")
        }
        val extra = JsonObject(standalone("C") + ("unexpected" to JsonPrimitive("stored-extra")))
        rejected(listOf(row), "entries[0].members[0].standalone_values", actual().copy(cashEntryMembers =
            actual().cashEntryMembers.map { if (it.transactionId == "1") it.copy(standaloneValues = extra) else it }))
    }

    @Test fun twoLogicalReferencesCannotDeclareTheSamePhysicalMemberTwice() {
        val row = result()
        rejected(listOf(row.copy(members = row.members + row.members.first().copy(transaction = fresh("alias")))), "entries[0].members")
    }

    @Test fun theSameActualEntryCannotBeDeclaredTwice() {
        rejected(listOf(result(), result()), "entries[1]")
    }

    @Test fun missingActualRootMembersAreAnEffectMismatch() {
        rejected(listOf(result()), "entries[0].root_transaction", actual().copy(cashEntryMembers = actual().cashEntryMembers.take(1)))
    }

    @Test fun malformedUnrelatedEntriesDoNotExpandTheResultScope() {
        val source = actual()
        val unrelated = source.cashEntries.single().copy(id = "unrelated", rootTransactionId = "5", identityTransactionId = "5",
            manualFields = JsonPrimitive("not-an-array"))
        check(source = source.copy(cashEntries = source.cashEntries + unrelated,
            cashEntryMembers = source.cashEntryMembers + LedgerCashEntryMemberFactDto("5", "unrelated", JsonPrimitive("not-an-object"), array)))
        validateCashWriteSetEntryResults(CashWriteSetDto(), source) { error("empty results must not resolve references") }
    }

    @Test fun resolverProgramErrorsPropagateWithoutBecomingBusinessRejections() {
        val failure = IllegalStateException("actual allocation failure")
        assertSame(failure, assertFailsWith<IllegalStateException> {
            validateCashWriteSetEntryResults(CashWriteSetDto(entries = listOf(result())), actual()) { throw failure }
        })
    }

    @Test fun allocatedRootIdentifiersRemainExactBeyondTheJavaScriptSafeIntegerRange() {
        val source = actual()
        val identifier = "9007199254740993"
        val renamed = source.copy(
            cashTransactions = source.cashTransactions.map { if (it.id == "2") it.copy(id = identifier) else it },
            cashTransactionComponents = source.cashTransactionComponents.map {
                if (it.cashTransactionId == "2") it.copy(cashTransactionId = identifier) else it
            },
            transactionRelations = source.transactionRelations.map { it.copy(
                primaryTransactionId = if (it.primaryTransactionId == "2") identifier else it.primaryTransactionId,
                secondaryTransactionId = if (it.secondaryTransactionId == "2") identifier else it.secondaryTransactionId) },
            cashTransactionIssues = emptyList(),
            cashEntries = source.cashEntries.map { it.copy(rootTransactionId = identifier) },
            cashEntryMembers = source.cashEntryMembers.map { if (it.transactionId == "2") it.copy(transactionId = identifier) else it })
        validateCashWriteSetEntryResults(CashWriteSetDto(entries = listOf(result())), renamed) {
            if (it == NewCashWriteReferenceDto("root")) identifier else resolve(it)
        }
    }

    @Test fun malformedPersistedManualFlagsAreNotCoercedIntoRequestRejections() {
        val source = actual()
        val invalid = JsonArray(listOf(JsonPrimitive(123)))
        assertFailsWith<IllegalStateException> {
            check(source = source.copy(cashEntries = source.cashEntries.map { it.copy(manualFields = invalid) }))
        }
        assertFailsWith<IllegalStateException> {
            check(source = source.copy(cashEntryMembers = source.cashEntryMembers.map { it.copy(standaloneManualFields = invalid) }))
        }
    }
}
