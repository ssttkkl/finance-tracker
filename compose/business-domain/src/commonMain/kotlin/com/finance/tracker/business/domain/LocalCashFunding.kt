package com.finance.tracker.business.domain

import kotlinx.serialization.json.*

/** Scan locally normalized statement files without a server or a platform-supplied rule adapter. */
fun scanLocalCashFunding(files: List<ParsedImportFile>): CashFundingScan = localCashFundingScan.scan(files)

private val localCashFundingScan by lazy {
    CashFundingScanRules(
        { LedgerArithmeticValue.fromCashAmount(exactCashAmount(it)).toPlainString() },
        ::normalizeCashNfc,
        LocalCashSourceAccountMapping::parseAlipayPaymentComponents,
        LocalCashSourceAccountMapping::normalizeAlipayComponent,
        LocalCashSourceAccountMapping::normalizeWechatPaymentIdentity,
        ::localSourceStringDigest,
        { fields -> copyLocalCashFields(fields) },
    )
}

/** Normalized documents contain acyclic JSON containers and immutable scalar values. */
internal fun copyLocalCashFields(fields: Map<String, Any?>): Map<String, Any?> {
    fun copy(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.entries.associateTo(linkedMapOf()) { (key, item) -> key to copy(item) }
        is List<*> -> value.mapTo(arrayListOf()) { copy(it) }
        is Set<*> -> value.mapTo(linkedSetOf()) { copy(it) }
        else -> value
    }
    return fields.mapValuesTo(linkedMapOf()) { copy(it.value) }
}

/** Resolve choices from their JSON boundary, preserving integer revisions on JS and Wasm. */
fun resolveLocalCashFunding(scan: CashFundingScan, selections: JsonElement?, accounts: JsonElement?): List<CashFundingResolution> =
    localCashFundingResolution.resolve(scan, localFundingJsonValue(selections), localFundingJsonValue(accounts))

private val localCashFundingResolution by lazy {
    CashFundingResolutionRules(
        object : CashFundingAmountValues<LedgerArithmeticValue> {
            override fun parse(text: String) = LedgerArithmeticValue.fromCashAmount(exactCashAmount(text))
            override fun absolute(value: LedgerArithmeticValue) = value.abs()
            override fun negate(value: LedgerArithmeticValue) = value.negate()
            override fun total(values: List<LedgerArithmeticValue>) = values.fold(LedgerArithmeticValue.ZERO) { sum, value -> sum.add(value, 80) }
            override fun compare(left: LedgerArithmeticValue, right: LedgerArithmeticValue) = left.compareTo(right)
            override fun sign(value: LedgerArithmeticValue) = value.signum
            override fun plain(value: LedgerArithmeticValue) = value.toPlainString()
        },
        // Full posting validation; no process-wide success cache or test-only no-op validator.
        { LocalCashFundingPosting::validate },
        ::copyLocalCashFields,
        { value -> value is JsonPrimitive && !value.isString && localFundingInteger.matches(value.content) &&
            !value.content.startsWith('-') && value.content.any { it != '0' } },
        ::SourceJsonInteger,
    )
}

internal val localFundingInteger = Regex("-?[0-9]+")
private fun localFundingJsonValue(value: JsonElement?): Any? = when (value) {
    null, JsonNull -> null
    is JsonObject -> value.mapValues { localFundingJsonValue(it.value) }
    is JsonArray -> value.map(::localFundingJsonValue)
    is JsonPrimitive -> when {
        value.isString -> value.content
        value.booleanOrNull != null -> value.boolean
        localFundingInteger.matches(value.content) -> {
            val digits = value.content.removePrefix("-").trimStart('0').ifEmpty { "0" }
            Json.parseToJsonElement((if (value.content.startsWith('-') && digits != "0") "-" else "") + digits)
        }
        else -> value // Float 1.0 must never become an integer revision through JS type erasure.
    }
}
