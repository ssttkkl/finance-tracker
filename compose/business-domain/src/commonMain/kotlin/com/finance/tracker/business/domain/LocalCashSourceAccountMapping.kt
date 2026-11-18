package com.finance.tracker.business.domain

/** Production source-account decisions over decoded statement fields and component choices. */
object LocalCashSourceAccountMapping {
    private val rules = CashSourceAccountMappingRules(
        object : CashSourceAccountAmounts<LedgerArithmeticValue> {
            override fun parse(text: String) = parseSourceAccountAmount(text)
            override fun sign(value: LedgerArithmeticValue) = value.signum
            override fun negate(value: LedgerArithmeticValue) = value.negate()
            override fun plain(value: LedgerArithmeticValue) = value.toPlainString()
            override fun add(left: LedgerArithmeticValue, right: LedgerArithmeticValue) = left.add(right)
            override fun compare(left: LedgerArithmeticValue, right: LedgerArithmeticValue) = left.compareTo(right)
            override val zero = LedgerArithmeticValue.ZERO
        }, ::localSourceText, ::localSourcePythonText, ::localSourceInteger, ::localSourceTruthy,
        ::pythonCashUpper, ::localSourceStringDigest, CashSourceAccountTextPatterns,
    )
    fun parseAlipayPaymentComponents(row: Map<String, Any?>) = rules.parseAlipayPaymentComponents(row)
    fun alipayComponentAllocationStatus(row: Map<String, Any?>) = rules.alipayComponentAllocationStatus(row)
    fun buildComponentAllocationDraft(row: Map<String, Any?>, allocations: Any? = null) = rules.buildComponentAllocationDraft(row, allocations)
    fun sourceComponentIdentityKeys(row: Map<String, Any?>) = rules.sourceComponentIdentityKeys(row)
    fun sourceIdentityKey(row: Map<String, Any?>) = rules.sourceIdentityKey(row)
    fun scanSourceRowsWithIssues(rows: List<Map<String, Any?>>) = rules.scanSourceRowsWithIssues(rows)
    fun scanSourceRows(rows: List<Map<String, Any?>>) = rules.scanSourceRows(rows)
    internal fun normalizeAlipayComponent(value: String) = rules.normalizeAlipayComponent(value)
    internal fun normalizeWechatPaymentIdentity(value: Any?) = rules.normalizeWechatPaymentIdentity(value)
}

private fun localSourcePythonText(value: Any?): String = when (value) {
    null -> "None"
    is Boolean -> if (value) "True" else "False"
    is SourceJsonInteger -> value.value.toString()
    else -> value.toString()
}
private fun localSourceText(value: Any?): String = if (value == null) ""
    else localSourcePythonText(value).trim { it.code in pythonWhitespace }
private fun localSourceInteger(value: Any?): Long = when (value) {
    is Boolean -> if (value) 1 else 0
    is SourceJsonInteger -> value.value.toDouble().toLong()
    is Number -> value.toDouble().toLong()
    else -> {
        val raw = localSourcePythonText(value).trim { it.code in pythonWhitespace }
        val ascii = buildString(raw.length) {
            for (char in raw) {
                val start = decimalDigitStarts.firstOrNull { it <= 0xffff && char.code in it until it + 10 }
                append(if (start == null) char else ('0'.code + char.code - start).toChar())
            }
        }
        ascii.toLong()
    }
}
private fun localSourceTruthy(value: Any?): Boolean = when (value) {
    null -> false
    is Boolean -> value
    is SourceJsonInteger -> value.value != 0
    is String -> value.isNotEmpty()
    is Number -> value.toDouble() != 0.0
    is Collection<*> -> value.isNotEmpty()
    is Map<*, *> -> value.isNotEmpty()
    else -> true
}
internal fun localSourceStringDigest(values: List<String>): String =
    sha256Hex(sourceJson(values, { null }, ::pythonFloatText).toString().encodeToByteArray(throwOnInvalidSequence = true))
