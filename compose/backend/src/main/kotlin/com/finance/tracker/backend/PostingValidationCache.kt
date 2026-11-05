package com.finance.tracker.backend

import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant

internal class PostingValidationCache(private val validate: (Map<*, *>) -> Unit) {
    private val successful = hashSetOf<List<Any?>>()
    internal val successCount get() = successful.size

    operator fun invoke(record: Map<*, *>) {
        val key = try { key(record) } catch (_: Uncacheable) { null }
        if (key != null && key in successful) return
        validate(record)
        if (key != null && successful.size < 256) successful.add(key)
    }

    private class Uncacheable : RuntimeException(null, null, false, false)
    private data class Scalar(val kind: Class<*>?, val value: Any?)
    private data class Field(val present: Boolean, val value: Any?)
    private fun scalar(value: Any?): Scalar {
        val kind = value?.javaClass
        return when {
            value == null || scalarClasses.any { it == kind } -> Scalar(kind, value)
            kind == BigDecimal::class.java && (value as BigDecimal).precision() <= 38 -> Scalar(kind, value)
            kind == CashAmount::class.java && (value as CashAmount).representation.coefficient.length <= 38 -> Scalar(kind, value.representation)
            else -> throw Uncacheable()
        }
    }
    private fun plainMap(value: Any?): Map<*, *> {
        if (value == null || value.javaClass !in mapClasses) throw Uncacheable()
        val map = value as Map<*, *>
        if (map.keys.any { it !is String }) throw Uncacheable()
        return map
    }
    private fun key(record: Map<*, *>): List<Any?> {
        plainMap(record)
        return fields.map { field ->
            val value = record[field]
            if (field != "components") Field(field in record, scalar(value))
            else {
                if (value == null || value.javaClass !in listClasses) throw Uncacheable()
                val parts = value as List<*>
                if (parts.size > 128) throw Uncacheable()
                Field(field in record, parts.map { raw ->
                    val part = plainMap(raw)
                    if (part.keys.any { it !in componentFields }) throw Uncacheable()
                    componentFields.map { name -> Field(name in part, scalar(part[name])) }
                })
            }
        }
    }
    private companion object {
        val fields = listOf("amount", "currency", "occurred_at", "record_type", "record_subtype", "account_id", "cash_granularity", "components")
        val componentFields = listOf("component_id", "account_id", "ordinal", "amount", "currency")
        val scalarClasses: Set<Class<*>> = setOf(String::class.java, Boolean::class.javaObjectType, Byte::class.javaObjectType,
            Short::class.javaObjectType, Int::class.javaObjectType, Long::class.javaObjectType, BigInteger::class.java, Instant::class.java)
        val mapClasses: Set<Class<*>> = setOf(HashMap::class.java, LinkedHashMap::class.java, emptyMap<Any, Any>().javaClass,
            mapOf(1 to 1).javaClass, java.util.Map.of(1, 1, 2, 2).javaClass, java.util.Map.of(1, 1).javaClass)
        val listClasses: Set<Class<*>> = setOf(ArrayList::class.java, emptyList<Any>().javaClass, listOf(1).javaClass, listOf(1, 2).javaClass,
            java.util.List.of(1).javaClass, java.util.List.of(1, 2, 3).javaClass)
    }
}
