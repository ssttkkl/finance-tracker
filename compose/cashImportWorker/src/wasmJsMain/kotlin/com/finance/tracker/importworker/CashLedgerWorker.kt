@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package com.finance.tracker.importworker

import com.finance.tracker.business.contracts.CashFiltersDto
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.domain.*
import kotlinx.serialization.json.*
import kotlin.js.JsExport
import kotlin.time.Instant

private class LedgerReader(val workspace: String, val query: LocalCashLedgerQuery)
private var ledgerReader: LedgerReader? = null

@JsExport
fun readCashLedgerPage(query: String, snapshot: String?): String = try {
    val input = Json.parseToJsonElement(query).jsonObject
    val workspace = input.value("workspace")
    if (snapshot != null) {
        ledgerReader = null
        val committed = Json.decodeFromString<LedgerSnapshotDto>(snapshot)
        check(committed.workspaceId == workspace) { "ledger_snapshot_workspace_mismatch" }
        check(committed.ledgerSeq.toLongOrNull()?.let { it >= 0 } == true) { "ledger_version_invalid" }
        ledgerReader = LedgerReader(workspace, LocalCashLedgerQuery(committed, ::wasmCashLedgerMonth))
    }
    val reader = checkNotNull(ledgerReader?.takeIf { it.workspace == workspace }) { "ledger_snapshot_missing" }
    val filters = Json.decodeFromJsonElement<CashFiltersDto>(input.getValue("filters"))
    val bounds = CashLedgerDateBounds(input.optional("dateFrom"), input.optional("dateTo"), input.value("zone"),
        input.optional("from")?.let(Instant::parse), input.optional("until")?.let(Instant::parse))
    Json.encodeToString(reader.query.page(
        normalizeLocalCashLedgerQueryInput(filters, bounds.timeZoneId) { _, _, _ -> bounds },
        input.optional("cursor"), input["start"]?.jsonPrimitive?.int, input.optional("expectedSeq")?.toLong(),
        input.getValue("limit").jsonPrimitive.int,
    ))
} catch (failure: CashProjectionDomainFailure) {
    buildJsonObject { put("code", failure.code) }.toString()
}

private fun JsonObject.value(key: String) = getValue(key).jsonPrimitive.content
private fun JsonObject.optional(key: String) = get(key)?.jsonPrimitive?.content
