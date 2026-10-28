package com.finance.tracker.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.finance.tracker.core.WindowSizeClass

@Composable
internal fun CashImportProcessingPanel(state: ImportTaskUiState, sizeClass: WindowSizeClass,
    onMore: () -> Unit, onRetry: () -> Unit, onClose: () -> Unit) {
    CashRecordModalPanel(sizeClass, localizedText("cash_import_processing_details"), true,
        semanticStateKey = state.plan?.planId, onDismiss = onClose, onBack = onClose) {
        LazyColumn(Modifier.fillMaxSize().padding(16.dp).testTag("cash.import.processing-details"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.errorCode?.let { item { Text(cashImportTaskErrorText(it), color = MaterialTheme.colorScheme.error) } }
            itemsIndexed(state.processingItems) { _, item ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.plan?.files?.firstOrNull { it.fileRef == item.fileRef }?.let { Text(it.displayName, style = MaterialTheme.typography.titleSmall) }
                    Text(localizedText(if (item.plannedAction in setOf("duplicate", "duplicate_in_batch")) "cash_import_duplicate" else "cash_import_not_imported"), style = MaterialTheme.typography.titleSmall)
                    item.message?.takeIf(String::isNotBlank)?.let { Text(it) }
                        ?: item.code?.let { Text(cashImportTaskErrorText(it)) }
                    HorizontalDivider()
                }
            }
            if (!state.processingInitialized && !state.busy) item { TextButton(onClick = onRetry) { Text(localizedText("copy_e2d53a6d3a")) } }
            if (state.processingNextCursor != null) item { TextButton(onClick = onMore, enabled = !state.busy) { Text(localizedText("cash_import_more")) } }
        }
    }
}
