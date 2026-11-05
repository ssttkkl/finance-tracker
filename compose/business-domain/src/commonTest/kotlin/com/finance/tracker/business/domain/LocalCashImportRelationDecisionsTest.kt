package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.LedgerCashEntryMemberFactDto
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

class LocalCashImportRelationDecisionsTest {
    private val now = Instant.parse("2026-10-01T09:00:00Z")

    @Test fun computedRefundChoiceRetainsItsExactTypesAndReadonlyPreviousConfirmation() {
        val base = LocalCashRelationContextTest().snapshot()
        val source = base.copy(facts = base.facts.copy(transactionRelations = emptyList()))
        @Suppress("UNCHECKED_CAST") val operations = decodeLocalCashValue(LocalCashImportPreviewSnapshotTest().operation) as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST") val operation = operations.single() as MutableMap<String, Any?>
        operation["stable_source_key"] = listOf("ccb_debit", "account", "owner", "provider", "record-new")
        @Suppress("UNCHECKED_CAST") val values = operations.single()["values"] as MutableMap<String, Any?>
        values["amount"] = CashAmount.parse("5.00")
        values["record_type"] = "refund"
        for (part in values["components"] as List<*>) {
            @Suppress("UNCHECKED_CAST") val component = part as MutableMap<String, Any?>
            component["amount"] = CashAmount.parse("5.00")
        }
        val preview = buildLocalCashImportPreviewSnapshot(source, readLocalCashRelationContext(source, setOf(1L)),
            operations, emptyList()) { now }
        val parent = preview.parentOps.keys.single()
        val part = preview.components.values.single { it.transactionId == parent }.componentId as String
        val roles = discoverCashRelationRoles("refund_offset", preview.components.getValue("11"), preview.components.getValue(part))
        preview.build(additions = listOf(CashRelationGraphEdge(CashImportPreviewInteger.parse("999"), "refund_offset", "1", parent,
            "11", part, roles.first, roles.second, CashDecimalLiteral("5.00"), "confirmed", true)))
        val groups = buildLocalCashImportRelationGroups(preview, listOf(RelationProposal("refund_offset", "11", part,
            evidence = RelationEvidence(extras = mapOf("refund_amount" to "5.00"))))).groups
        val group = groups.single()
        val candidate = (group["candidates"] as List<*>).single() as Map<*, *>
        val previous = mapOf("relation_groups" to copyLocalCashValue(groups))
        val before = localCashImportDigest(listOf(previous))
        val choice = mapOf("suggestion_id" to group["suggestion_id"], "candidate_id" to candidate["candidate_id"], "action" to "accept",
            "common_fields" to mapOf("note" to "confirmed-note"))
        val result = applyLocalCashImportRelationChoices(groups, listOf(choice), previous)
        assertTrue(result.invalidated.isEmpty())
        assertEquals("accept", group["action"])
        val edge = (group["edges"] as List<*>).single() as Map<*, *>
        val amount = edge["applied_amount"] as CashDecimalLiteral
        assertEquals("5.00", amount.text)
        assertSame(amount, copyLocalCashValue(amount))
        assertTrue(edge["id"] is CashImportPreviewInteger)
        assertFalse((group["choice_generation"] as JsonPrimitive).isString)
        assertEquals(before, localCashImportDigest(listOf(previous)))
        assertTrue(applyLocalCashImportRelationGroups(preview, groups.toMutableList()).isEmpty())
        assertEquals("accept", group["action"])
        assertEquals(CashAmount.parse("5.00"), preview.build().projections.single().relations.single().appliedAmount)
        assertFailsWith<InvalidCashValue> { LocalCashRecordValues.normalize("source_payload", mapOf("amount" to amount)) }
    }

    @Test fun splittingRestoresOnlyChosenExistingMembersAcrossTheConfirmedClosure() {
        val base = LocalCashRelationContextTest().snapshot()
        fun member(id: String, note: String) = LedgerCashEntryMemberFactDto(id, "entry", buildJsonObject { put("note", note) }, JsonArray(emptyList()))
        val source = base.copy(facts = base.facts.copy(cashEntryMembers = listOf(member("1", "独立一"), member("2", "独立二"),
            LedgerCashEntryMemberFactDto("5", "unrelated", JsonPrimitive("invalid unused metadata"), JsonArray(emptyList())))))
        val context = readLocalCashRelationContext(source, setOf(1L))
        val preview = buildLocalCashImportPreviewSnapshot(source, context, emptyList(), emptyList()) { now }
        val groups = listOf<MutableMap<String, Any?>>(linkedMapOf("origin" to "current", "action" to "remove",
            "parent_ids" to listOf("1"), "old_parent_ids" to listOf("1"), "split_field_diffs" to emptyList<Any>()))
        restoreLocalCashImportSplitValues(source, preview, groups)
        assertEquals(false, groups.single()["inherit_common_fields"])
        assertEquals("独立一", preview.facts.getValue("1").note)
        assertEquals("独立二", preview.facts.getValue("2").note)
        assertEquals(mapOf("note" to "独立二"), preview.entryPublic.getValue("2")["values"])
        assertEquals("公共备注", base.facts.cashEntries.single().note)
    }

    @Test fun unselectedOrMissingMembersDoNotDecodeTheirStandaloneValues() {
        val source = LocalCashRelationContextTest().snapshot()
        val preview = buildLocalCashImportPreviewSnapshot(source, readLocalCashRelationContext(source, setOf(1L)),
            emptyList(), emptyList()) { now }
        val malformed = source.copy(facts = source.facts.copy(cashEntryMembers = source.facts.cashEntryMembers.map {
            it.copy(standaloneValues = JsonPrimitive("not an object"))
        }))
        val keep = listOf<MutableMap<String, Any?>>(linkedMapOf("action" to "remove", "parent_ids" to listOf("1"),
            "inherit_common_fields" to true))
        restoreLocalCashImportSplitValues(malformed, preview, keep)
        assertEquals("公共备注", preview.facts.getValue("1").note)
        val remove = listOf<MutableMap<String, Any?>>(linkedMapOf("action" to "remove", "parent_ids" to listOf("1"),
            "inherit_common_fields" to false))
        val fact = preview.facts.remove("1")!!
        restoreLocalCashImportSplitValues(malformed, preview, remove)
        preview.facts["1"] = fact
        assertFailsWith<IllegalArgumentException> { restoreLocalCashImportSplitValues(malformed, preview, remove) }
    }
}
