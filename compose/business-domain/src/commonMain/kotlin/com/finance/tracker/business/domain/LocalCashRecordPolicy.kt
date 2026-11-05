package com.finance.tracker.business.domain

import kotlinx.serialization.json.*
import kotlin.time.Instant

/** Apply the shared record policy to JSON-origin records without erasing integer/float distinctions. */
fun prepareLocalCashRecord(current: JsonObject, patch: JsonElement?, protectedFields: Set<String> = emptySet()): CashPolicyUpdate {
    @Suppress("UNCHECKED_CAST") val fields = decodeLocalCashValue(current) as Map<String, Any?>
    return LocalCashRecordValues.prepare(fields, decodeLocalCashValue(patch), protectedFields)
}

/** Production values for normalized documents and decoded cached facts; not arbitrary JVM object graphs. */
internal object LocalCashRecordValues : CashRecordValues {
    private val rules = CashRecordPolicyRules(::normalize, ::equal)
    override fun valuesEqual(left: Any?, right: Any?): Boolean = equal(left, right)
    override fun copyValue(value: Any?): Any? = copyLocalCashValue(value)
    private fun invalid(): Nothing = throw InvalidCashValue()
    override fun prepare(current: Map<*, *>, patch: Any?, protected: Set<String>) = rules.prepare(current, patch, protected)
    fun validateForPosting(record: Map<*, *>) = rules.validateForPosting(record)
    override fun validateConsistency(record: Map<*, *>) = rules.validateConsistency(record)

    private fun amount(value: Any?): CashAmount? = when (value) {
        null -> null
        is CashAmount -> value
        is String -> CashAmount.parseOrNull(value) ?: invalid()
        else -> localCashIntegerText(value)?.let { CashAmount.parseOrNull(it) } ?: invalid()
    }
    private fun reference(value: Any?): String {
        if (value is String) return value.takeIf { it.isNotEmpty() } ?: invalid()
        val integer = localCashIntegerText(value) ?: invalid()
        return integer.takeIf { !it.startsWith('-') && it != "0" } ?: invalid()
    }
    private fun currency(value: Any?): String = (value as? String)
        ?.takeIf { it.length == 3 && it.all { char -> char in 'A'..'Z' } } ?: invalid()
    private fun timestamp(value: Any): Instant = when (value) {
        is String -> try { parsePythonCashTimestamp(value) } catch (_: IllegalArgumentException) { invalid() }
        is Instant -> {
            if (value.epochSeconds < -62135596800L || value.epochSeconds >= 253402300800L) invalid()
            Instant.fromEpochSeconds(value.epochSeconds, value.nanosecondsOfSecond / 1000 * 1000L)
        }
        else -> invalid()
    }.also { if (!cashOccurrenceTimeInRange(it.epochSeconds)) invalid() }
    private fun payload(value: Any?, depth: Int = 0) {
        if (depth > 64) invalid()
        when (value) {
            null, is String, is Boolean, is SourceJsonInteger, is Long -> Unit
            is JsonPrimitive -> if (!value.isString && value.booleanOrNull == null && !localCashInteger.matches(value.content) && !value.double.isFinite()) invalid()
            is Number -> if (!value.toDouble().isFinite()) invalid()
            is Map<*, *> -> {
                if (value.keys.any { it !is String }) invalid()
                value.values.forEach { payload(it, depth + 1) }
            }
            is List<*> -> value.forEach { payload(it, depth + 1) }
            else -> invalid()
        }
    }
    private fun components(value: Any?): List<Map<String, Any?>> {
        if (value !is List<*>) invalid()
        return value.map { raw ->
            if (raw !is Map<*, *> || raw.keys.any { it !is String }) invalid()
            @Suppress("UNCHECKED_CAST") val item = copyLocalCashValue(raw) as MutableMap<String, Any?>
            for (field in listOf("component_id", "account_id")) if (item[field] != null) item[field] = reference(item[field])
            if (item["amount"] != null) item["amount"] = amount(item["amount"])
            if ("ordinal" in item) {
                val ordinal = localCashIntegerText(item["ordinal"])?.toLongOrNull()?.takeIf { it in 0..Int.MAX_VALUE.toLong() } ?: invalid()
                item["ordinal"] = SourceJsonInteger(ordinal.toInt())
            }
            if (item["currency"] != null) item["currency"] = currency(item["currency"])
            item
        }
    }
    override fun normalize(field: String, value: Any?): Any? = when (field) {
        "counterparty", "counterparty_account", "note" -> value as? String ?: invalid()
        "amount" -> amount(value)
        "occurred_at" -> if (value == null) null else timestamp(value)
        "account_id" -> if (value == null) null else reference(value)
        "cash_granularity" -> (value as? String)?.takeIf { it == "atomic" || it == "aggregate" } ?: invalid()
        "currency" -> if (value == null) null else currency(value)
        "components" -> components(value)
        "counterparty_account_attrs" -> if (value is List<*> && value.all { it is String }) value.toMutableList() else invalid()
        "source_payload" -> {
            if (value != null && value !is Map<*, *>) invalid()
            payload(value); copyLocalCashValue(value)
        }
        "record_type", "record_subtype", "category_id" -> if (value == null || value is String) value else invalid()
        else -> copyLocalCashValue(value)
    }
    private fun numeric(value: Any?): LedgerArithmeticValue? = when (value) {
        is CashAmount -> LedgerArithmeticValue.fromCanonical(value.numericText())
        is Boolean -> LedgerArithmeticValue.fromCanonical(if (value) "1" else "0")
        else -> localCashIntegerText(value)?.let(LedgerArithmeticValue::fromCanonical) ?: run {
            val floating = when (value) {
                is JsonPrimitive -> if (value != JsonNull && !value.isString && value.booleanOrNull == null) value.double else null
                is Number -> value.toDouble()
                else -> null
            }
            floating?.takeIf { it.isFinite() }?.let { number ->
                val parts = exactBinary64Decimal(number)
                LedgerArithmeticValue.fromCanonical((if (parts.negative) "-" else "") + parts.coefficient + "E" + parts.exponent)
            }
        }
    }
    private fun nan(value: Any?): Boolean = when (value) {
        is Number -> value.toDouble().isNaN()
        is JsonPrimitive -> !value.isString && value.booleanOrNull == null && value.doubleOrNull?.isNaN() == true
        else -> false
    }
    fun equal(left: Any?, right: Any?): Boolean = equalInside(left, right, false)
    private fun equalInside(left: Any?, right: Any?, withinContainer: Boolean): Boolean {
        if (nan(left) || nan(right)) return withinContainer && left === right
        val a = numeric(left); val b = numeric(right)
        if (a != null && b != null) return a == b
        if (left is Map<*, *> && right is Map<*, *>) return left.size == right.size && left.all { (key, value) -> key in right && equalInside(value, right[key], true) }
        if (left is List<*> && right is List<*>) return left.size == right.size && left.indices.all { equalInside(left[it], right[it], true) }
        return left == right
    }
}
