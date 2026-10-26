package com.finance.tracker.domain

data class ImportDiagnosticLocation(
    val fileRef: String? = null, val displayName: String? = null, val rowRef: String? = null,
    val rowNumber: Int? = null, val positionKind: String? = null,
    val documentPosition: String? = null, val transactionId: String? = null,
)
data class ImportDiagnostic(
    val errorId: String, val stage: String, val scope: String,
    val locations: List<ImportDiagnosticLocation>, val totalLocations: Int,
)
