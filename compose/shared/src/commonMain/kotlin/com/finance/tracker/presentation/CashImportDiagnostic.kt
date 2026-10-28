package com.finance.tracker.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.finance.tracker.domain.*

@Composable
internal fun CashImportDiagnostic(diagnostic: ImportDiagnostic) {
    var expanded by remember(diagnostic.errorId) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("cash.import.diagnostic"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(localizedText("cash_import_diagnostic_stage", localizedText("cash_import_stage_${diagnostic.stage}")),
            style = MaterialTheme.typography.bodyMedium)
        if (diagnostic.scope == "request") Text(localizedText("cash_import_diagnostic_request"), style = MaterialTheme.typography.bodyMedium)
        else if (diagnostic.scope == "related" || diagnostic.totalLocations > 1) {
            Text(localizedText("cash_import_diagnostic_related"), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("cash.import.diagnostic-toggle")) {
                Text(localizedText(if (expanded) "cash_import_diagnostic_hide" else "cash_import_diagnostic_show", diagnostic.totalLocations.toString()))
            }
            if (expanded) Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                diagnostic.locations.forEach { DiagnosticLocation(it) }
                if (diagnostic.totalLocations > diagnostic.locations.size) Text(localizedText("cash_import_diagnostic_limit", diagnostic.locations.size.toString()))
            }
        } else diagnostic.locations.forEach { DiagnosticLocation(it) }
        Text(localizedText("cash_import_diagnostic_id", diagnostic.errorId), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DiagnosticLocation(location: ImportDiagnosticLocation) {
    val parts = listOfNotNull(location.displayName,
        location.documentPosition?.let { position ->
            val parts = importDiagnosticPdfParts(position)
            if (parts == null) position else localizedText("cash_import_diagnostic_pdf_row", parts[0], parts[1], parts[2])
        } ?: location.rowNumber?.let { number -> localizedText(when (location.positionKind) {
            "csv_record" -> "cash_import_diagnostic_csv_record"
            "worksheet_row" -> "cash_import_diagnostic_worksheet_row"
            else -> "cash_import_diagnostic_document_row"
        }, number.toString()) },
        location.transactionId?.let { localizedText("cash_import_diagnostic_transaction", it) })
    Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
}

internal fun importDiagnosticPdfParts(position: String): List<String>? {
    val parts = Regex("p([0-9]+):t([0-9]+):r([0-9]+)").matchEntire(position)?.groupValues ?: return null
    val table = parts[2].toIntOrNull()?.takeIf { it < Int.MAX_VALUE } ?: return null
    return listOf(parts[1], (table + 1).toString(), parts[3])
}
