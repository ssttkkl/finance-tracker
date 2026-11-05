package com.finance.tracker.backend

import kotlin.test.*
import kotlinx.serialization.json.*

class MutationPlanEffectsGoldenTest {
    @Test fun additionalEffectsMatchPythonOrderingAndDeduplication() {
        val cases = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/python-mutation-plan-effects.json"))
            .bufferedReader().use { it.readText() }).jsonArray
        assertEquals(4, cases.size)
        for ((index, raw) in cases.withIndex()) {
            val case = raw.jsonObject
            val request = MutationPlanRequest.parse(buildJsonObject {
                put("intent", "edit_record"); put("transaction_id", "42"); put("expected_revision", 1)
                put("relation_choices", case.getValue("request").jsonObject["relation_choices"] ?: JsonArray(emptyList()))
            })
            val baseline = (case["baseline"] as? JsonArray ?: JsonArray(emptyList())).map {
                val value = it.jsonObject
                Triple(value.getValue("transaction_id").jsonPrimitive.content,
                    value.getValue("issue_key").jsonPrimitive.content, value.getValue("code").jsonPrimitive.content)
            }
            assertEquals(case.getValue("expected"),
                MutationPlanEffects.additionalEffects(request, case.getValue("result").jsonObject, baseline), "case $index")
        }
    }
}
