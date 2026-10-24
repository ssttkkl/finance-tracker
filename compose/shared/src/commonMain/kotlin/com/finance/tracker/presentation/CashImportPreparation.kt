package com.finance.tracker.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.finance.tracker.domain.*
import com.finance.tracker.core.LocalFinanceColorRoles

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CashImportPreparation(state: ImportTaskUiState, accountState: ImportAccountUiState,
    fileNames: Map<String, String>, onPick: (String?) -> Unit, onRetry: (ImportContextFile, String) -> Unit,
    onRemove: (String) -> Unit, onScope: (String, String?) -> Unit, onSelectAccount: (String, String?) -> Unit,
    onCreateAccount: () -> Unit, onEditAccount: (String) -> Unit, onResolveAccounts: () -> Unit, modifier: Modifier = Modifier) {
    var removing by remember { mutableStateOf<ImportContextFile?>(null) }
    var scopeFile by remember { mutableStateOf<ImportContextFile?>(null) }
    var scopeLabel by remember { mutableStateOf("") }
    val busy = !state.canEdit || accountState.busy
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(localizedText("cash_import_files_heading"), style = MaterialTheme.typography.titleLarge)
        state.context?.files.orEmpty().forEach { file ->
            key(file.fileRef) {
                var password by remember { mutableStateOf("") }
                Column(Modifier.fillMaxWidth().testTag("cash.import.file-${file.fileRef}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(file.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(localizedText(when (file.status) {
                        "ready" -> "cash_import_file_ready"
                        "partial" -> "cash_import_file_partial"
                        "password_required" -> "cash_import_file_password"
                        else -> "cash_import_file_failed"
                    }), color = if (file.status in setOf("password_required", "failed")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    file.issues.distinctBy { it.code }.forEach { Text(it.message, style = MaterialTheme.typography.bodySmall) }
                    if (file.status == "password_required") OutlinedTextField(password, { password = it }, label = { Text(localizedText("cash_import_password")) },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp).testTag("cash.import.password-${file.fileRef}"))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (file.canRetry) TextButton(onClick = { onRetry(file, password); password = "" }, enabled = !busy) { Text(localizedText("copy_e2d53a6d3a")) }
                        TextButton(onClick = { onPick(file.fileRef) }, enabled = !busy) { Text(localizedText("cash_import_replace_file")) }
                        if (file.sourceType == "wechat") TextButton(onClick = { scopeFile = file; scopeLabel = file.sourceScopeLabel.orEmpty() }, enabled = !busy) {
                            Text(localizedText("cash_import_source_settings"))
                        }
                        TextButton(onClick = { removing = file }, enabled = !busy) { Text(localizedText("cash_import_remove_file")) }
                    }
                    HorizontalDivider()
                }
            }
        }
        state.localFileFailures.forEach { failure ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                fileNames[failure.clientFileRef]?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                Text(cashImportTaskErrorText(failure.code), color = MaterialTheme.colorScheme.error)
            }
        }
        OutlinedButton(onClick = { onPick(null) }, enabled = !busy && state.context?.files.orEmpty().size < 20,
            modifier = Modifier.testTag("cash.import.add-files")) { Text(localizedText("copy_7c3a726882")) }
        if (state.context?.sourceAccounts.orEmpty().isNotEmpty()) {
            Text(localizedText("cash_import_accounts_heading"), style = MaterialTheme.typography.titleLarge)
            state.context?.sourceAccounts.orEmpty().forEach { group ->
                val target = state.choices.accountSelections.firstOrNull { it.sourceAccountRef == group.sourceAccountRef }?.target
                Column(Modifier.fillMaxWidth().widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val accountOptions = listOf("" to localizedText("cash_import_no_account")) +
                        accountState.accounts.filter { it.active && it.type in setOf("cash", "loan", "lend") || it.id == target?.accountId }.map { it.id to it.name }
                    ChoicePicker(group.displayName, target?.accountId.orEmpty(), accountOptions,
                        { onSelectAccount(group.sourceAccountRef, it.takeIf(String::isNotEmpty)) }, enabled = !busy && accountState.ready,
                        semanticId = "cash.import.account-${group.sourceAccountRef}")
                    target?.accountId?.let { id -> TextButton(onClick = { onEditAccount(id) }, enabled = !busy) { Text(localizedText("cash_import_edit_account")) } }
                }
            }
            OutlinedButton(onClick = onCreateAccount, enabled = !busy && accountState.ready,
                modifier = Modifier.testTag("cash.import.create-account")) { Text(localizedText("cash_import_create_account")) }
        }
        if (accountState.pendingKeys.isNotEmpty()) {
            Text(localizedText("cash_import_account_unknown"), color = LocalFinanceColorRoles.current.warning)
            TextButton(onClick = onResolveAccounts, enabled = !busy, modifier = Modifier.testTag("cash.import.resolve-accounts")) {
                Text(localizedText("cash_import_check_result"))
            }
        }
        accountState.errorCode?.let { Text(cashImportTaskErrorText(it), color = MaterialTheme.colorScheme.error) }
    }
    removing?.let { file -> AlertDialog(onDismissRequest = { removing = null }, title = { Text(localizedText("cash_import_remove_file_question", file.displayName)) },
        confirmButton = { TextButton(onClick = { removing = null; onRemove(file.fileRef) }, enabled = !busy) { Text(localizedText("cash_import_remove_file")) } },
        dismissButton = { TextButton(onClick = { removing = null }) { Text(localizedText("copy_4d0b4688c7")) } }) }
    scopeFile?.let { file -> AlertDialog(onDismissRequest = { scopeFile = null }, title = { Text(localizedText("cash_import_source_settings")) },
        text = { OutlinedTextField(scopeLabel, { scopeLabel = it }, label = { Text(localizedText("cash_import_source_name")) },
            placeholder = { Text(localizedText("cash_import_default_source")) }, singleLine = true, isError = scopeLabel.length > 64) },
        confirmButton = { TextButton(onClick = { scopeFile = null; onScope(file.fileRef, scopeLabel.takeIf(String::isNotBlank)) }, enabled = !busy && scopeLabel.length <= 64) { Text(localizedText("copy_fadf24dbc5")) } },
        dismissButton = { TextButton(onClick = { scopeFile = null }) { Text(localizedText("copy_4d0b4688c7")) } }) }
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
