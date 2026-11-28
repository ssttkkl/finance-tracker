package com.finance.tracker.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.finance.tracker.core.LocalFinanceColorRoles
import com.finance.tracker.core.PlatformModalEscapeHandler
import com.finance.tracker.core.isWebPlatform
import com.finance.tracker.core.observeBrowserContextMenu
import com.finance.tracker.data.LedgerAssistantConversation
import com.finance.tracker.presentation.FinanceAlertDialog
import com.finance.tracker.presentation.AssistantHistoryDialog
import com.finance.tracker.presentation.LedgerAssistantUi
import com.finance.tracker.presentation.AssistantHistoryMenuTarget
import com.finance.tracker.presentation.localizedText
import com.finance.tracker.resources.Res
import com.finance.tracker.resources.ic_delete
import com.finance.tracker.resources.ic_edit
import com.finance.tracker.resources.ic_keep_off
import com.finance.tracker.resources.ic_more_vert
import com.finance.tracker.resources.ic_push_pin
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun AssistantHistoryRow(
    assistant: LedgerAssistantUi,
    conversation: LedgerAssistantConversation,
    title: String,
    selected: Boolean,
    showFocusRing: Boolean,
    touchLayout: Boolean,
    onSelect: () -> Unit,
) {
    val colors = LocalFinanceColorRoles.current
    val shape = MaterialTheme.shapes.medium
    val hoverSource = remember { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var touched by remember { mutableStateOf(false) }
    val menuLabel = localizedText("assistant_history_actions")
    val pinnedLabel = localizedText("assistant_history_pinned")
    val rowFocus = remember { FocusRequester() }
    val menuFocus = remember { FocusRequester() }

    var rowBounds by remember { mutableStateOf(Rect.Zero) }
    var browserBounds by remember { mutableStateOf(Rect.Zero) }
    val density = LocalDensity.current
    val actionWidth = with(density) { 48.dp.toPx() }
    fun openMenu(useRowFocus: Boolean = true) {
        if (isWebPlatform) {
            val anchor = Rect(rowBounds.right - actionWidth, rowBounds.top, rowBounds.right, rowBounds.bottom)
            assistant.historyMenu = AssistantHistoryMenuTarget(conversation.id, anchor, fromSidebar = true) {
                if (useRowFocus) rowFocus.requestFocus() else menuFocus.requestFocus()
            }
        } else menuOpen = true
    }
    if (isWebPlatform) DisposableEffect(Unit) {
        val remove = observeBrowserContextMenu { x, y ->
            val target = browserBounds.contains(Offset(x.toFloat(), y.toFloat()))
            if (target) openMenu()
            target
        }
        onDispose(remove)
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .onGloballyPositioned {
                rowBounds = it.boundsInRoot()
                browserBounds = it.boundsInWindow()
            }
            .onFocusChanged { focused = it.hasFocus }
            .focusGroup()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.F10 && event.isShiftPressed) {
                    openMenu()
                    true
                } else false
            }
            .clip(shape)
            .background(if (selected || hovered) colors.field else Color.Transparent)
            .border(if (focused && showFocusRing) 2.dp else 0.dp, colors.focus, shape)
            .hoverable(hoverSource)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type != PointerEventType.Press) continue
                        if (event.changes.any { it.type == PointerType.Touch }) touched = true
                        if (!isWebPlatform && event.buttons.isSecondaryPressed) {
                            event.changes.forEach { it.consume() }
                            menuOpen = true
                        }
                    }
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).heightIn(min = 48.dp)
                .focusRequester(rowFocus)
                .combinedClickable(
                    role = Role.Button,
                    onClick = onSelect,
                    onLongClickLabel = menuLabel,
                    onLongClick = { openMenu() },
                )
                .semantics {
                    this.selected = selected
                    if (conversation.pinned) stateDescription = pinnedLabel
                }
                .testTag("assistant.history")
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(4.dp), contentAlignment = Alignment.Center) {
                if (selected) Box(Modifier.width(3.dp).height(28.dp).background(colors.accent, MaterialTheme.shapes.small))
            }
            if (conversation.pinned) Icon(
                painterResource(Res.drawable.ic_push_pin),
                contentDescription = null,
                tint = colors.foreground.copy(alpha = 0.68f),
                modifier = Modifier.padding(start = 6.dp).size(16.dp),
            )
            Text(
                title, Modifier.padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (selected) colors.foreground else colors.foreground.copy(alpha = 0.68f),
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
            )
        }
        Box {
            if (hovered || focused || menuOpen || assistant.historyMenu?.id == conversation.id ||
                (selected && (touchLayout || touched))) {
                IconButton(
                    onClick = { openMenu(useRowFocus = false) },
                    modifier = Modifier.focusRequester(menuFocus).testTag("assistant.history.more"),
                ) {
                    Icon(painterResource(Res.drawable.ic_more_vert), menuLabel)
                }
            }
            AssistantNativeHistoryMenu(assistant, conversation, menuOpen) { menuOpen = false }
        }
    }
}

@Composable
internal fun AssistantCurrentHistoryMenu(assistant: LedgerAssistantUi, conversation: LedgerAssistantConversation) {
    var expanded by remember(conversation.id) { mutableStateOf(false) }
    val triggerFocus = remember { FocusRequester() }
    var anchor by remember { mutableStateOf(Rect.Zero) }
    Box(Modifier.onGloballyPositioned { anchor = it.boundsInRoot() }) {
        IconButton(
            onClick = {
                if (isWebPlatform) {
                    assistant.historyMenu = AssistantHistoryMenuTarget(conversation.id, anchor, fromSidebar = false) {
                        triggerFocus.requestFocus()
                    }
                } else expanded = true
            },
            modifier = Modifier.focusRequester(triggerFocus).testTag("assistant.header.more"),
        ) {
            Icon(painterResource(Res.drawable.ic_more_vert), localizedText("assistant_history_actions"))
        }
        AssistantNativeHistoryMenu(assistant, conversation, expanded) { expanded = false }
    }
}

@Composable
private fun AssistantNativeHistoryMenu(
    assistant: LedgerAssistantUi,
    conversation: LedgerAssistantConversation,
    expanded: Boolean,
    close: () -> Unit,
) {
    if (!isWebPlatform) {
        DropdownMenu(expanded, onDismissRequest = close, modifier = Modifier.testTag("assistant.history.menu")) {
            AssistantHistoryMenuItems(assistant, conversation, Modifier, close)
        }
    }
}

@Composable
internal fun AssistantWebHistoryMenu(assistant: LedgerAssistantUi?) {
    val target = assistant?.historyMenu ?: return
    val conversation = assistant.conversations.firstOrNull { it.id == target.id } ?: return
    val firstAction = remember(target) { FocusRequester() }
    val close = { assistant.historyMenu = null }
    PlatformModalEscapeHandler(enabled = true) {
        target.restoreFocus()
        close()
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().focusProperties { canFocus = false }.clearAndSetSemantics { }.clickable(
            interactionSource = null,
            indication = null,
            onClick = close,
        ))
        val width = minOf(208.dp, maxWidth)
        val density = LocalDensity.current
        val x = with(density) { target.anchor.left.toDp() }.coerceIn(0.dp, maxWidth - width)
        val bottom = with(density) { target.anchor.bottom.toDp() }
        val top = with(density) { target.anchor.top.toDp() }
        val height = if (assistant.canDeleteHistory(conversation)) 164.dp else 208.dp
        val y = if (bottom + height <= maxHeight) bottom else (top - height).coerceAtLeast(0.dp)
        Surface(
            Modifier.offset(x, y).width(width).testTag("assistant.history.menu"),
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 3.dp,
        ) {
            Column(Modifier.focusGroup().padding(vertical = 8.dp)) {
                AssistantHistoryMenuItems(assistant, conversation, Modifier.focusRequester(firstAction), close)
            }
        }
        LaunchedEffect(target) {
            androidx.compose.runtime.withFrameNanos { }
            firstAction.requestFocus()
        }
    }
}

@Composable
private fun AssistantHistoryMenuItems(
    assistant: LedgerAssistantUi,
    conversation: LedgerAssistantConversation,
    firstModifier: Modifier,
    close: () -> Unit,
) {
    val canDelete = assistant.canDeleteHistory(conversation)
    val pending = assistant.historyActionPending
    DropdownMenuItem(
        text = { Text(localizedText(if (conversation.pinned) "assistant_history_unpin" else "assistant_history_pin")) },
        leadingIcon = {
            val icon = if (conversation.pinned) Res.drawable.ic_keep_off else Res.drawable.ic_push_pin
            Icon(painterResource(icon), contentDescription = null)
        },
        enabled = !pending,
        onClick = { assistant.pinHistory(conversation.id, !conversation.pinned); close() },
        modifier = firstModifier.testTag("assistant.history.pin"),
    )
    DropdownMenuItem(
        text = { Text(localizedText("assistant_history_rename")) },
        leadingIcon = { Icon(painterResource(Res.drawable.ic_edit), contentDescription = null) },
        enabled = !pending,
        onClick = { assistant.requestHistoryDialog(AssistantHistoryDialog.Rename(conversation.id)); close() },
        modifier = Modifier.testTag("assistant.history.rename"),
    )
    HorizontalDivider()
    DropdownMenuItem(
        text = {
            Column {
                Text(localizedText("assistant_history_delete"))
                if (!canDelete) Text(localizedText("assistant_history_delete_blocked"), style = MaterialTheme.typography.bodySmall)
            }
        },
        leadingIcon = { Icon(painterResource(Res.drawable.ic_delete), contentDescription = null) },
        colors = MenuDefaults.itemColors(
            textColor = MaterialTheme.colorScheme.error,
            leadingIconColor = MaterialTheme.colorScheme.error,
        ),
        enabled = !pending && canDelete,
        onClick = { assistant.requestHistoryDialog(AssistantHistoryDialog.Delete(conversation.id)); close() },
        modifier = Modifier.testTag("assistant.history.delete"),
    )
}

@Composable
internal fun AssistantHistoryDialogs(assistant: LedgerAssistantUi?) {
    val dialog = assistant?.historyDialog ?: return
    val conversation = assistant.conversations.firstOrNull { it.id == dialog.id } ?: return
    val close = { assistant.requestHistoryDialog(null) }
    val renaming = dialog is AssistantHistoryDialog.Rename
    var title by remember(dialog) { mutableStateOf(conversation.historyTitle.orEmpty()) }
    val blocked = !assistant.canDeleteHistory(conversation) || assistant.historyDeleteBlocked
    PlatformModalEscapeHandler(enabled = isWebPlatform, onEscape = close)
    FinanceAlertDialog(
        onDismissRequest = close,
        title = { Text(localizedText(if (renaming) "assistant_history_rename" else "assistant_history_delete")) },
        text = {
            if (renaming) OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth().testTag("assistant.history.title"),
                singleLine = true,
                label = { Text(localizedText("assistant_history_name")) },
            ) else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(localizedText("assistant_history_delete_prompt"))
                if (blocked) Text(localizedText("assistant_history_delete_blocked"), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = close, enabled = !assistant.historyActionPending) { Text(localizedText("assistant_cancel")) }
        },
        confirmButton = {
            val enabled = !assistant.historyActionPending && if (renaming) title.isNotBlank() else !blocked
            Button(
                onClick = {
                    if (renaming) assistant.renameHistory(conversation.id, title)
                    else assistant.deleteHistory(conversation.id)
                },
                enabled = enabled,
                colors = if (renaming) ButtonDefaults.buttonColors()
                    else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.testTag("assistant.history.confirm"),
            ) { Text(localizedText(if (renaming) "assistant_history_save" else "assistant_history_delete")) }
        },
    )
}
