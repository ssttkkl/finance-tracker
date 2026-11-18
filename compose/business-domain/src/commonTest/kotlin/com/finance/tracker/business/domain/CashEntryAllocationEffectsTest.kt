package com.finance.tracker.business.domain

import kotlinx.serialization.json.*
import kotlin.test.*

class CashEntryAllocationEffectsTest {
    private fun fields(note: String, party: String = "", category: String? = null) = buildJsonObject {
        put("category_id", category?.let(::JsonPrimitive) ?: JsonNull)
        put("note", note); put("counterparty", party); put("counterparty_account", "account:$note")
        put("counterparty_account_attrs", JsonArray(listOf(JsonPrimitive("party:$party"))))
    }
    private data class Result(val business: Map<String, Any?>, val selected: String, val freshAnchor: String, val order: List<String>)
    private fun calculate(virtual: Boolean, clear: Boolean, choose: Boolean): Result {
        val ids = mapOf("A1" to "1", "A2" to "2", "B1" to "3", "B2" to "4", "C" to "19",
            "N" to if (virtual) "100000000000000000000" else "20",
            "M" to if (virtual) "100000000000000000001" else "21",
            "X" to if (virtual) "100000000000000000002" else "23",
            "Y" to if (virtual) "100000000000000000003" else "22").mapValues { CashImportPreviewInteger.parse(it.value) }
        val labels = ids.entries.associate { it.value to it.key }
        val sources = ids.map { (label, id) -> id to CashEntrySource(fields("source:$label", "party:$label", "category:$label"), emptySet()) }.toMap()
        val entries = linkedMapOf(
            "m-A" to CashEntry("m-A", ids.getValue("A1"), ids.getValue("A1"), 5, fields("human", "party:A1"), listOf("note"), null),
            "n-B" to CashEntry("n-B", ids.getValue("B1"), ids.getValue("B1"), 7, fields("source:B1", "human-party"), listOf("counterparty"), null),
            "o-C" to CashEntry("o-C", ids.getValue("C"), ids.getValue("C"), 9, fields("source:C", "party:C"), emptyList(), null))
        val members = linkedMapOf<CashImportPreviewInteger, CashEntryMember<CashImportPreviewInteger>>()
        for ((label, entry) in mapOf("A1" to "m-A", "A2" to "m-A", "B1" to "n-B", "B2" to "n-B", "C" to "o-C")) {
            val id = ids.getValue(label)
            members[id] = CashEntryMember(id, entry, fields("standalone:$label", "party:$label"), listOf("note"))
        }
        var sequence = 0
        val core = CashEntryReconciliationRules<CashImportPreviewInteger, CashImportPreviewInteger>({ it }, { it },
            { (if (virtual) "a-fresh-" else "z-fresh-") + sequence++ }, { members[it.transactionId] = it })
        val edits = CashEntryEditRules(LocalCashRecordValues)
        fun group(vararg names: String): CashEntryProjectionGroup<CashImportPreviewInteger> {
            val values = names.map(ids::getValue)
            return CashEntryProjectionGroup("cash:${values.first()}", values.first(), values, values)
        }
        fun resolve(id: CashImportPreviewInteger): CashEntry<CashImportPreviewInteger> {
            members[id]?.let { return entries.getValue(it.entryId) }
            val built = core.reconcileGroups(listOf(CashEntryProjectionGroup("cash:$id", id, listOf(id), listOf(id))), members.toMap(), entries, sources)
            built.newEntries.forEach { entries[it.id] = it }
            return built.entriesByProjection.getValue("cash:$id")
        }
        val merged = group("N", "A1", "A2", "B1", "B2")
        if (choose) edits.chooseCommon(merged.memberIds, mapOf("note" to if (clear) "" else "human", "counterparty" to "human-party"), {}, ::resolve)
        val final = core.reconcileGroups(listOf(merged, group("C", "M"), group("X", "Y")), members.toMap(), entries, sources)
        val business = final.entriesByProjection.map { (_, entry) ->
            val own = members.values.filter { it.entryId == entry.id }.sortedBy { labels.getValue(it.transactionId) }
            val root = labels.getValue(entry.rootTransactionId)
            own.map { labels.getValue(it.transactionId) }.joinToString(",") to mapOf(
                "root" to root, "values" to entry.values, "manual" to entry.manualFields.toSet(),
                "members" to own.associate { labels.getValue(it.transactionId) to (it.standaloneValues to it.standaloneManualFields.toSet()) })
        }.toMap()
        val retained = final.entriesByProjection.getValue(merged.projectionId)
        assertEquals(if (clear && choose) "" else "human", retained.values.getValue("note").jsonPrimitive.content)
        assertEquals("human-party", retained.values.getValue("counterparty").jsonPrimitive.content)
        assertEquals(setOf("note", "counterparty"), retained.manualFields.toSet())
        assertEquals(fields("source:N", "party:N", "category:N") + mapOf("note" to JsonPrimitive(if (clear && choose) "" else "human"),
            "counterparty" to JsonPrimitive("human-party")), retained.values)
        for (label in listOf("A1", "A2", "B1", "B2")) {
            val member = members.getValue(ids.getValue(label))
            assertEquals("standalone:$label", member.standaloneValues.getValue("note").jsonPrimitive.content)
            assertEquals(listOf("note"), member.standaloneManualFields)
        }
        val newMember = members.getValue(ids.getValue("N"))
        assertEquals(sources.getValue(ids.getValue("N")).values, newMember.standaloneValues)
        assertEquals(emptyList(), newMember.standaloneManualFields)
        val allNew = final.entriesByProjection.getValue(group("X", "Y").projectionId)
        return Result(business, retained.id, labels.getValue(allNew.identityTransactionId),
            final.entriesByProjection.values.map { labels.getValue(it.rootTransactionId) })
    }
    @Test fun wholeOldGroupsPreserveEveryBusinessValueAcrossAllocationAndProjectionOrder() {
        for (clear in listOf(false, true)) for (choose in listOf(false, true)) {
            val physical = calculate(false, clear, choose)
            val virtual = calculate(true, clear, choose)
            assertEquals(physical.business, virtual.business)
        }
    }

    @Test fun theSameBusinessEffectsDoNotImplyTheSameStorageIdentityOrProcessingOrder() {
        val physical = calculate(virtual = false, clear = false, choose = true)
        val virtual = calculate(virtual = true, clear = false, choose = true)
        assertEquals("m-A", physical.selected)
        assertEquals("a-fresh-0", virtual.selected)
        assertEquals("Y", physical.freshAnchor)
        assertEquals("X", virtual.freshAnchor)
        assertEquals(listOf("C", "N", "X"), physical.order)
        assertEquals(listOf("N", "C", "X"), virtual.order)
        assertEquals(physical.business, virtual.business)
    }
}
