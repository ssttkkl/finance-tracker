package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*
import kotlin.test.*

class CashWriteSetEntryValuesTest {
    private val empty = JsonObject(emptyMap())
    private val array = JsonArray(emptyList())
    private val target = CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto("1"), "1")
    private fun values() = buildJsonObject {
        put("category_id", JsonNull); put("note", ""); put("counterparty", "merchant")
        put("counterparty_account", ""); put("counterparty_account_attrs", JsonArray(listOf(JsonPrimitive("tail:1234"))))
    }
    private fun entry() = CashEntryWriteDto(target, target, target, null, "", "merchant", "",
        values().getValue("counterparty_account_attrs"), emptyList(), null,
        listOf(CashEntryMemberWriteDto(target, values(), emptyList())))
    private fun invalid(entry: CashEntryWriteDto, code: String = "invalid_field") {
        val failure = assertFailsWith<CashPolicyError> { normalizeCashWriteSetEntryValues(CashWriteSetDto(entries = listOf(entry))) }
        assertEquals(code, failure.code)
        assertTrue(failure.fields.single().startsWith("entries[0]"))
    }

    private fun rowWithPreEdit(patch: JsonObject): CashTransactionWriteDto = CashTransactionWriteDto(target,
        CashTransactionWriteValuesDto(null, "draft", "import", null, "atomic", "bank", "source", empty, empty, null, empty,
            null, null, null, "", "", array, "S", null, null, null), emptyList(),
        entryEdit = CashEntryBeforeRelationEditDto(null, patch))

    @Test fun beforeRelationPublicPatchIsIndependentOfSourceAndFinalPublicValues() {
        val patch = buildJsonObject { put("note", "C"); put("category_id", JsonNull) }
        val source = CashWriteSetDto(transactions = listOf(rowWithPreEdit(patch)), entries = listOf(entry().copy(note = "D", editedFields = listOf("note"))))
        val normalized = normalizeCashWriteSetEntryValues(source)
        assertEquals("S", normalized.transactions.single().values.note)
        assertEquals(patch, normalized.transactions.single().entryEdit!!.patch)
        assertEquals("D", normalized.entries.single().note)
        assertEquals(source, normalized)
        assertFalse("counterparty" in normalized.transactions.single().entryEdit!!.patch)
    }

    @Test fun beforeRelationPatchUsesOnlyPublicFieldsAndShallowNativeValueValidation() {
        for ((patch, code) in listOf(
            empty to "invalid_field",
            buildJsonObject { put("amount", "1") } to "field_not_editable",
            buildJsonObject { put("note", JsonNull) } to "invalid_field",
            buildJsonObject { put("counterparty_account_attrs", JsonArray(listOf(empty))) } to "invalid_field",
        )) {
            val failure = assertFailsWith<CashPolicyError> { normalizeCashWriteSetEntryValues(CashWriteSetDto(transactions = listOf(rowWithPreEdit(patch)))) }
            assertEquals(code, failure.code)
            assertTrue(failure.fields.single().startsWith("transactions[0].entry_edit.patch"))
        }
        val clear = buildJsonObject { put("note", ""); put("counterparty_account_attrs", array) }
        val normalized = normalizeCashWriteSetEntryValues(CashWriteSetDto(transactions = listOf(rowWithPreEdit(clear))))
        assertEquals(clear, normalized.transactions.single().entryEdit!!.patch)
    }

    @Test fun explicitEmptyValuesAndHistoricalCategoryReferencesArePreserved() {
        val source = entry().let { it.copy(members = listOf(it.members.single().copy(
            standaloneValues = JsonObject(values() + ("category_id" to JsonPrimitive("deleted-category")))))) }
        val changes = CashWriteSetDto(entries = listOf(source), sourceEmptyPages = listOf(
            LedgerSourceEmptyPageFactDto("bank", "1", "document", 1, empty)))
        val normalized = normalizeCashWriteSetEntryValues(changes)
        assertEquals(changes, normalized)
        assertSame(source.target, normalized.entries.single().target)
        assertSame(changes.sourceEmptyPages, normalized.sourceEmptyPages)
        assertEquals("", normalized.entries.single().note)
    }

    @Test fun flagSetsNormalizeWithoutTreatingUnsortedHistoricalListsAsCorruption() {
        val source = entry().let { it.copy(manualFields = listOf("note", "category_id", "note"), editedFields = listOf("note", "category_id", "note"),
            members = listOf(it.members.single().copy(standaloneManualFields = listOf("note", "category_id", "note")))) }
        val normalized = normalizeCashWriteSetEntryValues(CashWriteSetDto(entries = listOf(source))).entries.single()
        assertEquals(listOf("category_id", "note"), normalized.manualFields)
        assertEquals(listOf("category_id", "note"), normalized.editedFields)
        assertEquals(listOf("category_id", "note"), normalized.members.single().standaloneManualFields)
        assertEquals(listOf("note", "category_id", "note"), source.manualFields)
        assertEquals(listOf("note", "category_id", "note"), source.members.single().standaloneManualFields)
    }

    @Test fun onlyTheFivePublicFieldsMayBeMarkedOrEdited() {
        invalid(entry().copy(manualFields = listOf("amount")), "field_not_editable")
        invalid(entry().copy(editedFields = listOf("occurred_at")), "field_not_editable")
        val source = entry()
        invalid(source.copy(members = listOf(source.members.single().copy(standaloneManualFields = listOf("record_type")))), "field_not_editable")
    }

    @Test fun standaloneValuesMustContainTheCompleteOriginalPublicObject() {
        val source = entry()
        for (bad in listOf<JsonElement>(JsonNull, JsonPrimitive("not an object"), array, empty,
            JsonObject(values() - "note"), JsonObject(values() + ("amount" to JsonPrimitive("1"))))) {
            invalid(source.copy(members = listOf(source.members.single().copy(standaloneValues = bad))))
        }
        for ((field, bad) in listOf("note" to JsonNull, "counterparty" to JsonPrimitive(123),
            "category_id" to JsonPrimitive(true), "counterparty_account" to array,
            "counterparty_account_attrs" to JsonArray(listOf(JsonPrimitive(123))), "counterparty_account_attrs" to empty)) {
            invalid(source.copy(members = listOf(source.members.single().copy(standaloneValues = JsonObject(values() + (field to bad))))))
        }
    }

    @Test fun attributesAreTypedBeforeAnyRecursiveDecodeAndPublicValuesUseTheSameBoundary() {
        for (bad in listOf<JsonElement>(JsonNull, empty, JsonPrimitive("text"), JsonArray(listOf(JsonNull)), JsonArray(listOf(JsonPrimitive(true))))) {
            invalid(entry().copy(counterpartyAccountAttrs = bad))
        }
        var nested: JsonElement = JsonPrimitive("leaf")
        repeat(4096) { nested = JsonArray(listOf(nested)) }
        val source = entry().copy(counterpartyAccountAttrs = nested)
        invalid(source)
        assertSame(nested, source.counterpartyAccountAttrs)
    }

    @Test fun finalSplitStateIsAlwaysClearedWhileTheSeparateDecisionIsPreserved() {
        for (state in listOf(false, true)) invalid(entry().copy(splitInherit = state))
        for (inherit in listOf(false, true)) {
            val choices = listOf(CashEntrySplitChoiceDto(target, inherit))
            val changes = CashWriteSetDto(entries = listOf(entry()), entrySplitChoices = choices)
            val normalized = normalizeCashWriteSetEntryValues(changes)
            assertNull(normalized.entries.single().splitInherit)
            assertSame(choices, normalized.entrySplitChoices)
        }
    }
}
