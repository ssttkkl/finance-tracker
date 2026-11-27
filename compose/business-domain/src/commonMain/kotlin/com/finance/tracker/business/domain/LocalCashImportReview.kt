package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.CashWriteSetDto
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import kotlinx.serialization.json.*
import kotlin.time.Instant

/** Frozen user decisions and their minimal previous targets. Sensitive recovery data, never a plan or diagnostic. */
class LocalCashImportReview private constructor(private val text: String) {
    fun encode(): String = text
    fun inputJson(): JsonObject = document().getValue("input").jsonObject
    fun input(): Map<String, Any?> = reviewInput(document().getValue("input").jsonObject)
    fun conclusion(): String? = document()["conclusion"]?.jsonPrimitive?.content

    fun previousPlan(): Map<String, Any?> {
        val document = document()
        @Suppress("UNCHECKED_CAST") val context = decodeLocalCashValue(document.getValue("context")) as Map<String, Any?>
        return context + ("entry_targets" to document.getValue("targets").jsonObject.mapValues { restoreTarget(it.value) })
    }

    /** File edits invalidate confirmation and entry edits, retaining the original relation history. */
    fun afterFilesChanged(obsoleteRefs: Set<String>): LocalCashImportReview {
        val original = document()
        val input = original.getValue("input").jsonObject
        val rows = input.getValue("row_choices").jsonArray.filter { choice ->
            val reference = choice.jsonObject.getValue("row_ref").jsonPrimitive.content
            obsoleteRefs.none { reference.startsWith("$it:") }
        }
        return decode(buildJsonObject {
            put("version", 1)
            put("input", JsonObject(input + mapOf("row_choices" to JsonArray(rows), "entry_choices" to JsonArray(emptyList()))))
            put("context", original.getValue("context"))
            put("targets", JsonObject(emptyMap()))
        }.toString())
    }

    fun recordIds(): Map<String, String> = (previousPlan().getValue("operations") as List<*>).associate { raw ->
        val operation = raw as Map<*, *>
        operation["occurrence_key"] as String to (operation["values"] as Map<*, *>)["record_id"] as String
    }

    private fun document(): JsonObject = Json.parseToJsonElement(text).jsonObject

    companion object {
        /** Keep only live typed targets and choice context; each input still freezes only its actual selections. */
        fun forPreview(plan: Map<String, Any?>): (JsonObject) -> LocalCashImportReview {
            @Suppress("UNCHECKED_CAST")
            val context = copyLocalCashValue(previewContext(plan)) as Map<String, Any?>
            return previewReview(context)
        }

        fun encodePreviewContext(plan: Map<String, Any?>): JsonElement = freezeTarget(previewContext(plan))

        fun restorePreviewReview(encoded: JsonElement): (JsonObject) -> LocalCashImportReview {
            @Suppress("UNCHECKED_CAST")
            val context = restoreTarget(encoded) as Map<String, Any?>
            return previewReview(context)
        }

        private fun previewContext(plan: Map<String, Any?>): Map<String, Any?> =
            localCashImportChoiceContextFromPlan(plan) + ("entry_targets" to (plan["entry_targets"] ?: emptyMap<String, Any?>()))

        private fun previewReview(context: Map<String, Any?>): (JsonObject) -> LocalCashImportReview =
            { input -> freeze(JsonObject(listOf("account_selections", "row_choices", "relation_choices", "entry_choices")
                .associateWith { input[it] ?: JsonArray(emptyList()) }), context) }

        /** Freeze the complete result at confirmation; recovery never substitutes facts from a later snapshot. */
        fun freeze(input: JsonObject, plan: Map<String, Any?>, source: LedgerSnapshotDto, changes: CashWriteSetDto,
            frozenPlan: JsonObject = freezeLocalCashImportPreviewPlan(plan)): LocalCashImportReview {
            val original = freeze(input, plan).document()
            val conclusion = localCashImportConclusion(source, frozenPlan, changes)
            val bindings = freezeLocalCashImportChoiceBindings(source, frozenPlan, changes, input, original.getValue("context").jsonObject)
            return LocalCashImportReview(JsonObject(original + mapOf("version" to JsonPrimitive(2), "conclusion" to JsonPrimitive(conclusion), "bindings" to bindings)).toString())
        }

        fun freeze(input: JsonObject, plan: Map<String, Any?>): LocalCashImportReview {
            val decisions = reviewInput(input)
            val oldTargets = plan["entry_targets"] as? Map<*, *> ?: emptyMap<String, Any?>()
            val targets = linkedMapOf<String, JsonElement>()
            for (choice in decisions.getValue("entry_choices") as List<*>) {
                val reference = (choice as Map<*, *>)["edit_ref"] as String
                val target = oldTargets[reference] as? Map<*, *> ?: throw CashImportPlanError("plan_changed")
                require(target["parent_ids"] is List<*>) { "cash_import_review_invalid" }
                targets[reference] = freezeTarget(target)
            }
            val document = buildJsonObject {
                put("version", 1); put("input", input)
                put("context", encodeLocalCashValue(localCashImportChoiceContextFromPlan(plan)))
                put("targets", JsonObject(targets))
            }
            return LocalCashImportReview(document.toString())
        }

        fun decode(text: String): LocalCashImportReview {
            val document = Json.parseToJsonElement(text).jsonObject
            val version = document["version"]
            val confirmed = version == JsonPrimitive(2)
            val fields = setOf("version", "input", "context", "targets") + if (confirmed) setOf("conclusion") +
                (if ("bindings" in document) setOf("bindings") else emptySet()) else emptySet()
            require(document.keys == fields && (confirmed || version == JsonPrimitive(1))) {
                "cash_import_review_invalid"
            }
            if (confirmed) {
                val value = document.getValue("conclusion") as? JsonPrimitive
                require(value != null && value.isString && value.content.length == 64 && value.content.all { it in '0'..'9' || it in 'a'..'f' }) {
                    "cash_import_review_invalid"
                }
            }
            val decisions = reviewInput(document.getValue("input").jsonObject)
            validateLocalCashImportChoiceContext(document.getValue("context"))
            document["bindings"]?.let { validateLocalCashImportChoiceBindings(it.jsonObject, document.getValue("input").jsonObject, document.getValue("context").jsonObject) }
            val targets = document.getValue("targets").jsonObject
            val references = (decisions.getValue("entry_choices") as List<*>).map { (it as Map<*, *>)["edit_ref"] }.toSet()
            require(targets.keys == references) { "cash_import_review_invalid" }
            for (target in targets.values) {
                val restored = restoreTarget(target) as? Map<*, *> ?: error("cash_import_review_invalid")
                require(restored["parent_ids"] is List<*>) { "cash_import_review_invalid" }
            }
            return LocalCashImportReview(text)
        }
    }
}

private fun reviewInput(input: JsonObject): Map<String, Any?> {
    require(input.keys == setOf("account_selections", "row_choices", "relation_choices", "entry_choices")) {
        "cash_import_review_invalid"
    }
    return prepareLocalCashImportInput(input["account_selections"], input["row_choices"], input["relation_choices"], input["entry_choices"])
}

// Tags are local to retained entry dependencies. Ordinary strings never acquire calculation types.
private fun freezeTarget(value: Any?): JsonElement {
    val (kind, body) = when (value) {
        is CashAmount -> "amount" to JsonPrimitive(value.toString())
        is CashDecimalLiteral -> "decimal" to JsonPrimitive(value.text)
        is Instant -> "instant" to JsonPrimitive(value.toString())
        is Map<*, *> -> "object" to JsonObject(value.entries.associate { (key, item) ->
            require(key is String) { "cash_import_review_invalid" }; key to freezeTarget(item)
        })
        is List<*> -> "array" to JsonArray(value.map(::freezeTarget))
        else -> "json" to encodeLocalCashValue(value)
    }
    return JsonArray(listOf(JsonPrimitive(kind), body))
}

private fun restoreTarget(raw: JsonElement): Any? {
    val pair = raw.jsonArray
    require(pair.size == 2) { "cash_import_review_invalid" }
    val body = pair[1]
    return when (pair[0].jsonPrimitive.content) {
        "amount" -> CashAmount.parse(body.jsonPrimitive.content.also { require(body.jsonPrimitive.isString) })
        "decimal" -> CashDecimalLiteral(body.jsonPrimitive.content.also { require(body.jsonPrimitive.isString) })
        "instant" -> Instant.parse(body.jsonPrimitive.content.also { require(body.jsonPrimitive.isString) })
        "object" -> body.jsonObject.mapValuesTo(linkedMapOf()) { restoreTarget(it.value) }
        "array" -> body.jsonArray.mapTo(arrayListOf(), ::restoreTarget)
        "json" -> { require(body is JsonPrimitive) { "cash_import_review_invalid" }; decodeLocalCashValue(body) }
        else -> error("cash_import_review_invalid")
    }
}
