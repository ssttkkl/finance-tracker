package com.finance.tracker.backend

import com.finance.tracker.business.contracts.CashWriteSetRequestDto
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.domain.*
import java.time.Instant
import kotlin.test.*
import kotlinx.serialization.json.*

internal data class CurrentCashImportTestPlan(val source: LedgerSnapshotDto, val plan: JsonObject,
    val request: CashWriteSetRequestDto)

/** Actual local calculation for permanent-command tests; no staged session or legacy plan identity. */
internal fun currentCashImportTestPlan(database: Database, token: String, workspace: String,
    files: List<ParsedImportFile>, at: Instant, review: (List<ParsedImportFile>) -> JsonObject): CurrentCashImportTestPlan {
    val source = LedgerReadService(database, now = { at }).snapshot(token)
    assertEquals(workspace, source.workspaceId)
    val normalized = files.map { file -> file.copy(document = file.document?.let { document ->
        localNormalizedCashDocument(normalizedCashDocumentWire(document) { sourceJson(it).jsonObject })
    }) }
    val (plan, changes) = calculateLocalCashImportBusinessPlan(normalized, source, review(normalized),
        requireActive = {}, now = { kotlin.time.Instant.parse(at.toString()) })
    val frozen = freezeLocalCashImportPreviewPlan(plan)
    assertEquals(JsonPrimitive(true), frozen["can_commit"])
    return CurrentCashImportTestPlan(source, frozen, CashWriteSetRequestDto(source.ledgerSeq, CASH_RULES_VERSION, changes))
}
