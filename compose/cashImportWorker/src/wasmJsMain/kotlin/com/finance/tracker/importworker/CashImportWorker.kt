@file:OptIn(ExperimentalJsExport::class)

package com.finance.tracker.importworker

import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.contracts.NormalizedCashDocumentDto
import com.finance.tracker.business.domain.CashImportPlanError
import com.finance.tracker.business.domain.LocalCashImportReview
import com.finance.tracker.business.domain.ParsedImportFile
import com.finance.tracker.business.domain.localNormalizedCashDocument
import com.finance.tracker.client.calculateCashImportWorkerReview
import kotlinx.serialization.json.*
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

fun main() = Unit

@JsExport
fun calculatePreview(payload: String): String = try {
    val value = computePreview(Json.parseToJsonElement(payload).jsonObject)
    buildJsonObject {
        put("plan", value.plan)
        put("review", value.review)
        put("reviewContext", value.reviewContext)
        put("writeSet", value.writeSet)
    }.toString()
} catch (failure: CashImportPlanError) {
    previewPlanError(failure).toString()
}

internal class WorkerCashImportPreview(val plan: JsonObject, val review: String, val reviewContext: JsonElement, val writeSet: JsonElement)

internal fun computePreview(request: JsonObject,
    files: List<ParsedImportFile> = request.getValue("files").jsonArray.map { readFile(it.jsonObject) },
    snapshot: LedgerSnapshotDto = Json.decodeFromJsonElement(request.getValue("snapshot")),
): WorkerCashImportPreview {
    val input = request.getValue("choices").jsonObject
    val previous = request["previous"]?.jsonPrimitive?.content?.let(LocalCashImportReview::decode)
    val calculation = calculateCashImportWorkerReview(files, snapshot, input, previous)
    return WorkerCashImportPreview(calculation.plan, calculation.review.encode(), calculation.previewContext,
        Json.encodeToJsonElement(calculation.request))
}

internal fun previewPlanError(failure: CashImportPlanError): JsonObject = buildJsonObject {
    put("code", failure.code)
    failure.commitState?.let { put("commitState", it) }
    failure.diagnostic?.let { put("diagnostic", Json.encodeToJsonElement(it)) }
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
