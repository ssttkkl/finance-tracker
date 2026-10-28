package com.finance.tracker.data

import com.finance.tracker.domain.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable
data class CashDiagnosticLocationDto(
    @SerialName("file_ref") val fileRef: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("row_ref") val rowRef: String? = null,
    @SerialName("row_number") val rowNumber: Int? = null,
    @SerialName("position_kind") val positionKind: String? = null,
    @SerialName("document_position") val documentPosition: String? = null,
    @SerialName("transaction_id") val transactionId: String? = null,
)
@Serializable
data class CashDiagnosticDto(
    @SerialName("error_id") val errorId: String,
    val stage: String, val scope: String, val locations: List<CashDiagnosticLocationDto>,
    @SerialName("total_locations") val totalLocations: Int,
)
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
