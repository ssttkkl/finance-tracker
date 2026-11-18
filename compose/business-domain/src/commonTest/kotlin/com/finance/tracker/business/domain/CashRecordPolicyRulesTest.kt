package com.finance.tracker.business.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CashRecordPolicyRulesTest {
    private val rules = CashRecordPolicyRules(
        normalize = { field, value ->
            if (field == "note" && value !is String) throw InvalidCashValue()
            if (field == "amount" && value is String) CashAmount.parse(value) else value
        },
        equal = { first, second -> first == second },
    )
    private fun record() = mapOf<String, Any?>("state" to "posted", "record_origin" to "import",
        "record_type" to "consumption", "record_subtype" to "not_applicable", "amount" to CashAmount.parse("-20"),
        "currency" to "CNY", "account_id" to "41", "occurred_at" to "2026-08-01T00:00:00Z", "note" to "original",
        "cash_granularity" to "atomic", "components" to listOf(mapOf("account_id" to "41",
            "amount" to CashAmount.parse("-20"), "currency" to "CNY", "ordinal" to 0)))

    @Test
    fun fixedFinancialConflictRejectsTheWholePatch() {
        val current = record()
        val failure = assertFailsWith<CashPolicyError> {
            rules.prepare(current, mapOf("note" to "changed", "amount" to "-21"))
        }
        assertEquals("fixed_field_conflict", failure.code)
        assertEquals(listOf("amount"), failure.fields)
        assertEquals(record(), current)
        assertEquals(mapOf("note" to "changed"), rules.prepare(current, mapOf("note" to "changed", "amount" to "-20.00")).values)
    }

    @Test
    fun protectedAndExplicitNullValuesRetainTheirSeparateMeanings() {
        val current = record() + ("category_id" to "category")
        val protected = rules.prepare(current, mapOf("note" to "changed", "category_id" to null), setOf("note"))
        assertEquals(listOf("note"), protected.protectedFields)
        assertEquals(mapOf("category_id" to null), protected.values)
        assertEquals(mapOf("note" to "changed", "category_id" to null), protected.normalizedValues)
        assertTrue(rules.prepare(current, emptyMap<String, Any?>()).values.isEmpty())
    }

    @Test
    fun malformedPatchAndNormalizationFailureKeepTheirBusinessErrors() {
        assertEquals("invalid_field", assertFailsWith<CashPolicyError> { rules.prepare(record(), emptyList<String>()) }.code)
        assertEquals("invalid_state", assertFailsWith<CashPolicyError> { rules.prepare(record() + ("state" to "other"), emptyMap<String, Any?>()) }.code)
        val unknown = assertFailsWith<CashPolicyError> { rules.prepare(record(), mapOf("😀" to 1, "\ue000" to 2)) }
        assertEquals("field_not_editable", unknown.code)
        assertEquals(listOf("\ue000", "😀"), unknown.fields)
        val invalid = assertFailsWith<CashPolicyError> { rules.prepare(record(), mapOf("note" to null)) }
        assertEquals("invalid_field", invalid.code)
        assertEquals(listOf("note"), invalid.fields)
    }

    @Test
    fun typeChangesAndPostingCompletenessUseTheSameCatalogue() {
        val manual = record() + ("record_origin" to "manual")
        assertEquals("invalid_record_type", assertFailsWith<CashPolicyError> {
            rules.prepare(manual, mapOf("record_type" to "transfer_out"))
        }.code)
        assertTrue(rules.prepare(manual, mapOf("record_type" to "transfer_out", "record_subtype" to "ordinary_transfer")).typeChanged)
        rules.validateForPosting(record())
        val missing = assertFailsWith<CashPolicyError> { rules.validateForPosting(record() + ("occurred_at" to null)) }
        assertEquals("incomplete_record", missing.code)
        assertEquals(listOf("occurred_at"), missing.fields)
        val mismatch = assertFailsWith<CashPolicyError> { rules.validateForPosting(record() + ("amount" to CashAmount.parse("-21"))) }
        assertEquals("component_total_mismatch", mismatch.code)
    }

    @Test
    fun sourceNoteUsesTheFirstNonblankOriginalFieldWithoutTruncation() {
        val longText = " original ".repeat(300)
        assertEquals(longText, rules.sourceNote(mapOf("备注" to "\u001c", "交易地点/附言" to longText, "摘要" to "other")))
        assertEquals("first", rules.sourceNote(mapOf("备注" to "first", "商品说明" to "second")))
        assertEquals("", rules.sourceNote(null))
    }
}
