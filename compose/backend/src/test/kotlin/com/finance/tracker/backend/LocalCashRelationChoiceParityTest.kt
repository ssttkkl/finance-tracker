package com.finance.tracker.backend

import com.finance.tracker.business.domain.applyLocalCashImportRelationChoices
import java.math.BigInteger
import kotlinx.serialization.json.*
import kotlin.test.*

class LocalCashRelationChoiceParityTest {
    private fun native(groups: JsonArray, choices: JsonArray, previous: JsonObject): JsonElement {
        @Suppress("UNCHECKED_CAST") val mutable = cashSourceValue(groups) as List<MutableMap<String, Any?>>
        @Suppress("UNCHECKED_CAST") val input = cashSourceValue(choices) as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST") val old = cashSourceValue(previous) as Map<String, Any?>
        val result = CashImportRelationDecisions.applyChoices(mutable, input, old)
        return sourceJson(mapOf("groups" to mutable, "invalidated" to result.invalidated, "sources" to result.sources,
            "generations" to result.generations))
    }
    @Test fun generationParsingMatchesTheNativeIntegerGateAcrossEveryBmpCodeUnit() {
        val empty = JsonArray(emptyList())
        for (code in 0..0xffff) {
            val raw = code.toChar().toString()
            val previous = buildJsonObject { put("relation_choice_generations", buildJsonObject { put("source", raw) }) }
            val number = raw.toBigIntegerOrNull()
            if (number == null) assertFailsWith<NumberFormatException>("U+${code.toString(16)}") {
                applyLocalCashImportRelationChoices(empty, empty, previous)
            } else {
                assertEquals(native(empty, empty, previous), applyLocalCashImportRelationChoices(empty, empty, previous), "U+${code.toString(16)}")
            }
        }
    }
    @Test fun exactGenerationHashingAndCarryMatchNativeWithoutChangingPreviousInput() {
        val cases = Json.parseToJsonElement(javaClass.getResourceAsStream("/python-cash-import-relation-choices.json")!!
            .bufferedReader().use { it.readText() }).jsonArray.map { it.jsonObject }
        val initial = cases.single { it.getValue("name").jsonPrimitive.content == "defaults" }
        val groups = initial.getValue("groups").jsonArray
        val source = groups.first().jsonObject.getValue("suggestion_id").jsonPrimitive.content
        for (raw in listOf("0", "+٠٠٢", "-0", "-2", "9007199254740993", Long.MAX_VALUE.toString(),
            BigInteger.ONE.shiftLeft(80).toString(), "9".repeat(2000))) {
            val previous = buildJsonObject { put("relation_choice_generations", buildJsonObject { put(source, raw) }) }
            val before = previous.toString()
            assertEquals(native(groups, JsonArray(emptyList()), previous),
                applyLocalCashImportRelationChoices(groups, JsonArray(emptyList()), previous))
            assertEquals(before, previous.toString())
        }
        val disappeared = cases.single { it.getValue("name").jsonPrimitive.content == "candidate_disappeared" }
        val old = disappeared.getValue("previous").jsonObject
        val oldGroup = old.getValue("relation_groups").jsonArray.first().jsonObject
        val huge = "9".repeat(2000)
        val changed = JsonObject(old + ("relation_groups" to JsonArray(listOf(JsonObject(oldGroup + ("choice_generation" to JsonPrimitive(huge)))))))
        val result = applyLocalCashImportRelationChoices(disappeared.getValue("groups").jsonArray,
            disappeared.getValue("choices").jsonArray, changed)
        assertEquals(native(disappeared.getValue("groups").jsonArray, disappeared.getValue("choices").jsonArray, changed), result)
        assertEquals((BigInteger(huge) + BigInteger.ONE).toString(), result.getValue("generations").jsonObject.values.single().jsonPrimitive.content)
    }
}
