package com.finance.tracker.presentation

import com.finance.tracker.core.*
import com.finance.tracker.domain.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.size
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher

private class PlatformImportFileSource(private val file: PlatformFile) : FileSource {
    override val name: String get() = file.name
    override val mediaType: String? get() = file.mimeType()?.toString()
    override val size: Long? get() = file.size().takeIf { it >= 0 }
    override suspend fun read(): ByteArray = file.readBytes()
}

@Composable
internal fun CashImportScreen(
    repository: CashImportRepository,
    workspaceId: String,
    sizeClass: WindowSizeClass,
    canWrite: Boolean,
    onBack: () -> Unit,
    onDone: () -> Unit,
    diagnostics: DiagnosticLogger = NoOpDiagnosticLogger,
) {
    val viewModel: CashImportViewModel = viewModel(
        key = "cash-import:$workspaceId",
        factory = viewModelFactory { initializer { CashImportViewModel(repository, diagnostics) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.reset() }
    LaunchedEffect(state.stage) { if (state.stage == ImportStage.SUCCESS) onDone() }

    val stage = state.stage
    val selectedFiles = state.selectedFiles
    val scan = state.scan
    val accountDrafts = state.accountDrafts
    val preview = state.preview
    val allocationDrafts = state.allocationDrafts
    val previewFilter = state.previewFilter
    val relationDrafts = state.relationDrafts
    val relationFilter = state.relationFilter
    val result = state.result
    val passwords = state.passwords
    val busy = state.busy
    val error = state.errorCode?.let { importErrorText(it) }

    val filePicker = rememberFilePickerLauncher(
        type = FileKitType.File(extensions = listOf("csv", "xls", "xlsx", "pdf")),
        mode = FileKitMode.Multiple(maxItems = MAX_IMPORT_FILES),
        onError = { viewModel.setPickerError() },
        onResult = { files -> viewModel.receiveFiles(files?.map(::PlatformImportFileSource)) },
    )

    FeaturePage(localizedText("copy_26dc37229c"), SemanticIds.importScreen) {
        if (!canWrite) {
            StateMessage(localizedText("copy_459f937086"))
            FinanceTertiaryButton(onClick = onBack) { Text(localizedText("copy_855fa817d2")) }
        } else {
        ImportStepper(stage, sizeClass, busy, scan != null, preview != null) { target -> viewModel.stage(target) }
        InlineError(error)
        when (stage) {
            ImportStage.SELECT -> {
                SectionCard {
                    Text(localizedText("copy_21a6f5a8d9"), style = MaterialTheme.typography.titleLarge)
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("↑", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.headlineMedium)
                        Text(localizedText("copy_a490e6f06c"), style = MaterialTheme.typography.bodySmall)
                        FinanceButton(onClick = { filePicker.launch() }, enabled = !busy, modifier = Modifier.testTag(SemanticIds.importChooseFile)) {
                            Text(if (selectedFiles.isEmpty()) localizedText("copy_21a6f5a8d9") else localizedText("copy_7c3a726882"))
                        }
                        if (selectedFiles.isNotEmpty()) Text("${selectedFiles.size}/$MAX_IMPORT_FILES")
                    }
                    if (selectedFiles.isNotEmpty()) {
                        selectedFiles.forEachIndexed { index, picked ->
                            HorizontalDivider()
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text(picked.identity.name, style = MaterialTheme.typography.titleSmall)
                                    Text(formatFileSize(picked.identity.size))
                                    scan?.files?.firstOrNull { it.index == index }?.let { status ->
                                        Text(importFileStatus(status))
                                    }
                                }
                                FinanceTertiaryButton(onClick = {
                                    viewModel.removeFile(picked.identity.digest)
                                }, enabled = !busy, modifier = Modifier.testTag("${SemanticIds.importRemoveFile}.${picked.identity.digest}")) { Text(localizedText("copy_2f752c005e")) }
                            }
                        }
                    }
                    scan?.files?.filter { it.status == "password_required" }?.forEach { fileStatus ->
                        val picked = selectedFiles.getOrNull(fileStatus.index) ?: return@forEach
                        val passwordLabel = localizedText("copy_f1b6b76a6f", picked.identity.name)
                        OutlinedTextField(
                            value = passwords[fileStatus.index.toString()].orEmpty(),
                            onValueChange = { viewModel.updatePassword(fileStatus.index, it) },
                            label = { Text(passwordLabel) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                                .testTag("${SemanticIds.importFilePassword}.${fileStatus.index}")
                                .semantics(mergeDescendants = true) { contentDescription = passwordLabel },
                        )
                    }
                    scan?.files?.filter { it.status == "error" }?.forEach { fileStatus ->
                        InlineError("${selectedFiles.getOrNull(fileStatus.index)?.identity?.name ?: fileStatus.filename}：${importFileStatus(fileStatus)}")
                    }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FinanceSecondaryButton(onClick = onBack, enabled = !busy, modifier = Modifier.weight(1f)) { Text(localizedText("copy_4d0b4688c7")) }
                        FinanceButton(
                            onClick = { viewModel.scanSelectedFiles() },
                            enabled = selectedFiles.isNotEmpty() && !busy && scan?.files?.none { it.status == "error" || (it.status == "password_required" && passwords[it.index.toString()].isNullOrBlank()) } != false,
                            modifier = Modifier.weight(1f).testTag(SemanticIds.importNext),
                        ) { Text(if (busy) localizedText("copy_4a5e035eb9") else localizedText("copy_ce6f2afe85")) }
                    }
                }
            }
            ImportStage.MAPPING -> scan?.let { current ->
                SectionCard {
                    Text(localizedText("copy_bab3b7ef76"), style = MaterialTheme.typography.titleLarge)
                    Text(localizedText("copy_56bc6903d6", current.channelLabel, current.groups.size))
                    if (current.unresolvedCount > 0) Text(localizedText("copy_40b67ad6b4", current.unresolvedCount))
                    current.groups.forEach { group ->
                        HorizontalDivider()
                        val draft = accountDrafts[group.groupId]
                        val sharedDrafts = accountDrafts.values.mapNotNull(ImportAccountDraft::newAccount).distinctBy { it.draftId }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(group.displayName, style = MaterialTheme.typography.titleMedium)
                            Text(localizedText("copy_92356f95e8", group.maskedEvidence, group.currencies.joinToString(" / "), group.rowCount))
                            ChoicePicker(
                                localizedText("copy_8ab177def7"),
                                draft?.newAccount?.let { "__draft__${it.draftId}" } ?: draft?.accountId?.toString().orEmpty(),
                                listOf("" to localizedText("copy_ce1ec4bd2e")) + current.accounts.map { it.id.toString() to it.name } +
                                    sharedDrafts.map { "__draft__${it.draftId}" to localizedText("copy_ec72e9b1d3", it.name) } + ("__create__" to localizedText("copy_93b0b8ba3b")),
                                { viewModel.selectAccount(group, it) },
                                semanticId = "${SemanticIds.importMapping}.${group.groupId}",
                            )
                            draft?.newAccount?.let { account ->
                                LabeledInput(account.name, { value -> viewModel.updateNewAccount(group.groupId) { it.copy(name = value.take(255)) } }, localizedText("copy_238452a115"))
                                ChoicePicker(localizedText("copy_082c31df2b"), account.type, listOf("cash" to localizedText("copy_ca5bd21330"), "loan" to localizedText("copy_099f8b5c68"), "lend" to localizedText("copy_4a2637c23f")), { value -> viewModel.updateNewAccount(group.groupId) { it.copy(type = value) } })
                                Text(localizedText("copy_b241eb825a", account.currencies.joinToString(" / ")))
                            }
                            val selectedAccount = current.accounts.firstOrNull { it.id == draft?.accountId }
                            val missingCurrencies = if (selectedAccount == null) emptyList() else group.currencies.filterNot { it in selectedAccount.currencies.orEmpty() }
                            if (missingCurrencies.isNotEmpty()) Text(localizedText("copy_6f136836ac", missingCurrencies.joinToString("、")))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FinanceTertiaryButton(onClick = { viewModel.stage(ImportStage.SELECT) }, enabled = !busy, modifier = Modifier.testTag(SemanticIds.importPrevious)) { Text(localizedText("copy_75ef1241c0")) }
                        FinanceButton(onClick = { viewModel.loadPreview() }, enabled = state.mappingComplete() && !busy, modifier = Modifier.testTag(SemanticIds.importNext)) { Text(if (busy) localizedText("copy_a99736a830") else localizedText("copy_b8a3f85115")) }
                    }
                }
            }
            ImportStage.PREVIEW -> preview?.let { current ->
                SectionCard {
                    Text(localizedText("copy_0433a1c196"), style = MaterialTheme.typography.titleLarge)
                    Text(current.channelLabel)
                    ChoicePicker(localizedText("copy_f7acefd2d4"), previewFilter, listOf(
                        "all" to localizedText("copy_f229c64eb7", current.summary.total), "new" to localizedText("copy_4be80bff60", current.summary.new),
                        "existing" to localizedText("copy_eb4f38232d", current.summary.existing), "unresolved" to localizedText("copy_6d5268fd33", current.summary.unresolved),
                        "requires_allocation" to localizedText("copy_44d8ec2ba9", incompleteAllocationCount(current, allocationDrafts)),
                    ), { viewModel.setPreviewFilter(it) })
                    val visibleItems = current.items.filter { previewFilter == "all" || it.status == previewFilter }
                    val monthGroups = importPreviewMonthGroups(visibleItems)
                    if (visibleItems.isEmpty()) Text(if (current.items.isEmpty()) localizedText("copy_e0329985a9") else localizedText("copy_43dd57a28f"))
                    monthGroups.forEach { group ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(importPreviewMonthLabel(group.month), style = MaterialTheme.typography.titleMedium)
                            if (group.summary?.currencies.isNullOrEmpty()) {
                                Text(localizedText("copy_9c522d837a"), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        group.summary?.currencies.orEmpty().forEach { totals ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(localizedText("copy_5e64d382a1", importPreviewSummaryAmount("income", totals.income), totals.currency), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                                Text(localizedText("copy_19c079014a", importPreviewSummaryAmount("expense", totals.expense), totals.currency), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        group.items.forEach { item ->
                            HorizontalDivider()
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.testTag("import-item-${item.recordId}")) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(importPreviewDateTimeLabel(item.occurredAt), style = MaterialTheme.typography.bodySmall)
                                    Text(importStatusLabel(item.status), style = MaterialTheme.typography.labelMedium)
                                }
                                Text(item.counterparty.ifBlank { "-" }, style = MaterialTheme.typography.bodyLarge)
                                Text(item.note.ifBlank { "-" }, style = MaterialTheme.typography.bodyMedium)
                                Text("${item.accountName} · ${importRecordTypeLabel(item.recordType)}", style = MaterialTheme.typography.bodySmall)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    val direction = importPreviewDirection(item)
                                    Text(
                                        importPreviewAmountLabel(item),
                                        color = when (direction) {
                                            "income" -> MaterialTheme.colorScheme.primary
                                            "expense" -> MaterialTheme.colorScheme.error
                                            else -> MaterialTheme.colorScheme.onSurface
                                        },
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                }
                                item.message.takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                if (item.components.orEmpty().size > 1) {
                                    Text(localizedText("copy_a62d0b68c2"), style = MaterialTheme.typography.labelLarge)
                                    val key = importItemKey(item)
                                    val currentAmounts = allocationDrafts[key].orEmpty()
                                    item.components.orEmpty().forEachIndexed { index, component ->
                                        LabeledInput(
                                            currentAmounts.getOrElse(index) { component.amount?.removePrefix("+")?.removePrefix("-").orEmpty() },
                                            { value -> viewModel.updateAllocation(key, index, value) },
                                            component.sourceLabel,
                                            semanticId = "${SemanticIds.importAllocation}.$key.$index",
                                        )
                                    }
                                    val balance = allocationBalance(item.amount, currentAmounts)
                                    Text(when (balance.state) {
                                        "complete" -> localizedText("copy_67bca171d2", balance.total, item.currency)
                                        "invalid" -> localizedText("copy_d9ea7affdf")
                                        else -> if (balance.difference.startsWith("-")) localizedText("copy_4c52e0e5fc", balance.difference.drop(1), item.currency) else localizedText("copy_9be1d4be37", balance.difference, item.currency)
                                    }, color = if (balance.state == "complete") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                    if (importUnresolvedCount(current) > 0) Text(localizedText("copy_3652352085", importUnresolvedCount(current)))
                    if (importUnsupportedCount(current) > 0) InlineError(localizedText("copy_1b89edc1fd"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FinanceTertiaryButton(onClick = { viewModel.stage(ImportStage.MAPPING) }, enabled = !busy, modifier = Modifier.testTag(SemanticIds.importPrevious)) { Text(localizedText("copy_75ef1241c0")) }
                        FinanceButton(onClick = {
                            if (hasIncompleteAllocations(current, allocationDrafts)) {
                                viewModel.setErrorCode("import_component_allocation_incomplete")
                            } else if (current.items.any { it.components.orEmpty().size > 1 }) {
                                viewModel.loadPreview(ImportStage.RELATIONS)
                            } else {
                                viewModel.stage(ImportStage.RELATIONS)
                            }
                        }, enabled = !busy && !hasIncompleteAllocations(current, allocationDrafts), modifier = Modifier.testTag(SemanticIds.importNext)) { Text(localizedText("copy_1fc1afc5c5")) }
                    }
                }
            }
            ImportStage.RELATIONS -> preview?.let { current ->
                SectionCard {
                    Text(localizedText("copy_88e2dc4ef7"), style = MaterialTheme.typography.titleLarge)
                    val automaticCount = current.relations.count { it.automatic }
                    val pendingCount = current.relations.count { relation ->
                        val draft = relationDrafts[relation.id]
                        (draft?.status ?: if (relation.automatic) "automatic" else "pending") == "pending"
                    }
                    ChoicePicker(localizedText("copy_f7acefd2d4"), relationFilter, listOf(
                        "all" to localizedText("copy_f229c64eb7", current.relations.size),
                        "automatic" to localizedText("copy_d58770beea", automaticCount),
                        "pending" to localizedText("copy_f2a9d9a789", pendingCount),
                    ), { viewModel.setRelationFilter(it) })
                    val filtered = current.relations.filter { relation ->
                        val draft = relationDrafts[relation.id]
                        relationFilter == "all" || (relationFilter == "automatic" && relation.automatic) || (relationFilter == "pending" && (draft?.status ?: "pending") == "pending")
                    }
                    if (current.relations.isEmpty()) Text(localizedText("copy_f7ad800eb1"))
                    filtered.forEach { relation ->
                        HorizontalDivider()
                        val draft = relationDrafts[relation.id] ?: ImportRelationDraft(relation.kind, if (relation.automatic) "automatic" else "pending", relation.secondary)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${if (draft.status == "rejected") localizedText("copy_4c7c52c706") else if (relation.automatic) localizedText("copy_2939838436") else if (draft.status == "accepted") localizedText("copy_d9fea67ad2") else localizedText("copy_59a9eb4e65")} · ${relation.label}", style = MaterialTheme.typography.titleSmall)
                            Text(localizedText("copy_dd8fbdc06f", importRelationRecordLabel(relation.primary), draft.secondary?.let { importRelationRecordLabel(it) } ?: localizedText("copy_53e2db7016")))
                            if (!relation.automatic && draft.status != "rejected") {
                                ChoicePicker(localizedText("copy_80d486a71f"), draft.kind, listOf("payment_mirror" to localizedText("copy_2b6feb78fe"), "refund_offset" to localizedText("copy_b441aa2b9b"), "transfer_pair" to localizedText("copy_e4aaa144b7")), { value -> viewModel.setRelationDraft(relation, draft.copy(kind = value)) })
                                ChoicePicker(localizedText("copy_9b6d26d5f6"), draft.secondary?.let(::importItemKey) ?: "", listOf("" to localizedText("copy_0153e1a001")) + relation.candidates.map { importItemKey(it) to importRelationRecordLabel(it) }, { value ->
                                    val selected = relation.candidates.firstOrNull { importItemKey(it) == value }
                                    viewModel.setRelationDraft(relation, draft.copy(status = if (selected == null) "pending" else "accepted", secondary = selected, restoreStatus = null, restoreSecondary = null))
                                })
                            }
                            FinanceTertiaryButton(onClick = { viewModel.toggleRelationRejected(relation, draft) }) {
                                Text(if (draft.status == "rejected") localizedText("copy_3290b42bd1") else localizedText("copy_86ab462000"))
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FinanceTertiaryButton(onClick = { viewModel.stage(ImportStage.PREVIEW) }, enabled = !busy, modifier = Modifier.testTag(SemanticIds.importPrevious)) { Text(localizedText("copy_75ef1241c0")) }
                        FinanceButton(onClick = { viewModel.commitImport() }, enabled = state.canCommit(), modifier = Modifier.testTag(SemanticIds.importConfirm)) { Text(if (busy) localizedText("copy_cd74f09629") else localizedText("copy_de6433b70d")) }
                    }
                }
            }
            ImportStage.SUCCESS -> result?.let { committed ->
                SectionCard {
                    Text(localizedText("copy_75c33f5c2e"), style = MaterialTheme.typography.titleLarge)
                    Text(localizedText("copy_07c4b4b5e5", committed.newRows, committed.updatedRows, preview?.summary?.existing ?: 0, committed.skippedRows))
                    FinanceButton(onClick = onBack) { Text(localizedText("copy_855fa817d2")) }
                }
            }
        }
        }
    }
}

@Composable
private fun ImportStepper(
    stage: ImportStage,
    sizeClass: WindowSizeClass,
    busy: Boolean,
    hasScan: Boolean,
    hasPreview: Boolean,
    onSelect: (ImportStage) -> Unit,
) {
    val steps = listOf(ImportStage.SELECT to localizedText("copy_1074712074"), ImportStage.MAPPING to localizedText("copy_bab3b7ef76"), ImportStage.PREVIEW to localizedText("copy_0433a1c196"), ImportStage.RELATIONS to localizedText("copy_88e2dc4ef7"))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        steps.forEachIndexed { index, (step, label) ->
            val current = stage == step
            val enabled = !busy && index <= steps.indexOfFirst { it.first == stage } &&
                (step == ImportStage.SELECT || hasScan && index == 1 || hasPreview && index >= 2)
            FinanceTertiaryButton(
                onClick = { onSelect(step) },
                enabled = enabled,
                modifier = Modifier.weight(1f).testTag("${SemanticIds.importStepper}.$index"),
            ) {
                Text(
                    "${index + 1} ${label}",
                    color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = if (sizeClass == WindowSizeClass.COMPACT) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun importRelationRecordLabel(record: ImportRelationRecord): String =
    "${record.counterparty.ifBlank { localizedText("copy_204c9c54c2") }} · ${record.amount} ${record.currency} · ${formatLocalDateTime(record.occurredAt)}"

@Composable
private fun importRecordTypeLabel(value: String): String = when (value) {
    "consumption", "expense" -> localizedText("copy_71d6df4130")
    "refund" -> localizedText("copy_b82ef83b7f")
    "reversal" -> localizedText("copy_47524db6a4")
    "transfer_reversal" -> localizedText("copy_ac69079e02")
    "withdrawal_in" -> localizedText("copy_f1c19482a9")
    "withdrawal_out" -> localizedText("copy_a471daf080")
    "transfer_in" -> localizedText("copy_5b87cb874f")
    "transfer_out" -> localizedText("copy_e39b50c02d")
    "repayment" -> localizedText("copy_a9af9c2d42")
    "income" -> localizedText("copy_aaaf7ca11e")
    "investment_in" -> localizedText("copy_1f9774478d")
    "investment_out" -> localizedText("copy_f80e7c74fd")
    "interest" -> localizedText("copy_a5b7f233fa")
    "fee" -> localizedText("copy_9836a95e9d")
    "fx_in" -> localizedText("copy_69778bdcd4")
    "fx_out" -> localizedText("copy_8efd23d759")
    "bank_security_transfer" -> localizedText("copy_c18e325ed4")
    "merged" -> localizedText("copy_9c8acd3d30")
    else -> localizedText("copy_1a26edf94a")
}

@Composable
private fun importFileStatus(file: ImportFileScan): String = when (file.status) {
    "ready" -> localizedText("copy_5707975153", file.channelLabel.orEmpty())
    "password_required" -> localizedText("copy_4908aa3009")
    else -> localizedText("copy_53514e0040")
}

private fun formatFileSize(size: Long): String = when {
    size < 1024 -> "$size B"
    size < 1024L * 1024L -> "${(size + 1023) / 1024} KB"
    else -> "${(size / (1024L * 1024L))}.${((size % (1024L * 1024L)) * 10 / (1024L * 1024L))} MB"
}

@Composable
private fun importStatusLabel(status: String): String = when (status) {
    "new" -> localizedText("copy_956fb0cc79")
    "existing" -> localizedText("copy_a867d42ddf")
    "unresolved" -> localizedText("copy_d041bb6737")
    "requires_allocation" -> localizedText("copy_feb85ed082")
    else -> localizedText("copy_f9c565b337")
}

private fun importAllocationCount(preview: ImportPreview): Int = preview.summary.requiresAllocation
    .takeIf { it > 0 } ?: preview.items.count { it.status == "requires_allocation" }

private fun incompleteAllocationCount(preview: ImportPreview, drafts: Map<String, List<String>>): Int =
    preview.items.filter { it.components.orEmpty().size > 1 }.count { item ->
        allocationBalance(item.amount, drafts[importItemKey(item)].orEmpty()).state != "complete"
    }

private fun importUnresolvedCount(preview: ImportPreview): Int = preview.summary.unresolved
    .takeIf { it > 0 } ?: preview.items.count { it.status == "unresolved" }

private fun importUnsupportedCount(preview: ImportPreview): Int = (preview.summary.unsupported - importUnresolvedCount(preview)).coerceAtLeast(0)

private fun hasIncompleteAllocations(preview: ImportPreview, drafts: Map<String, List<String>>): Boolean =
    preview.items.filter { it.components.orEmpty().size > 1 }.any { item ->
        allocationBalance(item.amount, drafts[importItemKey(item)].orEmpty()).state != "complete"
    }

@Composable
private fun importMappingError(code: String?): String? = when (code) {
    "import_account_unavailable" -> localizedText("copy_03c994116c")
    "import_account_name_conflict" -> localizedText("copy_e5e27271b5")
    "import_account_draft_invalid" -> localizedText("copy_3ee2de090b")
    "import_mapping_incomplete" -> localizedText("copy_02ec7f0183")
    "import_composite_payment_unresolved" -> localizedText("copy_5a8a3e7f03")
    "import_component_allocation_incomplete" -> localizedText("copy_240cf25f9e")
    "import_component_amount_invalid" -> localizedText("copy_3581074e60")
    else -> null
}

@Composable
private fun importErrorText(code: String): String = importMappingError(code) ?: when (code) {
    "import_password_required" -> localizedText("copy_d925c10d8c")
    "import_password_invalid" -> localizedText("copy_eb3d4679e0")
    "import_channel_unrecognized" -> localizedText("copy_13e3557403")
    "import_mapping_stale" -> localizedText("copy_f9529f1543")
    "import_relation_reconfirmation_required" -> localizedText("copy_fda94c82c4")
    "import_preview_stale" -> localizedText("copy_cc7eb85420")
    "relation_impact_required" -> localizedText("copy_ee4303c207")
    "import_file_unsupported_type" -> localizedText("copy_e473077a21")
    "import_file_too_large" -> localizedText("copy_05e60942e2")
    "import_file_size_unavailable" -> localizedText("copy_6087e12a0f")
    "import_file_too_many" -> localizedText("copy_2ea314ef4c")
    "import_file_read_failed" -> localizedText("copy_7265b90625")
    "import_file_picker_failed" -> localizedText("copy_cb28b5a6f2")
    "network_unavailable" -> localizedText("copy_e0a1c9fd69")
    "temporarily_unavailable" -> localizedText("copy_8d856685b1")
    "authentication_required" -> localizedText("copy_8e38717a57")
    "workspace_forbidden" -> localizedText("copy_dd9a4bdee0")
    "unknown_error" -> localizedText("copy_382ef5a830")
    else -> localizedText("copy_8d856685b1")
}
