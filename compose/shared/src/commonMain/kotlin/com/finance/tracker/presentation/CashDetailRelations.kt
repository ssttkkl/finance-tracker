package com.finance.tracker.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
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

internal fun cashDetailRelationKinds(member: CashSourceMember): List<Pair<String, String?>> =
    member.relations.filter { it.active && it.state == "confirmed" }.map { it.kind to it.subtype }.distinct()

@Composable
private fun relationLabel(kind: String, subtype: String?, options: LedgerOptions): String = when (subtype) {
    "credit_repayment" -> localizedText("cash_detail_repayment")
    "currency_exchange" -> localizedText("cash_detail_exchange")
    "cross_currency_remittance" -> localizedText("cash_detail_remittance")
    "ordinary_transfer" -> localizedText("cash_detail_ordinary_transfer")
    else -> cashRelationTypeLabel(kind, options)
}

@Composable
private fun suggestionRelationLabel(group: ImportPairOption, options: LedgerOptions): String {
    val relations = group.relations.ifEmpty { group.candidates.flatMap { it.relations } }
    val types = relations.map { it.kind to it.subtype }.distinct()
    if (types.isEmpty()) return relationLabel(group.kind.orEmpty(), group.subtype, options)
    val labels = mutableListOf<String>()
    for ((kind, subtype) in types) labels += relationLabel(kind, subtype, options)
    return labels.joinToString(" · ")
}

@Composable
private fun AssociationRelationSummary(relations: List<ImportPairRelation>, records: Map<String, CashCurrentRecord>, options: LedgerOptions) {
    if (relations.map { it.kind to it.subtype }.distinct().size > 1) {
        relations.forEach { relation ->
            val names = relation.members.mapNotNull { ref -> records[ref.transactionId]?.fields?.fields?.get("counterparty").cashText() }
            Text(relationLabel(relation.kind, relation.subtype, options) + " · " + names.joinToString(" ↔ "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemberTags(member: CashSourceMember, currentId: String?, options: LedgerOptions, modifier: Modifier = Modifier) {
    val labels = if (member.transactionId == currentId) listOf(localizedText("ledger_detail_current_record"))
        else cashDetailRelationKinds(member).map { (kind, subtype) -> relationLabel(kind, subtype, options) }
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
    member: CashSourceMember? = null,
) {
    val values = fields.fields
    val amount = member?.amount ?: values["amount"].cashText()
    val currency = member?.currency ?: values["currency"].cashText()
    val counterparty = member?.counterparty ?: values["counterparty"].cashText().orEmpty()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(counterparty.ifBlank { localizedText("display_not_provided") }, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            FinanceAmount(cashAmountWithCurrencyLabel(amount, currency), negative = amount?.startsWith("-") == true)
        }
        Text(listOfNotNull(values["record_type"]?.let { cashBusinessFieldValue("record_type", it, accounts, categories, options) },
            localDateTimeDisplayLabel(member?.occurredAt ?: values["occurred_at"].cashText())).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
internal fun CashDetailRelations(
    state: CashRecordTaskState, evidence: CashSourceEvidence?, sourceError: String?, sizeClass: WindowSizeClass,
    accounts: List<Account>, categories: List<CashCategory>, options: LedgerOptions,
    onLoadSources: () -> Unit, onBegin: (() -> Unit)?, onSave: (List<CashRelationDecision>) -> Unit,
    onAdd: (CashRelationAddition, String?) -> Unit, onSearch: suspend (CashAssociationSearch) -> CashAssociationPage,
    onConfirm: () -> Unit, onCancel: () -> Unit,
    onCommonFields: (StructuredObject) -> Unit, onResolve: () -> Unit, onRetryOriginal: () -> Unit,
) {
    val record = state.record ?: return
    var dialog by remember(record.transactionId, state.savedRevision) { mutableStateOf<String?>(null) }
    var targetRelationIds by remember(record.transactionId) { mutableStateOf<List<String>>(emptyList()) }
    var suggestionId by remember(record.transactionId) { mutableStateOf<String?>(null) }
    var cachedGroups by remember(record.transactionId, state.savedRevision) { mutableStateOf<List<ImportPairOption>>(emptyList()) }
    val actualGroups = state.details.flatMap { it.relationChoices }.distinctBy { it.suggestionId }
    LaunchedEffect(actualGroups, state.selectionPlan) { if (state.selectionPlan) cachedGroups = actualGroups }
    LaunchedEffect(record.transactionId, record.rowRevision, state.savedRevision) {
        if (record.projectionRef != null && evidence == null && sourceError == null) onLoadSources()
    }
    val groups = if (state.selectionPlan) actualGroups else cachedGroups
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
            Card(Modifier.fillMaxWidth().testTag("cash.current.associated-${member.transactionId}"), shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RelatedRecordInformation(member.fields, accounts, categories, options, member)
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { MemberTags(member, null, options) }
                        if (canAssociate) TextButton(onClick = { open("remove", relationIds = member.relations.filter { it.active && it.state == "confirmed" }.map { it.relationId }) }, enabled = !busy,
                            modifier = Modifier.testTag("cash.current.remove-${member.transactionId}"),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                            Icon(painterResource(Res.drawable.ic_link_off), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text(localizedText("cash_detail_remove"))
                        }
                    }
                }
            }
        }
        suggestions.forEach { group ->
            OutlinedCard(Modifier.fillMaxWidth()
                .testTag("cash.current.suggestion-${group.suggestionId}"), shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(localizedText("cash_detail_suggestion") + " · " + suggestionRelationLabel(group, options), style = MaterialTheme.typography.titleSmall)
                    SuggestionSummary(group, record, state.affectedRecords, accounts, categories, options)
                    Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (canAssociate) TextButton(onClick = { open("reject", group.suggestionId) }, enabled = !busy,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                            Icon(painterResource(Res.drawable.ic_close), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text(localizedText("cash_detail_reject"))
                        }
                        if (canAssociate) OutlinedButton(onClick = { open("suggestion", group.suggestionId) }, enabled = !busy) {
                            Icon(painterResource(Res.drawable.ic_check), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text(localizedText(if (group.candidates.size <= 1 && "accept" in group.allowedActions) "cash_detail_accept" else "cash_detail_check"))
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
        Card(Modifier.fillMaxWidth().testTag("cash.current.source-body-${member.transactionId}"), shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (members.size > 1) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        MemberHeading(member) { MemberTags(member, record.transactionId, options, Modifier.widthIn(max = 160.dp)) }
                    }
                    HorizontalDivider()
                }
                StatementValues(member.sourcePayload)
            }
        }
    }
    dialog?.let { mode ->
        CashAssociationDialog(state, mode, suggestionId, targetRelationIds, sizeClass, accounts, categories, options,
            onSearch, onSave, onAdd, onConfirm, onCommonFields, onResolve, onRetryOriginal,
            onDismiss = { if (!busy) { dialog = null; onCancel() } })
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
        AssociationRelationSummary(group.candidates.singleOrNull()?.relations?.ifEmpty { group.relations } ?: group.relations, records, options)
        val refs = group.candidates.singleOrNull()?.members ?: group.members
        refs.mapNotNull { records[it.transactionId] }.distinctBy { it.transactionId }.filterNot { it.transactionId == record.transactionId }.forEachIndexed { index, other ->
            if (index > 0) HorizontalDivider()
            RelatedRecordInformation(other.fields, accounts, categories, options)
        }
    } else Text(localizedText(when (group.kind) {
        "refund_offset" -> if (record.fields.fields["record_type"].cashText() == "refund") "cash_detail_find_purchase" else "cash_detail_find_refund"
        "transfer_pair" -> "cash_detail_find_transfer"
        "payment_mirror" -> "cash_detail_find_payment"
        else -> "cash_detail_no_target"
    }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    group.code?.let { Text(ledgerErrorText(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CashAssociationDialog(
    state: CashRecordTaskState, mode: String, suggestionId: String?, relationIds: List<String>, sizeClass: WindowSizeClass,
    accounts: List<Account>, categories: List<CashCategory>, options: LedgerOptions,
    onSearch: suspend (CashAssociationSearch) -> CashAssociationPage, onSave: (List<CashRelationDecision>) -> Unit,
    onAdd: (CashRelationAddition, String?) -> Unit, onConfirm: () -> Unit,
    onCommonFields: (StructuredObject) -> Unit, onResolve: () -> Unit, onRetryOriginal: () -> Unit, onDismiss: () -> Unit,
) {
    val record = state.record ?: return
    val groups = state.details.flatMap { it.relationChoices }.distinctBy { it.suggestionId }
    val matchingGroups = groups.filter { if (mode == "remove") it.relationIds.any { id -> id in relationIds } else it.suggestionId == suggestionId }
    var chosenGroup by remember { mutableStateOf<String?>(null) }
    val group = matchingGroups.singleOrNull() ?: matchingGroups.firstOrNull { it.suggestionId == chosenGroup }
    val busy = state.busy || state.unresolved
    var query by remember { mutableStateOf("") }; var account by remember { mutableStateOf("") }; var amount by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }
    var searchItems by remember { mutableStateOf<List<CashCurrentRecord>>(emptyList()) }; var cursor by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    var appliedSearch by remember { mutableStateOf(CashAssociationSearch()) }
    var searchGeneration by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<CashCurrentRecord?>(null) }; var candidate by remember { mutableStateOf<String?>(null) }
    var kind by remember { mutableStateOf("payment_mirror") }; var subtype by remember { mutableStateOf("ordinary_transfer") }
    var anchorPart by remember { mutableStateOf<String?>(null) }; var targetPart by remember { mutableStateOf<String?>(null) }
    var inherit by remember { mutableStateOf<Boolean?>(null) }; var common by remember { mutableStateOf<Map<String, StructuredValue>>(emptyMap()) }
    val scope = rememberCoroutineScope()
    suspend fun search(more: Boolean = false) {
        val generation = searchGeneration
        searching = true; error = null
        try {
            val input = if (more) appliedSearch.copy(cursor = cursor) else CashAssociationSearch(query, account.takeIf { it.isNotBlank() }, amount.takeIf { it.isNotBlank() }, date.takeIf { it.isNotBlank() }, date.takeIf { it.isNotBlank() })
            val page = onSearch(input)
            if (generation != searchGeneration) return
            if (!more) appliedSearch = input
            searchItems = if (more) (searchItems + page.items).distinctBy { it.transactionId } else page.items
            cursor = page.nextCursor; selected = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: DomainFailure) { if (generation == searchGeneration) error = failure.code }
        finally { if (generation == searchGeneration) searching = false }
    }
    LaunchedEffect(query, account, amount, date) {
        if (mode == "add" || mode == "suggestion") {
            searchGeneration++; selected = null; searchItems = emptyList(); cursor = null; searching = true
            delay(300)
            search()
        }
    }
    LaunchedEffect(group?.suggestionId) {
        candidate = group?.candidateId ?: group?.candidates?.singleOrNull()?.candidateId
        kind = group?.kind ?: "payment_mirror"
        if (group?.subtype?.isNotBlank() == true) subtype = group.subtype
    }
    val changes = group?.splitFieldDiffs.orEmpty().distinctBy { it.field to it.current }
    val conflicts = groups.flatMap { it.commonFieldConflicts.entries }.associate { it.key to it.value }
    val resolvingConflicts = conflicts.isNotEmpty() && state.input?.let { input ->
        input.relationAdditions.isNotEmpty() || input.relationChoices.any { it.action == "accept" }
    } == true
    LaunchedEffect(conflicts) {
        common = common.filter { (field, value) -> field in conflicts && value in cashRelationCommonValues(field, conflicts.getValue(field)) }
    }
    val readyToConfirm = state.plan != null && !state.selectionPlan
    val compact = sizeClass == WindowSizeClass.COMPACT
    val choosing = mode in setOf("add", "suggestion") && !readyToConfirm && !resolvingConflicts
    val showSuggestions = choosing && query.isBlank() && account.isBlank() && amount.isBlank() && date.isBlank()
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = !compact)) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(if (compact) 0.dp else 24.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = if (choosing && !compact) 1120.dp else 560.dp).fillMaxWidth()
                .then(if (compact) Modifier.fillMaxHeight() else Modifier.heightIn(max = maxHeight))
                .testTag("cash.association.dialog"), shape = if (compact) RoundedCornerShape(0.dp) else MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column {
                    Row(Modifier.fillMaxWidth().then(if (compact) Modifier.height(56.dp).padding(horizontal = 16.dp) else Modifier.padding(24.dp)), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconButton(onClick = onDismiss, enabled = !busy) { Icon(painterResource(Res.drawable.ic_close), contentDescription = localizedText("copy_4d0b4688c7")) }
                        Text(localizedText(when (mode) {
                            "remove" -> "cash_detail_remove"; "reject" -> "cash_detail_reject"; "add" -> "copy_f46e36d281"
                            else -> if (group?.let(::cashDetailAlternativeCount) == 1) "cash_detail_accept" else "cash_detail_check"
                        }), style = MaterialTheme.typography.titleLarge)
                    }
                    Column(Modifier.weight(1f, fill = compact).verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp).padding(top = if (compact) 24.dp else 0.dp, bottom = if (compact) 24.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.errorCode?.let { Text(ledgerErrorText(it), color = MaterialTheme.colorScheme.error) }
                        if (state.unresolved) {
                            Text(localizedText("cash_save_unknown"), color = MaterialTheme.colorScheme.error)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = onResolve, enabled = !state.busy) { Text(localizedText("cash_check_result")) }
                                if (state.retryOriginalAllowed) TextButton(onClick = onRetryOriginal, enabled = !state.busy) { Text(localizedText("cash_continue_save")) }
                            }
                        }
                        error?.let { Text(ledgerErrorText(it), color = MaterialTheme.colorScheme.error) }
                        if (busy) CircularProgressIndicator()
                        if (readyToConfirm && !resolvingConflicts) CashMaintenanceConfirmation(state, accounts, categories, options, onConfirm, onDismiss, showActions = false)
                        else if (resolvingConflicts) {
                            Text(localizedText("cash_detail_common_values"), style = MaterialTheme.typography.titleMedium)
                            conflicts.forEach { (field, values) ->
                                Text(cashFieldLabel(field)?.let { localizedText(it) }.orEmpty())
                                cashRelationCommonValues(field, values).forEachIndexed { index, value ->
                                    val clear = value == StructuredNull || value == StructuredText("") || value == StructuredArray(emptyList())
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(common[field] == value, onClick = { common = common + (field to value) }, enabled = !busy,
                                        modifier = Modifier.testTag("cash.association.common-$field-${if (clear) "clear" else index}"))
                                    Text(if (clear) localizedText("cash_clear_value") else cashBusinessFieldValue(field, value, accounts, categories, options))
                                } }
                            }
                        } else if (mode == "remove" || mode == "reject") {
                            if (mode == "remove" && matchingGroups.size > 1) matchingGroups.forEach { choice -> Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(chosenGroup == choice.suggestionId, onClick = { chosenGroup = choice.suggestionId; inherit = null }, enabled = !busy)
                                Text(relationLabel(choice.kind.orEmpty(), choice.subtype, options))
                            } }
                            Text(localizedText(if (mode == "remove") "cash_detail_remove_effect" else "cash_detail_reject_effect"))
                            group?.members?.mapNotNull { state.affectedRecords[it.transactionId] }?.forEach { CashAffectedRecordSummary(it.fields) }
                            if (changes.isNotEmpty() && mode == "remove") {
                                Text(localizedText("cash_detail_modified"), style = MaterialTheme.typography.titleSmall)
                                changes.forEach { diff -> DetailValue(cashFieldLabel(diff.field)?.let { localizedText(it) }.orEmpty(), cashBusinessFieldValue(diff.field, diff.current, accounts, categories, options)) }
                                Text(localizedText("cash_inherit_question"))
                                listOf(true to "cash_inherit_changes", false to "cash_keep_individual").forEach { (value, label) -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(inherit == value, onClick = { inherit = value }, enabled = !busy); Text(localizedText(label))
                                } }
                            }
                        } else {
                            if (mode == "add") {
                                val relationTypes = listOf("payment_mirror", "refund_offset", "transfer_pair")
                                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("cash.association.relation-types")) {
                                    relationTypes.forEachIndexed { index, value ->
                                        SegmentedButton(selected = kind == value, onClick = { kind = value }, enabled = !busy,
                                            shape = SegmentedButtonDefaults.itemShape(index, relationTypes.size),
                                            modifier = Modifier.testTag("cash.association.kind-$value")) { Text(cashRelationTypeLabel(value, options)) }
                                    }
                                }
                            }
                            OutlinedTextField(query, { query = it }, label = { Text(localizedText("cash_detail_search")) }, singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("cash.association.search"))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ChoicePicker(localizedText("cash_detail_account_id"), account,
                                    listOf("" to localizedText("copy_778fc8f994")) + accounts.map { it.id.toString() to it.name }, { account = it },
                                    modifier = Modifier.weight(1f).testTag("cash.association.filter-account"))
                                OutlinedTextField(amount, { amount = it }, label = { Text(localizedText("copy_34943c40c9")) }, singleLine = true,
                                    modifier = Modifier.weight(1f).testTag("cash.association.filter-amount"))
                                if (!compact) OutlinedTextField(date, { date = it }, label = { Text(localizedText("cash_detail_date")) }, placeholder = { Text("YYYY-MM-DD") }, singleLine = true,
                                    modifier = Modifier.weight(1f).testTag("cash.association.filter-date"))
                            }
                            if (compact) OutlinedTextField(date, { date = it }, label = { Text(localizedText("cash_detail_date")) }, placeholder = { Text("YYYY-MM-DD") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("cash.association.filter-date"))
                            if (showSuggestions && group != null) {
                                val candidates = group?.candidates.orEmpty()
                                if (cashDetailAlternativeCount(group) > 0) Text(localizedText("cash_detail_suggested_options"), style = MaterialTheme.typography.titleSmall)
                                if (candidates.isEmpty()) {
                                    if ("accept" in group.allowedActions) {
                                        AssociationRelationSummary(group.relations, state.affectedRecords, options)
                                        AssociationTable(group.members.mapNotNull { state.affectedRecords[it.transactionId] }, sizeClass, accounts, categories, options, selected == null, { selected = null })
                                    }
                                    else SuggestionSummary(group, record, state.affectedRecords, accounts, categories, options)
                                } else candidates.forEach { option ->
                                    val records = cashRelationMemberRecords(option.members, state.affectedRecords)
                                    if (records == null) Text(ledgerErrorText("record_changed"), color = MaterialTheme.colorScheme.error)
                                    else {
                                        AssociationRelationSummary(option.relations, state.affectedRecords, options)
                                        AssociationTable(records, sizeClass, accounts, categories, options, selected == null && candidate == option.candidateId, { candidate = option.candidateId; selected = null })
                                    }
                                    HorizontalDivider()
                                }
                            }
                            run {
                                if (showSuggestions && group != null && cashDetailAlternativeCount(group) > 0) Text(localizedText("cash_detail_other_records"), style = MaterialTheme.typography.titleSmall)
                                if (searching) CircularProgressIndicator()
                                val suggestedIds = if (showSuggestions) group?.let { (it.members + it.candidates.flatMap { option -> option.members }).mapNotNull { ref -> ref.transactionId }.toSet() }.orEmpty() else emptySet()
                                AssociationTable(searchItems.filterNot { it.transactionId in suggestedIds }, sizeClass, accounts, categories, options, false, {}, selected?.transactionId,
                                    { item -> selected = item; targetPart = null })
                                if (searchItems.isEmpty() && !searching) Text(localizedText("copy_8eb3d2788b"))
                                if (cursor != null) TextButton(onClick = { scope.launch { search(true) } }, enabled = !searching) { Text(localizedText("cash_detail_more")) }
                                selected?.let { target ->
                                    if (mode != "add") FlowRow { listOf("payment_mirror", "refund_offset", "transfer_pair").forEach { value -> Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(kind == value, onClick = { kind = value }); Text(cashRelationTypeLabel(value, options))
                                    } } }
                                    if (kind == "transfer_pair") FlowRow { listOf("ordinary_transfer", "credit_repayment", "currency_exchange", "cross_currency_remittance").forEach { value -> Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(subtype == value, onClick = { subtype = value }); Text(relationLabel("transfer_pair", value, options))
                                    } } }
                                    ComponentChoices(record, anchorPart, { anchorPart = it })
                                    ComponentChoices(target, targetPart, { targetPart = it })
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().then(if (compact) Modifier.height(56.dp).padding(horizontal = 24.dp) else Modifier.padding(24.dp)), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onDismiss, enabled = !busy) { Text(localizedText("copy_4d0b4688c7")) }
                        Spacer(Modifier.width(8.dp))
                        val parts = cashAssociationParts(record); val targetParts = selected?.let(::cashAssociationParts).orEmpty()
                        val sourcePart = anchorPart ?: parts.singleOrNull()?.first
                        val chosenPart = targetPart ?: targetParts.singleOrNull()?.first
                        val canAct = !busy && when {
                            resolvingConflicts -> conflicts.keys.all { field -> common[field] in cashRelationCommonValues(field, conflicts.getValue(field)) }
                            readyToConfirm -> state.plan?.canCommit == true && !state.previewDirty
                            mode == "remove" -> group != null && "remove" in group.allowedActions && (changes.isEmpty() || inherit != null)
                            mode == "reject" -> group != null && "ignore" in group.allowedActions
                            selected != null -> selected?.state == "posted" && !searching && sourcePart != null && chosenPart != null
                            else -> showSuggestions && group != null && "accept" in group.allowedActions && (group.candidates.isEmpty() || candidate != null)
                        }
                        Button(enabled = canAct, modifier = Modifier.testTag("cash.association.continue"), onClick = {
                            when {
                                resolvingConflicts -> onCommonFields(StructuredObject(common))
                                readyToConfirm -> onConfirm()
                                mode == "remove" -> group?.let { onSave(listOf(CashRelationDecision(it.suggestionId, "remove", inheritCommonFields = inherit))) }
                                mode == "reject" -> group?.let { onSave(listOf(CashRelationDecision(it.suggestionId, "ignore"))) }
                                selected != null -> selected?.let { onAdd(CashRelationAddition(it.transactionId, it.rowRevision, sourcePart!!, chosenPart!!, kind, if (kind == "transfer_pair") subtype else ""), suggestionId) }
                                else -> group?.let { onSave(listOf(CashRelationDecision(it.suggestionId, "accept", candidateId = candidate))) }
                            }
                        }) { Text(localizedText(if (readyToConfirm && !resolvingConflicts) "cash_confirm_save" else "cash_detail_preview")) }
                    }
                }
            }
        }
    }
}

internal fun cashAssociationParts(record: CashCurrentRecord): List<Pair<String, StructuredObject>> =
    (record.fields.fields["components"] as? StructuredArray)?.values.orEmpty().mapNotNull { value ->
        (value as? StructuredObject)?.let { part -> part.fields["component_id"].cashText()?.let { it to part } }
    }

@Composable
private fun ComponentChoices(record: CashCurrentRecord, selected: String?, onSelect: (String) -> Unit) {
    val parts = cashAssociationParts(record)
    if (parts.size > 1) {
        Text(localizedText("cash_detail_choose_part"), style = MaterialTheme.typography.titleSmall)
        parts.forEach { (id, part) -> Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected == id, onClick = { onSelect(id) })
            Text(cashAmountWithCurrencyLabel(part.fields["amount"].cashText(), part.fields["currency"].cashText()))
        } }
    }
}

@Composable
private fun AssociationTable(records: List<CashCurrentRecord>, sizeClass: WindowSizeClass, accounts: List<Account>, categories: List<CashCategory>, options: LedgerOptions,
    selected: Boolean, onSelect: () -> Unit, selectedId: String? = null, onSelectRecord: ((CashCurrentRecord) -> Unit)? = null) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
      val tableWidth = maxOf(1000.dp, maxWidth)
      val tableModifier = if (sizeClass == WindowSizeClass.COMPACT) Modifier.fillMaxWidth()
        else Modifier.horizontalScroll(rememberScrollState())
      Column(tableModifier) {
       Column(if (sizeClass == WindowSizeClass.COMPACT) Modifier.fillMaxWidth() else Modifier.width(tableWidth)) {
        if (records.isNotEmpty() && sizeClass != WindowSizeClass.COMPACT) CashLedgerTableHeader(Modifier.padding(horizontal = 16.dp))
        records.forEachIndexed { index, record ->
        val f = record.fields.fields
        val item = CashLedgerRowContent(record.transactionId, f["occurred_at"].cashText(), f["counterparty"].cashText().orEmpty(), f["note"].cashText().orEmpty(),
            f["account_name"].cashText() ?: accounts.firstOrNull { it.id.toString() == f["account_id"].cashText() }?.name.orEmpty(),
            cashCategoryDisplayPath(categories.firstOrNull { it.id == f["category_id"].cashText() }),
            cashRecordTypeLabel(f["record_type"].cashText().orEmpty(), options), f["source_type"].cashText(), record.confirmationStatus,
            cashAmountWithCurrencyLabel(f["amount"].cashText(), f["currency"].cashText()), f["amount"].cashText()?.startsWith("-") == true)
        val choose = { if (record.state == "posted") { if (onSelectRecord != null) onSelectRecord(record) else onSelect() } }
        CashLedgerRow(item, if (onSelectRecord != null) selectedId == record.transactionId else selected, record.state == "posted", sizeClass, true, index == records.lastIndex,
            { choose() }, choose)
        }
       }
      }
    }
}
