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
@Suppress("UNCHECKED_CAST")
internal fun copyLocalCashFields(fields: Map<String, Any?>): Map<String, Any?> =
    copyLocalCashValue(fields) as Map<String, Any?>

/** Resolve choices from their JSON boundary, preserving integer revisions on JS and Wasm. */
fun resolveLocalCashFunding(scan: CashFundingScan, selections: JsonElement?, accounts: JsonElement?): List<CashFundingResolution> =
    localCashFundingResolution.resolve(scan, decodeLocalCashValue(selections), decodeLocalCashValue(accounts))

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
        { value -> value is JsonPrimitive && !value.isString && localCashInteger.matches(value.content) &&
            !value.content.startsWith('-') && value.content.any { it != '0' } },
        ::SourceJsonInteger,
    )
}
