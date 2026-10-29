package com.finance.tracker.backend

import java.math.BigInteger
import kotlinx.serialization.json.*

internal val mutableCashFields = setOf("counterparty", "counterparty_account", "counterparty_account_attrs", "note",
    "category_id", "record_type", "record_subtype", "source_payload")
internal val financialCashFields = setOf("amount", "currency", "occurred_at", "account_id", "account_name", "components", "cash_granularity")
internal val editableCashRecordFields = (mutableCashFields + financialCashFields) - "account_name"

internal data class CashSourceIssue(val id: Long, val revision: Long, val code: String, val fieldPath: String?,
    val pendingFields: JsonElement?, val pendingSourcePayload: JsonElement?, val origin: JsonElement?, val issueKey: String = "")
internal data class CashSourceResolution(val issueId: String, val expectedRevision: Long, val resolution: String)
internal data class PreparedCashSourceResolution(val choice: CashSourceResolution, val bodyDigest: String)
internal data class PreparedCashSourceResolutions(val patch: Map<String, Any?>, val resolutions: List<PreparedCashSourceResolution>)

internal fun pendingCashSourcePatch(issue: CashSourceIssue): JsonObject {
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
    return copyCashJsonContainers(JsonObject(patch)).jsonObject
}

internal fun cashSourceValue(value: JsonElement?): Any? = when (value) {
    null, JsonNull -> null
    is JsonObject -> value.mapValues { cashSourceValue(it.value) }
    is JsonArray -> value.map(::cashSourceValue)
    is JsonPrimitive -> when {
        value.isString -> value.content
        value.booleanOrNull != null -> value.boolean
        value.content.matches(Regex("-?[0-9]+")) -> BigInteger(value.content)
        else -> value.content.toDouble()
    }
}

internal fun cashSourceDigest(vararg values: JsonElement?): String {
    fun encode(value: JsonElement?): String = when (value) {
        null, JsonNull -> "null"
        is JsonObject -> value.entries.sortedWith { a, b -> cashRelationStringOrder.compare(a.key, b.key) }
            .joinToString(",", "{", "}") { encode(JsonPrimitive(it.key)) + ":" + encode(it.value) }
        is JsonArray -> value.joinToString(",", "[", "]", transform = ::encode)
        is JsonPrimitive -> when {
            value.isString -> canonicalCashJson(value, validateUtf8 = false)
            value.booleanOrNull != null -> value.content
            value.content.matches(Regex("-?[0-9]+")) -> BigInteger(value.content).toString()
            else -> value.content.toDouble().let { number -> when {
                number.isNaN() -> "NaN"
                number == Double.POSITIVE_INFINITY -> "Infinity"
                number == Double.NEGATIVE_INFINITY -> "-Infinity"
                else -> pythonFloatText(number)
            } }
        }
    }
    val encoded = values.joinToString(",", "[", "]", transform = ::encode)
    strictUtf8(encoded)
    return tokenDigest(encoded)
}

internal fun validateCashSourceComponentIds(current: List<Map<String, Any?>>, requested: List<Map<String, Any?>>) {
    val owned = current.mapNotNull { it["component_id"] }.toSet()
    val references = requested.mapNotNull { it["component_id"] }
    if (references.any { it !in owned } || references.size != references.distinct().size)
        throw CashPolicyError("component_reference_invalid", listOf("components"))
    val allowed = setOf("component_id", "account_id", "ordinal", "amount", "currency")
    if (requested.any { part -> part.keys.any { it !in allowed } }) throw CashPolicyError("invalid_field", listOf("components"))
}

internal fun prepareCashSourceResolutions(current: Map<String, Any?>, issues: List<CashSourceIssue>,
    choices: List<CashSourceResolution>, patch: Map<String, Any?>, protectedFields: Set<String>): PreparedCashSourceResolutions {
    val byId = issues.associateBy { it.id.toString() }
    val incoming = linkedMapOf<String, MutableList<Any?>>()
    val selected = mutableListOf<PreparedCashSourceResolution>()
    for (choice in choices) {
        val issue = byId[choice.issueId]
        if (issue == null || issue.revision != choice.expectedRevision) throw ReceiptConflict("issue_changed")
        val independent = issue.code == "source_identity_ambiguous" && current["state"] == "draft" && choice.resolution == "create_independent"
        if (!independent && (issue.code != "source_update_pending" || choice.resolution !in setOf("apply_source", "keep_existing")))
            throw CashPolicyError("issue_resolution_invalid", emptyList())
        selected += PreparedCashSourceResolution(choice, cashSourceDigest(JsonPrimitive(issue.code), issue.fieldPath?.let(::JsonPrimitive),
            issue.pendingFields, issue.pendingSourcePayload, issue.origin))
        if (independent || choice.resolution == "keep_existing") continue
        val source = pendingCashSourcePatch(issue).mapValues { cashSourceValue(it.value) }
        CashRecordPolicy.prepare(current, source, protectedFields)
        val normalized = source.mapValues { CashRecordPolicy.normalize(it.key, it.value) }
        if ("components" in normalized) {
            @Suppress("UNCHECKED_CAST") val components = normalized["components"] as List<Map<String, Any?>>
            @Suppress("UNCHECKED_CAST") val existing = current["components"] as List<Map<String, Any?>>
            validateCashSourceComponentIds(existing, components)
            if (components.any { "ordinal" !in it } || components.map { it["ordinal"] }.distinct().size != components.size)
                throw CashPolicyError("invalid_field", listOf("components"))
        }
        for ((field, value) in normalized) {
            val values = incoming.getOrPut(field) { mutableListOf() }
            if (values.none { CashRecordPolicy.valuesEqual(it, value) }) values += value
        }
    }
    if (incoming.any { (field, values) -> values.size > 1 && field !in patch })
        throw CashPolicyError("source_resolution_conflict", emptyList())
    return PreparedCashSourceResolutions(incoming.filterValues { it.size == 1 }.mapValues { it.value.single() }, selected)
}
