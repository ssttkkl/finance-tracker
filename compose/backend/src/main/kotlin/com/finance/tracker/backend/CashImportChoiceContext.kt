package com.finance.tracker.backend

import com.finance.tracker.business.domain.CASH_IMPORT_CHOICE_CONTEXT_KEY
import com.finance.tracker.business.domain.CashImportChoiceContextRules
import java.math.BigInteger

internal typealias CashImportChoiceContextError = com.finance.tracker.business.domain.CashImportChoiceContextError

internal object CashImportChoiceContext {
    const val CONTEXT_KEY = CASH_IMPORT_CHOICE_CONTEXT_KEY
    private val rules = CashImportChoiceContextRules(::integer, ::truthy, CashRecordPolicy::copyValue,
        { CashRecordPolicy.prepare(mapOf("state" to "draft"), it) }, { it })

    private fun integer(value: Any?): Long? = when (value) {
        is Byte, is Short, is Int, is Long, is BigInteger -> value.toString().toLongOrNull()
        else -> null
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

    fun validated(raw: Any?): Map<String, Any?> = rules.validated(raw)
    fun fromPlan(plan: Map<String, Any?>): Map<String, Any?> = rules.fromPlan(plan)
    fun retained(current: ParsedImportSession): Map<String, Any?> = rules.retained(current.contextData, current.plan)
    fun previous(current: ParsedImportSession): Map<String, Any?>? = rules.previous(current.contextData)
    fun without(current: ParsedImportSession): Map<String, Any?> = rules.without(current.contextData)
}
