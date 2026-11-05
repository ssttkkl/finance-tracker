package com.finance.tracker.business.domain

import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

class LocalCashImportPreviewSnapshotTest {
    private val input get() = LocalCashRelationContextTest().snapshot()
    private val now = Instant.parse("2026-10-01T09:00:00Z")
    private val operation = Json.parseToJsonElement("""[{
        "operation_ref":"op:new","planned_action":"create_posted","occurrence_key":"stable-occurrence",
        "row_refs":["file:r1"],"source_scope_key":"owner","matching_record_id":"record-new",
        "stable_source_key":[],"manual_fields":["note"],"entry_edit":{"patch":{"note":"confirmed-note"}},
        "values":{"account_id":"10","amount":"-5.00","currency":"CNY","occurred_at":"2026-10-02T10:00:00Z",
            "state":"posted","cash_granularity":"atomic","record_type":"consumption","record_subtype":"not_applicable",
            "counterparty":"new-source","note":"source-note","source_type":"ccb_debit","record_id":"record-new",
            "components":[{"account_id":"10","amount":"-5.00","currency":"CNY","ordinal":0}]}
    }]""").jsonArray

    @Test fun storedFactsDraftsAndEntryInheritanceComposeWithoutMutatingTheSnapshot() {
        val source = input
        val original = source.facts
        val context = readLocalCashRelationContext(source, setOf(1L), listOf("group"))
        val result = buildLocalCashImportPreviewSnapshot(source, context, JsonArray(emptyList()), JsonArray(emptyList())) { now }
        assertEquals(setOf("1", "2", "3"), result.facts.keys)
        assertEquals("公共备注", result.facts.getValue("1").note)
        assertEquals("来源备注", result.facts.getValue("2").note)
        assertEquals("draft", result.facts.getValue("3").state)
        assertTrue(result.facts.getValue("3").needsAttention)
        assertNull(result.context.facts.first().createdAt)
        assertEquals("entry", result.entryPublic.getValue("1")["entry_id"])
        assertEquals(setOf("1", "2", "3", "4"), result.values.keys)
        assertEquals(setOf("1", "2", "3"), result.build().memberIds.map { it.toString() }.toSet())
        result.values.getValue("1")["note"] = "changed-preview"
        assertEquals(original, source.facts)
        assertEquals("来源备注", source.facts.cashTransactions.first().note)
    }

    @Test fun virtualIdsMatchIndependentPythonIntegersAndRemainNumbersInWireValues() {
        val source = input
        val context = readLocalCashRelationContext(source, emptySet())
        val before = operation.toString()
        val result = buildLocalCashImportPreviewSnapshot(source, context, operation, JsonArray(emptyList())) { now }
        val identifier = "34038115944072999195166050254573576354"
        val component = "232800787442169502504161063218564809373"
        assertEquals(setOf(identifier), result.facts.keys)
        assertEquals(setOf(component), result.components.keys)
        val fact = result.facts.getValue(identifier)
        assertEquals(identifier, fact.id.toString())
        assertEquals(now, fact.createdAt)
        assertEquals("import", fact.recordOrigin)
        assertEquals("confirmed-note", fact.note)
        assertEquals("new:$identifier", result.entryPublic.getValue(identifier)["entry_id"])
        assertEquals(Json.parseToJsonElement(identifier), encodeLocalCashValue(fact.id))
        assertFalse(encodeLocalCashValue(fact.id).jsonPrimitive.isString)
        assertEquals(before, operation.toString())
        assertEquals(listOf(identifier), result.build().memberIds.map { it.toString() })
    }

    @Test fun previewIntegerOrderingAndCanonicalTextUseTheOriginalExactIntegerDomain() {
        val large = CashImportPreviewInteger.parse("34038115944072999195166050254573576354")
        val ten = CashImportPreviewInteger.parse("+００１０")
        assertEquals("10", ten.toString())
        assertEquals(CashImportPreviewInteger.parse("10"), ten)
        assertEquals(CashImportPreviewInteger.parse("10").hashCode(), ten.hashCode())
        assertEquals(listOf("-10", "-2", "0", "2", "10", large.toString()),
            listOf(large, ten, CashImportPreviewInteger.parse("2"), CashImportPreviewInteger.parse("-2"),
                CashImportPreviewInteger.parse("-10"), CashImportPreviewInteger.parse("-0")).sorted().map { it.toString() })
        for (text in listOf("", "+", "1.0", "1e2", " 1", "𐒠"))
            assertFailsWith<NumberFormatException> { CashImportPreviewInteger.parse(text) }
        assertSame(large, copyLocalCashValue(large))
    }

    @Test fun injectedClockProgramErrorsAreNotConvertedIntoSkippedOperations() {
        val source = input
        val failure = IllegalStateException("clock failure")
        assertSame(failure, assertFailsWith<IllegalStateException> {
            buildLocalCashImportPreviewSnapshot(source, readLocalCashRelationContext(source, emptySet()), operation,
                JsonArray(emptyList())) { throw failure }
        })
    }
}
