package com.finance.tracker.presentation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.finance.tracker.domain.*
import com.finance.tracker.core.LocalFinanceColorRoles
import com.finance.tracker.resources.Res
import com.finance.tracker.resources.ic_add
import com.finance.tracker.resources.ic_close
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

internal val CashImportHorizontalContentInset = 24.dp
private const val CashImportCreateAccountOption = "__create_cash_account__"

internal data class CashImportPendingFile(
    val clientFileRef: String,
    val displayName: String,
    val startingRevision: Long?,
    val parseSucceeded: Boolean? = null,
)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun CashImportPreparation(state: ImportTaskUiState, accountState: ImportAccountUiState,
    fileNames: Map<String, String>, pendingFiles: List<CashImportPendingFile>, removingClientFileRefs: Set<String>,
    onPendingFinished: (String) -> Unit, onRemovePending: (String) -> Unit,
    onPick: (String?) -> Unit, onRetry: (ImportContextFile, String) -> Unit,
    onRemove: (String) -> Unit, onScope: (String, String?) -> Unit, onSelectAccount: (String, String?) -> Unit,
    onCreateAccount: (ImportSourceAccount) -> Unit, onResolveAccounts: () -> Unit, mappingStep: Boolean, modifier: Modifier = Modifier) {
    var scopeFile by remember { mutableStateOf<ImportContextFile?>(null) }
    var scopeLabel by remember { mutableStateOf("") }
    val busy = !state.canEdit || accountState.busy
    Column(modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        if (!mappingStep) {
            val pendingRefs = pendingFiles.mapTo(mutableSetOf()) { it.clientFileRef }
            val files = state.context?.files.orEmpty().filterNot { it.clientFileRef in pendingRefs || it.clientFileRef in removingClientFileRefs }
            val failures = state.localFileFailures.filterNot { it.clientFileRef in removingClientFileRefs }
            Column(Modifier.fillMaxWidth().testTag("cash.import.files-step"), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                    CashImportPreparationHeading(localizedText("cash_import_files_heading")) {
                        OutlinedButton(onClick = { onPick(null) }, enabled = !busy && state.context?.files.orEmpty().size < 20,
                            modifier = Modifier.testTag("cash.import.add-files")) {
                            Icon(painterResource(Res.drawable.ic_add), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text(localizedText("copy_7c3a726882"))
                        }
                    }
                    if (files.isEmpty() && pendingFiles.isEmpty() && failures.isEmpty()) {
                        Text(localizedText("cash_import_files_empty"), Modifier.padding(vertical = 20.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                    files.forEach { file ->
                        key(file.fileRef) {
                            var password by remember { mutableStateOf("") }
                            Column(Modifier.fillMaxWidth().padding(vertical = 16.dp).testTag("cash.import.file-${file.fileRef}"),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(file.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                                val statusLabel = when (file.status) {
                                    "ready" -> null
                                    "partial" -> "cash_import_file_partial"
                                    "password_required" -> "cash_import_file_password"
                                    else -> "cash_import_file_failed"
                                }
                                statusLabel?.let { Text(localizedText(it), color = if (file.status in setOf("password_required", "failed")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall) }
                                file.issues.distinctBy { it.code }.forEach { Text(it.message, style = MaterialTheme.typography.bodySmall) }
                                if (file.status == "password_required") OutlinedTextField(password, { password = it }, label = { Text(localizedText("cash_import_password")) },
                                    visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy,
                                    modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().testTag("cash.import.password-${file.fileRef}"))
                                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                                    verticalArrangement = Arrangement.Center, itemVerticalAlignment = Alignment.CenterVertically) {
                                    if (file.canRetry) TextButton(onClick = { onRetry(file, password); password = "" }, enabled = !busy) { Text(localizedText("copy_e2d53a6d3a")) }
                                    if (file.sourceType == "wechat") TextButton(onClick = { scopeFile = file; scopeLabel = file.sourceScopeLabel.orEmpty() }, enabled = !busy) {
                                        Text(localizedText("cash_import_source_settings"))
                                    }
                                    RemoveCashImportFileButton(file.displayName, enabled = !busy,
                                        tag = "cash.import.remove-file-${file.fileRef}", onClick = { onRemove(file.fileRef) })
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
                pendingFiles.forEach { file ->
                    key(file.clientFileRef) { CashImportPendingFileRow(file, onPendingFinished, onRemovePending) }
                }
                if (failures.isNotEmpty()) {
                    Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                        failures.forEach { failure ->
                            Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                fileNames[failure.clientFileRef]?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                                Text(cashImportTaskErrorText(failure.code), color = MaterialTheme.colorScheme.error)
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
        if (mappingStep) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().testTag("cash.import.mapping-step")) {
                CashImportPreparationHeading(localizedText("cash_import_accounts_heading")) {}
                state.context?.sourceAccounts.orEmpty().forEach { group ->
                    val target = state.choices.accountSelections.firstOrNull { it.sourceAccountRef == group.sourceAccountRef }?.target
                    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp).testTag("cash.import.account-group-${group.sourceAccountRef}"),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(group.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(localizedText("cash_import_source_rows", group.rowCount.toString(), group.currencies.joinToString(" / ")),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val selectedAccount = target?.accountId?.let { accountId -> accountState.accounts.firstOrNull { it.id == accountId } }
                        val missingCurrencies = selectedAccount?.currencies?.let { supported ->
                            group.currencies.filterNot { it in supported }
                        }.orEmpty()
                        val accountOptions = listOf("" to localizedText("cash_import_no_account")) +
                            accountState.accounts.filter { (it.active && it.type in setOf("cash", "loan", "lend")) || it.id == target?.accountId }
                                .map { it.id to it.name } +
                            listOf(CashImportCreateAccountOption to localizedText("cash_import_create_account_option"))
                        ChoicePicker(localizedText("cash_import_target_account"), target?.accountId.orEmpty(), accountOptions,
                            { selected ->
                                if (selected == CashImportCreateAccountOption) onCreateAccount(group)
                                else onSelectAccount(group.sourceAccountRef, selected.takeIf(String::isNotEmpty))
                            }, enabled = !busy && accountState.ready,
                            semanticId = "cash.import.account-${group.sourceAccountRef}")
                        if (missingCurrencies.isNotEmpty()) {
                            Text(localizedText("cash_import_account_currency_missing", missingCurrencies.joinToString(" / ")),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("cash.import.account-currency-missing-${group.sourceAccountRef}"))
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
        if (accountState.pendingKeys.isNotEmpty()) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(localizedText("cash_import_account_unknown"), color = LocalFinanceColorRoles.current.warning)
                TextButton(onClick = onResolveAccounts, enabled = !busy, modifier = Modifier.testTag("cash.import.resolve-accounts")) {
                    Text(localizedText("cash_import_check_result"))
                }
            }
        }
        accountState.errorCode?.let { Text(cashImportTaskErrorText(it), Modifier.widthIn(max = 720.dp).fillMaxWidth(), color = MaterialTheme.colorScheme.error) }
    }
    scopeFile?.let { file -> AlertDialog(onDismissRequest = { scopeFile = null }, title = { Text(localizedText("cash_import_source_settings")) },
        text = { OutlinedTextField(scopeLabel, { scopeLabel = it }, label = { Text(localizedText("cash_import_source_name")) },
            placeholder = { Text(localizedText("cash_import_default_source")) }, singleLine = true, isError = scopeLabel.length > 64) },
        confirmButton = { TextButton(onClick = { scopeFile = null; onScope(file.fileRef, scopeLabel.takeIf(String::isNotBlank)) }, enabled = !busy && scopeLabel.length <= 64) { Text(localizedText("copy_fadf24dbc5")) } },
        dismissButton = { TextButton(onClick = { scopeFile = null }) { Text(localizedText("copy_4d0b4688c7")) } }) }
}

@Composable
private fun CashImportPendingFileRow(file: CashImportPendingFile, onFinished: (String) -> Unit, onRemove: (String) -> Unit) {
    val progress = remember(file.clientFileRef) { Animatable(0f) }
    val parseSucceeded by rememberUpdatedState(file.parseSucceeded)
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val progressColor = MaterialTheme.colorScheme.primaryContainer
    val progressLabel = localizedText("cash_import_file_parsing")
    LaunchedEffect(file.clientFileRef) {
        val fakeProgress = launch {
            progress.animateTo(0.9f, animationSpec = tween(1_800, easing = LinearEasing))
        }
        val succeeded = snapshotFlow { parseSucceeded }.first { it != null }
        if (succeeded == true) {
            fakeProgress.join()
            progress.animateTo(1f, animationSpec = tween(180, easing = LinearEasing))
            delay(140)
        } else {
            fakeProgress.cancel()
        }
        onFinished(file.clientFileRef)
    }
    Box(Modifier.widthIn(max = 720.dp).fillMaxWidth().drawBehind {
        val fraction = progress.value.coerceIn(0f, 1f)
        val maxTrackWidth = 720.dp.toPx() + CashImportHorizontalContentInset.toPx() * 2f
        val bleed = minOf(CashImportHorizontalContentInset.toPx(), ((maxTrackWidth - size.width) / 2f).coerceAtLeast(0f))
        val trackWidth = size.width + bleed * 2f
        val trackOrigin = Offset(-bleed, 0f)
        drawRect(color = trackColor, topLeft = trackOrigin, size = Size(trackWidth, size.height))
        if (fraction > 0f) drawRect(color = progressColor, topLeft = trackOrigin, size = Size(trackWidth * fraction, size.height))
    }.testTag("cash.import.file-uploading-${file.clientFileRef}"), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(file.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 2)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp).semantics {
                    contentDescription = progressLabel
                    progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                }.testTag("cash.import.file-loading-${file.clientFileRef}"), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                RemoveCashImportFileButton(file.displayName, enabled = true,
                    tag = "cash.import.remove-pending-${file.clientFileRef}", onClick = { onRemove(file.clientFileRef) })
            }
        }
    }
    HorizontalDivider(Modifier.widthIn(max = 720.dp).fillMaxWidth())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoveCashImportFileButton(displayName: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(localizedText("cash_import_remove_file")) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.testTag(tag)) {
            Icon(painterResource(Res.drawable.ic_close),
                contentDescription = localizedText("cash_import_remove_file_named", displayName))
        }
    }
}

@Composable
private fun CashImportPreparationHeading(title: String, action: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        action()
    }
    HorizontalDivider()
}

@Composable
internal fun cashImportTaskErrorText(code: String): String = when (code) {
    "import_file_unsupported_type" -> localizedText("copy_e473077a21")
    "import_file_too_large" -> localizedText("copy_05e60942e2")
    "import_file_size_unavailable" -> localizedText("copy_6087e12a0f")
    "import_file_too_many" -> localizedText("copy_2ea314ef4c")
    "import_file_read_failed" -> localizedText("copy_7265b90625")
    "import_file_picker_failed" -> localizedText("copy_cb28b5a6f2")
    "plan_changed", "session_changed", "entry_changed", "record_changed", "relation_context_changed" -> localizedText("cash_import_changed")
    "plan_expired", "session_expired" -> localizedText("cash_import_expired")
    "category_unavailable" -> localizedText("cash_import_category_unavailable")
    "account_name_unavailable" -> localizedText("cash_import_account_name_unavailable")
    "account_in_use" -> localizedText("cash_import_account_in_use")
    "account_changed" -> localizedText("cash_import_account_changed")
    "network_unavailable" -> localizedText("copy_e0a1c9fd69")
    "workspace_forbidden" -> localizedText("copy_dd9a4bdee0")
    else -> localizedText("copy_8d856685b1")
}
