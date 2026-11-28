package com.finance.tracker.presentation

import androidx.compose.ui.geometry.Rect

internal sealed interface AssistantHistoryDialog {
    val id: String
    data class Rename(override val id: String) : AssistantHistoryDialog
    data class Delete(override val id: String) : AssistantHistoryDialog
}

internal data class AssistantHistoryMenuTarget(
    val id: String,
    val anchor: Rect,
    val fromSidebar: Boolean,
    val restoreFocus: () -> Unit,
)
