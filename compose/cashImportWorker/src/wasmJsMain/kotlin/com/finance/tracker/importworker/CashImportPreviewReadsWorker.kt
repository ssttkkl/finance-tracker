@file:OptIn(kotlin.js.ExperimentalJsExport::class, kotlin.js.ExperimentalWasmJsInterop::class)

package com.finance.tracker.importworker

import com.finance.tracker.business.domain.*
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import kotlinx.serialization.json.*
import kotlin.js.JsAny

@JsExport
fun openPreview(payload: String): JsAny = try {
    val input = Json.parseToJsonElement(payload).jsonObject
    openPreviewView(input, computePreview(input))
} catch (failure: CashImportPlanError) {
    previewError(previewPlanError(failure).toString())
}

@JsExport
fun openStoredPreview(metadata: String, files: JsAny, snapshotText: String): JsAny = try {
    val input = Json.parseToJsonElement(metadata).jsonObject
    val parsed = readCapturedCashImportFiles(files)
    val snapshot = Json.decodeFromString<LedgerSnapshotDto>(snapshotText)
    check(snapshot.workspaceId == input.text("workspaceId")) { "ledger_snapshot_workspace_mismatch" }
    check(snapshot.ledgerSeq.toLongOrNull()?.let { it >= 0 } == true) { "ledger_version_invalid" }
    openPreviewView(input, computePreview(input, parsed, snapshot))
} catch (failure: CashImportPlanError) {
    previewError(previewPlanError(failure).toString())
}

internal fun readCapturedCashImportFiles(files: JsAny): List<ParsedImportFile> {
    val size = storedFileCount(files)
    require(size in 0..20) { "cash_import_files_too_many" }
    val parsed = List(size) { decodeStoredCashImportFile(storedFileAt(files, it)).toParsedImportFile() }
    require(parsed.map { it.fileRef }.toSet().size == size) { "cash_import_file_ref_duplicate" }
    return parsed
}

private fun storedFileCount(files: JsAny): Int = js("files.length")
private fun storedFileAt(files: JsAny, index: Int): String = js("files[index]")

private fun openPreviewView(input: JsonObject, value: WorkerCashImportPreview): JsAny {
    val plan = identifyCashImportPreview(value.plan, input.text("planId"), input.text("batchId"),
        input.getValue("revision").jsonPrimitive.long)
    val reader = workerReader(plan)
    reader.validateReadable()
    val view = reader.view(input.text("timezone"))
    val confirmation = buildJsonObject {
        put("batchId", input.text("batchId"))
        put("canCommit", plan.getValue("can_commit"))
        put("writeSet", value.writeSet)
        put("resultPlan", buildJsonObject {
            put("operations", JsonArray(value.plan.getValue("operations").jsonArray.map { operation ->
                JsonObject(operation.jsonObject.filterKeys { it == "operation_ref" || it == "preview_transaction_id" })
            }))
            for (key in listOf("row_decisions", "file_results", "relation_failures")) put(key, value.plan.getValue(key))
        })
    }
    return makePreview(view.toString(), value.review, value.reviewContext.toString(), plan.toString(),
        JsonObject(plan.filterKeys { it == "plan_id" || it == "items" }).toString(),
        JsonObject(plan.filterKeys { it == "plan_id" || it == "processing_details" }).toString(),
        confirmation.toString())
}

@JsExport
fun readPreviewSection(query: String, material: String): String = try {
    val input = Json.parseToJsonElement(query).jsonObject
    val raw = Json.parseToJsonElement(material)
    when (val mode = input.text("mode")) {
        "view" -> workerReader(raw.jsonObject).view(input.text("timezone"))
        "items", "processing_details" -> workerReader(raw.jsonObject).page(
            mode, input["cursor"]?.jsonPrimitive?.content, input["start"]?.jsonPrimitive?.int,
            input.getValue("limit").jsonPrimitive.int, input["status"]?.jsonPrimitive?.content, mode == "items",
        )
        "detail" -> workerReader(raw.jsonObject).detail(input.text("id"))
        "review" -> buildJsonObject {
            put("review", LocalCashImportReview.restorePreviewReview(raw)(input.getValue("choices").jsonObject).encode())
        }
        else -> error("cash_import_worker_query_invalid")
    }.toString()
} catch (failure: CashImportPlanError) {
    previewPlanError(failure).toString()
}

private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

private fun workerReader(plan: JsonObject) = LocalCashImportPlanReader(plan) { rows, timezone ->
    summarizeLocalCashImportMonths(rows) { text ->
        wasmCashLedgerMonth(parsePythonCashTimestamp(text, allowNaive = true), timezone)
    }
}

private fun makePreview(header: String, review: String, context: String, detail: String, items: String,
    processing: String, confirmation: String): JsAny = js("""{
        // Preserve legacy UTF-16 units before UTF-8 encoding.
        const encoder = new TextEncoder();
        const section = text => {
            const bytes = encoder.encode(text.replace(/[\uD800-\uDFFF]/g,
                unit => "\\u" + unit.charCodeAt(0).toString(16).padStart(4, "0")));
            const buffer = new SharedArrayBuffer(bytes.length);
            new Uint8Array(buffer).set(bytes);
            return buffer;
        };
        return {
            header, review, context: section(context), detail: section(detail),
            items: section(items), processing: section(processing), confirmation: section(confirmation)
        };
    }""")

private fun previewError(header: String): JsAny = js("({ header })")
