package com.finance.tracker.data

import com.finance.tracker.business.domain.LocalCashImportPlanReader
import com.finance.tracker.client.cashImportPlanReader
import com.finance.tracker.core.localCashLedgerCalendar
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The lifecycle supplies its actual plan identity; no revision or expiry is invented here. */
internal fun LocalCashImportCalculation.reader(planId: String): LocalCashImportPlanReader {
    require(planId.isNotEmpty())
    return localCashImportPlanReader(JsonObject(plan + ("plan_id" to JsonPrimitive(planId))))
}

internal fun localCashImportPlanReader(plan: JsonObject): LocalCashImportPlanReader =
    cashImportPlanReader(plan, localCashLedgerCalendar)

internal fun validateCashImportPreviewTimezone(zone: String) = localCashLedgerCalendar.validateImportZone(zone)
