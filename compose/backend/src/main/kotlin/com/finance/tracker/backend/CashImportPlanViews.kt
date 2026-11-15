package com.finance.tracker.backend

import java.math.BigInteger
import kotlinx.serialization.json.*

internal typealias CashImportPlanError = com.finance.tracker.business.domain.CashImportPlanError

internal object CashImportPlanViews : CashImportPlanViewEngine()

internal open class CashImportPlanViewEngine : com.finance.tracker.business.domain.CashImportPlanViewRules<BigInteger>(
    CashRecordPolicy, ::planViewInteger, { BigInteger.valueOf(it.toLong()) },
    { componentIdentifier(it)?.let { id -> id >= BigInteger.TWO.pow(63) } == true },
    { it is String || it is Number || it is Boolean },
    { rows, zone -> CashImportMonthlySummaries.summarize(rows, zone) },
)

private fun planViewInteger(value: Any?): BigInteger? = when (value) {
    is Byte, is Short, is Int, is Long, is BigInteger -> BigInteger(value.toString())
    else -> null
}

private fun componentIdentifier(value: Any?): BigInteger? {
    if (value == null) return null
    val text = value.toString()
    if (value is BigInteger && text.length - (if (value.signum() < 0) 1 else 0) > 4300)
        throw IllegalArgumentException("Exceeds the limit (4300 digits) for integer string conversion; use sys.set_int_max_str_digits() to increase the limit")
    if (text.isEmpty() || !text.codePoints().allMatch { code -> code in pythonNondecimalDigits ||
            pythonIntegerDigitStarts.any { code in it until it + 10 } }) return null
    return try { pythonIntegerValue(JsonPrimitive(text)) }
    catch (failure: CategoryFailure) { throw IllegalArgumentException(failure.code) }
}
