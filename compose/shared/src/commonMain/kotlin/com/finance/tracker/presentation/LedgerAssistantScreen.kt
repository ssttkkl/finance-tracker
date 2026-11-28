package com.finance.tracker.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import com.finance.tracker.resources.Res
import com.finance.tracker.resources.ic_arrow_upward
import com.finance.tracker.resources.ic_build
import com.finance.tracker.resources.ic_chat
import com.finance.tracker.resources.ic_chevron_right
import com.finance.tracker.resources.ic_close
import com.finance.tracker.resources.ic_expand_less
import com.finance.tracker.resources.ic_expand_more
import org.jetbrains.compose.resources.painterResource
import dev.ynagai.a2ui.compose.ComponentRenderer
import dev.ynagai.a2ui.compose.rememberAction
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.finance.tracker.client.LedgerAssistantDeliveryStatus
import com.finance.tracker.core.CashMaintenanceRecoveryStore
import com.finance.tracker.core.WindowSizeClass
import com.finance.tracker.core.PlatformModalEscapeHandler
import com.finance.tracker.core.LocalFinanceColorRoles
import com.finance.tracker.core.isWebPlatform
import com.finance.tracker.core.DiagnosticLogger
import com.finance.tracker.core.DiagnosticAction
import com.finance.tracker.core.DiagnosticFeature
import com.finance.tracker.core.getLocalTimeZoneId
import com.finance.tracker.data.toDomain
import com.finance.tracker.domain.Account
import com.finance.tracker.domain.CashCategory
import com.finance.tracker.domain.LedgerOptions
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.paneTitle
import com.finance.tracker.data.LedgerAssistantConversation
import com.finance.tracker.data.LedgerAssistantDraft
import com.finance.tracker.data.LedgerAssistantMessage
import com.finance.tracker.data.LedgerAssistantToolResult
import com.finance.tracker.data.LedgerAssistantToolStatus
import com.finance.tracker.data.LedgerAssistantToolTrace
import com.finance.tracker.data.LedgerAssistantWorkspace
import com.finance.tracker.domain.CashMaintenanceRepository
import com.finance.tracker.domain.CashSourceEvidence
import dev.ynagai.a2ui.compose.A2uiRenderer
import dev.ynagai.a2ui.compose.A2uiSurface
import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.ActionMessage
import dev.ynagai.a2ui.core.protocol.AgentToRendererMessage
import dev.ynagai.a2ui.material3.Material3Components
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class LedgerAssistantUi(
    private val scope: CoroutineScope,
    private val diagnostics: DiagnosticLogger,
    private val selectedConversationId: () -> String?,
    private val captureConversationPublisher: () -> ((String) -> Unit),
    private val open: suspend () -> LedgerAssistantWorkspace,
) {
    var conversations by mutableStateOf<List<LedgerAssistantConversation>>(emptyList())
        private set
    val selectedId: String? get() = selectedConversationId()
    var busy by mutableStateOf(false)
        private set
    var confirmingId by mutableStateOf<String?>(null)
        private set
    var ready by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    var canWrite by mutableStateOf(false)
        private set

    var accounts by mutableStateOf<List<Account>>(emptyList())
        private set
    var categories by mutableStateOf<List<CashCategory>>(emptyList())
        private set
    var options by mutableStateOf(LedgerOptions())
        private set

    private var workspace: LedgerAssistantWorkspace? = null
    val current: LedgerAssistantConversation? get() = conversations.firstOrNull { it.id == selectedId }

    private val deletedHistory = mutableSetOf<String>()
    private var historyWriteId by mutableStateOf<String?>(null)
    var historyMenu by mutableStateOf<AssistantHistoryMenuTarget?>(null)
    var historyDialog by mutableStateOf<AssistantHistoryDialog?>(null)
        private set
    var historyActionPending by mutableStateOf(false)
        private set
    var historyDeleteBlocked by mutableStateOf(false)
        private set

    fun canDeleteHistory(conversation: LedgerAssistantConversation): Boolean =
        conversation.id != historyWriteId && !conversation.hasUnresolvedWrite

    fun requestHistoryDialog(dialog: AssistantHistoryDialog?) {
        if (historyActionPending) return
        historyDeleteBlocked = false
        historyDialog = dialog
    }

    fun pinHistory(id: String, pinned: Boolean) = updateHistory { bound ->
        bound.pin(id, pinned)?.let(::accept)
    }

    fun renameHistory(id: String, title: String) = updateHistory { bound ->
        bound.rename(id, title)?.let(::accept)
        historyDialog = null
    }

    fun deleteHistory(id: String) = updateHistory { bound ->
        if (conversations.firstOrNull { it.id == id }?.let(::canDeleteHistory) == false || !bound.delete(id)) {
            historyDeleteBlocked = true
        } else {
            deletedHistory += id
            conversations = conversations.filterNot { it.id == id }
            historyDialog = null
        }
    }

    private fun updateHistory(action: suspend (LedgerAssistantWorkspace) -> Unit) {
        val bound = workspace ?: return
        if (historyActionPending) return
        historyActionPending = true
        scope.launch {
            try {
                action(bound)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report(failure)
            } finally {
                historyActionPending = false
            }
        }
    }

    fun load() = run {
        val bound = open()
        workspace = bound
        canWrite = bound.canWrite
        accounts = bound.draftAccounts().map { Account(it.id, it.name, it.type, it.active, it.currencies) }
        categories = bound.draftCategories().toDomain().items
        val ledgerOptions = bound.draftOptions().toDomain()
        options = ledgerOptions.copy(recordTypes = ledgerOptions.recordTypes.filter { it.value in setOf("consumption", "income") })
        conversations = bound.load().filterNot { it.id in deletedHistory }
        ready = true
    }

    fun clearNavigationError() {
        if (ready) failed = false
    }

    fun send(text: String, onSaved: () -> Unit = {}) {
        val selected = current
        if (selected != null && confirmingId == selected.id && selected.draft?.command?.receipt == null) {
            val bound = checkNotNull(workspace)
            scope.launch {
                try {
                    accept(bound.send(selected.id, text) { accept(it); onSaved() })
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    report(failure)
                }
            }
            return
        }
        val selectedId = selected?.id
        val publishConversation = if (selectedId == null) captureConversationPublisher() else null
        run {
            val bound = checkNotNull(workspace)
            val id = selectedId ?: bound.create().id
            var published = false
            accept(bound.send(id, text) {
                accept(it)
                if (!published) {
                    published = true
                    publishConversation?.invoke(id)
                    onSaved()
                }
            })
        }
    }

    fun retry(id: String, messageId: String) = run {
        accept(checkNotNull(workspace).retry(id, messageId, ::accept))
    }

    fun confirm(id: String, callId: String, fields: JsonObject) = run {
        historyWriteId = id
        try {
            accept(checkNotNull(workspace).confirm(id, callId, fields) { prepared ->
                accept(prepared)
                confirmingId = id
            })
        } finally {
            confirmingId = null
            historyWriteId = null
        }
    }

    fun edit(id: String, callId: String, fields: JsonObject) {
        val bound = workspace ?: return
        scope.launch {
            try {
                accept(bound.edit(id, callId, fields))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report(failure)
            }
        }
    }

    fun cancelDraft(id: String, callId: String) = run {
        accept(checkNotNull(workspace).cancelDraft(id, callId))
    }

    fun recoverWrite(id: String) = run {
        historyWriteId = id
        try {
            accept(checkNotNull(workspace).recoverWrite(id, ::accept))
        } finally {
            historyWriteId = null
        }
    }

    private fun accept(conversation: LedgerAssistantConversation) {
        if (conversation.id in deletedHistory || conversations.any { it.id == conversation.id && it.revision > conversation.revision }) return
        conversations = listOf(conversation) + conversations.filterNot { it.id == conversation.id }
    }

    private fun report(failure: Exception) {
        failed = true
        diagnostics.record(DiagnosticFeature.LEDGER_ASSISTANT, DiagnosticAction.UPDATE, null, null, failure::class.simpleName)
    }

    private fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        failed = false
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report(failure)
                workspace?.let { bound ->
                    try { conversations = bound.load().filterNot { it.id in deletedHistory } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (refreshFailure: Exception) { report(refreshFailure) }
                }
            } finally {
                busy = false
            }
        }
    }
}

private fun assistantMessageRows(messages: List<LedgerAssistantMessage>): List<List<LedgerAssistantMessage>> = buildList {
    messages.forEach { message ->
        val previous = lastOrNull()
        val preceding = previous?.lastOrNull()
        val continuesTools = preceding?.toolTrace != null && message.toolTrace != null &&
            preceding.result?.hasVisibleCard() != true
        val followsTools = message.reply != null && previous?.firstOrNull()?.toolTrace != null
        if (previous != null && (continuesTools || followsTools)) {
            this[lastIndex] = previous + message
        } else {
            add(listOf(message))
        }
    }
}

@Composable
internal fun LedgerAssistantScreen(
    ui: LedgerAssistantUi?,
    maintenance: CashMaintenanceRepository,
    recovery: CashMaintenanceRecoveryStore,
    sizeClass: WindowSizeClass,
) {
    val connection = LocalSessionConnection.current
    val current = ui?.current
    val markdownImages = rememberAssistantMarkdownImages()
    val rows = remember(current?.messages) { assistantMessageRows(current?.messages.orEmpty()) }
    val currentMessages = current?.messages.orEmpty()
    val lastInput = currentMessages.indexOfLast { it.delivery != null }
    val lastDelivery = currentMessages.getOrNull(lastInput)?.delivery
    val preview = current?.incompleteReply
    val hasPreview = !preview.isNullOrBlank()
    val visibleReply = hasPreview || currentMessages.drop(lastInput + 1).any { !it.reply.isNullOrBlank() }
    val awaitingReply = lastDelivery?.status == LedgerAssistantDeliveryStatus.SENDING
    val replyInterrupted = visibleReply && (lastDelivery?.status == LedgerAssistantDeliveryStatus.NOT_DELIVERED ||
        (hasPreview && current?.draft?.command?.receipt != null && ui?.busy == false))

    var input by remember(current?.id) { mutableStateOf("") }
    var detail by remember(current?.id) { mutableStateOf<String?>(null) }
    var toolSheet by remember(current?.id) { mutableStateOf<AssistantToolSelection?>(null) }
    var toolReturnFocus by remember(current?.id) { mutableStateOf<FocusRequester?>(null) }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(toolReturnFocus, windowFocused) {
        val trigger = toolReturnFocus ?: return@LaunchedEffect
        if (windowFocused) {
            androidx.compose.runtime.withFrameNanos { }
            trigger.requestFocus()
            toolReturnFocus = null
        }
    }
    val listState = key(current?.id) { rememberLazyListState() }
    var followNextUpdate by remember(current?.id) { mutableStateOf(true) }
    var previousMessageCount by remember(current?.id) { mutableStateOf(0) }
    val hasDraft = current?.draft?.cancelled == false
    LaunchedEffect(current?.messages, hasDraft, hasPreview, replyInterrupted, current?.draft?.command?.receipt) {
        val messageCount = rows.size
        val itemCount = messageCount + (if (hasDraft) 1 else 0) + (if (hasPreview || replyInterrupted) 1 else 0)
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (toolSheet == null && itemCount > 0 && !listState.isScrollInProgress &&
            (followNextUpdate || lastVisible >= previousMessageCount - 1)) {
            listState.scrollToItem(itemCount - 1)
        }
        previousMessageCount = messageCount
        followNextUpdate = false
    }
    val canQueue = current != null && ui?.confirmingId == current.id &&
        current.draft?.command != null && current.draft.command.receipt == null
    val canSend = ui?.ready == true && (!ui.busy || canQueue) && connection.canSubmit &&
        current?.messages?.none {
            it.delivery?.status in setOf(LedgerAssistantDeliveryStatus.NOT_DELIVERED, LedgerAssistantDeliveryStatus.WAITING_FOR_RECORD)
        } != false
    val colors = MaterialTheme.colorScheme
    fun send() {
        followNextUpdate = true
        val submitted = input
        ui?.send(submitted) { if (input == submitted) input = "" }
    }
    Box(
        Modifier.fillMaxSize().background(colors.background).testTag("page.ledger-assistant"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 768.dp).fillMaxWidth().fillMaxHeight()
                .padding(horizontal = if (sizeClass == WindowSizeClass.COMPACT) 16.dp else 24.dp),
            verticalArrangement = if (current == null) Arrangement.Center else Arrangement.Top,
        ) {
            if (current == null && ui?.selectedId == null) {
                AssistantGlyph(Modifier.size(42.dp), colors.primary)
                Text(
                    localizedText("assistant_empty_heading"),
                    Modifier.padding(top = 20.dp, bottom = 22.dp),
                    color = colors.onBackground,
                    style = if (sizeClass == WindowSizeClass.COMPACT) MaterialTheme.typography.headlineMedium
                        else MaterialTheme.typography.displaySmall,
                )
                AssistantComposer(input, { input = it }, canSend && input.isNotBlank(), true, sizeClass, ::send)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val query = localizedText("assistant_example_query_prompt")
                    val write = localizedText("assistant_example_write_prompt")
                    TextButton(onClick = { ui?.send(query) }, enabled = canSend) { Text(localizedText("assistant_example_query")) }
                    TextButton(onClick = { ui?.send(write) }, enabled = canSend) { Text(localizedText("assistant_example_write")) }
                }
            } else if (current != null) {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    state = listState,
                    contentPadding = PaddingValues(top = if (sizeClass == WindowSizeClass.COMPACT) 16.dp else 32.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    items(rows) { row ->
                        val message = row.first()
                        message.delivery?.let { delivery ->
                            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                                val bubbleMaxWidth = maxWidth * 0.85f
                                Column(horizontalAlignment = Alignment.End) {
                                    Column(
                                        Modifier.widthIn(max = bubbleMaxWidth).clip(MaterialTheme.shapes.large)
                                            .background(colors.secondaryContainer)
                                            .testTag("assistant.user-bubble")
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                    ) {
                                        Text(delivery.input, Modifier.testTag("assistant.user-message"), color = colors.onSecondaryContainer, style = MaterialTheme.typography.bodyLarge)
                                        if (delivery.status == LedgerAssistantDeliveryStatus.WAITING_FOR_RECORD) {
                                            Text(
                                                localizedText("assistant_save_unknown"),
                                                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                                color = colors.onSecondaryContainer,
                                            )
                                        }
                                    }
                                    if (delivery.status == LedgerAssistantDeliveryStatus.NOT_DELIVERED && !replyInterrupted) {
                                        AssistantRetryStatus(
                                            "assistant_not_delivered", Modifier.testTag("assistant.delivery-status"),
                                            enabled = !ui.busy && connection.canSubmit,
                                        ) {
                                            followNextUpdate = true
                                            ui.retry(current.id, delivery.messageId)
                                        }
                                    } else if (delivery == lastDelivery && awaitingReply && !visibleReply) {
                                        LinearProgressIndicator(
                                            Modifier.padding(top = 6.dp).width(84.dp).height(2.dp)
                                                .testTag("assistant.waiting"),
                                        )
                                    }
                                }
                            }
                        }
                        if (message.toolTrace != null || message.reply != null || message.result != null) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (message.toolTrace != null) {
                                    Column(Modifier.fillMaxWidth()) {
                                        row.forEachIndexed { index, item ->
                                            item.toolTrace?.let { trace ->
                                                key(current.id, index, trace.tool, trace.draftId) {
                                                    AssistantToolSummary(trace) { toolSheet = AssistantToolSelection(trace, it) }
                                                }
                                            }
                                        }
                                    }
                                }
                                row.forEach { item -> item.reply?.let { AssistantReply(it, markdownImages) } }
                                row.forEach { item ->
                                    item.result?.let { AssistantResultCard(it, sizeClass, ui.accounts, onDetail = { id -> detail = id }) }
                                }
                            }
                        }
                    }
                    if (hasPreview || replyInterrupted) item {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            preview?.let { AssistantReply(it, markdownImages) }
                            if (replyInterrupted) AssistantRetryStatus(
                                "assistant_reply_interrupted", Modifier.testTag("assistant.reply-interrupted"),
                                enabled = !ui.busy && connection.canSubmit,
                            ) {
                                followNextUpdate = true
                                if (lastDelivery?.status == LedgerAssistantDeliveryStatus.NOT_DELIVERED) {
                                    ui.retry(current.id, lastDelivery.messageId)
                                } else ui.recoverWrite(current.id)
                            }
                        }
                    }
                    if (current.draft != null && !current.draft.cancelled) item {
                        val draft = current.draft
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when {
                            draft.command != null -> {
                                if (draft.command.receipt == null) Text(localizedText("assistant_save_unknown"))
                                TextButton(
                                    enabled = !ui.busy && connection.canSubmit,
                                    onClick = { ui.recoverWrite(current.id) },
                                ) { Text(localizedText("assistant_retry")) }
                            }
                            !ui.canWrite -> Text(localizedText("assistant_read_only"))
                            else -> AssistantDraftCard(
                                draft, ui.accounts, ui.categories, ui.options, sizeClass, ui.busy,
                                canConfirm = connection.canSubmit,
                                onEdit = { fields -> ui.edit(current.id, draft.localId, fields) },
                                onConfirm = { fields -> ui.confirm(current.id, draft.localId, fields) },
                                onCancel = { ui.cancelDraft(current.id, draft.localId) },
                            )
                        }
                        }
                    }
                }

                AssistantComposer(input, { input = it }, canSend && input.isNotBlank(), false, sizeClass, ::send)
            }
            if (ui?.busy == true && !awaitingReply && !hasPreview) CircularProgressIndicator(Modifier.padding(8.dp).testTag("assistant.loading"))
            if (ui == null || ui.failed) StateMessage(localizedText("assistant_unavailable"), isError = true) {
                if (ui != null && !ui.ready) TextButton(onClick = ui::load) { Text(localizedText("assistant_retry")) }
            }
        }
    }
    toolSheet?.let { selection ->
        AssistantToolSheet(selection.trace) {
            toolSheet = null
            toolReturnFocus = selection.focusRequester
        }
    }
    detail?.let { id ->
        AssistantRecordDetail(id, sizeClass, maintenance, recovery) { detail = null }
    }
}

@Composable
private fun AssistantComposer(
    value: String,
    onChange: (String) -> Unit,
    canSend: Boolean,
    empty: Boolean,
    sizeClass: WindowSizeClass,
    onSend: () -> Unit,
) {
    Text(
        localizedText("assistant_accuracy_notice"),
        Modifier.fillMaxWidth().padding(bottom = 6.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
    val hint = localizedText("assistant_prompt")
    Box(Modifier.fillMaxWidth().testTag("assistant.composer")) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth()
            .padding(bottom = if (empty) 0.dp else if (sizeClass == WindowSizeClass.COMPACT) 8.dp else 16.dp)
            .testTag("assistant.input").semantics { contentDescription = hint },
        shape = MaterialTheme.shapes.extraLarge,
        placeholder = { Text(hint) },
        minLines = 1,
        maxLines = 6,
        trailingIcon = {
            FilledIconButton(
                onClick = onSend,
                enabled = canSend,
                modifier = Modifier.padding(end = 4.dp).testTag("assistant.send"),
            ) {
                Icon(painterResource(Res.drawable.ic_arrow_upward), localizedText("assistant_send"))
            }
        },
    )
    }
}

@Composable
private fun AssistantRetryStatus(label: String, modifier: Modifier, enabled: Boolean, onRetry: () -> Unit) {
    val color = MaterialTheme.colorScheme.error
    val style = MaterialTheme.typography.labelSmall
    val retryLabel = localizedText("assistant_retry")
    Row(modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            localizedText(label),
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = color, style = style, fontWeight = FontWeight.Normal,
        )
        Text(
            retryLabel,
            Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onRetry)
                .semantics { contentDescription = retryLabel }
                .padding(start = 8.dp, top = 3.dp, bottom = 3.dp).testTag("assistant.retry"),
            color = color, style = style, fontWeight = FontWeight.Bold,
        )
    }
}

private fun formatAssistantToolData(data: JsonElement, indent: String = ""): String = when (data) {
    is JsonObject -> if (data.isEmpty()) "$indent{}" else data.entries.joinToString("\n") { (name, value) ->
        "$indent$name" + if (value is JsonPrimitive) "  ${value.content}" else "\n" + formatAssistantToolData(value, "$indent  ")
    }
    is JsonArray -> if (data.isEmpty()) "$indent[]" else data.mapIndexed { index, value ->
        "$indent[${index + 1}]\n" + formatAssistantToolData(value, "$indent  ")
    }.joinToString("\n")
    is JsonPrimitive -> "$indent${data.content}"
}

private data class AssistantToolSelection(val trace: LedgerAssistantToolTrace, val focusRequester: FocusRequester)

@Composable
private fun assistantToolLabel(tool: String) = localizedText(when (tool) {
    "list_accounts" -> "assistant_tool_accounts"
    "list_categories" -> "assistant_tool_categories"
    "list_transactions" -> "assistant_tool_transactions"
    "summarize_transactions" -> "assistant_tool_summary"
    "get_transaction" -> "assistant_tool_detail"
    "create_transaction" -> "assistant_tool_create"
    else -> "assistant_tool_unknown"
})

@Composable
private fun assistantToolStatus(status: LedgerAssistantToolStatus) = localizedText(when (status) {
    LedgerAssistantToolStatus.RUNNING -> "assistant_tool_running"
    LedgerAssistantToolStatus.INTERRUPTED -> "assistant_tool_interrupted"
    LedgerAssistantToolStatus.COMPLETED -> "assistant_tool_completed"
    LedgerAssistantToolStatus.FAILED -> "assistant_tool_failed"
    LedgerAssistantToolStatus.WAITING -> "assistant_tool_waiting"
    LedgerAssistantToolStatus.SUBMITTING -> "assistant_save_unknown"
    LedgerAssistantToolStatus.CANCELLED -> "assistant_cancelled"
    LedgerAssistantToolStatus.SAVED -> "assistant_saved"
})

@Composable
private fun AssistantToolSummary(trace: LedgerAssistantToolTrace, onOpen: (FocusRequester) -> Unit) {
    val restoreFocus = remember { FocusRequester() }
    val label = assistantToolLabel(trace.tool)
    val status = assistantToolStatus(trace.status)
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val focused by interactions.collectIsFocusedAsState()
    val scheme = MaterialTheme.colorScheme
    val tint = if (hovered || focused) scheme.onSurface else scheme.onSurfaceVariant
    val shape = MaterialTheme.shapes.small
    Row(
        modifier = Modifier.focusRequester(restoreFocus).testTag("assistant.tool.toggle")
            .then(if (focused) Modifier.border(1.dp, LocalFinanceColorRoles.current.focus, shape) else Modifier)
            .clickable(
                interactionSource = interactions,
                indication = null,
                role = Role.Button,
                onClick = { onOpen(restoreFocus) },
            )
            .heightIn(min = 32.dp)
            .widthIn(min = 48.dp)
            .padding(end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            painterResource(if (hovered) Res.drawable.ic_chevron_right else Res.drawable.ic_build),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = tint,
        )
        Text("$label · $status", color = tint, style = MaterialTheme.typography.labelMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssistantToolSheet(trace: LedgerAssistantToolTrace, onDismiss: () -> Unit) {
    val label = assistantToolLabel(trace.tool)
    val status = assistantToolStatus(trace.status)
    val closeFocus = remember { FocusRequester() }
    val bodyHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } * 0.68f
    val sheetModifier = Modifier.testTag("assistant.tool.sheet").semantics { paneTitle = label }
    if (isWebPlatform) {
        // Keep the Web sheet in the app canvas so pointer and focus coordinates share the root.
        PlatformModalEscapeHandler(enabled = true, onEscape = onDismiss)
        WebRootOverlay {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Box(
                    Modifier.matchParentSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                        .clickable(interactionSource = null, indication = null, onClick = onDismiss),
                )
                Surface(
                    modifier = sheetModifier.widthIn(max = 640.dp).fillMaxWidth().semantics { dialog() },
                    shape = BottomSheetDefaults.ExpandedShape,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(
                        Modifier.fillMaxWidth().focusProperties { onExit = { cancelFocusChange() } }.focusGroup(),
                    ) {
                        BottomSheetDefaults.DragHandle(Modifier.align(Alignment.CenterHorizontally))
                        AssistantToolSheetContent(trace, label, status, bodyHeight, closeFocus, onDismiss)
                    }
                }
            }
            LaunchedEffect(closeFocus) { closeFocus.requestFocus() }
        }
    } else {
        val sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            sheetMaxWidth = 640.dp,
            modifier = sheetModifier,
            properties = ModalBottomSheetProperties(shouldDismissOnBackPress = true),
        ) {
            AssistantToolSheetContent(trace, label, status, bodyHeight, closeFocus, onDismiss)
            LaunchedEffect(sheetState.currentValue) {
                if (sheetState.currentValue == SheetValue.Expanded) closeFocus.requestFocus()
            }
        }
    }
}

@Composable
private fun AssistantToolSheetContent(
    trace: LedgerAssistantToolTrace,
    label: String,
    status: String,
    bodyHeight: androidx.compose.ui.unit.Dp,
    closeFocus: FocusRequester,
    onDismiss: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        IconButton(onClick = onDismiss, modifier = Modifier.focusRequester(closeFocus).testTag("assistant.tool.close")) {
            Icon(painterResource(Res.drawable.ic_close), localizedText("assistant_close_tool"))
        }
    }
    Column(
        Modifier.fillMaxWidth().heightIn(max = bodyHeight).verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp).testTag("assistant.tool.detail"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AssistantToolDetailSection(localizedText("assistant_tool_status"), status)
        AssistantToolDetailSection(localizedText("assistant_tool_call_name"), trace.tool)
        AssistantToolDetailSection(
            localizedText("assistant_tool_parameters"),
            remember(trace.parameters) { formatAssistantToolData(trace.parameters) },
        )
        AssistantToolDetailSection(
            localizedText("assistant_tool_result"),
            trace.result?.let { result ->
                remember(result) { formatAssistantToolData(result) }
            } ?: status,
        )
    }
}

@Composable
private fun AssistantToolDetailSection(label: String, value: String) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = color, style = MaterialTheme.typography.labelMedium)
        Text(value, color = color, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun AssistantGlyph(modifier: Modifier, color: androidx.compose.ui.graphics.Color) {
    Icon(painterResource(Res.drawable.ic_chat), contentDescription = null, modifier = modifier, tint = color)
}

private fun LedgerAssistantToolResult.directoryEntries(): List<JsonObject> {
    val key = if (tool == "list_accounts") "accounts" else "items"
    return (payload[key] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
}

private fun LedgerAssistantToolResult.detailRecordId(): String? {
    val group = payload["group"] as? JsonObject
    val members = (group?.get("members") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
    return members.firstOrNull()?.string("transaction_id")
}

private fun LedgerAssistantToolResult.hasVisibleCard(): Boolean = when (tool) {
    "list_accounts", "list_categories" -> directoryEntries().isNotEmpty()
    "get_transaction" -> detailRecordId() != null
    else -> true
}

@Composable
private fun AssistantResultCard(
    result: LedgerAssistantToolResult,
    sizeClass: WindowSizeClass,
    accounts: List<Account>,
    onDetail: (String) -> Unit,
) {
    when (result.tool) {
        "list_transactions" -> LedgerAssistantCard(LedgerAssistantCardKind.QUERY) {
            val rows = (result.payload["items"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            if (rows.isEmpty()) Text(localizedText("assistant_no_records"))
            val fallback = localizedText("assistant_details")
            rows.forEach { row ->
                val id = row.string("transaction_id") ?: return@forEach
                val title = row.string("counterparty")?.takeIf { it.isNotBlank() }
                    ?: row.string("note")?.takeIf { it.isNotBlank() } ?: fallback
                val amount = listOfNotNull(row.string("amount"), row.string("currency")).joinToString(" ")
                TextButton(onClick = { onDetail(id) }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (amount.isNotBlank()) FinanceAmount(amount)
                    }
                }
            }
        }
        "summarize_transactions" -> AssistantSummaryCard(result, sizeClass, onDetail)
        "list_accounts", "list_categories" -> {
            val key = if (result.tool == "list_accounts") "accounts" else "items"
            val entries = result.directoryEntries()
            if (entries.isNotEmpty()) LedgerAssistantCard(LedgerAssistantCardKind.QUERY) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(localizedText(if (key == "accounts") "assistant_account" else "assistant_categories"),
                        style = MaterialTheme.typography.titleSmall)
                    entries.forEach { entry -> entry.string("name")?.let { Text(it) } }
                }
            }
        }
        "get_transaction" -> {
            result.detailRecordId()?.let { id ->
                AssistantActionSurface(localizedText("assistant_details"), "details") { onDetail(id) }
            }
        }
        "saved_transaction" -> LedgerAssistantCard(LedgerAssistantCardKind.RESULT) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(localizedText("assistant_saved"), style = MaterialTheme.typography.titleSmall)
                result.payload.string("account_name")?.let { Text(it) }
                val amount = listOfNotNull(result.payload.string("amount"), result.payload.string("currency")).joinToString(" ")
                if (amount.isNotBlank()) FinanceAmount(amount)
                result.payload.string("retained_transaction_id")?.let { id ->
                    AssistantActionSurface(localizedText("assistant_details"), "details") { onDetail(id) }
                }
            }
        }
        "cancelled_transaction" -> LedgerAssistantCard(LedgerAssistantCardKind.RESULT) {
            Column(Modifier.padding(if (sizeClass == WindowSizeClass.COMPACT) 16.dp else 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(localizedText("assistant_cancelled"), style = MaterialTheme.typography.titleMedium)
                val account = result.payload.string("account_name")
                    ?: accounts.firstOrNull { it.id.toString() == result.payload.string("account_id") }?.name
                val currency = result.payload.string("currency")
                val amount = result.payload.string("amount")?.let { exact ->
                    if (currency == "CNY") "¥ ${exact.removePrefix("-")}"
                    else listOfNotNull(exact, currency).joinToString(" ")
                }
                val caption = listOfNotNull(account, amount).joinToString(" · ")
                if (caption.isNotBlank()) Text(caption)
            }
        }
    }
}

@Composable
private fun AssistantSummaryCard(result: LedgerAssistantToolResult, sizeClass: WindowSizeClass, onDetail: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val months = (result.payload["months"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
    val items = (result.payload["display_items"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
    val category = result.payload.string("display_category")
    val singleMonth = months.singleOrNull()
    val singleCurrency = (singleMonth?.get("currencies") as? JsonArray)?.singleOrNull() as? JsonObject
    val income = singleCurrency?.string("income")
    val expense = singleCurrency?.string("expense")
    val expenseOnly = expense != null && income != null && income.all { it in "0.-+" }
    val title = when {
        expenseOnly && category != null -> localizedText("assistant_category_expense", category)
        else -> localizedText("assistant_summary")
    }
    var expanded by remember(result) { mutableStateOf(false) }
    LedgerAssistantCard(LedgerAssistantCardKind.QUERY) {
        Column(
            Modifier.padding(if (sizeClass == WindowSizeClass.COMPACT) 16.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, color = colors.onSurface, style = MaterialTheme.typography.titleMedium)
            if (expenseOnly) {
                val currency = singleCurrency.string("currency").orEmpty()
                val month = singleMonth?.string("month")?.let { localMonthLabel(it) }.orEmpty()
                val denomination = if (currency == "CNY") localizedText("assistant_yuan") else currency
                Text(listOf(month, denomination).filter(String::isNotBlank).joinToString(" · "), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                val magnitude = checkNotNull(expense).removePrefix("-")
                val displayAmount = if ('.' in magnitude) magnitude.trimEnd('0').trimEnd('.') else magnitude
                Text(
                    if (currency == "CNY") "¥ $displayAmount" else "$displayAmount $currency",
                    style = MaterialTheme.typography.headlineLarge, color = colors.onSurface,
                )
            } else if (months.isEmpty()) {
                Text(localizedText("assistant_no_records"))
            } else {
                months.forEach { month ->
                    month.string("month")?.let { Text(localMonthLabel(it)) }
                    (month["currencies"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().forEach { currency ->
                        Text(currency.string("currency").orEmpty(), color = colors.onSurfaceVariant)
                        currency.string("income")?.let { amount ->
                            Row(Modifier.fillMaxWidth()) {
                                Text(localizedText("assistant_income"), Modifier.weight(1f))
                                FinanceAmount(amount)
                            }
                        }
                        currency.string("expense")?.let { amount ->
                            Row(Modifier.fillMaxWidth()) {
                                Text(localizedText("assistant_expense"), Modifier.weight(1f))
                                FinanceAmount(amount)
                            }
                        }
                    }
                }
            }
            TextButton(onClick = { expanded = !expanded }) {
                Icon(
                    painterResource(if (expanded) Res.drawable.ic_expand_less else Res.drawable.ic_expand_more),
                    contentDescription = null,
                )
                Text(localizedText("assistant_show_records", items.size))
            }
            if (expanded) items.forEach { item ->
                val id = item.string("transaction_id")
                if (id != null) TextButton(onClick = { onDetail(id) }) {
                    Text(item.string("note")?.takeIf(String::isNotBlank) ?: localizedText("assistant_details"))
                }
            }
        }
    }
}

@Composable
private fun AssistantDraftCard(
    draft: LedgerAssistantDraft,
    accounts: List<Account>,
    categories: List<CashCategory>,
    options: LedgerOptions,
    sizeClass: WindowSizeClass,
    busy: Boolean,
    canConfirm: Boolean,
    onEdit: (JsonObject) -> Unit,
    onConfirm: (JsonObject) -> Unit,
    onCancel: () -> Unit,
) {
    val fields = remember(draft.localId) {
        cashOrdinaryFields.filter { it !in setOf("counterparty_account", "counterparty_account_attrs") }
    }
    val zone = getLocalTimeZoneId()
    val originalTimestamp = remember(draft.localId) { draft.fields.string("occurred_at").orEmpty() }
    val originalLocalTime = remember(draft.localId) {
        if (originalTimestamp.isBlank()) "" else
            runCatching { com.finance.tracker.core.formatLocalDateTime(originalTimestamp).replace(' ', 'T').take(16) }
                .getOrDefault("")
    }
    var values by remember(draft.localId) {
        mutableStateOf(fields.associateWith { field ->
            val value = draft.fields.string(field).orEmpty()
            when (field) {
                "amount" -> value.removePrefix("-")
                "occurred_at" -> originalLocalTime
                else -> value
            }
        })
    }
    var validate by remember(draft.localId) { mutableStateOf(false) }
    var detailsExpanded by remember(draft.localId) { mutableStateOf(false) }
    val compactFields = listOf("amount", "account_id")
    val invalid = buildSet {
        val account = accounts.singleOrNull { it.id.toString() == values["account_id"] }
        if (account == null) add("account_id")
        if (values["currency"] !in account?.currencies.orEmpty()) add("currency")
        if (values["record_type"] !in setOf("consumption", "income")) add("record_type")
        val amount = values["amount"].orEmpty()
        if (!Regex("[0-9]+(?:\\.[0-9]+)?").matches(amount) ||
            com.finance.tracker.business.domain.CashAmount.parseOrNull(amount) == null) add("amount")
        if (cashInstantForLocalDateTime(values["occurred_at"].orEmpty(), zone) == null) add("occurred_at")
    }
    val errorMessage = localizedText("assistant_check_fields")
    fun payload(): JsonObject = buildJsonObject {
        values.forEach { (field, value) ->
            val confirmed = when {
                field != "occurred_at" -> value
                value == originalLocalTime -> originalTimestamp
                else -> cashInstantForLocalDateTime(value, zone).orEmpty()
            }
            put(field, confirmed)
        }
    }
    LedgerAssistantCard(LedgerAssistantCardKind.DRAFT, Modifier.fillMaxWidth().testTag("assistant.draft")) {
        Column(
            Modifier.padding(if (sizeClass == WindowSizeClass.COMPACT) 16.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val heading = if (values["record_type"] == "consumption") "assistant_review_expense" else "assistant_review_transaction"
            Text(localizedText(heading), style = MaterialTheme.typography.titleMedium)
            if (!detailsExpanded) {
                val type = values["record_type"].orEmpty()
                val category = categories.singleOrNull { it.id == values["category_id"] }
                val subtype = options.recordTypes.firstOrNull { it.value == type }?.subtypes
                    ?.singleOrNull { it.value == values["record_subtype"] }?.label
                val metadata = listOfNotNull(
                    values["occurred_at"]?.takeIf(String::isNotBlank)?.substringBefore('T')
                        ?.let { localDateDisplayParts(it) }
                        ?.let { localizedText("assistant_short_date", it.month, it.day) },
                    type.takeIf { it.isNotBlank() && it != "consumption" }?.let { cashRecordTypeLabel(it, options) },
                    subtype?.takeUnless { values["record_subtype"] == "not_applicable" },
                    category?.let { cashCategoryDisplayPath(it) },
                    values["counterparty"]?.takeIf(String::isNotBlank),
                    values["note"]?.takeIf(String::isNotBlank),
                )
                if (metadata.isNotEmpty()) Text(metadata.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            fun change(field: String, selected: String) {
                values = values + (field to selected)
                if (field == "account_id") {
                    val currencies = accounts.firstOrNull { it.id.toString() == selected }?.currencies.orEmpty()
                    if (values["currency"] !in currencies) values = values + ("currency" to currencies.firstOrNull().orEmpty())
                }
                onEdit(payload())
            }
            if (detailsExpanded) {
                CashRecordFieldInputs(
                    values, fields, accounts, categories, options, sizeClass, busy,
                    "assistant.draft",
                    fieldErrors = if (validate) invalid.associateWith { errorMessage } else emptyMap(),
                    errorRevision = if (validate) 1 else 0,
                    onChange = ::change,
                )
            } else {
                val currency = values["currency"].orEmpty()
                val amountLabel = if (currency == "CNY") localizedText("assistant_cny_amount")
                    else listOf(localizedText("assistant_amount"), currency).filter(String::isNotBlank).joinToString(" · ")
                val amountError = validate && "amount" in invalid
                OutlinedTextField(
                    value = values["amount"].orEmpty(),
                    onValueChange = { change("amount", it) },
                    modifier = Modifier.fillMaxWidth().testTag("assistant.draft.amount"),
                    label = { Text(amountLabel) },
                    singleLine = true,
                    enabled = !busy,
                    isError = amountError,
                    supportingText = if (amountError) ({ Text(errorMessage) }) else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                ChoicePicker(
                    label = localizedText(if (values["record_type"] == "consumption") "assistant_payment_account" else "assistant_account"),
                    value = values["account_id"].orEmpty(),
                    options = accounts.map { it.id.toString() to it.name },
                    onSelected = { change("account_id", it) },
                    enabled = !busy,
                    semanticId = "assistant.draft.account_id",
                    errorMessage = errorMessage.takeIf { validate && "account_id" in invalid },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AssistantActionSurface(localizedText("assistant_confirm"), "confirm", enabled = !busy && canConfirm) {
                    if (invalid.isEmpty()) onConfirm(payload()) else {
                        validate = true
                        if (invalid.any { it !in compactFields }) detailsExpanded = true
                    }
                }
                TextButton(onClick = onCancel, enabled = !busy, modifier = Modifier.testTag("assistant.draft.cancel")) {
                    Text(localizedText("assistant_cancel"))
                }
                TextButton(
                    onClick = { detailsExpanded = !detailsExpanded },
                    enabled = !busy,
                    modifier = Modifier.testTag("assistant.draft.details"),
                ) { Text(localizedText(if (detailsExpanded) "assistant_hide_fields" else "assistant_edit_fields")) }
            }
        }
    }
}

@Composable
private fun AssistantActionSurface(label: String, event: String, enabled: Boolean = true, onAction: () -> Unit) {
    val currentAction = rememberUpdatedState(onAction)
    val actionEnabled = rememberUpdatedState(enabled)
    val surface = "assistant-$event"
    val renderer = remember(label, event) {
        val messages = buildJsonObject {
            put("id", "root")
            put("component", "Button")
            put("variant", if (event == "confirm") "primary" else "borderless")
            put("child", "label")
            put("action", buildJsonObject { put("event", buildJsonObject { put("name", event) }) })
        }
        val updates = listOf(
            "createSurface" to buildJsonObject {
                put("surfaceId", surface)
                put("catalogId", "https://a2ui.org/specification/v1_0/catalogs/basic/catalog.json")
            },
            "updateComponents" to buildJsonObject {
                put("surfaceId", surface)
                put("components", JsonArray(listOf(messages, buildJsonObject {
                    put("id", "label")
                    put("component", "Text")
                    put("text", label)
                })))
            },
        )
        A2uiRenderer().apply {
            applyAll(updates.map { (key, model) ->
                A2uiJson.strict.decodeFromJsonElement(AgentToRendererMessage.serializer(), buildJsonObject {
                    put("version", "v1.0")
                    put(key, model)
                })
            })
        }
    }
    val registry = remember(event, label, enabled) {
        if (event != "confirm") Material3Components.Basic else Material3Components.Basic.with(mapOf(
            "Button" to ComponentRenderer { scope, modifier ->
                val action = scope.rememberAction("action")
                Button(
                    onClick = { if (action != null) scope.dispatch(action) },
                    enabled = enabled && action != null,
                    modifier = modifier.testTag("assistant.draft.confirm"),
                ) { Text(label) }
            },
        ))
    }
    A2uiSurface(renderer, surface, registry, onMessage = { message ->
        if (actionEnabled.value && message is ActionMessage && message.name == event &&
            message.surfaceId == surface && message.sourceComponentId == "root") currentAction.value()
    })
}

@Composable
private fun AssistantRecordDetail(
    id: String,
    sizeClass: WindowSizeClass,
    maintenance: CashMaintenanceRepository,
    recovery: CashMaintenanceRecoveryStore,
    close: () -> Unit,
) {
    val task: CashRecordTaskViewModel = viewModel(
        key = "assistant-detail:$id:${maintenance.hashCode()}",
        factory = viewModelFactory { initializer { CashRecordTaskViewModel(maintenance, recovery) } },
    )
    val state by task.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var evidence by remember(id) { mutableStateOf<CashSourceEvidence?>(null) }
    var sourceError by remember(id) { mutableStateOf<String?>(null) }
    LaunchedEffect(task, id) { task.open(id) }
    LaunchedEffect(state.transactionId, state.record?.projectionRef?.projectionVersion) {
        evidence = null
        sourceError = null
    }
    CashCurrentRecordPanel(
        state, evidence?.projection, sizeClass, evidence, sourceError,
        onLoadSources = {
            val target = state.transactionId
            val ref = state.record?.projectionRef
            if (target != null && sourceError == "record_changed") {
                task.open(target)
            } else if (ref != null) scope.launch {
                fun current(): Boolean = task.state.value.transactionId == target && task.state.value.record?.projectionRef == ref
                sourceError = null
                try {
                    val result = maintenance.sources(ref.projectionId)
                    if (current()) {
                        if (result.projectionVersion == ref.projectionVersion && result.projection.projectionId == ref.projectionId) evidence = result
                        else sourceError = "record_changed"
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (current()) sourceError = "request_failed"
                }
            }
        },
        onRetry = { task.open(state.transactionId ?: id) },
        onResolve = null,
        onClose = close,
        readTaskState = { task.state.value },
        onSearchAssociations = { maintenance.associationTargets(id, it) },
        onAddAssociation = { _, _ -> },
        onRelationCommonFields = {},
        onOpenMember = task::open,
    )
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
