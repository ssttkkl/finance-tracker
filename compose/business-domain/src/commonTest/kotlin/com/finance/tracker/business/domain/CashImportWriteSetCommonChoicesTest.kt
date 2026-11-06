package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*
import kotlin.test.*

class CashImportWriteSetCommonChoicesTest {
    private val empty = JsonObject(emptyMap())
    private val array = JsonArray(emptyList())
    private val time = "2026-01-01T00:00:00Z"
    private fun ref(id: String) = ExistingCashWriteReferenceDto(id)
    private fun target(id: String) = CashWriteVersionedReferenceDto(ref(id), "1")
    private fun fresh(id: String) = NewCashWriteReferenceDto(id)
    private fun facts() = LedgerFactsDto(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "0").copy(
        accounts = listOf(LedgerAccountFactDto("10", "cash", "cash", true, JsonArray(listOf(JsonPrimitive("CNY"))), "1")),
        cashTransactions = (1..6).map { LedgerCashTransactionFactDto("$it", "10", "posted", "legacy", null, "1", "atomic", "bank",
            "record:$it", empty, empty, null, empty, time, "-5", "CNY", "", "", array, "", null, "consumption", "not_applicable", time, null, "", "") },
        cashTransactionComponents = (1..6).map { LedgerCashComponentFactDto("$it$it", "$it", "10", "-5", "CNY", "0", "", "", empty, time) },
    )
    private fun entry(parent: String, vararg fields: String) = LedgerCashEntryFactDto("entry:$parent", parent, parent, "1",
        null, "old-note", "old-party", "", array, JsonArray(fields.map(::JsonPrimitive)), null)
    private fun owned(source: LedgerFactsDto, vararg entries: LedgerCashEntryFactDto) = source.copy(cashEntries = entries.toList(),
        cashEntryMembers = entries.map { LedgerCashEntryMemberFactDto(it.rootTransactionId, it.id, empty, array) })
    private fun edge(id: String, primary: String, secondary: String, state: String = "confirmed") =
        CashRelationWriteDto(CashWriteVersionedReferenceDto(fresh(id), null), "payment_mirror", target(primary), target(secondary),
            ref("$primary$primary"), ref("$secondary$secondary"), target(primary), ref("$primary$primary"), "expense", "expense", state, true,
            null, "user", empty, "5", "rule", "user", emptyList())
    private fun group(id: String, patch: JsonObject = empty) = CashRelationWriteGroupDto(listOf(fresh(id)), patch)
    private fun patch(vararg values: Pair<String, String>) = JsonObject(values.associate { it.first to JsonPrimitive(it.second) })
    private fun joint(first: JsonObject, second: JsonObject, state: String = "draft") = CashWriteSetDto(
        relations = listOf(edge("a", "1", "2"), edge("b", "3", "4"), edge("bridge", "2", "3", state)),
        relationGroups = listOf(group("a", first), group("bridge"), group("b", second)))
    private fun rejected(value: CashWriteSetDto, source: LedgerFactsDto, path: String) {
        val failure = assertFailsWith<CashPolicyError> { prepareCashImportWriteSetCommonChoices(value, source) }
        assertEquals("entry_result_invalid", failure.code)
        assertEquals(listOf(path), failure.fields)
    }
    private fun editedRow(source: LedgerFactsDto, parent: String, owner: String?): CashTransactionWriteDto {
        val row = source.cashTransactions.single { it.id == parent }
        val values = CashTransactionWriteValuesDto(row.accountId, row.state, row.recordOrigin, row.manualTransferId, row.cashGranularity,
            row.sourceType, row.recordId, row.sourcePayload, row.manualOverrides, row.sourceFingerprint, row.relationMetadata,
            row.occurredAt, row.amount, row.currency, row.counterparty, row.counterpartyAccount, row.counterpartyAccountAttrs,
            row.note, row.categoryId, row.recordType, row.recordSubtype)
        return CashTransactionWriteDto(target(parent), values,
            listOf(CashComponentWriteDto(ref("$parent$parent"), "10", "-5", "CNY", "0", "", "", empty)),
            entryEdit = CashEntryBeforeRelationEditDto(owner?.let(::target), patch("note" to "C")))
    }

    @Test fun allAcceptedChoicesInAJointBatchMustHaveTheSamePresenceAndValues() {
        val note = patch("note" to "B")
        for (state in listOf("draft", "ignored")) {
            rejected(joint(note, patch("note" to "different"), state), facts(), "relation_groups[2].common_fields")
            rejected(joint(note, empty, state), facts(), "relation_groups[2].common_fields")
            rejected(joint(note, patch("counterparty" to "B"), state), facts(), "relation_groups[2].common_fields")
        }
    }

    @Test fun thePatchCoversManualFieldsFromEveryScannedMultiMemberProjection() {
        val source = owned(facts(), entry("1", "note"), entry("3", "counterparty"))
        val incomplete = patch("note" to "B")
        rejected(joint(incomplete, incomplete), source, "relation_groups[0].common_fields")
        val complete = patch("counterparty" to "B-party", "note" to "B")
        val value = joint(complete, JsonObject(complete.entries.reversed().associate { it.toPair() }))
        val before = source.copy()
        val batches = prepareCashImportWriteSetCommonChoices(value, source)
        assertEquals(listOf(value.relationGroups), batches)
        assertEquals(before, source)
        assertEquals(complete, batches.single().last().commonFields)
    }

    @Test fun emptyEffectiveChoicesCannotSilentlyRecomputeOrOmitOldManualFields() {
        val source = owned(facts(), entry("1", "category_id"))
        val value = joint(empty, empty)
        rejected(value, source, "relation_groups[0].common_fields")
        val clear = buildJsonObject { put("category_id", JsonNull) }
        assertEquals(clear, prepareCashImportWriteSetCommonChoices(joint(clear, clear), source).single().first().commonFields)
        assertEquals(listOf(value.relationGroups), prepareCashImportWriteSetCommonChoices(value, facts()))
    }

    @Test fun aPreMergeEditAddsManualFieldsEvenWhenItsOwnerWasNotManualBefore() {
        val source = owned(facts(), entry("1"))
        val update = editedRow(source, "1", "entry:1")
        val value = joint(empty, empty).copy(transactions = listOf(update), entryDependencies = listOf(target("entry:1")))
        rejected(value, source, "relation_groups[0].common_fields")
        val choice = patch("note" to "B")
        prepareCashImportWriteSetCommonChoices(value.copy(relationGroups = joint(choice, choice).relationGroups), source)
    }

    @Test fun editsToAnotherMemberOfTheSameOldOwnerPropagateToTheScannedMember() {
        val base = owned(facts(), entry("1"))
        val old = LedgerCashRelationFactDto("100", "payment_mirror", "1", "5", "11", "55", "1", "11", "expense", "expense",
            "confirmed", true, "1", null, "user", empty, "5", "rule", "user", time, "user", time, "")
        val source = base.copy(transactionRelations = listOf(old),
            cashEntryMembers = base.cashEntryMembers + LedgerCashEntryMemberFactDto("5", "entry:1", empty, array))
        val value = CashWriteSetDto(transactions = listOf(editedRow(source, "5", "entry:1")), entryDependencies = listOf(target("entry:1")),
            relations = listOf(edge("a", "1", "2")), relationGroups = listOf(group("a")))
        rejected(value, source, "relation_groups[0].common_fields")
        prepareCashImportWriteSetCommonChoices(value.copy(relationGroups = listOf(group("a", patch("note" to "B")))), source)
    }

    @Test fun newAndPreviouslyUnownedParentsContributeTheirPreMergeEditFields() {
        val source = facts()
        val unowned = CashWriteSetDto(transactions = listOf(editedRow(source, "1", null)),
            relations = listOf(edge("a", "1", "2")), relationGroups = listOf(group("a")))
        rejected(unowned, source, "relation_groups[0].common_fields")
        val parent = CashWriteVersionedReferenceDto(fresh("1"), null)
        val row = unowned.transactions.single().copy(target = parent, values = unowned.transactions.single().values.copy(
            recordOrigin = "import", recordId = "new-record"), components = listOf(CashComponentWriteDto(fresh("new-part"), "10", "-5", "CNY", "0", "", "", empty)))
        val relation = edge("a", "1", "2").copy(primaryTransaction = parent, anchorTransaction = parent,
            primaryComponent = fresh("new-part"), anchorComponent = fresh("new-part"))
        val value = unowned.copy(transactions = listOf(row), relations = listOf(relation), sourceBindings = listOf(
            CashSourceBindingWriteDto(CashWriteVersionedReferenceDto(fresh("binding"), null), parent.reference,
                "bank", "account", "10", "provider", "new-record", "strong", null, "1", empty)))
        rejected(value, source, "relation_groups[0].common_fields")
        prepareCashImportWriteSetCommonChoices(value.copy(relationGroups = listOf(group("a", patch("note" to "B")))), source)
        val oldOnly = owned(source, entry("1", "counterparty"))
        prepareCashImportWriteSetCommonChoices(value.copy(relationGroups = listOf(group("a", patch("note" to "B")))), oldOnly)
    }

    @Test fun finalPublicAndStandaloneResultsCannotAuthorizeAnOmittedBeforeMergeChoice() {
        val source = owned(facts(), entry("1", "note"))
        val standalone = buildJsonObject {
            put("category_id", JsonNull); put("note", "C"); put("counterparty", ""); put("counterparty_account", "")
            put("counterparty_account_attrs", array)
        }
        val result = CashEntryWriteDto(target("1"), null, "D", "", "", array, listOf("note"),
            listOf("1", "2").map { CashEntryMemberWriteDto(target(it), standalone, listOf("note")) }, listOf("note"))
        val value = CashWriteSetDto(relations = listOf(edge("a", "1", "2")), relationGroups = listOf(group("a")), entries = listOf(result),
            entryDependencies = listOf(target("entry:1")))
        rejected(value, source, "relation_groups[0].common_fields")
    }

    @Test fun oldConfirmedProjectionsReachedOnlyThroughANonacceptedGroupAlsoContribute() {
        val old = LedgerCashRelationFactDto("100", "payment_mirror", "5", "6", "55", "66", "5", "55", "expense", "expense",
            "confirmed", true, "1", null, "user", empty, "5", "rule", "user", time, "user", time, "")
        val source = owned(facts().copy(transactionRelations = listOf(old)), entry("5", "note"))
        val value = CashWriteSetDto(relations = listOf(edge("a", "1", "2"), edge("bridge", "2", "6", "ignored")),
            relationGroups = listOf(group("a"), group("bridge")))
        rejected(value, source, "relation_groups[0].common_fields")
        prepareCashImportWriteSetCommonChoices(value.copy(relationGroups = listOf(group("a", patch("note" to "B")), group("bridge"))), source)
    }

    @Test fun singletonAndUnrelatedManualFieldsDoNotCreateExtraRequiredChoices() {
        val source = owned(facts(), entry("3", "note"), entry("6", "counterparty"))
        val value = CashWriteSetDto(relations = listOf(edge("a", "1", "2"), edge("bridge", "2", "3", "draft")),
            relationGroups = listOf(group("a"), group("bridge")))
        assertEquals(listOf(value.relationGroups), prepareCashImportWriteSetCommonChoices(value, source))
    }

    @Test fun separateBatchesCanMakeDifferentExplicitChoicesWithoutManualPredecessors() {
        val value = CashWriteSetDto(relations = listOf(edge("a", "1", "2"), edge("b", "3", "4")),
            relationGroups = listOf(group("b", patch("note" to "B-b")), group("a", patch("counterparty" to "B-a"))))
        assertEquals(value.relationGroups.map { listOf(it) }, prepareCashImportWriteSetCommonChoices(value, facts()))
    }

    @Test fun batchesWithNoAcceptedGroupNeedNoPublicChoice() {
        val source = owned(facts(), entry("1", "note"))
        val value = CashWriteSetDto(relations = listOf(edge("a", "1", "2", "draft")), relationGroups = listOf(group("a")))
        assertEquals(listOf(value.relationGroups), prepareCashImportWriteSetCommonChoices(value, source))
        assertEquals(emptyList(), prepareCashImportWriteSetCommonChoices(CashWriteSetDto(), source))
    }
}
