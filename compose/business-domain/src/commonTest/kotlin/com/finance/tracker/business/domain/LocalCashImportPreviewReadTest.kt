package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

class LocalCashImportPreviewReadTest {
    private val base get() = LocalCashRelationContextTest().snapshot()
    private fun binding(id: String, tx: String, kind: String, key: String, source: String = "ccb_debit") =
        LedgerSourceBindingFactDto(id, tx, source, kind, key, "provider", "key-$id", "strong", null, "v1", JsonNull,
            "9007199254740993", "2026-10-02T10:00:00Z", "2026-10-02T10:00:00Z")

    @Test fun scopesKeepAccountPriorityFileFallbackAndAmbiguityPerSource() {
        val input = base.let { it.copy(facts = it.facts.copy(cashSourceBindings = listOf(
            binding("1", "1", "account", "owner"), binding("2", "1", "account", "owner"), binding("3", "1", "file_local", "file"),
            binding("4", "2", "account", "a"), binding("5", "2", "account", "b"), binding("6", "2", "file_local", "file"),
            binding("7", "3", "file_local", "only-file"), binding("8", "4", "other", "unknown")))) }
        val context = readLocalCashRelationContext(input, setOf(1L), listOf("group"))
        val values = readLocalCashImportPreviewValues(input, context)
        assertEquals(listOf("owner", "ambiguous:2", "file:only-file", "ambiguous:4"), (1..4).map { values.getValue("$it")["source_scope_key"] })
        val wrongSource = base.let { it.copy(facts = it.facts.copy(cashSourceBindings = listOf(binding("9", "1", "account", "other", "wechat")))) }
        assertEquals("unbound:1", readLocalCashImportPreviewValues(wrongSource, readLocalCashRelationContext(wrongSource, setOf(1L)))
            .getValue("1")["source_scope_key"])
        assertEquals("ambiguous:1", cashImportPreviewSourceScope("1", emptySet(), setOf("a", "b")))
    }

    @Test fun valuesRetainDraftAndDeletedMetadataAndUseExactContextPartsAndKeys() {
        val original = base
        val raw = Json.parseToJsonElement("""{"large":9007199254740993,"float":1.0,"nested":[null,true]}""")
        val input = original.copy(facts = original.facts.copy(cashTransactions = original.facts.cashTransactions.map {
            if (it.id == "1") it.copy(sourcePayload = raw, counterpartyAccount = "尾号", relationMetadata = buildJsonObject {
                put("txn_id", "identity"); put("_is_refund", true); put("unknown", "private"); put("summary", "")
            }) else it
        }))
        val context = readLocalCashRelationContext(input, setOf(1L), listOf("group"))
        val values = readLocalCashImportPreviewValues(input, context)
        assertEquals(setOf("1", "2", "3", "4"), values.keys)
        assertEquals("draft", values.getValue("3")["state"])
        assertNull(values.getValue("3")["amount"])
        assertEquals("来源备注", values.getValue("4")["note"])
        val row = values.getValue("1")
        assertEquals("尾号", row["counterparty_account"])
        assertEquals("occurrence:1", row["matching_record_id"])
        assertEquals(Instant.parse("2026-10-02T10:00:00Z"), row["created_at"])
        assertEquals(context.sourceOrderKeys.single { it.first == 1L }.second, row["stable_source_key"])
        assertEquals(raw, encodeLocalCashValue(row["source_payload"]))
        assertEquals(mapOf("txn_id" to "identity", "_is_refund" to true), row["source_explanation"])
        val parts = row["components"] as List<*>
        val part = parts.single() as Map<*, *>
        assertEquals("11", part["component_id"])
        assertEquals(0L, part["ordinal"])
        assertEquals(CashAmount.parse("-5.00"), part["amount"])
        row["note"] = "local-only"
        (row["components"] as MutableList<*>).clear()
        assertEquals("来源备注", input.facts.cashTransactions.first().note)
        assertEquals(4, input.facts.cashTransactionComponents.size)
        assertEquals(raw, input.facts.cashTransactions.first().sourcePayload)
    }

    @Test fun emptyOrSelectedReadsIgnoreUnrelatedInvalidStoredAmounts() {
        val input = base.let { it.copy(facts = it.facts.copy(cashTransactions = it.facts.cashTransactions.map { row ->
            if (row.id == "5") row.copy(amount = "invalid amount") else row
        })) }
        assertTrue(readLocalCashImportPreviewValues(input, readLocalCashRelationContext(input, emptySet())).isEmpty())
        assertEquals(setOf("1", "2"), readLocalCashImportPreviewValues(input, readLocalCashRelationContext(input, setOf(1L))).keys)
    }
}
