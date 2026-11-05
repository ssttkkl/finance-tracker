package com.finance.tracker.data

import com.finance.tracker.domain.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

typealias CashDiagnosticLocationDto = com.finance.tracker.business.contracts.CashDiagnosticLocationDto
typealias CashDiagnosticDto = com.finance.tracker.business.contracts.CashDiagnosticDto

private val diagnosticStages = setOf("request", "file_read", "row_conversion", "row_planning", "relation_discovery",
    "submit_revalidation", "source_evidence", "row_write", "batch_write", "account_mapping", "relation_save",
    "balances", "projection", "entry_edit", "database_commit")
internal fun CashDiagnosticDto.toDomain(): ImportDiagnostic? {
    if (!errorId.matches(Regex("[a-f0-9]{32}")) || stage !in diagnosticStages ||
        scope !in setOf("row", "related", "file", "request") || locations.size > 100 || totalLocations < locations.size) return null
    if (locations.any { location -> location.rowNumber?.let { it <= 0 } == true ||
        location.positionKind?.let { it !in setOf("csv_record", "worksheet_row", "document_row") } == true ||
        listOf(location.fileRef, location.rowRef, location.documentPosition, location.transactionId).any { it != null && it.length > 256 } ||
        location.displayName?.let { it.length > 512 } == true }) return null
    return ImportDiagnostic(errorId, stage, scope, locations.map { ImportDiagnosticLocation(
        it.fileRef, it.displayName, it.rowRef, it.rowNumber, it.positionKind, it.documentPosition, it.transactionId) }, totalLocations)
}
internal fun apiErrorDiagnostic(body: String): ImportDiagnostic? = runCatching {
    val error = (apiJson.parseToJsonElement(body) as? JsonObject)?.get("error") as? JsonObject
    error?.get("diagnostic")?.let { apiJson.decodeFromJsonElement<CashDiagnosticDto>(it).toDomain() }
}.getOrNull()
