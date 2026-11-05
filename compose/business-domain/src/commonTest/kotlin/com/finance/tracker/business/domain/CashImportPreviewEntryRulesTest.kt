package com.finance.tracker.business.domain

import kotlinx.serialization.json.*
import kotlin.test.*

class CashImportPreviewEntryRulesTest {
    private fun snapshot() = CashImportPreviewSnapshotTest().snapshot().also { state ->
        state.values.values.forEachIndexed { index, value ->
            value.putAll(mapOf("counterparty" to "root-$index", "category_id" to "category-$index", "note" to "root-note-$index",
                "counterparty_account" to "account-$index", "counterparty_account_attrs" to emptyMap<String, Any?>()))
        }
    }
    private fun entry(root: Long = 1) = CashEntry("entry", root, 1, 9007199254740993L,
        buildJsonObject { put("note", "manual-note"); put("counterparty_account_attrs", JsonArray(emptyList())) },
        listOf("note"), null)
    private fun member(id: Long, entry: String = "entry") = CashEntryMember(id, entry, JsonObject(emptyMap()), emptyList())
    private fun patch(vararg fields: Pair<String, Any?>): MutableMap<String, Any?> =
        linkedMapOf("entry_edit" to mapOf("patch" to linkedMapOf(*fields)))

    @Test fun existingMembersInheritRootAndShareTheLastOrderedEntryPatch() {
        val state = snapshot()
        val entry = entry()
        state.parentOps["1"] = patch("counterparty" to "first", "counterparty_account" to "patched-account")
        state.parentOps["2"] = patch("counterparty" to "last")
        val before = state.values.mapValues { it.value.toMap() }
        val decoded = mutableListOf<JsonElement?>()
        applyCashImportPreviewEntryPublic(state, mapOf(1L to member(1), 2L to member(2)), mapOf(entry.id to entry)) {
            decoded += it; decodeLocalCashValue(it)
        }
        for (id in listOf("1", "2")) {
            val result = state.entryPublic.getValue(id)
            val values = result["values"] as Map<*, *>
            assertEquals("entry", result["entry_id"])
            assertEquals("manual-note", values["note"])
            assertEquals("category-0", values["category_id"])
            assertEquals("last", values["counterparty"])
            assertEquals("patched-account", values["counterparty_account"])
            assertEquals(listOf("counterparty", "counterparty_account", "note"), result["manual_fields"])
            assertEquals("last", state.facts.getValue(id).counterparty)
            assertEquals("manual-note", state.facts.getValue(id).note)
        }
        assertEquals<List<JsonElement?>>(listOf(JsonPrimitive("manual-note"), JsonPrimitive("manual-note")), decoded)
        assertEquals(before, state.values.mapValues { it.value.toMap() })
        assertEquals(listOf("note"), entry.manualFields)
        assertEquals(9007199254740993L, entry.revision)
    }

    @Test fun missingRootFallsBackToEachMemberAndUnrelatedEntriesAreNotDecoded() {
        val state = snapshot()
        val entry = entry(99)
        applyCashImportPreviewEntryPublic(state, mapOf(1L to member(1), 2L to member(2, "missing")),
            mapOf("entry" to entry, "unrelated" to entry.copy(id = "unrelated", manualFields = listOf("counterparty_account_attrs")))) {
            assertEquals(JsonPrimitive("manual-note"), it)
            decodeLocalCashValue(it)
        }
        assertEquals("root-0", state.facts.getValue("1").counterparty)
        assertEquals("category-0", state.facts.getValue("1").categoryId)
        assertFalse("2" in state.entryPublic)
        assertEquals("备注", state.facts.getValue("2").note)
    }

    @Test fun newOperationMetadataKeepsExactParentReferenceAndManualFieldUnion() {
        val state = snapshot()
        val id = "9007199254740993"
        val values = CashProjectionValues<Long, kotlin.time.Instant>({ it > 0 }, { true }, { it })
        state.facts[id] = CashProjectionFact.create(values, id.toLong(), 10L, kotlin.time.Instant.parse("2026-10-02T10:00:00Z"),
            "-5.00", "CNY", "root-0", "category-0", "root-note-0", "ccb_debit", "new-record", recordType = "consumption")
        state.values[id] = state.values.getValue("1").toMutableMap()
        state.parentOps[id] = patch("note" to "edited", "category_id" to null).apply {
            put("manual_fields", listOf("counterparty", "note"))
        }
        applyCashImportPreviewEntryPublic(state, emptyMap(), emptyMap()) { error("no decoding for new rows") }
        val result = state.entryPublic.getValue(id)
        assertEquals("new:$id", result["entry_id"])
        assertEquals(listOf("category_id", "counterparty", "note"), result["manual_fields"])
        assertEquals("edited", state.facts.getValue(id).note)
        assertNull(state.facts.getValue(id).categoryId)
        assertEquals(9007199254740993L, state.facts.getValue(id).id)
    }

    @Test fun decoderProgramErrorsPropagateWithoutConversionOrFallback() {
        val state = snapshot()
        val failure = IllegalStateException("decoder failure")
        assertSame(failure, assertFailsWith<IllegalStateException> {
            applyCashImportPreviewEntryPublic(state, mapOf(1L to member(1)), mapOf("entry" to entry())) { throw failure }
        })
        assertTrue(state.entryPublic.isEmpty())
        assertEquals("备注", state.facts.getValue("1").note)
    }
}
