package com.finance.tracker.business.domain

import kotlinx.serialization.json.*
import kotlin.test.*

class LocalCashRecordPolicyTest {
    private val current = Json.parseToJsonElement("""{"state":"posted","record_origin":"import","amount":"-20.00","currency":"CNY","occurred_at":"2026-08-01T00:00:00Z","account_id":"41","record_type":"consumption","record_subtype":"not_applicable","cash_granularity":"atomic","components":[{"component_id":"501","account_id":"41","amount":"-20.00","currency":"CNY","ordinal":0}],"note":"原文","category_id":null,"source_payload":{"x":1}}""").jsonObject
    private fun patch(text: String, current: JsonObject = this.current, protected: Set<String> = emptySet()) =
        prepareLocalCashRecord(current, Json.parseToJsonElement(text), protected)

    @Test fun financialEqualityAndProtectedSourceFieldsUseProductionNormalization() {
        val result = patch("""{"amount":"-20.000","account_id":41,"occurred_at":"2026-08-01T08:00:00+08:00","note":"新文"}""")
        assertEquals(mapOf("note" to "新文"), result.values)
        assertEquals(CashAmount.parse("-20"), result.normalizedValues["amount"])
        val protected = patch("""{"note":"新文","category_id":"c"}""", protected = setOf("note"))
        assertEquals(mapOf("category_id" to "c"), protected.values)
        assertEquals(listOf("note"), protected.protectedFields)
        val failure = assertFailsWith<CashPolicyError> { patch("""{"note":"新文","amount":"-21"}""") }
        assertEquals("fixed_field_conflict", failure.code)
        assertEquals(listOf("amount"), failure.fields)
        assertEquals("原文", current.getValue("note").jsonPrimitive.content)
    }
    @Test fun numberTokensAreNotCoercedThroughJavascriptNumbers() {
        val draft = JsonObject(current + ("state" to JsonPrimitive("draft")))
        for (number in listOf("1.0", "1e0", "true"))
            assertEquals("invalid_field", assertFailsWith<CashPolicyError> { patch("""{"amount":$number}""", draft) }.code)
        assertEquals(CashAmount.parse("9007199254740993"), patch("""{"amount":9007199254740993}""", draft).values["amount"])
        assertEquals("9007199254740993", patch("""{"account_id":9007199254740993}""", draft).values["account_id"])
        assertTrue(patch("""{"source_payload":{"x":1.0}}""").values.isEmpty())
        assertFalse(patch("""{"source_payload":{"x":0.1}}""").values.isEmpty())
        for (ordinal in listOf("0.0", "true", "\"0\"", "2147483648"))
            assertEquals("invalid_field", assertFailsWith<CashPolicyError> {
                patch("""{"components":[{"ordinal":$ordinal}]}""", draft) }.code)
    }
    @Test fun payloadAndComponentsAreValidatedAndDetached() {
        val result = patch("""{"source_payload":{"nested":[{"x":"原文"}]}}""")
        @Suppress("UNCHECKED_CAST") val payload = result.values["source_payload"] as MutableMap<String, Any?>
        @Suppress("UNCHECKED_CAST") val nested = (payload["nested"] as List<*>).single() as MutableMap<String, Any?>
        nested["x"] = "已改"
        assertEquals(JsonPrimitive(1), current.getValue("source_payload").jsonObject["x"])
        val draft = JsonObject(current + ("state" to JsonPrimitive("draft")))
        val components = patch("""{"components":[{"account_id":41,"amount":"-20.000","currency":"CNY","ordinal":0}]}""", draft)
            .normalizedValues.getValue("components") as List<*>
        assertEquals(SourceJsonInteger(0), (components.single() as Map<*, *>)["ordinal"])
        assertEquals("invalid_field", assertFailsWith<CashPolicyError> { patch("""{"source_payload":[]}""") }.code)
    }
    @Test fun exactNumericEqualityAndLocalJsonKeepTheOriginalKinds() {
        val decimal = CashAmount.parse("0.1")
        assertFalse(LocalCashRecordValues.equal(decimal, Json.parseToJsonElement("0.1")))
        assertTrue(LocalCashRecordValues.equal(CashAmount.parse("0.5"), Json.parseToJsonElement("0.5")))
        assertTrue(LocalCashRecordValues.equal(SourceJsonInteger(1), Json.parseToJsonElement("1.0")))
        assertTrue(LocalCashRecordValues.equal(true, Json.parseToJsonElement("1.0")))
        assertFalse(LocalCashRecordValues.equal(Json.parseToJsonElement("9007199254740993"), Json.parseToJsonElement("9007199254740993.0")))
        val input = Json.parseToJsonElement("""{"integer":9007199254740993,"float":1.0,"small":1e-7}""")
        assertEquals("""{"float":1.0,"integer":9007199254740993,"small":1e-07}""", encodeLocalCashValue(decodeLocalCashValue(input)).toString())
        val bad = buildString { append(0xd800.toChar()) }
        assertFailsWith<CharacterCodingException> { encodeLocalCashValue(mapOf("key" to bad)) }
        assertFailsWith<CharacterCodingException> { encodeLocalCashValue(mapOf(bad to "value")) }
    }

    @Test fun payloadJsonLeavesUseTheSameBoundedInternalValuesAsCachedFacts() {
        val json = Json.parseToJsonElement("""{"s":"0001","b":true,"a":[null,{"x":false}],"n":9007199254740993,"i":1,"f":1.0,"rounded":9007199254740993.0,"zero":-0.0}""")
        val normalized = LocalCashRecordValues.normalize("source_payload", json) as Map<*, *>
        assertEquals("0001", normalized["s"]); assertEquals(true, normalized["b"])
        assertNull((normalized["a"] as List<*>).first())
        assertEquals(false, ((normalized["a"] as List<*>)[1] as Map<*, *>)["x"])
        assertEquals(JsonPrimitive(9007199254740993L), normalized["n"])
        assertEquals("1", normalized["i"].toString()); assertEquals("1.0", normalized["f"].toString())
        val wire = encodeLocalCashValue(normalized).jsonObject
        assertEquals("9007199254740992.0", wire.getValue("rounded").toString())
        assertEquals("-0.0", wire.getValue("zero").toString())
        assertNull(LocalCashRecordValues.normalize("source_payload", JsonNull))
        var deep: Any? = JsonNull
        repeat(64) { deep = mapOf("child" to deep) }
        LocalCashRecordValues.normalize("source_payload", deep)
        deep = mapOf("child" to deep)
        assertFailsWith<InvalidCashValue> { LocalCashRecordValues.normalize("source_payload", deep) }
        assertFailsWith<InvalidCashValue> { LocalCashRecordValues.normalize("source_payload", mapOf("x" to JsonPrimitive(Double.POSITIVE_INFINITY))) }
    }

}
