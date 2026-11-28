package com.finance.tracker.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import com.finance.tracker.data.LedgerAssistantFileContent
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.download
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.time.Clock

@Composable
internal actual fun rememberAssistantAttachmentExporter(onError: () -> Unit): (LedgerAssistantFileContent) -> Unit {
    val scope = rememberCoroutineScope()
    return { file ->
        scope.launch {
            try {
                if (file.metadata.canSave(Clock.System.now().toEpochMilliseconds())) {
                    FileKit.download(file.text.encodeToByteArray(), file.metadata.name)
                } else onError()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onError()
            }
        }
    }
}
