package com.finance.tracker.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.finance.tracker.data.LedgerAssistantFileContent
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.compose.rememberFileSaverLauncher
import io.github.vinceglb.filekit.write
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.time.Clock

@Composable
internal actual fun rememberAssistantAttachmentExporter(onError: () -> Unit): (LedgerAssistantFileContent) -> Unit {
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<LedgerAssistantFileContent?>(null) }
    val saver = rememberFileSaverLauncher(FileKitDialogSettings.createDefault(), onError = {
        pending = null
        onError()
    }) { result ->
        val file = pending
        if (file != null && result != null) {
            scope.launch {
                try {
                    if (file.metadata.canSave(Clock.System.now().toEpochMilliseconds())) result.write(file.text.encodeToByteArray())
                    else onError()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    onError()
                } finally {
                    pending = null
                }
            }
        } else {
            pending = null
        }
    }
    return { file ->
        if (pending == null && file.metadata.canSave(Clock.System.now().toEpochMilliseconds())) {
            pending = file
            saver.launch(
                suggestedName = file.metadata.name.substringBeforeLast('.'),
                defaultExtension = file.metadata.name.substringAfterLast('.'),
            )
        }
    }
}
