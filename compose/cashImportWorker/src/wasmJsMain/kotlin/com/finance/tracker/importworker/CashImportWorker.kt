@file:OptIn(ExperimentalJsExport::class)

package com.finance.tracker.importworker

import com.finance.tracker.business.contracts.CashWriteSetRequestDto
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.contracts.NormalizedCashDocumentDto
import com.finance.tracker.business.domain.CASH_RULES_VERSION
import com.finance.tracker.business.domain.CashImportPlanError
import com.finance.tracker.business.domain.LocalCashImportReview
import com.finance.tracker.business.domain.ParsedImportFile
import com.finance.tracker.business.domain.calculateLocalCashImportBusinessPlan
import com.finance.tracker.business.domain.freezeLocalCashImportPreviewPlan
import com.finance.tracker.business.domain.localNormalizedCashDocument
import kotlinx.serialization.json.*
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.time.Clock

fun main() = Unit

@JsExport
fun calculatePreview(payload: String): String {
    val request = Json.parseToJsonElement(payload).jsonObject
    val files = request.getValue("files").jsonArray.map { readFile(it.jsonObject) }
    val snapshot = Json.decodeFromJsonElement<LedgerSnapshotDto>(request.getValue("snapshot"))
    val input = request.getValue("choices").jsonObject
    val previous = request["previous"]?.jsonPrimitive?.content?.let(LocalCashImportReview::decode)
    val response = try {
        val (plan, changes) = calculateLocalCashImportBusinessPlan(
            files, snapshot, input, previous?.previousPlan().orEmpty(), previous?.recordIds().orEmpty(), {}, Clock.System::now,
        )
        buildJsonObject {
            put("plan", freezeLocalCashImportPreviewPlan(plan))
            put("review", LocalCashImportReview.freeze(input, plan, snapshot, changes).encode())
            put("reviewContext", LocalCashImportReview.encodePreviewContext(plan))
            put("writeSet", Json.encodeToJsonElement(CashWriteSetRequestDto(snapshot.ledgerSeq, CASH_RULES_VERSION, changes)))
        }
    } catch (failure: CashImportPlanError) {
        buildJsonObject {
            put("code", failure.code)
            failure.commitState?.let { put("commitState", it) }
            failure.diagnostic?.let { put("diagnostic", Json.encodeToJsonElement(it)) }
        }
    }
    return response.toString()
}

private fun readFile(wire: JsonObject): ParsedImportFile = ParsedImportFile(
    fileRef = wire.getValue("fileRef").jsonPrimitive.content,
    digest = wire.getValue("digest").jsonPrimitive.content,
    status = wire.getValue("status").jsonPrimitive.content,
    document = wire["document"]?.let { localNormalizedCashDocument(Json.decodeFromJsonElement<NormalizedCashDocumentDto>(it)) },
    filename = wire.getValue("filename").jsonPrimitive.content,
    size = wire.getValue("size").jsonPrimitive.long,
    lastModifiedAtMs = wire["lastModifiedAtMs"]?.jsonPrimitive?.long,
    sourceType = wire["sourceType"]?.jsonPrimitive?.content,
    errorCode = wire["errorCode"]?.jsonPrimitive?.content,
)
