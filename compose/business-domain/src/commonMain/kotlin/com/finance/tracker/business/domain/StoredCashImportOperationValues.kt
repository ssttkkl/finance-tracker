package com.finance.tracker.business.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant

internal class StoredCashImportOperationValues(workspace: CashImportReviewWorkspace) {
    private var writer: ReviewBlockWriter? = ReviewBlockWriter(workspace)
    private lateinit var records: List<JsonElement>

    fun retain(operation: MutableMap<String, Any?>) {
        val values = encodeFields(operation.getValue("values") as Map<*, *>)
        val current = (operation["current"] as? Map<*, *>)?.let(::encodeFields)
        val index = checkNotNull(writer).append(JsonArray(listOf(values, current ?: JsonNull)))
        operation["values"] = Record(index, 0)
        if (current != null) operation["current"] = Record(index, 1)
    }

    fun finish() {
        records = checkNotNull(writer).finish()
        writer = null
    }

    internal inner class Record(private val index: Int, private val field: Int) :
        CashReviewRecordMap({ (records[index] as JsonArray)[field].jsonObject }) {
        fun project(included: Set<String>): MutableMap<String, Any?> = linkedMapOf<String, Any?>().apply {
            for ((name, value) in fields()) {
                if (name !in included) continue
                put(name, if (name == "source_payload" && value is JsonObject)
                    CashReviewRecordMap { fields().getValue(name).jsonObject }
                else decodeValue(value))
            }
        }
    }
}

internal open class CashReviewRecordMap(private val read: () -> JsonObject) : AbstractMap<String, Any?>() {
    protected fun fields() = read()
    override val size get() = fields().size
    override val keys get() = fields().keys
    override fun containsKey(key: String) = fields().containsKey(key)
    override fun get(key: String): Any? = fields()[key]?.let(::decodeValue)
    override val entries: Set<Map.Entry<String, Any?>> get() = fields().mapValues { decodeValue(it.value) }.entries
}

private fun encodeFields(value: Map<*, *>) = JsonObject(buildMap(value.size) {
    for ((key, item) in value) {
        val name = key as? String ?: throw InvalidCashValue()
        name.encodeToByteArray(throwOnInvalidSequence = true)
        put(name, encodeValue(item))
    }
})

private fun encodeValue(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is String -> JsonPrimitive(value.also { it.encodeToByteArray(throwOnInvalidSequence = true) })
    is Boolean -> JsonPrimitive(value)
    is SourceJsonInteger -> tagged("integer", JsonPrimitive(value.value))
    is CashImportPreviewInteger -> tagged("preview", JsonPrimitive(value.toString()))
    is Long -> tagged("long", JsonPrimitive(value.toString()))
    is Number -> tagged("number", JsonPrimitive(pythonFloatText(value.toDouble())))
    is Instant -> tagged("instant", JsonPrimitive(value.toString()))
    is CashAmount -> tagged("amount", JsonPrimitive(value.toString()))
    is CashDecimalLiteral -> tagged("decimal", JsonPrimitive(value.text))
    is JsonElement -> tagged("json", value.also(::validateJsonStrings))
    is Map<*, *> -> encodeFields(value)
    is List<*> -> tagged("list", JsonArray(value.map(::encodeValue)))
    else -> throw InvalidCashValue()
}

private fun validateJsonStrings(value: JsonElement) {
    when (value) {
        is JsonObject -> for ((key, item) in value) {
            key.encodeToByteArray(throwOnInvalidSequence = true)
            validateJsonStrings(item)
        }
        is JsonArray -> for (item in value) validateJsonStrings(item)
        is JsonPrimitive -> if (value.isString) {
            value.content.encodeToByteArray(throwOnInvalidSequence = true)
        }
    }
}

private fun tagged(type: String, value: JsonElement) = JsonArray(listOf(JsonPrimitive(type), value))

private fun decodeValue(value: JsonElement): Any? = when (value) {
    JsonNull -> null
    is JsonObject -> value.mapValues { decodeValue(it.value) }
    is JsonPrimitive -> if (value.isString) value.content else value.boolean
    is JsonArray -> {
        check(value.size == 2) { "cash_review_block_incomplete" }
        val body = value[1]
        when (value[0].jsonPrimitive.content) {
            "integer" -> SourceJsonInteger(body.jsonPrimitive.int)
            "preview" -> CashImportPreviewInteger.parse(body.jsonPrimitive.content)
            "long" -> body.jsonPrimitive.content.toLong()
            "number" -> body.jsonPrimitive.content.toDouble()
            "instant" -> Instant.parse(body.jsonPrimitive.content)
            "amount" -> CashAmount.parse(body.jsonPrimitive.content)
            "decimal" -> CashDecimalLiteral(body.jsonPrimitive.content)
            "json" -> body
            "list" -> (body as JsonArray).map(::decodeValue)
            else -> error("cash_review_block_incomplete")
        }
    }
}
