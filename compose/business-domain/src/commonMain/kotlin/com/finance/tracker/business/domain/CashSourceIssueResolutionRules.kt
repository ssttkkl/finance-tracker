package com.finance.tracker.business.domain

import kotlinx.serialization.json.*

data class CashSourceIssue(val id: Long, val revision: Long, val code: String, val fieldPath: String?,
    val pendingFields: JsonElement?, val pendingSourcePayload: JsonElement?, val origin: JsonElement?, val issueKey: String = "")
data class CashSourceResolution(val issueId: String, val expectedRevision: Long, val resolution: String)
data class PreparedCashSourceResolution(val choice: CashSourceResolution, val bodyDigest: String)
data class PreparedCashSourceResolutions(val patch: Map<String, Any?>, val resolutions: List<PreparedCashSourceResolution>)

class CashSourceIssueChanged : RuntimeException("issue_changed") {
    val code: String = "issue_changed"
}

/** Prepare selected current-source patches; ownership, identity moves and final write effects stay with the caller. */
class CashSourceIssueResolutionRules(
    private val recordValues: CashRecordValues,
    private val decode: (JsonElement?) -> Any?,
    private val digest: (List<JsonElement?>) -> String,
    private val copyJson: (JsonElement) -> JsonElement,
) {
    fun pendingPatch(issue: CashSourceIssue): JsonObject {
        fun invalid(): Nothing = throw CashPolicyError("issue_resolution_invalid", emptyList())
        val fields = if (issue.pendingFields == null || issue.pendingFields == JsonNull) JsonObject(emptyMap())
            else issue.pendingFields as? JsonObject ?: invalid()
        val privateFields = setOf("_source_payload_present", "_source_explanation")
        if (fields.keys.any { it !in editableCashRecordFields && it !in privateFields }) invalid()
        if ("_source_payload_present" in fields && (fields["_source_payload_present"] !is JsonPrimitive ||
                fields["_source_payload_present"]!!.jsonPrimitive.isString || fields["_source_payload_present"]!!.jsonPrimitive.booleanOrNull == null)) invalid()
        val explanation = fields["_source_explanation"]
        if (explanation != null && explanation != JsonNull && explanation !is JsonObject) invalid()
        val payload = issue.pendingSourcePayload ?: JsonNull
        val present = fields["_source_payload_present"]?.jsonPrimitive?.boolean ?: (payload != JsonNull)
        if (!present && payload != JsonNull) invalid()
        val patch = fields.filterKeys { it in editableCashRecordFields }.toMutableMap()
        if (present) patch["source_payload"] = payload
        if (patch.isEmpty()) invalid()
        return copyJson(JsonObject(patch)).jsonObject
    }

    fun validateComponentIds(current: List<Map<String, Any?>>, requested: List<Map<String, Any?>>) {
        val owned = current.mapNotNull { it["component_id"] }.toSet()
        val references = requested.mapNotNull { it["component_id"] }
        if (references.any { it !in owned } || references.size != references.distinct().size)
            throw CashPolicyError("component_reference_invalid", listOf("components"))
        val allowed = setOf("component_id", "account_id", "ordinal", "amount", "currency")
        if (requested.any { part -> part.keys.any { it !in allowed } }) throw CashPolicyError("invalid_field", listOf("components"))
    }

    fun prepare(current: Map<String, Any?>, issues: List<CashSourceIssue>,
        choices: List<CashSourceResolution>, patch: Map<String, Any?>, protectedFields: Set<String>): PreparedCashSourceResolutions {
        val byId = issues.associateBy { it.id.toString() }
        val incoming = linkedMapOf<String, MutableList<Any?>>()
        val selected = mutableListOf<PreparedCashSourceResolution>()
        for (choice in choices) {
            val issue = byId[choice.issueId]
            if (issue == null || issue.revision != choice.expectedRevision) throw CashSourceIssueChanged()
            val independent = issue.code == "source_identity_ambiguous" && current["state"] == "draft" && choice.resolution == "create_independent"
            if (!independent && (issue.code != "source_update_pending" || choice.resolution !in setOf("apply_source", "keep_existing")))
                throw CashPolicyError("issue_resolution_invalid", emptyList())
            selected += PreparedCashSourceResolution(choice, digest(listOf(JsonPrimitive(issue.code), issue.fieldPath?.let(::JsonPrimitive),
                issue.pendingFields, issue.pendingSourcePayload, issue.origin)))
            if (independent || choice.resolution == "keep_existing") continue
            val source = pendingPatch(issue).mapValues { decode(it.value) }
            recordValues.prepare(current, source, protectedFields)
            val normalized = source.mapValues { recordValues.normalize(it.key, it.value) }
            if ("components" in normalized) {
                @Suppress("UNCHECKED_CAST") val components = normalized["components"] as List<Map<String, Any?>>
                @Suppress("UNCHECKED_CAST") val existing = current["components"] as List<Map<String, Any?>>
                validateComponentIds(existing, components)
                if (components.any { "ordinal" !in it } || components.map { it["ordinal"] }.distinct().size != components.size)
                    throw CashPolicyError("invalid_field", listOf("components"))
            }
            for ((field, value) in normalized) {
                val values = incoming.getOrPut(field) { mutableListOf() }
                if (values.none { recordValues.valuesEqual(it, value) }) values += value
            }
        }
        if (incoming.any { (field, values) -> values.size > 1 && field !in patch })
            throw CashPolicyError("source_resolution_conflict", emptyList())
        return PreparedCashSourceResolutions(incoming.filterValues { it.size == 1 }.mapValues { it.value.single() }, selected)
    }
}
