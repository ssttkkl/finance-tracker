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
            assertEquals(expected.getValue("values"), wire(result.values), "case $index values: $input")
            assertEquals(expected.getValue("explanation"), wire(result.explanation), "case $index explanation: $input")
            assertEquals(before, wire(values), "case $index input values")
            assertEquals(beforePatch, wire(patch), "case $index patch")
            assertEquals(beforeExplanation, wire(explanation), "case $index source explanation")
        }
    }
}
