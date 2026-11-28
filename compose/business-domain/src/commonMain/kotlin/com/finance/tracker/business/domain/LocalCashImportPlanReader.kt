package com.finance.tracker.business.domain

import kotlinx.serialization.json.*

/** Independent read-only views of a frozen calculation; lifecycle metadata must come from its actual owner. */
class LocalCashImportPlanReader(plan: JsonObject, stored: Boolean, private val monthly: (List<*>, String) -> Map<String, Any?>) {
    constructor(plan: JsonObject, monthly: (List<*>, String) -> Map<String, Any?>) : this(plan, false, monthly)
    @Suppress("UNCHECKED_CAST")
    private val plan = if (stored) plan.mapValues { (_, value) ->
        when {
            value is JsonObject && value.isStoredReviewMap() -> {
                val values = value
                val keys = values.keys.toList()
                lazyReviewMap(keys) { decode(values.getValue(keys[it])) }
            }
            value is JsonArray && value.isStoredReviewList() -> {
                val values = value
                object : AbstractList<Any?>() {
                    override val size get() = values.size
                    override fun get(index: Int): Any? = decode(values[index])
                }
            }
            else -> decode(value)
        }
    } else decode(plan) as Map<String, Any?>
    private val rules = CashImportPlanViewRules(LocalCashRecordValues, ::localCashPreviewInteger,
        { CashImportPreviewInteger.parse(it.toString()) }, ::localVirtualComponent,
        { it is String || it is Number || it is Boolean || it is JsonPrimitive || it is CashImportPreviewInteger }, monthly)

    fun validateReadable() = rules.validateReadable(plan)
    fun monthlySummaries(timezoneName: String = "UTC"): JsonObject =
        wire(monthly(plan["monthly_source"] as? List<*> ?: emptyList<Any?>(), timezoneName)).jsonObject
    fun view(timezoneName: String = "UTC"): JsonObject = wire(rules.view(plan, timezoneName)).jsonObject
    fun page(kind: String, cursor: String? = null, startIndex: Int? = null, limit: Int = 50,
        confirmationStatus: String? = null, indexed: Boolean = false): JsonObject =
        wire(rules.page(plan, kind, cursor, startIndex, limit, confirmationStatus, indexed)).jsonObject

    internal fun resolvedDetail(itemId: String): JsonObject {
        val details = plan["item_details"] as? Map<*, *> ?: throw CashImportPlanError("plan_build_failed")
        val detail = details[itemId] ?: throw CashImportPlanError("plan_item_not_found")
        return wire(rules.resolveDetail(plan, detail)).jsonObject
    }

    fun detail(itemId: String): JsonObject {
        val details = plan["item_details"] as? Map<*, *> ?: throw CashImportPlanError("plan_build_failed")
        val detail = details[itemId] ?: throw CashImportPlanError("plan_item_not_found")
        return wire(rules.publicDetail(rules.resolveDetail(plan, detail)) + ("preview_item_id" to itemId)).jsonObject
    }

    private fun decode(value: JsonElement): Any? = when (value) {
        JsonNull -> null
        is JsonObject -> value.mapValues { (_, item) -> decode(item) }
        is JsonArray -> value.map(::decode)
        is JsonPrimitive -> when {
            value.isString -> value.content
            value.booleanOrNull != null -> value.boolean
            else -> value
        }
    }

    private fun wire(value: Any?): JsonElement = when (value) {
        is JsonElement -> value
        is Int -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { (key, item) -> (key as String) to wire(item) })
        is List<*> -> JsonArray(value.map(::wire))
        else -> encodeLocalCashValue(value)
    }
}

private val planViewDigitStarts = pythonIntegerDigitStarts
private val planViewNondecimalDigits = pythonNondecimalDigits
private val planViewVirtualComponentStart = CashImportPreviewInteger.parse("9223372036854775808")

private fun localVirtualComponent(value: Any?): Boolean {
    if (value == null) return false
    val integral = localCashIntegerText(value)
    val raw = if (value is JsonPrimitive) pythonJsonText(value) else value.toString()
    if (value !is String && integral != null && integral.removePrefix("-").length > 4300)
        throw IllegalArgumentException("Exceeds the limit (4300 digits) for integer string conversion; use sys.set_int_max_str_digits() to increase the limit")
    val points = raw.pythonCodePoints()
    if (points.isEmpty() || points.any { code -> code !in planViewNondecimalDigits && planViewDigitStarts.none { code in it until it + 10 } }) return false
    val digits = buildString {
        for (point in points) {
            val start = planViewDigitStarts.firstOrNull { point in it until it + 10 }
            if (start == null) {
                val representation = pythonJsonStringRepr(raw).pythonCodePoints().take(200)
                val bounded = buildString { for (code in representation) appendPythonCodePoint(code) }
                throw IllegalArgumentException("invalid literal for int() with base 10: $bounded")
            }
            append(('0'.code + point - start).toChar())
        }
    }
    if (digits.length > 4300) throw IllegalArgumentException("Exceeds the limit (4300 digits) for integer string conversion: value has ${digits.length} digits; use sys.set_int_max_str_digits() to increase the limit")
    return CashImportPreviewInteger.parse(digits) >= planViewVirtualComponentStart
}
