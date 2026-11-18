package com.finance.tracker.backend

import com.finance.tracker.business.contracts.CashDiagnosticDto
import com.finance.tracker.business.contracts.CashDiagnosticLocationDto

internal class CashImportCommitFailure(val commitState: String, val diagnostic: CashDiagnosticDto, val failure: Exception) : RuntimeException("cash_import_commit_failed")
internal class CashImportCommitBoundary(private val afterStep: (String) -> Unit) {
    var commitState = "unknown"; private set
    var stage = "request"; private set
    var files: List<ParsedImportFile> = emptyList()
    private var scope = "request"
    private var locations: List<Map<String, Any?>> = emptyList()
    fun noPriorResult() { commitState = "not_committed" }
    fun enter(value: String, positions: List<Map<String, Any?>> = emptyList(), scope: String = "request") {
        stage = value; locations = positions; this.scope = scope
    }
    fun rows(value: String, operations: List<Map<String, Any?>>, scope: String = "related") {
        val refs = operations.flatMap { it["row_refs"] as List<*> }.distinct()
        enter(value, refs.map { mapOf("row_ref" to it) }, scope)
    }
    fun diagnostic(existing: CashDiagnosticDto? = null): CashDiagnosticDto {
        fun text(value: Any?, limit: Int = 256) = (value as? String)?.takeIf { it.isNotEmpty() && it.codePointCount(0, it.length) <= limit }
        val positions = existing?.locations?.map { location -> mapOf("file_ref" to location.fileRef, "display_name" to location.displayName,
            "row_ref" to location.rowRef, "row_number" to location.rowNumber, "position_kind" to location.positionKind,
            "document_position" to location.documentPosition, "transaction_id" to location.transactionId).filterValues { it != null && it != "" } } ?: locations
        val safe = positions.take(100).map { original ->
            val item = original.toMutableMap()
            val match = (item["row_ref"] as? String)?.let { Regex("(.+):r([0-9]+)").matchEntire(it) }
            val ref = item["file_ref"] ?: match?.groupValues?.get(1)
            val file = if (ref != null) files.find { it.fileRef == ref }
                else files.singleOrNull()?.takeIf { "row_number" in item && "transaction_id" !in item }
            if (file != null) {
                item["file_ref"] = file.fileRef; item["display_name"] = file.filename
                match?.groupValues?.get(2)?.toIntOrNull()?.let { item.putIfAbsent("row_number", it) }
                val row = item["row_number"] as? Int
                if (row != null && row > 0) {
                    item.putIfAbsent("row_ref", "${file.fileRef}:r$row")
                    file.document?.let { document ->
                        item.putIfAbsent("position_kind", when {
                            document.tableKey.startsWith("csv") || document.tableKey.startsWith("tsv") -> "csv_record"
                            document.tableKey.startsWith("xls") || document.tableKey.startsWith("sheet") -> "worksheet_row"
                            else -> "document_row"
                        })
                        if (item["position_kind"] == "document_row") document.rows.find { it.position == row }?.documentPosition?.let { item.putIfAbsent("document_position", it) }
                    }
                }
            }
            CashDiagnosticLocationDto(text(item["file_ref"]), text(item["display_name"], 512), text(item["row_ref"]),
                item["row_number"] as? Int, text(item["position_kind"]), text(item["document_position"]), text(item["transaction_id"]))
        }
        return CashDiagnosticDto(existing?.errorId ?: java.util.UUID.randomUUID().toString().replace("-", ""), existing?.stage ?: stage,
            existing?.scope ?: scope, safe, existing?.totalLocations ?: positions.size)
    }
    fun done(value: String) { afterStep(value) }
    fun committing() { enter("database_commit"); commitState = "unknown"; done(stage) }
}
