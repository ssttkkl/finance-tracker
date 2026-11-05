package com.finance.tracker.backend

import kotlinx.serialization.json.*

/** 本次明确请求之外的安全影响摘要；与 Python `additional_effects` 同一顺序与去重。 */
internal object MutationPlanEffects {
    fun additionalEffects(request: MutationPlanRequest, result: JsonObject,
        baselineIssues: List<Triple<String, String, String>> = emptyList()): JsonArray {
        val effects = linkedSetOf<Triple<String, String, String>>()
        fun add(kind: String, reference: Any?, field: String? = null) {
            effects += Triple(kind, reference.toString(), field ?: "")
        }
        val choices = request.relationChoices.associateBy { it["suggestion_id"]!!.jsonPrimitive.content }
        val explicitRemoved = mutableSetOf<String>()
        for (raw in result["relation_groups"]?.jsonArray ?: JsonArray(emptyList())) {
            val group = raw.jsonObject
            val reference = group.getValue("suggestion_id").jsonPrimitive.content
            val choice = choices[reference]
            val action = group.getValue("action").jsonPrimitive.content
            if (action == "skip") {
                add(if (choice != null) "choice_changed" else "relation_changed", reference)
            } else if (choice != null) {
                if (action != choice.getValue("action").jsonPrimitive.content) add("choice_changed", reference)
                else if (choice.getValue("action").jsonPrimitive.content == "remove")
                    for (edge in group["expected_record_edges"]?.jsonArray ?: JsonArray(emptyList()))
                        edge.jsonObject["id"]?.takeIf { it != JsonNull }?.let { explicitRemoved += it.jsonPrimitive.content }
            } else {
                val origin = group["origin"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull
                val changed = group["changed"]?.takeIf { it != JsonNull }?.jsonPrimitive?.booleanOrNull ?: (origin != "current")
                if (changed && action != "keep") add("relation_changed", reference)
            }
        }
        val typeEffects = result["type_effects"] as? JsonObject ?: JsonObject(emptyMap())
        for (reference in typeEffects["removed_relation_ids"]?.jsonArray ?: JsonArray(emptyList()))
            if (reference.jsonPrimitive.content !in explicitRemoved) add("relation_changed", reference.jsonPrimitive.content)
        for (reference in typeEffects["removed_funding_ids"]?.jsonArray ?: JsonArray(emptyList()))
            add("funding_relation_removed", reference.jsonPrimitive.content)
        for (item in result["invalidated_choices"]?.jsonArray ?: JsonArray(emptyList()))
            add("choice_changed", item.jsonObject.getValue("reference").jsonPrimitive.content)
        for (raw in result["operations"]?.jsonArray ?: JsonArray(emptyList())) {
            val operation = raw.jsonObject
            val reference = operation.getValue("transaction_id").jsonPrimitive.content
            for (diff in operation["field_diffs"]?.jsonArray ?: JsonArray(emptyList())) {
                val value = diff.jsonObject
                if (value["incoming"] != value["effective"]) add("field_value_changed", reference,
                    value.getValue("field").jsonPrimitive.content)
            }
            val previous = (operation["expected_issues"]?.jsonArray ?: JsonArray(emptyList())).map {
                it.jsonObject.getValue("code").jsonPrimitive.content to
                    it.jsonObject["field_path"]?.takeIf { path -> path != JsonNull }?.jsonPrimitive?.contentOrNull
            }.toSet()
            for (issue in operation["issues"]?.jsonArray ?: JsonArray(emptyList())) {
                val value = issue.jsonObject
                val code = value.getValue("code").jsonPrimitive.content
                val path = value["field_path"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull
                if (code to path !in previous) add("new_attention", reference, path)
            }
        }
        val baseline = baselineIssues.map { Triple(it.first, it.second, it.third) }.toSet()
        for (raw in result["relation_failures"]?.jsonArray ?: JsonArray(emptyList())) {
            val failure = raw.jsonObject
            if (failure["suggestion_id"]?.takeIf { it != JsonNull } != null) continue
            val parents = (failure["parent_ids"]?.jsonArray ?: JsonArray(emptyList()))
                .map { it.jsonPrimitive.content.toBigInteger() }.distinct().sorted()
            val reference = cashSourceDigest(JsonPrimitive("discovery"),
                JsonArray(parents.map { JsonPrimitive(it.toLong()) }))
            val issueKey = "relation:" + cashSourceDigest(JsonPrimitive(reference))
            val code = failure.getValue("code").jsonPrimitive.content
            for (parent in parents) if (Triple(parent.toString(), issueKey, code) !in baseline)
                effects += Triple("new_attention", parent.toString(), "")
        }
        val sorted = effects.sortedWith { a, b ->
            cashRelationStringOrder.compare(a.first, b.first).takeIf { it != 0 }
                ?: cashRelationStringOrder.compare(a.second, b.second).takeIf { it != 0 }
                ?: cashRelationStringOrder.compare(a.third, b.third)
        }
        return JsonArray(sorted.map { (kind, reference, field) -> buildJsonObject {
            put("kind", kind); put("reference", reference)
            if (field.isNotEmpty()) put("field", field)
        } })
    }
}
