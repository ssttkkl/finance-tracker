package com.finance.tracker.data

import com.finance.tracker.business.domain.CashImportPlanError
import com.finance.tracker.business.domain.LocalCashImportPlanReader
import com.finance.tracker.business.domain.parsePythonCashTimestamp
import com.finance.tracker.business.domain.pythonCodePointCount
import com.finance.tracker.business.domain.summarizeLocalCashImportMonths
import com.finance.tracker.core.cashLedgerPlatformTimeZoneExists
import com.finance.tracker.core.cashLedgerTimeZoneIds
import com.finance.tracker.core.localCashLedgerMonth
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The lifecycle supplies its actual plan identity; no revision or expiry is invented here. */
internal fun LocalCashImportCalculation.reader(planId: String): LocalCashImportPlanReader {
    require(planId.isNotEmpty())
    return localCashImportPlanReader(JsonObject(plan + ("plan_id" to JsonPrimitive(planId))))
}

internal fun localCashImportPlanReader(plan: JsonObject): LocalCashImportPlanReader = LocalCashImportPlanReader(plan) { rows, zone ->
    if (zone.pythonCodePointCount() > 128 || zone !in setOf("EST", "MST", "HST") &&
        (zone !in cashLedgerTimeZoneIds || !cashLedgerPlatformTimeZoneExists(zone))) throw CashImportPlanError("plan_input_invalid")
    summarizeLocalCashImportMonths(rows) { text -> localCashLedgerMonth(parsePythonCashTimestamp(text, allowNaive = true), zone) }
}
