package com.finance.tracker.presentation

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.finance.tracker.business.domain.parseCashStatementDelimited
import com.finance.tracker.data.LedgerAssistantFile
import com.finance.tracker.data.LedgerAssistantFileContent
import com.finance.tracker.resources.Res
import com.finance.tracker.resources.ic_description
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun AssistantAttachmentCard(file: LedgerAssistantFile, onOpen: (FocusRequester) -> Unit) {
    val focus = remember(file.id) { FocusRequester() }
    val actionName = localizedText("assistant_attachment_preview_named", file.name)
    LedgerAssistantCard(LedgerAssistantCardKind.ATTACHMENT, Modifier.testTag("assistant.attachment")) {
        Row(Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(Res.drawable.ic_description), contentDescription = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(file.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    localizedText("assistant_attachment_metadata", attachmentType(file.mimeType), file.size.toString()),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            TextButton(
                onClick = { onOpen(focus) },
                modifier = Modifier.focusRequester(focus).testTag("assistant.attachment-open").semantics {
                    contentDescription = actionName
                },
            ) {
                Text(localizedText("assistant_attachment_preview"))
            }
        }
    }
}

@Composable
internal fun AssistantAttachmentDialog(
    file: LedgerAssistantFile,
    read: suspend () -> LedgerAssistantFileContent?,
    onClose: () -> Unit,
) {
    var content by remember(file.id) { mutableStateOf<LedgerAssistantFileContent?>(null) }
    var loadFailed by remember(file.id) { mutableStateOf(false) }
    var exportFailed by remember(file.id) { mutableStateOf(false) }
    LaunchedEffect(file.id) {
        try {
            content = read()
            loadFailed = content == null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadFailed = true
        }
    }
    val exporter = rememberAssistantAttachmentExporter { exportFailed = true }
    val previewHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } * 0.52f
    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.testTag("assistant.attachment-dialog"),
        title = { Text(file.name) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = previewHeight).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val document = content
                when {
                    document != null -> AttachmentPreview(document)
                    loadFailed -> Text(
                        localizedText("assistant_attachment_unavailable"),
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error,
                    )
                    else -> CircularProgressIndicator()
                }
                if (exportFailed) Text(
                    localizedText("assistant_attachment_export_failed"),
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(localizedText("assistant_close")) } },
        confirmButton = {
            Button(onClick = {
                content?.let {
                    exportFailed = false
                    exporter(it)
                }
            }, enabled = content != null, modifier = Modifier.testTag("assistant.attachment-export")) {
                Text(localizedText("assistant_attachment_export"))
            }
        },
    )
}

@Composable
private fun AttachmentPreview(document: LedgerAssistantFileContent) {
    SelectionContainer {
        if (document.metadata.mimeType == "text/csv") {
            val records = remember(document) { parseCashStatementDelimited(document.text, ',', rowLimit = 31) }
            val columns = remember(records) { records.take(30).maxOfOrNull { it.size }?.coerceAtMost(20) ?: 0 }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.horizontalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    records.take(30).forEachIndexed { index, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            repeat(columns) { column ->
                                Text(row.getOrNull(column).orEmpty().take(2048), Modifier.width(120.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (index == 0) FontWeight.SemiBold else FontWeight.Normal)
                            }
                        }
                        HorizontalDivider()
                    }
                }
                if (records.size > 30 || records.any { it.size > 20 }) {
                    Text(localizedText("assistant_attachment_table_limit"), style = MaterialTheme.typography.labelMedium)
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(document.text.take(12_000), style = MaterialTheme.typography.bodyMedium)
                if (document.text.length > 12_000) {
                    Text(localizedText("assistant_attachment_text_limit"), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun attachmentType(mime: String) = when (mime) {
    "text/csv" -> "CSV"
    "text/markdown" -> "Markdown"
    else -> localizedText("assistant_attachment_text")
}

@Composable
internal expect fun rememberAssistantAttachmentExporter(onError: () -> Unit): (LedgerAssistantFileContent) -> Unit
