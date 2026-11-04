package com.finance.tracker.business.domain

import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Instant

/** Scalar bridge for locally normalized funding rows; field and component decisions remain shared policy. */
internal object LocalCashFundingPosting {
    private fun invalid(): Nothing = throw InvalidCashValue()
    private val rules = CashRecordPolicyRules(::normalize) { first, second -> first == second }
    fun validate(record: Map<*, *>) = rules.validateForPosting(record)

    private fun amount(value: Any?): CashAmount? = when (value) {
        null -> null
        is CashAmount -> value
        is String -> CashAmount.parseOrNull(value) ?: invalid()
        is SourceJsonInteger -> CashAmount.parseOrNull(value.value.toString()) ?: invalid()
        is JsonPrimitive -> if (!value.isString && localFundingInteger.matches(value.content))
            CashAmount.parseOrNull(value.content) ?: invalid() else invalid()
        else -> invalid()
    }
    private fun integerText(value: Any?): String? = when (value) {
        is SourceJsonInteger -> value.value.toString()
        is Int -> value.toString() // Only ordinals generated inside the shared resolver take this branch.
        is Long -> value.toString()
        is JsonPrimitive -> value.content.takeIf { !value.isString && localFundingInteger.matches(it) }
        else -> null
    }
    private fun reference(value: Any?): String {
        if (value is String) return value.takeIf { it.isNotEmpty() } ?: invalid()
        val text = integerText(value) ?: invalid()
        if (text.startsWith('-') || text.all { it == '0' }) invalid()
        return text.trimStart('0')
    }
    private fun currency(value: Any?): String = (value as? String)
        ?.takeIf { it.length == 3 && it.all { c -> c in 'A'..'Z' } } ?: invalid()
    private fun components(value: Any?): List<Map<String, Any?>> {
        if (value !is List<*>) invalid()
        return value.map { raw ->
            if (raw !is Map<*, *> || raw.keys.any { it !is String }) invalid()
            @Suppress("UNCHECKED_CAST") val part = copyLocalCashFields(raw as Map<String, Any?>).toMutableMap()
            for (field in listOf("component_id", "account_id")) if (part[field] != null) part[field] = reference(part[field])
            if (part["amount"] != null) part["amount"] = amount(part["amount"])
            if ("ordinal" in part) part["ordinal"] = integerText(part["ordinal"])?.toLongOrNull()
                ?.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt() ?: invalid()
            if (part["currency"] != null) part["currency"] = currency(part["currency"])
            part
        }
    }
    private fun normalize(field: String, value: Any?): Any? = when (field) {
        "amount" -> amount(value)
        "occurred_at" -> when (value) {
            null -> null
            is String -> try { parsePythonCashTimestamp(value) } catch (_: IllegalArgumentException) { invalid() }
            is Instant -> {
                if (value.epochSeconds < -62135596800L || value.epochSeconds >= 253402300800L) invalid()
                Instant.fromEpochSeconds(value.epochSeconds, value.nanosecondsOfSecond / 1000 * 1000L)
            }
            else -> invalid()
        }
        "account_id" -> if (value == null) null else reference(value)
        "cash_granularity" -> (value as? String)?.takeIf { it == "atomic" || it == "aggregate" } ?: invalid()
        "currency" -> if (value == null) null else currency(value)
        "components" -> components(value)
        "record_type", "record_subtype" -> if (value == null || value is String) value else invalid()
        else -> error("unexpected_local_posting_field")
    }
}
