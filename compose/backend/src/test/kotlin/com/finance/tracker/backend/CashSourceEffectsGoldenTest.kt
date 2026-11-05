package com.finance.tracker.backend

import kotlin.test.*
import kotlinx.serialization.json.*

class CashSourceEffectsGoldenTest {
    private fun wire(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is CashAmount -> JsonPrimitive(value.fixedTransferText())
        is Map<*, *> -> JsonObject(value.entries.associate { it.key as String to wire(it.value) })
        is List<*> -> JsonArray(value.map(::wire))
        else -> error("unexpected source effect value")
    }

    private fun assertWireEquals(expected: JsonElement, actual: JsonElement, context: String) {
        when (expected) {
            is JsonObject -> {
                assertIs<JsonObject>(actual, context)
                assertEquals(expected.keys, actual.keys, context)
                expected.forEach { (key, value) -> assertWireEquals(value, actual.getValue(key), "$context.$key") }
            }
            is JsonArray -> {
                assertIs<JsonArray>(actual, context)
                assertEquals(expected.size, actual.size, context)
                expected.forEachIndexed { index, value -> assertWireEquals(value, actual[index], "$context[$index]") }
            }
            is JsonPrimitive -> {
                assertIs<JsonPrimitive>(actual, context)
                if (expected == JsonNull || expected.isString || expected.booleanOrNull != null) {
                    assertEquals(expected, actual, context)
                } else {
                    assertFalse(actual.isString, context)
                    assertNull(actual.booleanOrNull, context)
                    val integer = expected.content.matches(Regex("-?[0-9]+"))
                    assertEquals(integer, actual.content.matches(Regex("-?[0-9]+")), context)
                    if (integer) assertEquals(expected.content.toBigInteger(), actual.content.toBigInteger(), context)
                    else assertEquals(expected.double.toRawBits(), actual.double.toRawBits(), context)
                }
            }
        }
    }

    @Test fun completeSourceEffectsMatchPythonWithoutMutatingInputs() {
        val cases = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/python-cash-source-effects.json"))
            .bufferedReader().use { it.readText() }).jsonArray
        for ((index, raw) in cases.withIndex()) {
            val expected = raw.jsonObject
            val input = expected.getValue("input").jsonObject
            val values = input.getValue("values").jsonObject.mapValues { cashSourceValue(it.value) }.toMutableMap()
            (values["amount"] as? String)?.let { values["amount"] = CashAmount.parse(it) }
            val patch = input.getValue("patch").jsonObject.mapValues { cashSourceValue(it.value) }
            val explanation = cashSourceValue(input.getValue("explanation"))
            val before = wire(values)
            val beforePatch = wire(patch)
            val beforeExplanation = wire(explanation)
            val result = prepareCashSourceEffects(values, patch, input.getValue("source").jsonPrimitive.content,
                explanation, input.getValue("protected").jsonArray.map { it.jsonPrimitive.content }.toSet())
            assertWireEquals(expected.getValue("values"), wire(result.values), "case $index values: $input")
            assertWireEquals(expected.getValue("explanation"), wire(result.explanation), "case $index explanation: $input")
            assertWireEquals(before, wire(values), "case $index input values")
            assertWireEquals(beforePatch, wire(patch), "case $index patch")
            assertWireEquals(beforeExplanation, wire(explanation), "case $index source explanation")
        }
    }
}
