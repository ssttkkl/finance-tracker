package com.finance.tracker.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.finance.tracker.core.WindowSizeClass
import com.finance.tracker.core.ControlAccessibilityState
import com.finance.tracker.core.PlatformControlAccessibility
import com.finance.tracker.domain.*

internal fun cashImportAccountTypeForName(name: String): String =
    if (name.contains("花呗") || name.contains("信用卡")) "loan" else "cash"

@Composable
internal fun CashImportAccountForm(account: ImportAccount?, loading: Boolean, busy: Boolean, sizeClass: WindowSizeClass, canWrite: Boolean,
    errorCode: String?, snackbar: SnackbarHostState, retrySave: Boolean = false, initialName: String = "", initialType: String = "cash", initialCurrencies: List<String> = emptyList(),
    onDirty: (Boolean) -> Unit, onClose: () -> Unit,
    onSave: (String, String, List<String>, Boolean) -> Unit,
    readOriginalResult: (() -> Unit)? = null) {
    val connection = LocalSessionConnection.current
    val editEnabled = canWrite && !busy && !loading && !connection.checking && !retrySave
    val originalName = account?.name ?: initialName
    val originalType = account?.type ?: initialType
    val originalCurrencies = account?.currencies?.joinToString(", ") ?: initialCurrencies.joinToString(", ").ifEmpty { "CNY" }
    val originalActive = account?.active ?: true
    var name by remember(account, initialName) { mutableStateOf(originalName) }
    var type by remember(account, initialType, initialName) { mutableStateOf(originalType) }
    var currencies by remember(account, initialCurrencies) { mutableStateOf(originalCurrencies) }
    var active by remember(account) { mutableStateOf(originalActive) }
    val dirty = name != originalName || type != originalType || currencies != originalCurrencies || active != originalActive
    LaunchedEffect(dirty) { onDirty(dirty) }
    val codes = currencies.split(',', '，').map(String::trim).filter(String::isNotEmpty).distinct()
    val nameValid = name.isNotBlank() && name.trim().length <= 128
    val currenciesValid = codes.isNotEmpty() && codes.all { it.matches(Regex("[A-Z]{3}")) }
    val valid = nameValid && currenciesValid
    val canSave = connection.canSubmit && canWrite && !loading && !busy && (retrySave || account == null || dirty || !valid)
    var submitted by remember { mutableStateOf(false) }
    val nameFocus = remember { FocusRequester() }
    val currenciesFocus = remember { FocusRequester() }
    CashRecordModalPanel(sizeClass, localizedText(if (account == null) "cash_import_create_account" else "cash_import_edit_account"), true,
        semanticStateKey = "import-account-${account?.accountId ?: "new"}", onDismiss = onClose, onBack = onClose,
        canAnimateDismiss = !busy && !dirty && !retrySave,
        footer = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp,
                if (sizeClass == WindowSizeClass.COMPACT) Alignment.End else Alignment.Start)) {
                if (sizeClass == WindowSizeClass.COMPACT) {
                    TextButton(onClick = onClose, modifier = Modifier.testTag("cash.import.account-form.close")) {
                        Text(localizedText(if (retrySave) "cash_action_back" else "copy_4d0b4688c7"))
                    }
                }
                PlatformControlAccessibility("cash.import.account-form.save", ControlAccessibilityState.Button(canSave))
                Button(onClick = {
                    if (!canSave) return@Button
                    if (readOriginalResult != null) {
                        readOriginalResult()
                        return@Button
                    }
                    submitted = true
                    if (!nameValid) nameFocus.requestFocus()
                    else if (!currenciesValid) currenciesFocus.requestFocus()
                    else onSave(name.trim(), type, codes, active)
                }, enabled = canSave,
                    colors = if (valid || readOriginalResult != null) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier.testTag("cash.import.account-form.save")) { Text(localizedText("copy_fadf24dbc5")) }
                if (sizeClass != WindowSizeClass.COMPACT) {
                    TextButton(onClick = onClose, modifier = Modifier.testTag("cash.import.account-form.close")) {
                        Text(localizedText(if (retrySave) "cash_action_back" else "copy_4d0b4688c7"))
                    }
                }
            }
        }) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                if (busy) TaskProgressIndicator(Modifier.testTag("cash.import.account-form.progress"))
                Column(Modifier.weight(1f).widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(top = if (sizeClass == WindowSizeClass.COMPACT) 0.dp else 24.dp, bottom = 24.dp)
                    .testTag("cash.import.account-form"),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SessionConnectionNotice()
                    if (!busy && retrySave) {
                        if (errorCode != null && errorCode !in setOf("network_unavailable", "account_result_unknown")) {
                            InlineError(cashImportTaskErrorText(errorCode))
                        }
                        InlineError(localizedText("cash_continue_pending_save"))
                    } else errorCode?.let { Text(cashImportTaskErrorText(it), color = MaterialTheme.colorScheme.error) }
                    if (readOriginalResult == null) {
                        OutlinedTextField(name, { name = it }, label = { Text(localizedText("cash_import_account_name")) }, singleLine = true,
                            isError = (submitted || name.isNotEmpty()) && !nameValid,
                            supportingText = { if ((submitted || name.isNotEmpty()) && !nameValid) Text(localizedText("cash_import_account_name_invalid")) },
                            enabled = editEnabled, modifier = Modifier.fillMaxWidth().focusRequester(nameFocus).testTag("cash.import.account-form.name"))
                        ChoicePicker(localizedText("cash_import_account_type"), type,
                            listOf("cash", "loan", "lend").map { it to localizedText(when (it) { "cash" -> "copy_ca5bd21330"; "loan" -> "copy_099f8b5c68"; else -> "copy_4a2637c23f" }) }, { type = it },
                            enabled = account == null && editEnabled, semanticId = "cash.import.account-form.type")
                        OutlinedTextField(currencies, { currencies = it.uppercase() }, label = { Text(localizedText("cash_import_currencies")) },
                            isError = (submitted || currencies.isNotEmpty()) && !currenciesValid,
                            supportingText = { if ((submitted || currencies.isNotEmpty()) && !currenciesValid) Text(localizedText("cash_invalid_currency")) },
                            singleLine = true, enabled = editEnabled, modifier = Modifier.fillMaxWidth().focusRequester(currenciesFocus).testTag("cash.import.account-form.currencies"))
                        if (account != null) Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(active, { active = it }, enabled = editEnabled)
                            Text(localizedText("cash_import_active"))
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).imePadding().testTag("cash.import.account-notice"))
        }
    }
}
