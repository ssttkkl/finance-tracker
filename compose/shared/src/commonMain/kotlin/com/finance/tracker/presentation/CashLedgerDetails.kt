package com.finance.tracker.presentation

import com.finance.tracker.core.*
import com.finance.tracker.domain.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

@Composable
internal fun CashRecordDetailDialog(
    state: CashLedgerUiState,
    viewModel: CashLedgerViewModel,
    canWrite: Boolean,
    options: LedgerOptions,
    onEdit: () -> Unit,
    onEditRelated: (CashRecord) -> Unit,
    onClose: () -> Unit,
) {
    val evidence = state.evidence
    val detail = state.recordDetail
    val record = detail?.record
    val relationBusy = state.relationBusy
    val relationError = state.detailActionErrorCode?.let { ledgerErrorText(it) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        SectionCard(modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp).heightIn(max = 760.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(localizedText("copy_8cb72a44a5"), style = MaterialTheme.typography.headlineSmall)
                FinanceTertiaryButton(onClick = onClose) { Text(localizedText("copy_6c14bd7f6f")) }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    state.recordLoading -> Text(localizedText("copy_875adbe057"))
                    state.recordErrorCode != null -> {
                        InlineError(ledgerErrorText(state.recordErrorCode))
                        FinanceTertiaryButton(onClick = viewModel::retrySelectedRow) { Text(localizedText("copy_e2d53a6d3a")) }
                    }
                    record != null -> {
                        Text(record.counterparty.ifBlank { record.accountName }, style = MaterialTheme.typography.titleLarge)
                        Text("${record.amount} ${record.currency}　${formatLocalDateTime(record.occurredAt)}")
                        Text("${cashRecordTypeLabel(record.recordType, options)} · ${record.accountName} · ${cashCategoryDisplayPath(record.category)}")
                        record.counterpartyAccount.takeIf(String::isNotBlank)?.let { Text(localizedText("copy_41673fc40f", it)) }
                        record.note.takeIf(String::isNotBlank)?.let { Text(it) }
                        HorizontalDivider()
                        Text(localizedText("copy_f46e36d281"), style = MaterialTheme.typography.titleMedium)
                        if (evidence != null) {
                            HorizontalDivider()
                            Text(localizedText("copy_b9e9fd25ac", cashEvidenceSourceLabel(evidence)), style = MaterialTheme.typography.bodySmall)
                            record.components.orEmpty().takeIf { it.size > 1 }?.let { components ->
                                Text(localizedText("copy_dad2cec350"), style = MaterialTheme.typography.titleMedium)
                                components.forEach { component ->
                                    Text("${component.account?.name ?: component.accountName ?: localizedText("copy_74e111acc1")} · ${component.amount} ${component.currency}")
                                }
                            }
                            evidence.members.filterNot { it.id == evidence.rootRecord.id }.forEach { member ->
                                HorizontalDivider()
                                Text("${cashEvidenceMemberLabel(member, options, evidence.projection)} · ${formatLocalDateTime(member.occurredAt)}", style = MaterialTheme.typography.titleSmall)
                                Text("${member.amount} ${member.currency} · ${member.accountName ?: member.account?.name ?: localizedText("copy_50d1d690ec")}")
                                Text(localizedText("copy_9e206deaf4", member.counterparty.ifBlank { "-" }, cashEvidenceMemberLabel(member, options, evidence.projection)))
                                Text(localizedText("copy_1017a5b808", cashCategoryDisplayPath(member.category), member.note.ifBlank { "-" }, member.sourceType ?: "-"))
                                Text(cashEvidenceMemberImpactLabel(member), style = MaterialTheme.typography.bodySmall)
                            }
                            evidence.refundTimeline.forEach { refund -> Text(localizedText("copy_ec7f8f67e2", formatLocalDateTime(refund.occurredAt), refund.amount, refund.currency)) }
                            evidence.acceptedRelations.forEach { relation -> Text(localizedText("copy_d9c0f0da95", cashRelationTypeLabel(relation.kind, options))) }
                        }
                        detail.relations.filter { it.status == "accepted" }.forEach { relation ->
                            val related = if (relation.primaryRecord?.id == record.id) relation.secondaryRecord else relation.primaryRecord
                            HorizontalDivider()
                            Text(relation.label, style = MaterialTheme.typography.titleMedium)
                            val relatedEvidence = evidence?.members?.firstOrNull { member ->
                                member.id == related?.id || member.recordId == related?.id
                            }
                            Text("${related?.amount ?: "-"} ${related?.currency.orEmpty()} · ${related?.let { formatLocalDateTime(it.occurredAt) } ?: "-"}")
                            Text("${related?.accountName ?: "-"} · ${related?.counterparty?.ifBlank { localizedText("copy_6ea13ee38c") } ?: "-"}")
                            related?.counterpartyAccount?.takeIf(String::isNotBlank)?.let { Text(localizedText("copy_41673fc40f", it)) }
                            Text(localizedText("copy_457760bf31", related?.let { cashRecordTypeLabel(it.recordType, options) } ?: "-", cashCategoryDisplayPath(related?.category)))
                            Text(localizedText("copy_abe9e9d5a4", related?.note?.ifBlank { "-" } ?: "-", related?.sourceType ?: "-"))
                            relatedEvidence?.let { Text(localizedText("copy_7c1393b787", cashEvidenceMemberImpactLabel(it))) }
                            FinanceTertiaryButton(onClick = { related?.let(onEditRelated) }, enabled = canWrite && !relationBusy && related != null) { Text(localizedText("copy_6a5c4515c6")) }
                            if (state.editingRelationId == relation.id) {
                                ChoicePicker(
                                    localizedText("copy_2ce741e7ab"),
                                    state.editingRelationKind ?: relation.kind,
                                    options.relationTypes.map { it.value to it.label },
                                    viewModel::updateEditingRelationKind,
                                    enabled = canWrite && !relationBusy,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    FinanceTertiaryButton(
                                        onClick = { viewModel.saveRelationEdit(canWrite) },
                                        enabled = canWrite && !relationBusy && state.editingRelationKind != relation.kind,
                                    ) { Text(localizedText("copy_fadf24dbc5")) }
                                    FinanceTertiaryButton(onClick = viewModel::cancelEditingRelation, enabled = !relationBusy) { Text(localizedText("copy_4d0b4688c7")) }
                                }
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    FinanceTertiaryButton(
                                        onClick = { viewModel.beginEditingRelation(relation.id, relation.kind) },
                                        enabled = canWrite && !relationBusy,
                                    ) { Text(localizedText("copy_9a0d4d4a96")) }
                                    FinanceTertiaryButton(
                                        onClick = { viewModel.cancelRelation(relation.id, canWrite) },
                                        enabled = canWrite && !relationBusy,
                                    ) { Text(localizedText("copy_e490abc2fa")) }
                                }
                            }
                        }
                        if (state.relationComposerOpen) {
                            HorizontalDivider()
                            Text(localizedText("copy_f1c5c548ba"), style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DatePickerInput(state.relationDateFrom, viewModel::updateRelationDateFrom, localizedText("copy_1f29196891"), modifier = Modifier.weight(1f))
                                DatePickerInput(state.relationDateTo, viewModel::updateRelationDateTo, localizedText("copy_f4b9b2b5de"), modifier = Modifier.weight(1f))
                            }
                            LabeledInput(state.relationQuery, viewModel::updateRelationQuery, localizedText("copy_c219d66be7"))
                            ChoicePicker(localizedText("copy_2ce741e7ab"), state.relationType, options.relationTypes.map { it.value to it.label }, viewModel::updateRelationType)
                            if (relationBusy) Text(localizedText("copy_455d870542"), style = MaterialTheme.typography.bodySmall)
                            if (state.relationLoadError) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(localizedText("copy_9af3904f9f"), color = MaterialTheme.colorScheme.error)
                                    FinanceTertiaryButton(onClick = viewModel::retryRelationPage, enabled = !relationBusy) { Text(localizedText("copy_e2d53a6d3a")) }
                                }
                            }
                            if (!relationBusy && !state.relationLoadError && state.relationCandidates.isEmpty() && isValidCashRelationDateFilter(state.relationDateFrom, state.relationDateTo)) {
                                Text(localizedText("copy_3e5d827a71"), style = MaterialTheme.typography.bodySmall)
                            }
                            state.relationCandidates.forEach { candidate ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    androidx.compose.material3.RadioButton(
                                        selected = state.relationTarget == candidate.id,
                                        onClick = { viewModel.selectRelationTarget(candidate.id) },
                                        enabled = canWrite && !relationBusy,
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(candidate.counterparty.ifBlank { localizedText("copy_6ea13ee38c") }, style = MaterialTheme.typography.titleSmall)
                                        Text("${candidate.accountName} · ${formatLocalDateTime(candidate.occurredAt)}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text("${candidate.amount} ${candidate.currency}", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                            if (state.relationCandidates.isNotEmpty()) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(localizedText("copy_d2fee7cb23", state.relationPageNumber), style = MaterialTheme.typography.bodySmall)
                                    Row {
                                        FinanceTertiaryButton(onClick = viewModel::previousRelationPage, enabled = state.relationPageNumber > 1 && !relationBusy) { Text(localizedText("copy_b41561d807")) }
                                        FinanceTertiaryButton(onClick = viewModel::nextRelationPage, enabled = state.relationNextCursor != null && !relationBusy) { Text(localizedText("copy_67a246a344")) }
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FinanceTertiaryButton(onClick = viewModel::closeRelationComposer) { Text(localizedText("copy_4d0b4688c7")) }
                                FinanceButton(
                                    onClick = { viewModel.createRelation(canWrite) },
                                    enabled = canWrite && state.relationTarget != null && !relationBusy,
                                ) { Text(if (relationBusy) localizedText("copy_12c5c83f56") else localizedText("copy_8120c99c99")) }
                            }
                        }
                        if (detail.relations.none { it.status == "accepted" } && !state.relationComposerOpen) {
                            Text(localizedText("copy_123bb49306"), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                InlineError(relationError)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (record != null && canWrite) {
                    FinanceButton(onClick = onEdit) { Text(localizedText("copy_a7f814c0a4")) }
                    FinanceTertiaryButton(onClick = viewModel::openRelationComposer, enabled = !relationBusy) { Text(localizedText("copy_695c4cd2e6")) }
                    FinanceTertiaryButton(
                        onClick = { viewModel.dissolveRelations(canWrite) },
                        enabled = !relationBusy && detail.relations.any { it.status == "accepted" },
                    ) { Text(localizedText("copy_833237e41b")) }
                    FinanceTertiaryButton(onClick = { viewModel.requestDeleteRecord(canWrite) }, enabled = !state.deleteRecordBusy) { Text(localizedText("copy_3755f56f2f")) }
                }
            }
        }
    }
    if (state.deleteRecordConfirm && record != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteRecord,
            title = { Text(localizedText("copy_a995e3b53e")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val hasRelations = detail.relations.any { it.status == "accepted" }
                    Text(if (hasRelations) localizedText("copy_7a51756482") else localizedText("copy_42ece48726"))
                    InlineError(relationError)
                }
            },
            confirmButton = {
                Row {
                    FinanceTertiaryButton(
                        onClick = { viewModel.deleteRecord("delete_current_dissolve", canWrite) },
                        enabled = !state.deleteRecordBusy,
                    ) { Text(if (detail.relations.any { it.status == "accepted" }) localizedText("copy_6f762bedaa") else localizedText("copy_3c06abe116")) }
                    if (detail.relations.any { it.status == "accepted" }) FinanceTertiaryButton(
                        onClick = { viewModel.deleteRecord("delete_all", canWrite) },
                        enabled = !state.deleteRecordBusy,
                    ) { Text(localizedText("copy_22edecb09e")) }
                }
            },
            dismissButton = { FinanceTertiaryButton(onClick = viewModel::dismissDeleteRecord, enabled = !state.deleteRecordBusy) { Text(localizedText("copy_4d0b4688c7")) } },
        )
    }
}

@Composable
internal fun CashRecordEditorDialog(
    draft: CashRecordDraft,
    sizeClass: WindowSizeClass,
    accounts: List<Account>,
    categories: List<CashCategory>,
    options: LedgerOptions,
    busy: Boolean,
    error: String?,
    onChange: (CashRecordDraft) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onInvalidAmount: () -> Unit,
    onInvalidDateTime: () -> Unit,
) {
    val selectedAccount = accounts.firstOrNull { it.name == draft.accountName }
    val selectedType = options.recordTypes.firstOrNull { it.value == draft.recordType }
    Dialog(onDismissRequest = { if (!busy) onCancel() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier.fillMaxSize().padding(if (sizeClass == WindowSizeClass.COMPACT) 0.dp else 16.dp),
            contentAlignment = if (sizeClass == WindowSizeClass.COMPACT) Alignment.Center else Alignment.CenterEnd,
        ) {
        SectionCard(modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().fillMaxHeight()) {
            Text(if (draft.id == null) localizedText("copy_1f62e3f53f") else localizedText("copy_6a5c4515c6"), style = MaterialTheme.typography.headlineSmall)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (sizeClass == WindowSizeClass.COMPACT) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LabeledInput(draft.amount, { onChange(draft.copy(amount = it)) }, localizedText("copy_34943c40c9"), modifier = Modifier.weight(2f), semanticId = SemanticIds.recordAmount, isError = draft.amount.isNotBlank() && !isExactDecimalString(draft.amount), onBlur = { if (draft.amount.isNotBlank() && !isExactDecimalString(draft.amount)) onInvalidAmount() })
                        ChoicePicker(localizedText("copy_a81ab5e100"), draft.currency, (selectedAccount?.currencies.orEmpty().ifEmpty { listOf(draft.currency) }).distinct().map { it to it }, { onChange(draft.copy(currency = it)) }, Modifier.weight(1f), semanticId = SemanticIds.recordCurrency)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LabeledInput(draft.amount, { onChange(draft.copy(amount = it)) }, localizedText("copy_34943c40c9"), modifier = Modifier.weight(1f), semanticId = SemanticIds.recordAmount, isError = draft.amount.isNotBlank() && !isExactDecimalString(draft.amount), onBlur = { if (draft.amount.isNotBlank() && !isExactDecimalString(draft.amount)) onInvalidAmount() })
                        ChoicePicker(localizedText("copy_a81ab5e100"), draft.currency, (selectedAccount?.currencies.orEmpty().ifEmpty { listOf(draft.currency) }).distinct().map { it to it }, { onChange(draft.copy(currency = it)) }, Modifier.weight(1f), semanticId = SemanticIds.recordCurrency)
                    }
                }
                LabeledInput(draft.counterparty, { onChange(draft.copy(counterparty = it)) }, localizedText("copy_4b5a03c119"), semanticId = SemanticIds.recordCounterparty)
                LabeledInput(draft.counterpartyAccount, { onChange(draft.copy(counterpartyAccount = it)) }, localizedText("copy_27a7edc87c"), semanticId = SemanticIds.recordCounterpartyAccount)
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
                LabeledInput(draft.note, { onChange(draft.copy(note = it)) }, localizedText("copy_e0361480e3"), semanticId = SemanticIds.recordNote, singleLine = false)
                InlineError(error)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                FinanceTertiaryButton(onClick = onCancel, enabled = !busy, modifier = Modifier.testTag(SemanticIds.recordCancel)) { Text(localizedText("copy_4d0b4688c7")) }
                FinanceButton(onClick = onSave, enabled = !busy && draft.amount.isNotBlank() && isExactDecimalString(draft.amount) && draft.accountName.isNotBlank() && draft.recordType.isNotBlank() && isValidLocalDateTime(draft.occurredAt), modifier = Modifier.testTag(SemanticIds.recordSave)) { Text(if (busy) localizedText("copy_6644f06197") else localizedText("copy_fadf24dbc5")) }
            }
        }
        }
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
