package com.finance.tracker.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.finance.tracker.core.*
import com.finance.tracker.domain.*
import com.finance.tracker.resources.Res
import com.finance.tracker.resources.ic_add
import com.finance.tracker.resources.ic_check
import com.finance.tracker.resources.ic_close
import com.finance.tracker.resources.ic_link_off
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

internal fun cashDetailRelationKinds(member: CashSourceMember): List<String> =
    member.relations.filter { it.active && it.state == "confirmed" }.map { it.kind }.distinct()

@Composable
private fun suggestionRelationLabel(group: ImportPairOption, options: LedgerOptions): String {
    val relations = group.relations.ifEmpty { group.candidates.flatMap { it.relations } }
    val types = relations.map { it.kind }.distinct()
    if (types.isEmpty()) return cashRelationTypeLabel(group.kind.orEmpty(), options)
    val labels = mutableListOf<String>()
    for (kind in types) labels += cashRelationTypeLabel(kind, options)
    return labels.joinToString(" · ")
}

@Composable
private fun AssociationRelationSummary(relations: List<ImportPairRelation>, records: Map<String, CashCurrentRecord>, options: LedgerOptions) {
    if (relations.isNotEmpty()) {
        relations.forEach { relation ->
            val names = relation.members.mapNotNull { ref -> records[ref.transactionId]?.fields?.fields?.get("counterparty").cashText() }
            Text(cashRelationTypeLabel(relation.kind, options) + " · " + names.joinToString(" 与 "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemberTags(member: CashSourceMember, currentId: String?, options: LedgerOptions, modifier: Modifier = Modifier) {
    val labels = if (member.transactionId == currentId) listOf(localizedText("ledger_detail_current_record"))
        else cashDetailRelationKinds(member).map { kind -> cashRelationTypeLabel(kind, options) }
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEach { label -> Surface(shape = MaterialTheme.shapes.extraSmall, color = Color.Transparent,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Text(label, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
    }
}

@Composable
private fun DetailValue(label: String, value: String, monospace: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.width(72.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospace) FontFamily.Monospace else null)
    }
}

@Composable
private fun RelatedRecordInformation(
    fields: StructuredObject, accounts: List<Account>, categories: List<CashCategory>, options: LedgerOptions,
    member: CashSourceMember? = null, showSummary: Boolean = true,
) {
    val values = fields.fields
    val amount = member?.amount ?: values["amount"].cashText()
    val currency = member?.currency ?: values["currency"].cashText()
    val counterparty = member?.counterparty ?: values["counterparty"].cashText().orEmpty()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showSummary) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(counterparty.ifBlank { localizedText("display_not_provided") }, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                FinanceAmount(cashAmountWithCurrencyLabel(amount, currency), negative = amount?.startsWith("-") == true)
            }
            Text(listOfNotNull(values["record_type"]?.let { cashBusinessFieldValue("record_type", it, accounts, categories, options) },
                localDateTimeDisplayLabel(member?.occurredAt ?: values["occurred_at"].cashText())).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            values["record_type"]?.let { type -> cashFieldLabel("record_type")?.let { label ->
                DetailValue(localizedText(label), cashBusinessFieldValue("record_type", type, accounts, categories, options))
            } }
        }
        val accountName = member?.accountName?.takeIf { it.isNotBlank() } ?: values["account_name"].cashText()?.takeIf { it.isNotBlank() }
            ?: cashBusinessFieldValue("account_id", values["account_id"], accounts, categories, options)
        DetailValue(localizedText("cash_detail_account_id"), accountName)
        listOf("record_subtype", "category_id", "source_type", "counterparty_account", "counterparty_account_attrs").forEach { field ->
            val value = values[field] ?: if (field == "counterparty_account") member?.counterpartyAccount?.let(::StructuredText) else null
            val present = if (field == "category_id") value != null
                else if (field == "counterparty_account_attrs") (value as? StructuredArray)?.values.orEmpty().isNotEmpty()
                else !value.cashText().isNullOrBlank() && value.cashText() != "not_applicable"
            if (present) {
                if (field == "source_type") DetailValue(localizedText("cash_detail_source"), cashEvidenceSourceTypeLabel(value.cashText().orEmpty()))
                else cashFieldLabel(field)?.let { label -> DetailValue(localizedText(label), cashBusinessFieldValue(field, value, accounts, categories, options)) }
            }
        }
        val note = member?.note ?: values["note"].cashText().orEmpty()
        if (note.isNotBlank()) DetailValue(localizedText("copy_e0361480e3"), note)
        val components = member?.components ?: (values["components"] as? StructuredArray)?.values.orEmpty().mapNotNull { value ->
            (value as? StructuredObject)?.fields?.let { part -> CashSourceComponent(
                part["component_id"].cashText().orEmpty(), part["amount"].cashText(), part["currency"].cashText(),
                part["account_name"].cashText() ?: accounts.firstOrNull { it.id.toString() == part["account_id"].cashText() }?.name,
                part["label"].cashText().orEmpty()) }
        }
        if (components.size > 1) components.forEach { part ->
            Text(listOfNotNull(part.accountName, part.label.takeIf { it.isNotBlank() }, cashAmountWithCurrencyLabel(part.amount, part.currency)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MemberHeading(member: CashSourceMember, tags: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(member.counterparty.ifBlank { localizedText("display_not_provided") }, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        tags?.invoke()
        FinanceAmount(cashAmountWithCurrencyLabel(member.amount, member.currency), negative = member.amount?.startsWith("-") == true)
    }
    Text(listOfNotNull(localDateTimeDisplayLabel(member.occurredAt), member.accountName).joinToString(" · "),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CashSourceMemberCard(
    member: CashSourceMember,
    modifier: Modifier = Modifier,
    showHeading: Boolean = true,
    tags: (@Composable () -> Unit)? = null,
    details: @Composable () -> Unit,
    action: (@Composable () -> Unit)? = null,
) {
    Card(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showHeading) {
                MemberHeading(member, tags)
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
            details()
            action?.invoke()
        }
    }
}

@Composable
internal fun CashDetailRelations(
    state: CashRecordTaskState, evidence: CashSourceEvidence?, sourceError: String?, sizeClass: WindowSizeClass,
    accounts: List<Account>, categories: List<CashCategory>, options: LedgerOptions,
    onLoadSources: () -> Unit, onBegin: (() -> Unit)?, onSave: (List<CashRelationDecision>) -> Unit,
    onAdd: (CashRelationAddition, String?) -> Unit, onSearch: suspend (CashAssociationSearch) -> CashAssociationPage,
    onConfirm: () -> Unit, onCancel: () -> Unit,
    onCommonFields: (StructuredObject) -> Unit, onResolve: () -> Unit, onRetryOriginal: () -> Unit, onRefresh: () -> Unit,
) {
    val record = state.record ?: return
    var dialog by remember(record.transactionId, state.savedRevision) { mutableStateOf<String?>(null) }
    var targetRelationIds by remember(record.transactionId) { mutableStateOf<List<String>>(emptyList()) }
    var suggestionId by remember(record.transactionId) { mutableStateOf<String?>(null) }
    val groups = state.relationGroups.ifEmpty { state.details.flatMap { it.relationChoices }.distinctBy { it.suggestionId } }
    LaunchedEffect(record.transactionId, record.rowRevision, state.savedRevision) {
        if (record.projectionRef != null && evidence == null && sourceError == null) onLoadSources()
    }
    val suggestions = groups.filter { "ignore" in it.allowedActions }
    val members = evidence?.members.orEmpty()
    val canAssociate = onBegin != null && record.recordOrigin in setOf("import", "legacy") && record.state == "posted"
    val busy = state.busy || state.unresolved
    fun open(mode: String, id: String? = null, relationIds: List<String> = emptyList()) {
        dialog = mode; suggestionId = id; targetRelationIds = relationIds
        onBegin?.invoke()
    }
    if (record.relations.any { it.active && it.state == "draft" }) {
        Surface(color = LocalFinanceColorRoles.current.warningSurface, contentColor = LocalFinanceColorRoles.current.warning,
            shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().testTag("cash.current.pending-relations")) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(localizedText("cash_import_relation_pending"), style = MaterialTheme.typography.titleSmall)
                Text(localizedText("cash_detail_pending_relations"), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    Column(Modifier.fillMaxWidth().testTag("cash.current.associations"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (members.size > 1 || canAssociate || groups.isNotEmpty()) Text(localizedText("copy_f46e36d281"), style = MaterialTheme.typography.titleMedium)
        members.filterNot { it.transactionId == record.transactionId }.forEach { member ->
            CashSourceMemberCard(
                member = member,
                modifier = Modifier.testTag("cash.current.associated-${member.transactionId}"),
                tags = { MemberTags(member, null, options, Modifier.widthIn(max = 160.dp)) },
                details = { RelatedRecordInformation(member.fields, accounts, categories, options, member, showSummary = false) },
                action = if (!canAssociate) null else {{
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { open("remove", relationIds = member.relations.filter { it.active && it.state == "confirmed" }.map { it.relationId }) }, enabled = !busy,
                            modifier = Modifier.testTag("cash.current.remove-${member.transactionId}"),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                            Icon(painterResource(Res.drawable.ic_link_off), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text(localizedText("cash_detail_remove"))
                        }
                    }
                }},
            )
        }
        suggestions.forEach { group ->
            OutlinedCard(Modifier.fillMaxWidth()
                .testTag("cash.current.suggestion-${group.suggestionId}"), shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(localizedText("cash_detail_suggestion") + " · " + suggestionRelationLabel(group, options), style = MaterialTheme.typography.titleSmall)
                    SuggestionSummary(group, record, state.relationRecords + state.affectedRecords, accounts, categories, options)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (canAssociate) TextButton(onClick = { open("reject", group.suggestionId) }, enabled = !busy,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                            Icon(painterResource(Res.drawable.ic_close), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text(localizedText("cash_detail_reject"))
                        }
                        Spacer(Modifier.weight(1f))
                        if (canAssociate) OutlinedButton(onClick = { open("suggestion", group.suggestionId) }, enabled = !busy) {
                            Icon(painterResource(Res.drawable.ic_check), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text(localizedText(if (cashDetailAlternativeCount(group) == 1 && !cashRelationIsJoint(group) && "accept" in group.allowedActions) "cash_detail_accept" else "cash_detail_check"))
                        }
                    }
                }
            }
        }
        if (canAssociate) OutlinedButton(onClick = { open("add") }, enabled = !busy, modifier = Modifier.testTag("cash.current.add-association")) {
            Icon(painterResource(Res.drawable.ic_add), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(localizedText("cash_detail_add"))
        }
    }
    HorizontalDivider()
    Text(localizedText("cash_statement_content"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("cash.current.sources-title"))
    if (sourceError != null) StateMessage(if (sourceError == "record_changed") ledgerErrorText(sourceError) else localizedText("cash_sources_unavailable"), isError = true) {
        TextButton(onClick = onLoadSources, modifier = Modifier.testTag("cash.current.sources-retry")) { Text(localizedText("copy_e2d53a6d3a")) }
    }
    if (evidence == null && record.projectionRef != null && sourceError == null) CircularProgressIndicator()
    else if (members.isEmpty()) {
        val payload = record.fields.fields["source_payload"] as? StructuredObject
        Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) { Column(Modifier.padding(16.dp)) { StatementValues(payload) } }
    } else members.forEach { member ->
        CashSourceMemberCard(
            member = member,
            modifier = Modifier.testTag("cash.current.source-body-${member.transactionId}"),
            showHeading = members.size > 1,
            tags = { MemberTags(member, record.transactionId, options, Modifier.widthIn(max = 160.dp)) },
            details = { StatementValues(member.sourcePayload) },
        )
    }
    dialog?.let { mode ->
        CashAssociationDialog(state, mode, suggestionId, targetRelationIds, sizeClass, accounts, categories, options,
            onSearch, onSave, onAdd, onConfirm, onCommonFields, onResolve, onRetryOriginal,
            onRestart = { onBegin?.invoke() }, onRefresh = { dialog = null; onCancel(); onRefresh() },
            onDismiss = { if (!state.busy) { dialog = null; if (!state.unresolved) onCancel() } })
    }
}

@Composable
private fun StatementValues(payload: StructuredObject?) {
    val lines = cashStatementLines(payload)
    if (lines.isEmpty()) Text(localizedText("cash_statement_empty"), style = MaterialTheme.typography.bodyMedium)
    else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        lines.forEach { (label, value) -> if (label.isBlank()) Text(value) else DetailValue(label, value,
            monospace = label.contains("单号") || label.contains("流水号") || label.contains("编号")) }
    }
}

internal fun cashDetailAlternativeCount(group: ImportPairOption): Int =
    group.candidates.size.takeIf { it > 0 } ?: if ("accept" in group.allowedActions) 1 else 0

@Composable
private fun SuggestionSummary(group: ImportPairOption, record: CashCurrentRecord, records: Map<String, CashCurrentRecord>,
    accounts: List<Account>, categories: List<CashCategory>, options: LedgerOptions) {
    val count = cashDetailAlternativeCount(group)
    if (count > 1) Text(localizedText("cash_detail_alternatives", count), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    else if (count == 1) {
        val refs = group.candidates.singleOrNull()?.members ?: group.members
        refs.mapNotNull { records[it.transactionId] }.distinctBy { it.transactionId }.filterNot { it.transactionId == record.transactionId }.forEachIndexed { index, other ->
            if (index > 0) HorizontalDivider()
            RelatedRecordInformation(other.fields, accounts, categories, options)
        }
    } else Text(cashSearchHint(group.kind, record), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun cashSearchHint(kind: String?, record: CashCurrentRecord): String = when (kind) {
    "payment_mirror" -> localizedText("cash_detail_find_payment")
    "transfer_pair" -> localizedText("cash_detail_find_transfer")
    "refund_offset" -> if (record.fields.fields["amount"].cashText()?.startsWith("-") == true) localizedText("cash_detail_find_refund") else localizedText("cash_detail_find_purchase")
    else -> localizedText("cash_detail_search_missing")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CashAssociationDialog(
    state: CashRecordTaskState, mode: String, suggestionId: String?, relationIds: List<String>, sizeClass: WindowSizeClass,
    accounts: List<Account>, categories: List<CashCategory>, options: LedgerOptions,
    onSearch: suspend (CashAssociationSearch) -> CashAssociationPage, onSave: (List<CashRelationDecision>) -> Unit,
    onAdd: (CashRelationAddition, String?) -> Unit, onConfirm: () -> Unit,
    onCommonFields: (StructuredObject) -> Unit, onResolve: () -> Unit, onRetryOriginal: () -> Unit,
    onRestart: () -> Unit, onRefresh: () -> Unit, onDismiss: () -> Unit,
) {
    val record = state.record ?: return
    val initialGroups = state.relationGroups.ifEmpty { state.details.flatMap { it.relationChoices }.distinctBy { it.suggestionId } }
    val matches = initialGroups.filter { if (mode == "remove") it.relationIds.any(relationIds::contains) else if (mode == "suggestion" && suggestionId == null) "ignore" in it.allowedActions else it.suggestionId == suggestionId }
    var chosenGroup by remember { mutableStateOf<String?>(null) }
    val group = matches.singleOrNull() ?: matches.firstOrNull { it.suggestionId == chosenGroup }
    val records = state.relationRecords + state.affectedRecords + (record.transactionId to record)
    var step by remember { mutableStateOf(if (mode == "add") "search" else if (mode == "suggestion") "plans" else mode) }
    var candidate by remember { mutableStateOf<String?>(null) }
    var initialized by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    var deferredDecision by remember { mutableStateOf<CashRelationDecision?>(null) }
    var returnStep by remember { mutableStateOf(step) }
    var query by remember { mutableStateOf("") }; var account by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }; var date by remember { mutableStateOf("") }
    var searchItems by remember { mutableStateOf<List<CashCurrentRecord>>(emptyList()) }
    var cursor by remember { mutableStateOf<String?>(null) }; var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }; var generation by remember { mutableStateOf(0) }
    var appliedSearch by remember { mutableStateOf(CashAssociationSearch()) }
    var selected by remember { mutableStateOf<CashCurrentRecord?>(null) }
    var kind by remember { mutableStateOf("payment_mirror") }
    var anchorPart by remember { mutableStateOf<String?>(null) }; var targetPart by remember { mutableStateOf<String?>(null) }
    var inherit by remember { mutableStateOf<Boolean?>(null) }
    var common by remember { mutableStateOf<Map<String, StructuredValue>>(emptyMap()) }
    val compact = sizeClass == WindowSizeClass.COMPACT
    val busy = state.busy || state.unresolved
    val scope = rememberCoroutineScope()
    LaunchedEffect(group?.suggestionId) {
        if (!initialized && group != null) {
            initialized = true; candidate = cashRelationInitialCandidate(group)
            kind = group.kind.orEmpty()
            if (mode == "suggestion" && cashDetailAlternativeCount(group) == 0) step = "search"
        }
    }
    val changes = group?.splitFieldDiffs.orEmpty().filter { it.current != it.restored }.distinct()
    val activeConflicts = if (submitted) cashRelationConflictGroups(state.input, state.details.flatMap { it.relationChoices }).flatMap { it.commonFieldConflicts.entries }.associate { it.key to it.value }
        else group?.commonFieldConflicts.orEmpty()
    val preparedCommon = state.input?.relationAdditions?.singleOrNull()?.commonFields?.fields
        ?: state.input?.relationChoices?.singleOrNull { it.action == "accept" }?.commonFields?.fields.orEmpty()
    val needsConflict = submitted && !state.busy && activeConflicts.any { (field, values) -> preparedCommon[field] !in cashRelationCommonValues(field, values) } &&
        state.input?.let { it.relationAdditions.isNotEmpty() || it.relationChoices.any { choice -> choice.action == "accept" } } == true
    val stale = state.errorCode in setOf("record_changed", "plan_changed", "plan_expired", "relation_context_changed", "issue_changed")
    val view = when { state.unresolved -> "unknown"; stale -> "stale"; needsConflict -> "conflict"; else -> step }
    val choosing = view in setOf("plans", "search")
    val joint = group?.let(::cashRelationIsJoint) == true
    val ready = submitted && state.plan != null && !state.selectionPlan && !state.previewDirty
    val invalidAmount = amount.isNotBlank() && !isExactDecimalString(amount)
    suspend fun search(more: Boolean = false) {
        if (invalidAmount) return
        val requestGeneration = generation
        searching = true; error = null
        try {
            val input = if (more) appliedSearch.copy(cursor = cursor) else CashAssociationSearch(query, account.takeIf(String::isNotBlank), amount.takeIf(String::isNotBlank), date.takeIf(String::isNotBlank), date.takeIf(String::isNotBlank), kind = kind)
            val page = onSearch(input)
            if (requestGeneration != generation) return
            if (!more) appliedSearch = input
            val selectableItems = page.items.filter { it.state == "posted" && it.unavailableReason == null && it.componentChoices.isNotEmpty() }
            searchItems = if (more) (searchItems + selectableItems).distinctBy { it.transactionId } else selectableItems
            cursor = page.nextCursor
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: DomainFailure) { if (requestGeneration == generation) error = failure.code }
        finally { if (requestGeneration == generation) searching = false }
    }
    LaunchedEffect(query, account, amount, date, kind, view) {
        generation++; searching = false
        if (view == "search") {
            selected = null; anchorPart = null; targetPart = null; searchItems = emptyList(); cursor = null; searching = true
            if (!invalidAmount) { searching = true; delay(300); search() } else searching = false
        }
    }
    fun requestPreview() {
        if (step != "review" && step != "conflict") returnStep = step
        submitted = true; step = "review"
    }
    val confirmationDecision = when {
        mode == "reject" && group != null && "ignore" in group.allowedActions -> CashRelationDecision(group.suggestionId, "ignore")
        mode == "remove" && group != null && "remove" in group.allowedActions && (changes.isEmpty() || inherit != null) ->
            CashRelationDecision(group.suggestionId, "remove", inheritCommonFields = inherit.takeIf { changes.isNotEmpty() })
        else -> null
    }
    LaunchedEffect(confirmationDecision, state.busy, state.plan?.planId) {
        if (confirmationDecision != null && !busy && state.errorCode == null && state.plan != null &&
            state.input?.relationChoices != listOf(confirmationDecision)) {
            submitted = true
            onSave(listOf(confirmationDecision))
        }
    }
    val confirmationReady = confirmationDecision != null && ready && state.input?.relationChoices == listOf(confirmationDecision) &&
        !cashHasUnresolvedEffects(state) && state.plan?.canCommit == true && state.plan.invalidatedChoices.isEmpty()
    val title = when (view) {
        "review" -> "cash_detail_preview"; "conflict" -> "cash_detail_common_values"
        "remove" -> "cash_detail_remove"; "reject" -> "cash_detail_reject"
        "unknown" -> "cash_save_unknown"; "stale" -> "cash_detail_stale_title"
        else -> if (mode == "add") "copy_f46e36d281" else if (group != null && cashDetailAlternativeCount(group) == 1 && !joint) "cash_detail_accept" else "cash_detail_check"
    }
    Dialog(onDismissRequest = { if (!state.busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = !compact)) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(if (compact) 0.dp else 24.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = if (choosing && !compact) 1120.dp else 560.dp).fillMaxWidth()
                .then(if (compact) Modifier.fillMaxHeight() else Modifier.heightIn(max = maxHeight))
                .testTag("cash.association.dialog"), shape = if (compact) RoundedCornerShape(0.dp) else MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column {
                    Row(Modifier.fillMaxWidth().then(if (compact) Modifier.height(56.dp).padding(horizontal = 16.dp) else Modifier.padding(24.dp)), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconButton(onClick = onDismiss, enabled = !state.busy) { Icon(painterResource(Res.drawable.ic_close), contentDescription = localizedText(if (view == "unknown" || view == "stale") "cash_detail_close" else "copy_4d0b4688c7")) }
                        Text(localizedText(title), style = MaterialTheme.typography.titleLarge)
                    }
                    Column(Modifier.weight(1f, fill = compact).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
                        .padding(top = if (compact) 24.dp else 0.dp, bottom = if (compact) 24.dp else 0.dp)
                        .testTag("cash.association.view-$view"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (state.busy) CircularProgressIndicator()
                        if (!stale && !state.unresolved) state.errorCode?.let { Text(ledgerErrorText(it), color = MaterialTheme.colorScheme.error) }
                        when (view) {
                            "unknown" -> {
                                Text(localizedText("cash_detail_unknown_hint"))
                                if (state.retryOriginalAllowed) TextButton(onClick = onRetryOriginal, enabled = !state.busy) { Text(localizedText("cash_continue_save")) }
                            }
                            "stale" -> Text(localizedText("cash_detail_stale_hint"), color = MaterialTheme.colorScheme.error)
                            "review" -> {
                                Text(localizedText("cash_detail_preview_hint"), style = MaterialTheme.typography.bodySmall)
                                if (ready) {
                                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainer) {
                                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            val choices = state.input?.relationChoices.orEmpty()
                                            choices.forEach { choice ->
                                                Text(localizedText(when (choice.action) { "ignore" -> "cash_detail_reject"; "remove" -> "cash_detail_remove"; else -> "cash_detail_accept" }), style = MaterialTheme.typography.titleSmall)
                                                val original = initialGroups.firstOrNull { it.suggestionId == choice.suggestionId }
                                                val option = original?.candidates?.firstOrNull { it.candidateId == choice.candidateId }
                                                AssociationRelationSummary(option?.relations?.ifEmpty { original.relations } ?: original?.relations.orEmpty(), records, options)
                                                val refs = option?.members ?: original?.members.orEmpty()
                                                refs.mapNotNull { records[it.transactionId] }.distinctBy { it.transactionId }.forEach { RelatedRecordInformation(it.fields, accounts, categories, options) }
                                            }
                                            state.input?.relationAdditions.orEmpty().forEach { addition ->
                                                Text(cashRelationTypeLabel(addition.kind, options), style = MaterialTheme.typography.titleSmall)
                                                val target = records[addition.targetTransactionId] ?: selected
                                                target?.let { RelatedRecordInformation(it.fields, accounts, categories, options) }
                                                target?.let { cashAssociationChoice(record, it, addition.anchorComponentId, addition.targetComponentId) }
                                                    ?.let { SelectedComponentSummary(record, target, it, accounts) }
                                            }
                                        }
                                    }
                                    CashMaintenanceConfirmation(state, accounts, categories, options, onConfirm, onDismiss, showActions = false)
                                }
                            }
                            "conflict" -> {
                                Text(localizedText("cash_detail_conflict_hint"))
                                activeConflicts.forEach { (field, values) ->
                                    OutlinedCard(Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(12.dp).selectableGroup()) {
                                            Text(cashFieldLabel(field)?.let { localizedText(it) }.orEmpty(), style = MaterialTheme.typography.labelMedium)
                                            cashRelationCommonValues(field, values).forEachIndexed { index, value ->
                                                val clear = value == StructuredNull || value == StructuredText("") || value == StructuredArray(emptyList())
                                                val label = if (clear) localizedText("cash_clear_value") else cashBusinessFieldValue(field, value, accounts, categories, options)
                                                AssociationRadio(label, common[field] == value, !busy, "cash.association.common-$field-${if (clear) "clear" else index}") { common = common + (field to value) }
                                            }
                                        }
                                    }
                                }
                            }
                            "reject", "remove" -> {
                                Text(localizedText(if (view == "remove") "cash_detail_remove_effect" else "cash_detail_reject_effect"))
                                if (view == "remove" && matches.size > 1) Column(Modifier.selectableGroup()) {
                                    matches.forEach { choice -> AssociationRadio(suggestionRelationLabel(choice, options), chosenGroup == choice.suggestionId, !busy,
                                        "cash.association.remove-group-${choice.suggestionId}") { chosenGroup = choice.suggestionId; inherit = null } }
                                }
                                group?.let { choice ->
                                    AssociationRelationSummary(choice.relations, records, options)
                                    cashRelationMemberRecords(choice.members, records)?.forEach { RelatedRecordInformation(it.fields, accounts, categories, options) }
                                }
                                if (view == "remove" && changes.isNotEmpty()) {
                                    Text(localizedText("cash_detail_modified"), style = MaterialTheme.typography.titleSmall)
                                    changes.forEach { diff -> OutlinedCard(Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(cashFieldLabel(diff.field)?.let { localizedText(it) }.orEmpty(), style = MaterialTheme.typography.labelMedium)
                                            DetailValue(localizedText("cash_current_value"), cashBusinessFieldValue(diff.field, diff.current, accounts, categories, options))
                                            DetailValue(localizedText("cash_detail_restored_value"), cashBusinessFieldValue(diff.field, diff.restored, accounts, categories, options))
                                        }
                                    } }
                                    Column(Modifier.selectableGroup()) {
                                        AssociationRadio(localizedText("cash_detail_keep_split"), inherit == true, !busy, "cash.association.inherit-keep") { inherit = true }
                                        AssociationRadio(localizedText("cash_detail_restore_split"), inherit == false, !busy, "cash.association.inherit-restore") { inherit = false }
                                    }
                                }
                                if (ready && state.input?.relationChoices == listOf(confirmationDecision)) {
                                    CashMaintenanceConfirmation(state, accounts, categories, options, onConfirm, onDismiss, showActions = false)
                                }
                                if (state.errorCode != null && !busy) TextButton(onClick = onRestart) { Text(localizedText("copy_e2d53a6d3a")) }
                            }
                            "plans" -> {
                                if (group != null) {
                                    Text(localizedText("cash_detail_suggested_options"), style = MaterialTheme.typography.titleSmall)
                                    val candidates = group.candidates.ifEmpty { if ("accept" in group.allowedActions) listOf(ImportPairCandidate("direct", null, group.members, null, group.relations)) else emptyList() }
                                    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        candidates.forEachIndexed { index, option ->
                                            val memberRecords = cashRelationMemberRecords(option.members, records)
                                            OutlinedCard(Modifier.fillMaxWidth().testTag("cash.association.plan-${option.candidateId}"),
                                                border = BorderStroke(1.dp, if (candidates.size == 1 || candidate == option.candidateId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    if (candidates.size == 1) Text(localizedText("cash_detail_selected_plan"), style = MaterialTheme.typography.labelMedium,
                                                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("cash.association.single-selected"))
                                                    else AssociationOptionHeader(localizedText("cash_detail_option", index + 1), candidate == option.candidateId, !busy && memberRecords != null, option.candidateId) { candidate = option.candidateId }
                                                    if (joint) Text(localizedText("cash_detail_joint_whole"), style = MaterialTheme.typography.labelMedium)
                                                    if (memberRecords == null) Text(ledgerErrorText("record_changed"), color = MaterialTheme.colorScheme.error)
                                                    else AssociationTable(memberRecords.filterNot { it.transactionId == record.transactionId }, sizeClass, accounts, categories, options)
                                                    AssociationRelationSummary(option.relations.ifEmpty { group.relations }, records, options)
                                                }
                                            }
                                        }
                                    }
                                    TextButton(onClick = { selected = null; step = "search" }, enabled = !busy, modifier = Modifier.testTag("cash.association.choose-other")) { Text(localizedText("cash_detail_choose_other")) }
                                }
                            }
                            "search" -> {
                                if (mode == "add") {
                                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("cash.association.relation-types")) {
                                        listOf("payment_mirror", "refund_offset", "transfer_pair").forEachIndexed { index, value ->
                                            SegmentedButton(kind == value, { kind = value },
                                                SegmentedButtonDefaults.itemShape(index, 3), enabled = !busy, modifier = Modifier.testTag("cash.association.kind-$value")) { Text(cashRelationTypeLabel(value, options)) }
                                        }
                                    }
                                } else {
                                    if (group != null && cashDetailAlternativeCount(group) == 0) Text(cashSearchHint(group.kind, record))
                                    Text(localizedText("cash_detail_locked_type") + suggestionRelationLabel(group ?: return@Column, options), modifier = Modifier.testTag("cash.association.locked-type"), style = MaterialTheme.typography.bodySmall)
                                    if (joint) Surface(color = LocalFinanceColorRoles.current.warningSurface, contentColor = LocalFinanceColorRoles.current.warning, shape = MaterialTheme.shapes.small) {
                                        Text(localizedText("cash_detail_joint_search_blocked"), Modifier.padding(12.dp).testTag("cash.association.joint-search-blocked"))
                                    }
                                }
                                OutlinedTextField(query, { query = it }, label = { Text(localizedText("cash_detail_search")) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("cash.association.search"))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ChoicePicker(localizedText("cash_detail_account_id"), account, listOf("" to localizedText("copy_778fc8f994")) + accounts.map { it.id.toString() to it.name }, { account = it }, modifier = Modifier.weight(1f).testTag("cash.association.filter-account"))
                                    OutlinedTextField(amount, { amount = it }, label = { Text(localizedText("copy_34943c40c9")) }, isError = invalidAmount, singleLine = true, modifier = Modifier.weight(1f).testTag("cash.association.filter-amount"))
                                    if (!compact) DatePickerInput(date, { date = it }, localizedText("cash_detail_date"), modifier = Modifier.weight(1f), semanticId = "cash.association.filter-date")
                                }
                                if (compact) DatePickerInput(date, { date = it }, localizedText("cash_detail_date"), semanticId = "cash.association.filter-date")
                                if (searching) CircularProgressIndicator()
                                error?.let { code -> Text(ledgerErrorText(code), color = MaterialTheme.colorScheme.error); TextButton(onClick = { scope.launch { search() } }, enabled = !searching) { Text(localizedText("copy_e2d53a6d3a")) } }
                                AssociationTable(searchItems, sizeClass, accounts, categories, options, selected?.transactionId, { selected = it; anchorPart = null; targetPart = null })
                                if (!searching && error == null && searchItems.isEmpty()) Text(localizedText("copy_8eb3d2788b"))
                                if (cursor != null) TextButton(onClick = { scope.launch { search(true) } }, enabled = !searching) { Text(localizedText("cash_detail_more")) }
                                selected?.let { target ->
                                    val choices = cashAssociationChoices(record, target)
                                    val (effectiveAnchor, effectiveTarget) = cashAssociationDisplayParts(record, target, anchorPart, targetPart)
                                    val matching = if (effectiveAnchor == null) choices else choices.filter { it.anchorComponentId == effectiveAnchor }
                                    ComponentChoices(record, true, accounts, effectiveAnchor, { anchorPart = it; targetPart = null }, choices.map { it.anchorComponentId }.toSet())
                                    ComponentChoices(target, false, accounts, effectiveTarget, { targetPart = it }, matching.map { it.targetComponentId }.toSet())
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().then(if (compact) Modifier.height(56.dp).padding(horizontal = 24.dp) else Modifier.padding(24.dp)), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                        if (view == "search" && mode == "suggestion" && group != null && cashDetailAlternativeCount(group) > 0) TextButton(onClick = { selected = null; step = "plans" }, enabled = !busy,
                            modifier = Modifier.testTag("cash.association.back-suggestions")) { Text(localizedText("cash_detail_back_suggestions")) }
                        if (view == "review") TextButton(onClick = { submitted = false; step = returnStep; onRestart() }, enabled = !busy,
                            modifier = Modifier.testTag("cash.association.back-preview")) { Text(localizedText("cash_detail_back")) }
                        if (view != "stale") TextButton(onClick = onDismiss, enabled = !state.busy) { Text(localizedText(if (view == "unknown") "cash_detail_close" else "copy_4d0b4688c7")) }
                        val selectedParts = selected?.let { cashAssociationChoice(record, it, anchorPart, targetPart) }
                        val choiceLoaded = group?.let { g -> cashRelationChoicesLoaded(listOf(CashRelationDecision(g.suggestionId, "accept", candidateId = candidate)), listOf(g), records) } == true
                        val canAct = !state.busy && when (view) {
                            "unknown", "stale" -> true
                            "review" -> ready && !cashHasUnresolvedEffects(state) && state.plan?.canCommit == true && state.plan.invalidatedChoices.isEmpty()
                            "conflict" -> activeConflicts.isNotEmpty() && activeConflicts.all { (field, values) -> common[field] in cashRelationCommonValues(field, values) }
                            "remove", "reject" -> confirmationReady
                            "search" -> !invalidAmount && !joint && selected?.state == "posted" && !searching && selectedParts != null
                            "plans" -> group != null && "accept" in group.allowedActions && choiceLoaded
                            else -> false
                        }
                        Button(enabled = canAct, modifier = Modifier.testTag("cash.association.continue"), onClick = {
                            when (view) {
                                "unknown" -> onResolve()
                                "stale" -> onRefresh()
                                "review" -> onConfirm()
                                "conflict" -> {
                                    val pending = deferredDecision
                                    requestPreview()
                                    if (pending != null) { deferredDecision = null; onSave(listOf(pending.copy(commonFields = StructuredObject(common)))) }
                                    else onCommonFields(StructuredObject(common))
                                }
                                "remove", "reject" -> onConfirm()
                                "search" -> selected?.let { requestPreview(); onAdd(CashRelationAddition(it.transactionId, it.rowRevision, selectedParts!!.anchorComponentId, selectedParts.targetComponentId, kind), suggestionId) }
                                "plans" -> group?.let {
                                    val decision = CashRelationDecision(it.suggestionId, "accept", candidateId = candidate)
                                    if (it.commonFieldConflicts.isNotEmpty()) { deferredDecision = decision; step = "conflict" }
                                    else { requestPreview(); onSave(listOf(decision)) }
                                }
                            }
                        }) { Text(localizedText(when (view) {
                            "unknown" -> "cash_check_result"; "stale" -> "cash_detail_close_refresh"
                            "review" -> "cash_confirm_save"; "conflict" -> "cash_detail_continue_review"
                            "remove" -> "cash_detail_confirm_remove"; "reject" -> "cash_detail_confirm_reject"; else -> "cash_detail_preview"
                        })) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AssociationRadio(label: String, selected: Boolean, enabled: Boolean, tag: String, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect).testTag(tag),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

internal fun cashAssociationParts(record: CashCurrentRecord): List<Pair<String, StructuredObject>> =
    (record.fields.fields["components"] as? StructuredArray)?.values.orEmpty().mapNotNull { value ->
        (value as? StructuredObject)?.let { part -> part.fields["component_id"].cashText()?.let { it to part } }
    }

internal fun cashAssociationChoices(anchor: CashCurrentRecord, target: CashCurrentRecord): List<CashAssociationComponentChoice> {
    if (anchor.transactionId == target.transactionId || anchor.state != "posted" || target.state != "posted" || target.unavailableReason != null) return emptyList()
    val anchors = cashAssociationParts(anchor).map { it.first }.toSet()
    val targets = cashAssociationParts(target).map { it.first }.toSet()
    return target.componentChoices.filter { it.anchorComponentId in anchors && it.targetComponentId in targets }.distinct()
}

internal fun cashAssociationChoice(anchor: CashCurrentRecord, target: CashCurrentRecord, anchorPart: String?, targetPart: String?): CashAssociationComponentChoice? {
    val choices = cashAssociationChoices(anchor, target)
    val source = anchorPart ?: choices.map { it.anchorComponentId }.distinct().singleOrNull() ?: return null
    val matching = choices.filter { it.anchorComponentId == source }
    val counterpart = targetPart ?: matching.map { it.targetComponentId }.distinct().singleOrNull() ?: return null
    return matching.singleOrNull { it.targetComponentId == counterpart }
}

internal fun cashAssociationDisplayParts(anchor: CashCurrentRecord, target: CashCurrentRecord, anchorPart: String?, targetPart: String?): Pair<String?, String?> {
    val choices = cashAssociationChoices(anchor, target)
    val effectiveAnchor = anchorPart ?: choices.map { it.anchorComponentId }.distinct().singleOrNull()
    val matching = if (effectiveAnchor == null) choices else choices.filter { it.anchorComponentId == effectiveAnchor }
    return effectiveAnchor to (targetPart ?: matching.map { it.targetComponentId }.distinct().singleOrNull())
}


@Composable
private fun cashAssociationPartLabel(part: StructuredObject, accounts: List<Account>): String {
    val account = part.fields["account_name"].cashText()?.takeIf { it.isNotBlank() }
        ?: accounts.firstOrNull { it.id.toString() == part.fields["account_id"].cashText() }?.name
        ?: localizedText("display_not_provided")
    return listOfNotNull(account, part.fields["label"].cashText()?.takeIf { it.isNotBlank() },
        cashAmountWithCurrencyLabel(part.fields["amount"].cashText(), part.fields["currency"].cashText())).distinct().joinToString(" · ")
}

@Composable
private fun SelectedComponentSummary(anchor: CashCurrentRecord, target: CashCurrentRecord, choice: CashAssociationComponentChoice, accounts: List<Account>) {
    val anchorPart = cashAssociationParts(anchor).firstOrNull { it.first == choice.anchorComponentId }?.second
    val targetPart = cashAssociationParts(target).firstOrNull { it.first == choice.targetComponentId }?.second
    if (anchorPart == null && targetPart == null) return
    Column(Modifier.fillMaxWidth().testTag("cash.association.selected-parts"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        anchorPart?.let { Text(localizedText("ledger_detail_current_record") + " · " + cashAssociationPartLabel(it, accounts),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        targetPart?.let { Text(localizedText("cash_detail_counterpart") + " · " + cashAssociationPartLabel(it, accounts),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun ComponentChoices(record: CashCurrentRecord, current: Boolean, accounts: List<Account>, selected: String?, onSelect: (String) -> Unit, available: Set<String>) {
    val parts = cashAssociationParts(record)
    if (parts.size > 1) {
        Text(localizedText(if (current) "ledger_detail_current_record" else "cash_detail_counterpart") + " · " +
            record.fields.fields["counterparty"].cashText().orEmpty(), style = MaterialTheme.typography.titleSmall)
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            parts.forEach { (id, part) ->
                AssociationRadio(cashAssociationPartLabel(part, accounts), selected == id, id in available, "cash.association.part-${record.transactionId}-$id") { onSelect(id) }
            }
        }
    }
}

@Composable
private fun AssociationOptionHeader(label: String, selected: Boolean, enabled: Boolean, id: String, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
        .testTag("cash.association.option-$id"), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun AssociationTable(records: List<CashCurrentRecord>, sizeClass: WindowSizeClass, accounts: List<Account>, categories: List<CashCategory>, options: LedgerOptions,
    selectedId: String? = null, onSelectRecord: ((CashCurrentRecord) -> Unit)? = null) {
    if (records.isEmpty()) return
    val selectable = onSelectRecord != null
    val listState = rememberLazyListState()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val visibleWidth = maxWidth
        val tableWidth = maxOf(1000.dp, visibleWidth)
        val horizontalScroll = rememberScrollState()
        val tableModifier = if (sizeClass == WindowSizeClass.COMPACT) Modifier.fillMaxWidth()
            else Modifier.horizontalScroll(horizontalScroll)
        Box(tableModifier.then(if (selectable) Modifier.selectableGroup() else Modifier)) {
            CashWaterfallViewport(
                listState = listState,
                totalCount = records.size,
                monthAt = { index -> records.getOrNull(index)?.let { localMonthKey(it.fields.fields["occurred_at"].cashText()) } },
                monthStartAt = { index ->
                    val month = localMonthKey(records[index].fields.fields["occurred_at"].cashText())
                    var start = index
                    while (start > 0 && localMonthKey(records[start - 1].fields.fields["occurred_at"].cashText()) == month) start--
                    start
                },
                monthPositions = emptyList(),
                isOrdinalLoaded = { it in records.indices },
                summaryForMonth = { null },
                showTotals = false,
                sizeClass = sizeClass,
                showSelection = selectable,
                auxiliaryColumn = CashLedgerAuxiliaryColumn.TYPE,
                modifier = (if (sizeClass == WindowSizeClass.COMPACT) Modifier.fillMaxWidth() else Modifier.width(tableWidth))
                    .height(minOf(400, 88 + records.size * 112).dp),
                horizontalScroll = if (sizeClass != WindowSizeClass.COMPACT && visibleWidth < 1000.dp)
                    CashWaterfallHorizontalScroll(horizontalScroll, visibleWidth) else null,
            ) { onHeaderHeight ->
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    items(records.size) { index ->
                        val record = records[index]
                        val month = localMonthKey(record.fields.fields["occurred_at"].cashText())
                        val first = index == 0 || month != localMonthKey(records[index - 1].fields.fields["occurred_at"].cashText())
                        val last = index == records.lastIndex || month != localMonthKey(records[index + 1].fields.fields["occurred_at"].cashText())
                        CashWaterfallItem(month, first, last, first && index > 0, null, false, sizeClass,
                            selectable, CashLedgerAuxiliaryColumn.TYPE, onHeaderHeight) {
                            val f = record.fields.fields
                            val enabled = record.state == "posted" && (!selectable || record.componentChoices.isNotEmpty())
                            val item = CashLedgerRowContent(record.transactionId, f["occurred_at"].cashText(), f["counterparty"].cashText().orEmpty(), f["note"].cashText().orEmpty(),
                                f["account_name"].cashText() ?: accounts.firstOrNull { it.id.toString() == f["account_id"].cashText() }?.name.orEmpty(),
                                cashCategoryDisplayPath(categories.firstOrNull { it.id == f["category_id"].cashText() }),
                                cashRecordTypeAndSubtypeLabel(f["record_type"].cashText(), f["record_subtype"].cashText(), options),
                                null, record.confirmationStatus,
                                cashAmountWithCurrencyLabel(f["amount"].cashText(), f["currency"].cashText()), f["amount"].cashText()?.startsWith("-") == true,
                                showConfirmationStatus = false)
                            val choose = { if (enabled) onSelectRecord?.invoke(record); Unit }
                            CashLedgerRow(item, selectable && selectedId == record.transactionId, enabled, sizeClass, selectable, last,
                                { choose() }, choose, showSelection = selectable, singleSelection = selectable, showOpenAction = selectable)
                        }
                    }
                }
            }
        }
    }
}
