@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package com.finance.tracker.importworker

import com.finance.tracker.business.contracts.CashFiltersDto
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.domain.*
import com.finance.tracker.client.CashLedgerOperations
import com.finance.tracker.client.cashCategoryDirectory
import com.finance.tracker.client.cashAccountDirectory
import com.finance.tracker.client.cashAccountFact
import kotlinx.serialization.json.*
import kotlin.js.JsExport
import kotlin.time.Instant

private class LedgerReader(val snapshot: LedgerSnapshotDto) {
    val workspace get() = snapshot.workspaceId
    val query by lazy { CashLedgerOperations(snapshot, ::wasmCashLedgerMonth) }
}
private var ledgerReader: LedgerReader? = null

@JsExport
fun readCashLedgerPage(query: String, snapshot: String?): String = try {
    readCashLedger(query, snapshot)
} catch (failure: CashProjectionDomainFailure) {
    buildJsonObject { put("code", failure.code) }.toString()
}

private fun readCashLedger(query: String, snapshot: String?): String {
    val input = Json.parseToJsonElement(query).jsonObject
    val workspace = input.value("workspace")
    if (snapshot != null) {
        ledgerReader = null
        val committed = Json.decodeFromString<LedgerSnapshotDto>(snapshot)
        check(committed.workspaceId == workspace) { "ledger_snapshot_workspace_mismatch" }
        check(committed.ledgerSeq.toLongOrNull()?.let { it >= 0 } == true) { "ledger_version_invalid" }
        ledgerReader = LedgerReader(committed)
    }
    val reader = checkNotNull(ledgerReader?.takeIf { it.workspace == workspace }) { "ledger_snapshot_missing" }
    val mode = input.optional("mode")
    if (mode != null) {
        val facts = reader.snapshot.facts
        return when (mode) {
            "group" -> Json.encodeToString(reader.query.currentGroup(input.value("id")))
            "record" -> reader.query.record(input.value("id"), input.getValue("canWrite").jsonPrimitive.boolean).toString()
            "sources" -> {
                val evidence = reader.query.evidence(input.value("id"))
                val typed = ledgerDetailJson.decodeFromJsonElement<com.finance.tracker.business.contracts.CashSourceEvidenceDto>(evidence)
                val canWrite = input.getValue("canWrite").jsonPrimitive.boolean
                buildJsonObject {
                    put("evidence", evidence)
                    put("records", JsonObject(typed.members.associate { it.id to reader.query.record(it.id, canWrite) }))
                }.toString()
            }
            "refs" -> buildJsonObject {
                put("version", reader.snapshot.ledgerSeq)
                put("refs", Json.encodeToJsonElement(reader.query.projectionRefs(Json.decodeFromJsonElement<List<String>>(input.getValue("ids")))))
            }.toString()
            "selectionAccount" -> Json.encodeToString(cashAccountFact(facts.accounts, input.value("id")))
            "accounts" -> Json.encodeToString(cashAccountDirectory(facts.accounts))
            "categories" -> Json.encodeToString(cashCategoryDirectory(reader.snapshot))
            else -> error("ledger_query_invalid")
        }
    }
    val filters = Json.decodeFromJsonElement<CashFiltersDto>(input.getValue("filters"))
    val bounds = CashLedgerDateBounds(input.optional("dateFrom"), input.optional("dateTo"), input.value("zone"),
        input.optional("from")?.let(Instant::parse), input.optional("until")?.let(Instant::parse))
    return Json.encodeToString(reader.query.page(
        filters, bounds,
        input.optional("cursor"), input["start"]?.jsonPrimitive?.int, input.optional("expectedSeq")?.toLong(),
        input.getValue("limit").jsonPrimitive.int,
    ))
}

private val ledgerDetailJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

private fun JsonObject.value(key: String) = getValue(key).jsonPrimitive.content
private fun JsonObject.optional(key: String) = get(key)?.jsonPrimitive?.content

@JsExport
fun inspectCachedLedgerVersion(workspace: String, text: String): String {
    val snapshot = Json.decodeFromString<LedgerSnapshotDto>(text)
    check(snapshot.workspaceId == workspace) { "ledger_snapshot_workspace_mismatch" }
    check(snapshot.ledgerSeq.toLongOrNull()?.let { it >= 0 } == true) { "ledger_version_invalid" }
    return snapshot.ledgerSeq
}
