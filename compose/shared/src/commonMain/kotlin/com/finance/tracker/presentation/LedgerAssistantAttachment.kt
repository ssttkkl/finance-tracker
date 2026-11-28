package com.finance.tracker.presentation

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.finance.tracker.core.LocalFinanceColorRoles
import com.finance.tracker.core.getLocalDateForInstant
import com.finance.tracker.data.LedgerAssistantFile
import com.finance.tracker.data.LedgerAssistantFileContent
import com.finance.tracker.resources.Res
import com.finance.tracker.resources.ic_description
import com.finance.tracker.resources.ic_file_download
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import kotlin.time.Clock
import kotlin.time.Instant

@Composable
internal fun AssistantAttachmentCard(
    file: LedgerAssistantFile,
    read: suspend () -> LedgerAssistantFileContent?,
) {
    val foreground = rememberPageVisible()
    val scope = rememberCoroutineScope()
    val deadline = file.expiresAtMs
    var expired by remember(file) { mutableStateOf(!file.canSave(Clock.System.now().toEpochMilliseconds())) }
    var loading by remember(file) { mutableStateOf(false) }
    var failed by remember(file) { mutableStateOf(false) }
    LaunchedEffect(file, foreground) {
        if (deadline == null) return@LaunchedEffect
        while (true) {
            val remaining = deadline - Clock.System.now().toEpochMilliseconds()
            expired = remaining <= 0
            if (expired || !foreground) break
            delay(remaining.coerceAtMost(60_000))
        }
    }

    val export = rememberAssistantAttachmentExporter { failed = true }
    val label = localizedText("assistant_attachment_save_named", file.name)
    var focused by remember(file) { mutableStateOf(false) }
    val actionModifier = Modifier.onFocusChanged { focused = it.isFocused }
        .semantics { contentDescription = label }
        .then(if (focused) Modifier.border(1.dp, LocalFinanceColorRoles.current.focus, MaterialTheme.shapes.medium) else Modifier)
    val saveAction: () -> Unit = {
        loading = true
        failed = false
        scope.launch {
            try {
                val content = read()
                if (!file.canSave(Clock.System.now().toEpochMilliseconds())) expired = true
                else if (content == null) failed = true
                else export(content)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
            } finally {
                loading = false
            }
        }
    }
    LedgerAssistantCard(
        LedgerAssistantCardKind.ATTACHMENT,
        Modifier.testTag("assistant.attachment"),
        onClick = saveAction.takeUnless { expired },
        enabled = !loading,
        actionModifier = actionModifier,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(Res.drawable.ic_description), contentDescription = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(file.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (deadline != null) {
                    Text(
                        localizedText("assistant_attachment_expiry", getLocalDateForInstant(Instant.fromEpochMilliseconds(deadline).toString())),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                if (expired || failed) {
                    Text(
                        localizedText(if (expired) "assistant_attachment_expired" else "assistant_attachment_export_failed"),
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = if (expired) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            if (!expired) Icon(painterResource(Res.drawable.ic_file_download), contentDescription = null)
        }
    }
}

@Composable
internal expect fun rememberAssistantAttachmentExporter(onError: () -> Unit): (LedgerAssistantFileContent) -> Unit
