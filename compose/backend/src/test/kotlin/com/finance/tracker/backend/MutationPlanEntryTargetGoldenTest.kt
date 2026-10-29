package com.finance.tracker.backend

import kotlin.test.*
import kotlinx.serialization.json.*

class MutationPlanEntryTargetGoldenTest {
    @Test fun entryTargetDigestsMatchPython() {
        val cases = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/python-mutation-plan-entry-target.json"))
            .bufferedReader().use { it.readText() }).jsonArray
        assertEquals(3, cases.size)
        for ((index, raw) in cases.withIndex()) {
            val case = raw.jsonObject
            val input = case.getValue("input").jsonObject
            val parentRefs = (input["parent_ops"] as? JsonObject ?: JsonObject(emptyMap())).mapValues { (_, value) ->
                value.jsonArray.map { it.jsonPrimitive.content }
            }
            val edges = (input["edges"] as? JsonArray ?: JsonArray(emptyList())).map { item ->
                val edge = item.jsonObject
                PlanRelationEdge(
                    edge.getValue("id").jsonPrimitive.long, edge.getValue("kind").jsonPrimitive.content,
                    edge.getValue("primary_transaction_id").jsonPrimitive.content,
                    edge["secondary_transaction_id"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content,
                    edge["primary_component_id"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content,
                    edge["secondary_component_id"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content,
                    edge["primary_role"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content,
                    edge["secondary_role"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content,
                    edge.getValue("subtype").jsonPrimitive.content,
                    edge["applied_amount"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content,
                    edge.getValue("state").jsonPrimitive.content, edge.getValue("active").jsonPrimitive.boolean,
                )
            }
            val value = EntryTargetInput(
                input.getValue("parents").jsonArray.map { it.jsonPrimitive.content },
                input.getValue("rows").jsonArray.map { it.jsonArray },
                input.getValue("entries").jsonArray.map { it.jsonArray },
                input.getValue("relations").jsonArray.map { it.jsonArray },
                emptyList(), edges, parentRefs,
            )
            val (editRef, target) = MutationPlanEntryTarget.target(value)
            assertEquals(case.getValue("target"), target, "case $index")
            assertEquals(case.getValue("edit_ref").jsonPrimitive.content, editRef, "case $index")
        }
    }
}
