package com.finance.tracker.backend

import java.math.BigDecimal
import java.time.Instant

internal typealias SourceBatchResolution = com.finance.tracker.business.domain.SourceBatchResolution
internal typealias SourceBatchGroup = com.finance.tracker.business.domain.SourceBatchGroup
internal typealias SourceBatchPlan = com.finance.tracker.business.domain.SourceBatchPlan

internal object CashSourceBatch {
    private val rules = com.finance.tracker.business.domain.CashSourceBatch(
        resolver = CashSourceResolver.sharedRules,
        normalizePatch = { patch -> CashRecordPolicy.prepare(mapOf("state" to "draft"), patch).normalizedValues },
        contentValue = ::contentValue,
        digestValues = { values -> sourceDigest(*values.toTypedArray()) },
        coverageComparable = { first, second, value -> first.comparable(second, value) },
    )
    fun resolve(input: SourceBatchRows, records: List<SourceRecord>,
        knownDocumentEvidence: List<SourceDocumentEvidence> = emptyList()): SourceBatchPlan =
        rules.resolve(input, records, knownDocumentEvidence)

    private fun contentValue(value: Any?): Any? = when (value) {
        is CashAmount -> decimalContent(value.representation.negative, value.representation.coefficient, value.representation.exponent)
        is BigDecimal -> decimalContent(value.signum() < 0, value.unscaledValue().abs().toString(), -value.scale().toLong())
        is Instant -> value.atOffset(java.time.ZoneOffset.UTC).toString().replace("Z", "+00:00")
        is Map<*, *> -> value.mapValues { contentValue(it.value) }
        is List<*> -> value.map(::contentValue)
        else -> value
    }
    private fun decimalContent(negative: Boolean, coefficient: String, exponent: Long): Map<String, Any> {
        val digits = coefficient.trimEnd('0')
        return mapOf("decimal" to listOf(if (digits.isEmpty()) 0 else if (negative) 1 else 0,
            digits.map { it.digitToInt() }, if (digits.isEmpty()) 0L else exponent + coefficient.length - digits.length))
    }
}
