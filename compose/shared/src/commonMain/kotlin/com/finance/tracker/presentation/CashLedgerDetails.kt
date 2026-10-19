package com.finance.tracker.presentation

import com.finance.tracker.core.*
import com.finance.tracker.domain.*
import com.finance.tracker.resources.Res
import com.finance.tracker.resources.ic_close
import com.finance.tracker.resources.ic_delete
import com.finance.tracker.resources.ic_edit
import com.finance.tracker.resources.ic_expand_less
import com.finance.tracker.resources.ic_expand_more
import com.finance.tracker.resources.ic_link
import com.finance.tracker.resources.ic_link_off
import com.finance.tracker.resources.ic_more_vert

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import org.jetbrains.compose.resources.painterResource
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
internal fun CashRecordModalPanel(
    sizeClass: WindowSizeClass,
    title: String,
    compactFullScreen: Boolean,
    semanticStateKey: Any? = null,
    onDismiss: () -> Unit,
    onBack: () -> Unit,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val compact = sizeClass == WindowSizeClass.COMPACT
    val fullScreen = compact && compactFullScreen
    PlatformModalEscapeHandler(enabled = isWebPlatform, onEscape = onBack)
    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !isWebPlatform,
            dismissOnClickOutside = !fullScreen,
        ),
    ) {
        key(sizeClass, title, semanticStateKey) {
            CashRecordModalOverlay(
                sizeClass = sizeClass,
                title = title,
                onDismiss = onDismiss,
                onBack = onBack,
                compactFullScreen = fullScreen,
                footer = footer,
                content = content,
            )
        }
    }
}

@Composable
private fun CashRecordModalOverlay(
    sizeClass: WindowSizeClass,
    title: String,
    onDismiss: () -> Unit,
    onBack: () -> Unit,
    compactFullScreen: Boolean,
    footer: (@Composable () -> Unit)?,
    content: @Composable () -> Unit,
) {
    val compact = sizeClass == WindowSizeClass.COMPACT
    val fullScreen = compact && compactFullScreen
    val sideSheet = !compact
    val horizontalMargin = when (sizeClass) {
        WindowSizeClass.COMPACT -> if (fullScreen) 0.dp else 16.dp
        WindowSizeClass.REGULAR -> 16.dp
        WindowSizeClass.WIDE -> 56.dp
    }
    val layoutDirection = LocalLayoutDirection.current
    val endDirection = if (layoutDirection == LayoutDirection.Ltr) 1f else -1f
    val dragThresholdPx = with(LocalDensity.current) { 120.dp.toPx() }
    var sideSheetDragOffset by remember(sizeClass, title) { mutableFloatStateOf(0f) }
    var sideSheetWidthPx by remember(sizeClass, title) { mutableFloatStateOf(0f) }
    val animationScope = rememberCoroutineScope()
    val sideSheetDragModifier = Modifier.pointerInput(sizeClass, title, layoutDirection) {
        detectHorizontalDragGestures(
            onHorizontalDrag = { change, dragAmount ->
                change.consume()
                val proposedOffset = sideSheetDragOffset + dragAmount
                sideSheetDragOffset = if (proposedOffset * endDirection > 0f) proposedOffset else 0f
            },
            onDragEnd = {
                val currentOffset = sideSheetDragOffset
                if (currentOffset * endDirection >= dragThresholdPx) {
                    animationScope.launch {
                        Animatable(currentOffset).animateTo(
                            targetValue = sideSheetWidthPx * endDirection,
                            animationSpec = tween(durationMillis = 180),
                        ) { sideSheetDragOffset = value }
                        onBack()
                    }
                } else {
                    animationScope.launch {
                        Animatable(currentOffset).animateTo(
                            targetValue = 0f,
                            animationSpec = tween(durationMillis = 180),
                        ) { sideSheetDragOffset = value }
                    }
                }
            },
            onDragCancel = { sideSheetDragOffset = 0f },
        )
    }
    Box(
        modifier = Modifier.fillMaxSize().padding(start = if (compact) 0.dp else horizontalMargin),
        contentAlignment = if (compact) Alignment.Center else Alignment.CenterEnd,
    ) {
        if (!compact) {
            Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null, onClick = onBack))
        }
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = if (compact) Alignment.Center else Alignment.CenterEnd,
        ) {
            Surface(
                modifier = (if (fullScreen) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier.widthIn(max = 560.dp)
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .onSizeChanged { sideSheetWidthPx = it.width.toFloat() }
                        .offset { IntOffset(sideSheetDragOffset.roundToInt(), 0) }
                        .then(sideSheetDragModifier)
                })
                    .testTag(SemanticIds.ledgerRecordPanel)
                    .semantics { paneTitle = title },
                shape = if (fullScreen) RectangleShape else MaterialTheme.shapes.large,
                color = if (fullScreen) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 0.dp,
            ) {
                Column(
                    Modifier.fillMaxWidth()
                        .then(if (fullScreen || !compact) Modifier.fillMaxHeight() else Modifier)
                        .then(if (fullScreen || !compact) Modifier.windowInsetsPadding(WindowInsets.safeDrawing) else Modifier),
                ) {
                    Row(
                        Modifier.fillMaxWidth()
                            .then(
                                if (fullScreen || !sideSheet) {
                                    Modifier.height(64.dp).padding(start = 16.dp, end = 24.dp)
                                } else {
                                    Modifier.padding(start = 16.dp, top = 24.dp, end = 24.dp)
                                },
                            )
                            .testTag("ledger.record-panel.header"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val closeLabel = localizedText("copy_6c14bd7f6f")
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.testTag("ledger.record-panel.close")
                                .semantics { contentDescription = closeLabel },
                        ) {
                            Icon(painterResource(Res.drawable.ic_close), contentDescription = null, modifier = Modifier.size(24.dp))
                        }
                        Text(
                            title,
                            modifier = Modifier.weight(1f)
                                .padding(start = if (fullScreen) 8.dp else 12.dp)
                                .testTag("ledger.record-panel.title"),
                            style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    HorizontalDivider()
                    Box(
                        Modifier.weight(1f, fill = fullScreen || !compact).fillMaxWidth().padding(horizontal = 24.dp)
                            .testTag("ledger.record-panel.content"),
                    ) { content() }
                    footer?.let {
                        HorizontalDivider()
                        if (sideSheet) {
                            Box(
                                Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 24.dp)
                                    .testTag("ledger.record-panel.footer"),
                                contentAlignment = Alignment.CenterStart,
                            ) { it() }
                        } else {
                            Box(
                                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 24.dp)
                                    .testTag("ledger.record-panel.footer"),
                                contentAlignment = Alignment.CenterEnd,
                            ) { it() }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CashRecordDetailContent(
    state: CashLedgerUiState,
    viewModel: CashLedgerViewModel,
    canWrite: Boolean,
    options: LedgerOptions,
    relatedExpanded: Boolean,
    onToggleRelated: () -> Unit,
    onEditRelated: (CashRecord) -> Unit,
) {
    val evidence = state.evidence
    val detail = state.recordDetail
    val record = detail?.record
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                state.recordLoading -> Text(localizedText("copy_875adbe057"))
                state.recordErrorCode != null -> {
                    InlineError(ledgerErrorText(state.recordErrorCode))
                    TextButton(onClick = viewModel::retrySelectedRow) { Text(localizedText("copy_e2d53a6d3a")) }
                }
                record != null -> {
                    val selectedProjection = state.selectedRow
                    val acceptedRelations = detail?.relations.orEmpty().filter { it.status == "accepted" }
                    val relatedRecordIds = acceptedRelations.mapNotNull { relation ->
                        if (relation.primaryRecord?.id == record.id) relation.secondaryRecord?.id else relation.primaryRecord?.id
                    }.toSet()
                    val relatedEvidenceMembers = evidence?.members.orEmpty().filterNot { member ->
                        val id = member.recordId ?: member.id
                        id == record.id || id == evidence?.rootRecord?.id || id in relatedRecordIds
                    }
                    val hasRelatedInformation = relatedEvidenceMembers.isNotEmpty() || acceptedRelations.isNotEmpty() || record.components.orEmpty().size > 1 ||
                        evidence?.refundTimeline.orEmpty().isNotEmpty() || evidence?.acceptedRelations.orEmpty().isNotEmpty()
                    val economicType = selectedProjection?.let { cashProjectionEconomicTypeLabel(it) }
                    val amountLabel = selectedProjection?.let(::cashProjectionAmountLabel) ?: "${record.amount} ${record.currency}"
                    val amountIsNegative = selectedProjection?.amount?.startsWith("-") ?: record.amount.startsWith("-")

                    Text(
                        amountLabel,
                        style = MaterialTheme.typography.headlineMedium.withTabularNumbers(),
                        color = if (amountIsNegative) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                    Text(record.counterparty.ifBlank { record.accountName }, style = MaterialTheme.typography.titleLarge)
                    Text(
                        listOfNotNull(economicType, formatLocalDateTime(record.occurredAt)).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(localizedText("ledger_detail_transaction_facts"), style = MaterialTheme.typography.titleMedium)
                            HorizontalDivider()
                            CashRecordFact(localizedText("copy_e81089915c"), cashRecordTypeLabel(record.recordType, options))
                            CashRecordFact(localizedText("copy_c3d92b20c8"), record.accountName)
                            CashRecordFact(localizedText("copy_a42e73f0a7"), cashCategoryDisplayPath(record.category))
                            options.recordTypes.firstOrNull { it.value == record.recordType }
                                ?.subtypes?.firstOrNull { it.value == record.recordSubtype }
                                ?.label?.takeIf { record.recordSubtype != "not_applicable" }
                                ?.let { CashRecordFact(localizedText("copy_1fcff8b8d5"), it) }
                            record.counterpartyAccount.takeIf(String::isNotBlank)?.let {
                                CashRecordFact(localizedText("copy_27a7edc87c"), it)
                            }
                            record.note.takeIf(String::isNotBlank)?.let {
                                CashRecordFact(localizedText("copy_e0361480e3"), it)
                            }
                            if (evidence != null && cashEvidenceSourceLabel(evidence).isNotBlank()) {
                                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                                Text(localizedText("copy_b9e9fd25ac", cashEvidenceSourceLabel(evidence)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (hasRelatedInformation) {
                        CashRecordRelatedSection(
                            record = record,
                            amountLabel = amountLabel,
                            acceptedRelations = acceptedRelations,
                            relatedEvidenceMembers = relatedEvidenceMembers,
                            evidence = evidence,
                            options = options,
                            state = state,
                            viewModel = viewModel,
                            canWrite = canWrite,
                            relationBusy = state.relationBusy,
                            onEditRelated = onEditRelated,
                            expanded = relatedExpanded,
                            onToggle = onToggleRelated,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun CashRecordRelationComposerContent(
    state: CashLedgerUiState,
    viewModel: CashLedgerViewModel,
    canWrite: Boolean,
    options: LedgerOptions,
) {
    var activeDatePicker by remember { mutableStateOf<String?>(null) }
    val relationDateFrom: @Composable (Modifier) -> Unit = { fieldModifier ->
        DatePickerInput(
            state.relationDateFrom,
            viewModel::updateRelationDateFrom,
            localizedText("copy_1f29196891"),
            modifier = fieldModifier,
            semanticId = "record.relation-date-from",
            inlinePicker = true,
            inlinePickerActive = activeDatePicker == "from",
            onInlinePickerActiveChange = { activeDatePicker = if (it) "from" else null },
        )
    }
    val relationDateTo: @Composable (Modifier) -> Unit = { fieldModifier ->
        DatePickerInput(
            state.relationDateTo,
            viewModel::updateRelationDateTo,
            localizedText("copy_f4b9b2b5de"),
            modifier = fieldModifier,
            semanticId = "record.relation-date-to",
            inlinePicker = true,
            inlinePickerActive = activeDatePicker == "to",
            onInlinePickerActiveChange = { activeDatePicker = if (it) "to" else null },
        )
    }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (activeDatePicker != null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    relationDateFrom(Modifier.fillMaxWidth())
                    relationDateTo(Modifier.fillMaxWidth())
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    relationDateFrom(Modifier.weight(1f))
                    relationDateTo(Modifier.weight(1f))
                }
            }
            OutlinedTextField(
                value = state.relationQuery,
                onValueChange = viewModel::updateRelationQuery,
                modifier = Modifier.accessibleTextFieldLabel(localizedText("copy_c219d66be7")),
                label = { Text(localizedText("copy_c219d66be7")) },
                singleLine = true,
            )
            ChoicePicker(
                localizedText("copy_2ce741e7ab"),
                state.relationType,
                options.relationTypes.map { it.value to it.label },
                viewModel::updateRelationType,
            )
            if (state.relationBusy) Text(localizedText("copy_455d870542"), style = MaterialTheme.typography.bodySmall)
            if (state.relationLoadError) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(localizedText("copy_9af3904f9f"), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = viewModel::retryRelationPage, enabled = !state.relationBusy) { Text(localizedText("copy_e2d53a6d3a")) }
                }
            }
            if (!state.relationBusy && !state.relationLoadError && state.relationCandidates.isEmpty() &&
                isValidCashRelationDateFilter(state.relationDateFrom, state.relationDateTo)
            ) {
                Text(localizedText("copy_3e5d827a71"), style = MaterialTheme.typography.bodySmall)
            }
            state.relationCandidates.forEach { candidate ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = state.relationTarget == candidate.id,
                        onClick = { viewModel.selectRelationTarget(candidate.id) },
                        enabled = canWrite && !state.relationBusy,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(candidate.counterparty.ifBlank { localizedText("copy_6ea13ee38c") }, style = MaterialTheme.typography.titleSmall)
                        Text("${candidate.accountName} · ${formatLocalDateTime(candidate.occurredAt)}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("${candidate.amount} ${candidate.currency}", style = MaterialTheme.typography.labelLarge.withTabularNumbers())
                }
            }
            if (state.relationCandidates.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(localizedText("copy_d2fee7cb23", state.relationPageNumber), style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = viewModel::previousRelationPage, enabled = state.relationPageNumber > 1 && !state.relationBusy) { Text(localizedText("copy_b41561d807")) }
                        TextButton(onClick = viewModel::nextRelationPage, enabled = state.relationNextCursor != null && !state.relationBusy) { Text(localizedText("copy_67a246a344")) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CashRecordEditorContent(
    draft: CashRecordDraft,
    sizeClass: WindowSizeClass,
    canWrite: Boolean,
    accounts: List<Account>,
    categories: List<CashCategory>,
    options: LedgerOptions,
    busy: Boolean,
    error: String?,
    onChange: (CashRecordDraft) -> Unit,
    onInvalidAmount: () -> Unit,
    onInvalidDateTime: () -> Unit,
) {
    val selectedAccount = accounts.firstOrNull { it.name == draft.accountName }
    val selectedType = options.recordTypes.firstOrNull { it.value == draft.recordType }
    val compact = sizeClass == WindowSizeClass.COMPACT
    Column(Modifier.fillMaxWidth().then(if (compact) Modifier.fillMaxHeight() else Modifier)) {
            Column(
                Modifier.fillMaxWidth()
                    .then(if (compact) Modifier.weight(1f) else Modifier)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (sizeClass == WindowSizeClass.COMPACT) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = draft.amount,
                            onValueChange = { onChange(draft.copy(amount = it)) },
                            modifier = Modifier.weight(2f).onFocusLost(onFocusLost = { if (draft.amount.isNotBlank() && !isExactDecimalString(draft.amount)) onInvalidAmount() }).testTag(SemanticIds.recordAmount)
                                .accessibleTextFieldLabel(localizedText("copy_34943c40c9")),
                            label = { Text(localizedText("copy_34943c40c9")) },
                            isError = draft.amount.isNotBlank() && !isExactDecimalString(draft.amount),
                            singleLine = true,
                        )
                        ChoicePicker(localizedText("copy_a81ab5e100"), draft.currency, (selectedAccount?.currencies.orEmpty().ifEmpty { listOf(draft.currency) }).distinct().map { it to it }, { onChange(draft.copy(currency = it)) }, Modifier.weight(1f), semanticId = SemanticIds.recordCurrency)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = draft.amount,
                            onValueChange = { onChange(draft.copy(amount = it)) },
                            modifier = Modifier.weight(1f).onFocusLost(onFocusLost = { if (draft.amount.isNotBlank() && !isExactDecimalString(draft.amount)) onInvalidAmount() }).testTag(SemanticIds.recordAmount)
                                .accessibleTextFieldLabel(localizedText("copy_34943c40c9")),
                            label = { Text(localizedText("copy_34943c40c9")) },
                            isError = draft.amount.isNotBlank() && !isExactDecimalString(draft.amount),
                            singleLine = true,
                        )
                        ChoicePicker(localizedText("copy_a81ab5e100"), draft.currency, (selectedAccount?.currencies.orEmpty().ifEmpty { listOf(draft.currency) }).distinct().map { it to it }, { onChange(draft.copy(currency = it)) }, Modifier.weight(1f), semanticId = SemanticIds.recordCurrency)
                    }
                }
                OutlinedTextField(
                    value = draft.counterparty,
                    onValueChange = { onChange(draft.copy(counterparty = it)) },
                    modifier = Modifier.testTag(SemanticIds.recordCounterparty)
                        .accessibleTextFieldLabel(localizedText("copy_4b5a03c119")),
                    label = { Text(localizedText("copy_4b5a03c119")) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = draft.counterpartyAccount,
                    onValueChange = { onChange(draft.copy(counterpartyAccount = it)) },
                    modifier = Modifier.testTag(SemanticIds.recordCounterpartyAccount)
                        .accessibleTextFieldLabel(localizedText("copy_27a7edc87c")),
                    label = { Text(localizedText("copy_27a7edc87c")) },
                    singleLine = true,
                )
                DateTimePickerInput(
                    draft.occurredAt,
                    { onChange(draft.copy(occurredAt = it)) },
                    localizedText("copy_51f85a78ca"),
                    sizeClass,
                    modifier = Modifier.testTag(SemanticIds.recordOccurredAt),
                    onInvalidValue = onInvalidDateTime,
                )
                ChoicePicker(localizedText("copy_c3d92b20c8"), draft.accountName, accounts.map { it.name to it.name }, { accountName ->
                    val next = accounts.firstOrNull { it.name == accountName }
                    onChange(draft.copy(accountName = accountName, currency = next?.currencies?.firstOrNull() ?: draft.currency))
                }, semanticId = SemanticIds.recordAccount)
                ChoicePicker(localizedText("copy_e81089915c"), draft.recordType, options.recordTypes.map { it.value to it.label }, { type ->
                    val selected = options.recordTypes.firstOrNull { it.value == type }
                    onChange(draft.copy(recordType = type, recordSubtype = selected?.subtypes?.firstOrNull()?.value ?: "not_applicable"))
                }, semanticId = SemanticIds.recordType)
                if (selectedType?.subtypes?.isNotEmpty() == true) ChoicePicker(localizedText("copy_1fcff8b8d5"), draft.recordSubtype, selectedType.subtypes.map { it.value to it.label }, { onChange(draft.copy(recordSubtype = it)) })
                ChoicePicker(localizedText("copy_a42e73f0a7"), draft.categoryId.orEmpty(), listOf("" to localizedText("copy_f11956caf6")) + categories.map { it.id to it.name }, { onChange(draft.copy(categoryId = it.ifEmpty { null })) }, semanticId = SemanticIds.recordCategory)
                OutlinedTextField(
                    value = draft.note,
                    onValueChange = { onChange(draft.copy(note = it)) },
                    modifier = Modifier.testTag(SemanticIds.recordNote)
                        .accessibleTextFieldLabel(localizedText("copy_e0361480e3")),
                    label = { Text(localizedText("copy_e0361480e3")) },
                    singleLine = false,
                )
                if (error != null) {
                    Text(
                        error,
                        modifier = Modifier.testTag("ledger.record.save-error-inline"),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CashRecordDetailActions(
    state: CashLedgerUiState,
    viewModel: CashLedgerViewModel,
    canWrite: Boolean,
    sizeClass: WindowSizeClass,
    onEdit: () -> Unit,
) {
    val record = state.recordDetail?.record ?: return
    if (!canWrite) return
    val moreActionsExpanded = remember(record.id) { mutableStateOf(false) }
    PlatformEscapeHandler(
        enabled = isWebPlatform && moreActionsExpanded.value,
        onEscape = { moreActionsExpanded.value = false },
    )
    val sideSheet = sizeClass != WindowSizeClass.COMPACT
    FlowRow(
        modifier = Modifier.fillMaxWidth().testTag("record.detail.actions"),
        horizontalArrangement = Arrangement.spacedBy(8.dp, if (sideSheet) Alignment.Start else Alignment.End),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (sideSheet) {
            Button(onClick = onEdit, modifier = Modifier.testTag("record.detail.edit")) {
                Icon(painterResource(Res.drawable.ic_edit), contentDescription = null)
                Text(localizedText("copy_a7f814c0a4"))
            }
            CashRecordMoreActions(
                state = state,
                viewModel = viewModel,
                canWrite = canWrite,
                expanded = moreActionsExpanded.value,
                onExpandedChange = { moreActionsExpanded.value = it },
            )
        } else {
            CashRecordMoreActions(
                state = state,
                viewModel = viewModel,
                canWrite = canWrite,
                expanded = moreActionsExpanded.value,
                onExpandedChange = { moreActionsExpanded.value = it },
            )
            Button(onClick = onEdit, modifier = Modifier.testTag("record.detail.edit")) {
                Icon(painterResource(Res.drawable.ic_edit), contentDescription = null)
                Text(localizedText("copy_a7f814c0a4"))
            }
        }
    }
}

@Composable
private fun CashRecordMoreActions(
    state: CashLedgerUiState,
    viewModel: CashLedgerViewModel,
    canWrite: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    Box {
        TextButton(
            onClick = { onExpandedChange(true) },
            modifier = Modifier.testTag("record.detail.more-actions"),
        ) {
            Icon(painterResource(Res.drawable.ic_more_vert), contentDescription = null)
            Text(localizedText("ledger_detail_more_actions"))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            DropdownMenuItem(
                text = { Text(localizedText("copy_695c4cd2e6")) },
                leadingIcon = { Icon(painterResource(Res.drawable.ic_link), contentDescription = null) },
                onClick = {
                    onExpandedChange(false)
                    viewModel.openRelationComposer()
                },
                enabled = !state.relationBusy,
                modifier = Modifier.testTag("record.detail.action.associate"),
            )
            if (state.recordDetail?.relations?.any { it.status == "accepted" } == true) {
                DropdownMenuItem(
                    text = { Text(localizedText("copy_833237e41b"), color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(painterResource(Res.drawable.ic_link_off), contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    onClick = {
                        onExpandedChange(false)
                        viewModel.dissolveRelations(canWrite)
                    },
                    enabled = !state.relationBusy,
                    modifier = Modifier.testTag("record.detail.action.dissolve"),
                )
            }
            DropdownMenuItem(
                text = { Text(localizedText("copy_3755f56f2f"), color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(painterResource(Res.drawable.ic_delete), contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                onClick = {
                    onExpandedChange(false)
                    viewModel.requestDeleteRecord(canWrite)
                },
                enabled = !state.deleteRecordBusy,
                modifier = Modifier.testTag("record.detail.action.delete"),
            )
        }
    }
}

@Composable
private fun CashRecordRelatedSection(
    record: CashRecord,
    amountLabel: String,
    acceptedRelations: List<CashRelation>,
    relatedEvidenceMembers: List<EvidenceMember>,
    evidence: Evidence?,
    options: LedgerOptions,
    state: CashLedgerUiState,
    viewModel: CashLedgerViewModel,
    canWrite: Boolean,
    relationBusy: Boolean,
    onEditRelated: (CashRecord) -> Unit,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val relationRecordIds = acceptedRelations.mapNotNull { relation ->
        if (relation.primaryRecord?.id == record.id) relation.secondaryRecord?.id else relation.primaryRecord?.id
    }.toSet()
    val evidenceRecordIds = relatedEvidenceMembers.mapNotNull { it.recordId ?: it.id }.toSet()
    val relatedCount = (relationRecordIds + evidenceRecordIds).size
        .coerceAtLeast(evidence?.acceptedRelations.orEmpty().size)

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = onToggle,
                modifier = Modifier.fillMaxWidth().testTag(SemanticIds.recordRelatedToggle),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(localizedText("copy_f46e36d281"), style = MaterialTheme.typography.titleMedium)
                        Text(
                            localizedText(if (expanded) "ledger_detail_hide_related" else "ledger_detail_view_related"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (relatedCount > 0) {
                        Text(
                            localizedText("ledger_detail_related_count", relatedCount),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        painterResource(if (expanded) Res.drawable.ic_expand_less else Res.drawable.ic_expand_more),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (expanded) Column(
                Modifier.fillMaxWidth().testTag(SemanticIds.recordRelatedContent),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(localizedText("ledger_detail_current_record"), style = MaterialTheme.typography.titleSmall)
                CashRelatedRecordSummary(
                    counterparty = record.counterparty.ifBlank { record.accountName },
                    amount = amountLabel,
                    occurredAt = record.occurredAt,
                    accountName = record.accountName,
                    recordType = cashRecordTypeLabel(record.recordType, options),
                    category = cashCategoryDisplayPath(record.category),
                    note = record.note,
                    counterpartyAccount = record.counterpartyAccount,
                )
                if (acceptedRelations.isNotEmpty() || relatedEvidenceMembers.isNotEmpty()) {
                    Text(localizedText("ledger_detail_other_records"), style = MaterialTheme.typography.titleSmall)
                }
                acceptedRelations.forEach { relation ->
                    val related = if (relation.primaryRecord?.id == record.id) relation.secondaryRecord else relation.primaryRecord
                    HorizontalDivider()
                    Text(relation.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    related?.let {
                        CashRelatedRecordSummary(
                            counterparty = it.counterparty.ifBlank { localizedText("copy_6ea13ee38c") },
                            amount = "${it.amount} ${it.currency}",
                            occurredAt = it.occurredAt,
                            accountName = it.accountName,
                            recordType = cashRecordTypeLabel(it.recordType, options),
                            category = cashCategoryDisplayPath(it.category),
                            note = it.note,
                            counterpartyAccount = it.counterpartyAccount,
                        )
                    }
                    val relatedEvidence = evidence?.members?.firstOrNull { member ->
                        member.id == related?.id || member.recordId == related?.id
                    }
                    relatedEvidence?.let {
                        Text(cashEvidenceMemberImpactLabel(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(
                        onClick = { related?.let(onEditRelated) },
                        enabled = canWrite && !relationBusy && related != null,
                    ) { Text(localizedText("copy_6a5c4515c6")) }
                    if (state.editingRelationId == relation.id) {
                        ChoicePicker(
                            localizedText("copy_2ce741e7ab"),
                            state.editingRelationKind ?: relation.kind,
                            options.relationTypes.map { it.value to it.label },
                            viewModel::updateEditingRelationKind,
                            enabled = canWrite && !relationBusy,
                            semanticId = "record.related.kind.edit",
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(
                                onClick = { viewModel.saveRelationEdit(canWrite) },
                                enabled = canWrite && state.editingRelationKind != relation.kind,
                                modifier = Modifier.testTag("record.related.kind.save"),
                            ) { Text(localizedText("copy_fadf24dbc5")) }
                            TextButton(
                                onClick = viewModel::cancelEditingRelation,
                                modifier = Modifier.testTag("record.related.kind.cancel"),
                            ) { Text(localizedText("copy_4d0b4688c7")) }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(
                                onClick = { viewModel.beginEditingRelation(relation.id, relation.kind) },
                                enabled = canWrite && !relationBusy,
                                modifier = Modifier.testTag("record.related.kind.edit-trigger"),
                            ) { Text(localizedText("copy_9a0d4d4a96")) }
                            TextButton(
                                onClick = { viewModel.cancelRelation(relation.id, canWrite) },
                                enabled = canWrite && !relationBusy,
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.testTag("record.related.unlink-trigger"),
                            ) { Text(localizedText("copy_e490abc2fa")) }
                        }
                    }
                }
                if (evidence != null) {
                    relatedEvidenceMembers.forEach { member ->
                        HorizontalDivider()
                        Text(cashEvidenceMemberLabel(member, options, evidence.projection), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        CashRelatedRecordSummary(
                            counterparty = member.counterparty.ifBlank { localizedText("copy_6ea13ee38c") },
                            amount = "${member.amount} ${member.currency}",
                            occurredAt = member.occurredAt,
                            accountName = member.accountName ?: member.account?.name ?: localizedText("copy_50d1d690ec"),
                            recordType = cashEvidenceMemberLabel(member, options, evidence.projection),
                            category = cashCategoryDisplayPath(member.category),
                            note = member.note,
                            counterpartyAccount = member.counterpartyAccount.orEmpty(),
                        )
                        Text(cashEvidenceMemberImpactLabel(member), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    record.components.orEmpty().takeIf { it.size > 1 }?.let { components ->
                        Text(localizedText("copy_dad2cec350"), style = MaterialTheme.typography.titleSmall)
                        components.forEach { component ->
                            Text("${component.account?.name ?: component.accountName ?: localizedText("copy_74e111acc1")} · ${component.amount} ${component.currency}")
                        }
                    }
                    evidence.refundTimeline.forEach { refund ->
                        Text(localizedText("copy_ec7f8f67e2", formatLocalDateTime(refund.occurredAt), refund.amount, refund.currency))
                    }
                    evidence.acceptedRelations.forEach { relation ->
                        Text(localizedText("copy_d9c0f0da95", cashRelationTypeLabel(relation.kind, options)))
                    }
                } else {
                    record.components.orEmpty().takeIf { it.size > 1 }?.let { components ->
                        Text(localizedText("copy_dad2cec350"), style = MaterialTheme.typography.titleSmall)
                        components.forEach { component ->
                            Text("${component.account?.name ?: component.accountName ?: localizedText("copy_74e111acc1")} · ${component.amount} ${component.currency}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CashRecordFact(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, modifier = Modifier.widthIn(min = 88.dp, max = 104.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Start)
    }
}

@Composable
private fun CashRelatedRecordSummary(
    counterparty: String,
    amount: String,
    occurredAt: String,
    accountName: String,
    recordType: String,
    category: String,
    note: String,
    counterpartyAccount: String,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Text(counterparty, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(amount, style = MaterialTheme.typography.titleSmall.withTabularNumbers(), textAlign = TextAlign.End)
        }
        Text(
            "${formatLocalDateTime(occurredAt)} · $accountName",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CashRecordFact(localizedText("copy_e81089915c"), recordType)
        CashRecordFact(localizedText("copy_a42e73f0a7"), category)
        if (counterpartyAccount.isNotBlank()) CashRecordFact(localizedText("copy_27a7edc87c"), counterpartyAccount)
        if (note.isNotBlank()) CashRecordFact(localizedText("copy_e0361480e3"), note)
    }
}

@Composable
internal fun economicTypeLabel(value: String): String = when (value) {
    "expense" -> localizedText("copy_71d6df4130")
    "income" -> localizedText("copy_aaaf7ca11e")
    "internal_transfer" -> localizedText("copy_4dabeefa09")
    else -> value
}

@Composable
internal fun transferSubtypeLabel(value: String): String = when (value) {
    "bank_security_transfer" -> localizedText("copy_c18e325ed4")
    "cross_currency_remittance" -> localizedText("copy_bfde411775")
    "ordinary_transfer" -> localizedText("copy_58b0da22c2")
    "currency_exchange" -> localizedText("copy_978624700f")
    else -> value
}

internal fun localDateTimeForInput(): String = "${getLocalDateForInput()}T12:00"
