package com.finance.tracker.business.domain

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
