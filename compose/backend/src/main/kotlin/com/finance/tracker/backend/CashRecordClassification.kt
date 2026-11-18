package com.finance.tracker.backend

import java.math.BigDecimal

internal typealias CashRecordRuleFailure = com.finance.tracker.business.domain.CashRecordRuleFailure
internal typealias CounterpartyAccount = com.finance.tracker.business.domain.CounterpartyAccount

internal object CashRecordClassification {
    val validTypes = cashRecordTypes
    val validSubtypes = cashRecordSubtypes
    private val typeRules = com.finance.tracker.business.domain.CashRecordTypeRules(::pythonStrOrEmpty, ::pythonTruthy)
    private val accountRules = com.finance.tracker.business.domain.CounterpartyAccountRules(::pythonStrOrEmpty, ::pythonStr, ::sourcePayloadDigest)
    fun defaultSubtype(recordType: Any?): String = typeRules.defaultSubtype(recordType)
    fun validateSubtype(recordType: Any?, recordSubtype: Any?) = typeRules.validateSubtype(recordType, recordSubtype)
    fun classify(source: String?, row: Map<String, Any?>): Pair<String, String> = typeRules.classify(source, row)
    fun classifyType(source: String?, row: Map<String, Any?>): String = typeRules.classifyType(source, row)
    fun normalizeAccount(value: Any?, source: String = "", sourceAccountIdentifier: String = "", sourcePayload: Any? = null): CounterpartyAccount =
        accountRules.normalizeAccount(value, source, sourceAccountIdentifier, sourcePayload)
    fun validateAccount(value: Any?, attrs: Any?) = accountRules.validateAccount(value, attrs)
    fun validateAccountForWrite(value: Any?, attrs: Any?, reconstructionProof: Any? = null, sourceType: Any? = "", sourcePayload: Any? = null) =
        accountRules.validateAccountForWrite(value, attrs, reconstructionProof, sourceType, sourcePayload)

    internal fun sourcePayloadDigest(value: Any?): String {
        if (value !is Map<*, *> || value.isEmpty()) return ""
        return try {
            tokenDigest(canonicalCashJson(value))
        } catch (_: Exception) {
            ""
        }
    }

    private fun pythonTruthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is String -> value.isNotEmpty()
        is BigDecimal -> value.signum() != 0
        is Number -> value.toDouble() != 0.0
        is Collection<*> -> value.isNotEmpty()
        is Map<*, *> -> value.isNotEmpty()
        else -> true
    }

    internal fun pythonStr(value: Any?): String = when (value) {
        null -> "None"
        is Boolean -> if (value) "True" else "False"
        is String -> value
        is BigDecimal -> value.toString()
        is Double -> value.toString()
        else -> value.toString()
    }

    private fun pythonStrOrEmpty(value: Any?): String = when (value) {
        null -> ""
        is Boolean -> if (value) "True" else ""
        is String -> value
        is BigDecimal -> if (value.signum() == 0) "" else value.toString()
        is Number -> if (value.toDouble() == 0.0) "" else pythonStr(value)
        else -> value.toString()
    }
}
