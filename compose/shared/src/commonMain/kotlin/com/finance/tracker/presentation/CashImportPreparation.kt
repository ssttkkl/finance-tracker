package com.finance.tracker.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.finance.tracker.domain.*
import com.finance.tracker.core.LocalFinanceColorRoles
import com.finance.tracker.resources.Res
import com.finance.tracker.resources.ic_add
import com.finance.tracker.resources.ic_arrow_forward
import com.finance.tracker.resources.ic_close
import org.jetbrains.compose.resources.painterResource

internal val CashImportHorizontalContentInset = 24.dp
private const val CashImportCreateAccountOption = "__create_cash_account__"

internal data class CashImportPendingFile(
    val clientFileRef: String,
    val displayName: String,
    val startingRevision: Long?,
    val parseSucceeded: Boolean? = null,
)

internal fun canAddCashImportFiles(
    state: ImportTaskUiState, pendingFiles: List<CashImportPendingFile>, canWrite: Boolean,
): Boolean = canWrite && state.canAddFiles &&
    (state.context?.files.orEmpty().map { it.clientFileRef } + pendingFiles.map { it.clientFileRef }).toSet().size < 20

internal fun cashImportFileStepNotice(state: ImportTaskUiState): String? = when {
    state.context?.files.orEmpty().any { it.status == "password_required" } -> "cash_import_enter_password"
    state.localFileFailures.isNotEmpty() || state.context?.files.orEmpty().any { it.status == "failed" } -> "cash_import_fix_failed_files"
    else -> null
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun CashImportPreparation(state: ImportTaskUiState, accountState: ImportAccountUiState, canWrite: Boolean,
    fileNames: Map<String, String>, pendingFiles: List<CashImportPendingFile>, removingClientFileRefs: Set<String>,
    onPendingFinished: (String) -> Unit, onRemovePending: (String) -> Unit,
    onPick: (String?) -> Unit, onRetry: (ImportContextFile, String) -> Unit,
    onRetryLocal: (String) -> Unit, onRemoveLocal: (String) -> Unit,
    onRemove: (String) -> Unit, onSelectAccount: (String, String?) -> Unit,
    onMergeSameNameAcrossChannels: (Boolean) -> Unit,
    onCreateAccount: (ImportSourceAccount) -> Unit, onResolveAccounts: () -> Unit, mappingStep: Boolean, modifier: Modifier = Modifier) {
    val busy = !state.canEdit || accountState.busy
    val addEnabled = canAddCashImportFiles(state, pendingFiles, canWrite)
    Column(modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        if (!mappingStep) {
            val pendingRefs = pendingFiles.mapTo(mutableSetOf()) { it.clientFileRef }
            val files = state.context?.files.orEmpty().filterNot { it.clientFileRef in pendingRefs || it.clientFileRef in removingClientFileRefs }
            val failures = state.localFileFailures.filterNot { it.clientFileRef in removingClientFileRefs || it.clientFileRef in pendingRefs ||
                files.any { file -> file.clientFileRef == it.clientFileRef } }
            Column(Modifier.fillMaxWidth().testTag("cash.import.files-step"), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                    CashImportPreparationHeading(localizedText("cash_import_files_heading")) {
                        OutlinedButton(onClick = { onPick(null) }, enabled = addEnabled,
                            border = financeInteractiveBorder(addEnabled),
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
                            var passwordMissing by remember { mutableStateOf(false) }
                            val passwordFocus = remember { FocusRequester() }
                            Column(Modifier.fillMaxWidth().padding(vertical = 16.dp).testTag("cash.import.file-${file.fileRef}"),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(file.displayName, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                    RemoveCashImportFileButton(file.displayName, enabled = !busy,
                                        tag = "cash.import.remove-file-${file.fileRef}", onClick = { onRemove(file.fileRef) })
                                }
                                val statusLabel = when (file.status) {
                                    "ready" -> null
                                    "partial" -> "cash_import_file_partial"
                                    "password_required" -> "cash_import_file_password"
                                    else -> "cash_import_file_failed"
                                }
                                statusLabel?.let { Text(localizedText(it), color = if (file.status in setOf("password_required", "failed")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall) }
                                file.issues.distinctBy { it.code }
                                    .filterNot { file.status == "password_required" && it.code == "password_required" }
                                    .forEach { Text(it.message, style = MaterialTheme.typography.bodySmall) }
                                 file.diagnostic?.let { CashImportDiagnostic(it) }
                                if (file.status == "password_required") OutlinedTextField(password, { password = it; passwordMissing = false }, label = { Text(localizedText("cash_import_password")) },
                                    visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy,
                                    isError = passwordMissing, supportingText = if (passwordMissing) ({ Text(localizedText("cash_import_enter_password")) }) else null,
                                    modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().focusRequester(passwordFocus).testTag("cash.import.password-${file.fileRef}"))
                                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                                    verticalArrangement = Arrangement.Center, itemVerticalAlignment = Alignment.CenterVertically) {
                                    if (file.canRetry || file.status == "failed") TextButton(onClick = {
                                        if (file.status == "password_required" && password.isEmpty()) {
                                            passwordMissing = true; passwordFocus.requestFocus()
                                        } else { onRetry(file, password); password = "" }
                                    }, enabled = !busy,
                                        modifier = Modifier.testTag("cash.import.retry-${file.fileRef}")) {
                                        if (file.status == "password_required") {
                                            Icon(painterResource(Res.drawable.ic_arrow_forward), contentDescription = null,
                                                modifier = Modifier.size(ButtonDefaults.IconSize))
                                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                            Text(localizedText("cash_import_password_submit"))
                                        } else Text(localizedText("copy_e2d53a6d3a"))
                                    }
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
                            Column(Modifier.fillMaxWidth().padding(vertical = 16.dp).testTag("cash.import.local-file-${failure.clientFileRef}"),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                fileNames[failure.clientFileRef]?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                                Text(cashImportTaskErrorText(failure.code), color = MaterialTheme.colorScheme.error)
                                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                                    itemVerticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { onRetryLocal(failure.clientFileRef) }, enabled = !busy && state.canAddFiles,
                                        modifier = Modifier.testTag("cash.import.retry-local-${failure.clientFileRef}")) {
                                        Text(localizedText("copy_e2d53a6d3a"))
                                    }
                                    RemoveCashImportFileButton(fileNames[failure.clientFileRef].orEmpty(), enabled = !busy,
                                        tag = "cash.import.remove-local-${failure.clientFileRef}", onClick = { onRemoveLocal(failure.clientFileRef) })
                                }
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
                val sourceAccounts = state.context?.sourceAccounts.orEmpty()
                val multipleChannels = sourceAccounts.map(ImportSourceAccount::sourceType).distinct().size > 1
                if (multipleChannels) {
                    val mergeSameNameLabel = localizedText("cash_import_merge_same_name")
                    val mergeSameNameStateLabel = localizedText(if (state.mergeSameNameAcrossChannels)
                        "cash_import_merge_same_name_on" else "cash_import_merge_same_name_off")
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("cash.import.merge-same-name")
                        .toggleable(value = state.mergeSameNameAcrossChannels, enabled = !busy, role = Role.Switch,
                            onValueChange = onMergeSameNameAcrossChannels)
                        .semantics { contentDescription = "$mergeSameNameLabel，$mergeSameNameStateLabel" },
                        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(mergeSameNameLabel, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Switch(state.mergeSameNameAcrossChannels, onCheckedChange = null, enabled = !busy)
                    }
                }
                if (multipleChannels && state.mergeSameNameAcrossChannels) {
                    // 合并同名账户：跨渠道同名只出现一条（各渠道各一笔），其余账户平铺，无第一级分组标题。
                    val clusterByRef = mergeableAccountClusters(sourceAccounts)
                        .flatMap { cluster -> cluster.map { it.sourceAccountRef to cluster } }.toMap()
                    val used = mutableSetOf<String>()
                    val entries = sourceAccounts.mapNotNull { account ->
                        if (account.sourceAccountRef in used) null
                        else (clusterByRef[account.sourceAccountRef] ?: listOf(account)).also { members ->
                            used += members.map { it.sourceAccountRef }
                        }
                    }
                    entries.forEachIndexed { index, members ->
                        val target = state.choices.accountSelections
                            .firstOrNull { it.sourceAccountRef == members.first().sourceAccountRef }?.target
                        val memberLabels = mutableListOf<String>()
                        for (member in members) {
                            val channel = cashImportSourceChannelLabel(member.sourceType)
                            val account = member.sessionLabel?.takeIf { it.isNotBlank() && it != member.displayName }
                            memberLabels += if (account == null) channel else "$channel（$account）"
                        }
                        CashImportSourceAccountEntry(
                            testRef = members.first().sourceAccountRef,
                            title = members.first().displayName,
                            matchName = members.first().displayName,
                            suggestedAccountId = members.mapNotNull { it.suggestion?.accountId }.distinct().singleOrNull(),
                            memberLabels = memberLabels,
                            rowCount = members.sumOf { it.rowCount },
                            currencies = members.flatMap { it.currencies }.distinct(),
                            targetAccountId = target?.accountId,
                            accountState = accountState,
                            busy = busy,
                            onSelect = { selected -> onSelectAccount(members.first().sourceAccountRef, selected) },
                            onCreate = { onCreateAccount(members.first()) },
                        )
                        if (index != entries.lastIndex) HorizontalDivider()
                    }
                } else sourceAccounts.groupBy { group ->
                    group.sessionGroupRef?.takeIf { group.sessionLabel != null }?.let { "account::${group.sourceType}::$it" }
                        ?: "channel::${group.sourceType}"
                }.values.forEach { groups ->
                    val firstGroup = groups.first()
                    val hasAccountHeader = multipleChannels && firstGroup.sessionGroupRef != null && firstGroup.sessionLabel != null
                    val heading = if (hasAccountHeader) {
                        firstGroup.sessionLabel.orEmpty()
                    } else cashImportSourceChannelLabel(firstGroup.sourceType)
                    val sectionModifier = Modifier.fillMaxWidth().then(
                        if (hasAccountHeader) Modifier.testTag("cash.import.account-session-group-${firstGroup.sessionGroupRef}") else Modifier)
                    Column(sectionModifier) {
                        if (multipleChannels) Text(heading, Modifier.padding(top = 20.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (hasAccountHeader) Text(cashImportAccountGroupChannelLabel(firstGroup.sourceType), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        groups.forEachIndexed { index, group ->
                            val target = state.choices.accountSelections.firstOrNull { it.sourceAccountRef == group.sourceAccountRef }?.target
                            val accountName = group.sessionLabel?.takeIf {
                                !hasAccountHeader && it.isNotBlank() && it != group.displayName
                            }?.let { "$it · ${group.displayName}" } ?: group.displayName
                            CashImportSourceAccountEntry(
                                testRef = group.sourceAccountRef,
                                title = accountName,
                                matchName = group.displayName,
                                suggestedAccountId = group.suggestion?.accountId,
                                memberLabels = emptyList(),
                                rowCount = group.rowCount,
                                currencies = group.currencies,
                                targetAccountId = target?.accountId,
                                accountState = accountState,
                                busy = busy,
                                onSelect = { selected -> onSelectAccount(group.sourceAccountRef, selected) },
                                onCreate = { onCreateAccount(group) },
                            )
                            if (!hasAccountHeader || index == groups.lastIndex) HorizontalDivider()
                        }
                    }
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
}

@Composable
private fun cashImportSourceChannelLabel(sourceType: String): String = when (sourceType.lowercase()) {
    "alipay" -> localizedText("cash_source_alipay")
    "wechat", "wechatpay", "wechat_pay" -> localizedText("cash_source_wechat")
    "bank" -> localizedText("cash_source_bank")
    "icbc_asia" -> localizedText("cash_source_icbc_asia")
    "icbc_credit" -> localizedText("cash_source_icbc_credit")
    "icbc_debit" -> localizedText("cash_import_channel_icbc_debit")
    "ccb_debit" -> localizedText("cash_import_channel_ccb_debit")
    else -> localizedText("cash_source_imported")
}

@Composable
private fun cashImportAccountGroupChannelLabel(sourceType: String): String = when (sourceType.lowercase()) {
    "wechat", "wechatpay", "wechat_pay" -> localizedText("cash_import_account_group_wechat")
    else -> cashImportSourceChannelLabel(sourceType)
}

/** 单个来源账户或合并后的同名账户：标题是账户名，detail 只放来源补充信息，分组标题由调用方单独渲染。 */
@Composable
private fun CashImportSourceAccountEntry(
    testRef: String,
    title: String,
    matchName: String,
    suggestedAccountId: String?,
    memberLabels: List<String>,
    rowCount: Int,
    currencies: List<String>,
    targetAccountId: String?,
    accountState: ImportAccountUiState,
    busy: Boolean,
    onSelect: (String?) -> Unit,
    onCreate: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("cash.import.account-group-$testRef"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (memberLabels.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            memberLabels.forEach { label ->
                Text(label, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        Text(localizedText("cash_import_source_rows", rowCount.toString(), currencies.joinToString(" / ")),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val missingCurrencies = targetAccountId?.let { id ->
            accountState.accounts.firstOrNull { it.id == id }?.currencies?.let { owned ->
                currencies.filterNot { it in owned }
            }
        }.orEmpty()
        if (missingCurrencies.isNotEmpty()) Text(
            localizedText("cash_import_currency_will_add", missingCurrencies.joinToString(" / ")),
            style = MaterialTheme.typography.bodySmall, color = LocalFinanceColorRoles.current.warning)
        val eligibleAccounts = accountState.accounts.filter {
            (it.active && it.type in setOf("cash", "loan", "lend")) || it.id == targetAccountId
        }
        val suggestedIds = remember(matchName, accountState.accounts, suggestedAccountId, targetAccountId) {
            importAccountSuggestions(matchName, eligibleAccounts, suggestedAccountId)
        }
        val suggestedSet = suggestedIds.toSet()
        val accountsById = eligibleAccounts.associateBy { it.id }
        val orderedAccounts = suggestedIds.mapNotNull(accountsById::get) +
            eligibleAccounts.filterNot { it.id in suggestedSet }
        val hasSuggestions = suggestedIds.isNotEmpty()
        val accountOptions = listOf("" to localizedText("cash_import_no_account")) +
            orderedAccounts.map { it.id to it.name } +
            listOf(CashImportCreateAccountOption to localizedText("cash_import_create_account_option"))
        val suggestedLabel = localizedText("cash_import_suggested_group")
        val sectionOf: ((String) -> ChoiceSection?)? = if (hasSuggestions) { id ->
            when {
                id.isEmpty() -> ChoiceSection("none")
                id == CashImportCreateAccountOption -> ChoiceSection("create")
                id in suggestedSet -> ChoiceSection("suggested", suggestedLabel)
                else -> ChoiceSection("other")
            }
        } else null
        ChoicePicker(localizedText("cash_import_target_account"), targetAccountId.orEmpty(), accountOptions,
            { selected ->
                if (selected == CashImportCreateAccountOption) onCreate()
                else onSelect(selected.takeIf(String::isNotEmpty))
            }, enabled = !busy && accountState.ready,
            semanticId = "cash.import.account-$testRef", sectionOf = sectionOf)
    }
}

@Composable
private fun CashImportPendingFileRow(file: CashImportPendingFile, onFinished: (String) -> Unit, onRemove: (String) -> Unit) {
    val progressLabel = localizedText("cash_import_file_parsing")
    LaunchedEffect(file.clientFileRef, file.parseSucceeded) {
        if (file.parseSucceeded != null) onFinished(file.clientFileRef)
    }
    Box(Modifier.widthIn(max = 720.dp).fillMaxWidth()
        .testTag("cash.import.file-uploading-${file.clientFileRef}"), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(file.displayName, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                CircularProgressIndicator(Modifier.size(20.dp).semantics {
                    contentDescription = progressLabel
                    progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                }.testTag("cash.import.file-loading-${file.clientFileRef}"), strokeWidth = 2.dp)
                RemoveCashImportFileButton(file.displayName, enabled = true,
                    tag = "cash.import.remove-pending-${file.clientFileRef}", onClick = { onRemove(file.clientFileRef) })
            }
        }
    }
    HorizontalDivider(Modifier.widthIn(max = 720.dp).fillMaxWidth())
}

@Composable
private fun RemoveCashImportFileButton(displayName: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    val accessibleLabel = localizedText("cash_import_remove_file_named", displayName)
    TextButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.testTag(tag).semantics { contentDescription = accessibleLabel }) {
        Icon(painterResource(Res.drawable.ic_close), contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(localizedText("cash_import_remove_file"))
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
    "plan_input_invalid" -> localizedText("cash_import_input_invalid")
    "import_payload_invalid" -> localizedText("cash_import_payload_invalid")
    "category_unavailable" -> localizedText("cash_import_category_unavailable")
    "account_name_unavailable" -> localizedText("cash_import_account_name_unavailable")
    "account_in_use" -> localizedText("cash_import_account_in_use")
    "account_changed" -> localizedText("cash_import_account_changed")
    "network_unavailable" -> localizedText("copy_e0a1c9fd69")
    "unknown_error", "internal_error", "plan_build_failed", "account_result_invalid" -> localizedText("cash_import_system_error")
    "workspace_forbidden" -> localizedText("copy_dd9a4bdee0")
    else -> localizedText("copy_8d856685b1")
}

internal fun cashImportRecoverableCode(code: String?): Boolean =
    code in setOf("network_unavailable", "import_unavailable", "account_unavailable", "request_failed")

@Composable
internal fun cashRelationErrorText(code: String): String? =
    if (code == "insufficient_evidence") null else cashImportTaskErrorText(code)
