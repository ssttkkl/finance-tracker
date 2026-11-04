package com.finance.tracker.backend

import java.math.BigDecimal

internal typealias CashAccountMappingFailure = com.finance.tracker.business.domain.CashAccountMappingFailure
internal typealias SourceAccountGroup = com.finance.tracker.business.domain.SourceAccountGroup
internal typealias SourceRowIssue = com.finance.tracker.business.domain.SourceRowIssue

/** Native scalar and regex bridge; source-account decisions have one shared implementation. */
internal object CashSourceAccountMapping {
    private val rules = com.finance.tracker.business.domain.CashSourceAccountMappingRules(
        object : com.finance.tracker.business.domain.CashSourceAccountAmounts<BigDecimal> {
            override fun parse(text: String) = BigDecimal(text)
            override fun sign(value: BigDecimal) = value.signum()
            override fun negate(value: BigDecimal) = value.negate()
            override fun plain(value: BigDecimal) = value.toPlainString()
            override fun add(left: BigDecimal, right: BigDecimal) = left.add(right)
            override fun compare(left: BigDecimal, right: BigDecimal) = left.compareTo(right)
            override val zero = BigDecimal.ZERO
        }, ::text, ::pythonStr, ::pythonInt, ::truthy, { it.uppercase() },
        { sourceDigest(*it.toTypedArray()) }, ::Regex,
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

    private fun pythonStr(value: Any?): String = when (value) {
        null -> "None"
        is Boolean -> if (value) "True" else "False"
        is String -> value
        is BigDecimal -> value.toString()
        else -> value.toString()
    }

    private fun pythonInt(value: Any?): Long = when (value) {
        is Boolean -> if (value) 1 else 0
        is Number -> value.toDouble().toLong()
        else -> pythonStr(value).trim { it.code in pythonWhitespace }.toLong()
    }

    private fun text(value: Any?): String = when (value) {
        null -> ""
        is String -> value.trim { it.code in pythonWhitespace }
        is Boolean -> if (value) "True" else "False"
        is BigDecimal -> value.toString()
        else -> value.toString().trim { it.code in pythonWhitespace }
    }

    private fun truthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is String -> value.isNotEmpty()
        is Number -> value.toDouble() != 0.0
        is Collection<*> -> value.isNotEmpty()
        is Map<*, *> -> value.isNotEmpty()
        else -> true
    }

}
