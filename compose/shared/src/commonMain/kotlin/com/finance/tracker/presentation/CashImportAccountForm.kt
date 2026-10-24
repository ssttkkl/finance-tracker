package com.finance.tracker.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.finance.tracker.core.WindowSizeClass
import com.finance.tracker.domain.*

@Composable
internal fun CashImportAccountForm(account: ImportAccount?, loading: Boolean, busy: Boolean, sizeClass: WindowSizeClass,
    errorCode: String?, onDirty: (Boolean) -> Unit, onClose: () -> Unit,
    onSave: (String, String, List<String>, Boolean) -> Unit) {
    var name by remember(account) { mutableStateOf(account?.name.orEmpty()) }
    var type by remember(account) { mutableStateOf(account?.type ?: "cash") }
    var currencies by remember(account) { mutableStateOf(account?.currencies?.joinToString(", ") ?: "CNY") }
    var active by remember(account) { mutableStateOf(account?.active ?: true) }
    val dirty = name != account?.name.orEmpty() || type != (account?.type ?: "cash") ||
        currencies != (account?.currencies?.joinToString(", ") ?: "CNY") || active != (account?.active ?: true)
    LaunchedEffect(dirty) { onDirty(dirty) }
    val codes = currencies.split(',', '，').map(String::trim).filter(String::isNotEmpty).distinct()
    val valid = name.isNotBlank() && name.length <= 128 && codes.isNotEmpty() && codes.all { it.matches(Regex("[A-Z]{3}")) }
    CashRecordModalPanel(sizeClass, localizedText(if (account == null) "cash_import_create_account" else "cash_import_edit_account"), true,
        semanticStateKey = "import-account-${account?.accountId ?: "new"}", onDismiss = onClose, onBack = onClose,
        footer = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp,
                if (sizeClass == WindowSizeClass.COMPACT) Alignment.End else Alignment.Start)) {
                if (sizeClass == WindowSizeClass.COMPACT) {
                    TextButton(onClick = onClose, enabled = !busy) { Text(localizedText("copy_4d0b4688c7")) }
                }
                Button(onClick = { onSave(name.trim(), type, codes, active) }, enabled = !loading && !busy && valid && dirty,
                    modifier = Modifier.testTag("cash.import.account-form.save")) { Text(localizedText("copy_fadf24dbc5")) }
                if (sizeClass != WindowSizeClass.COMPACT) {
                    TextButton(onClick = onClose, enabled = !busy) { Text(localizedText("copy_4d0b4688c7")) }
                }
            }
        }) {
        Column(Modifier.fillMaxHeight().widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(top = if (sizeClass == WindowSizeClass.COMPACT) 0.dp else 24.dp, bottom = 24.dp)
            .testTag("cash.import.account-form"),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            errorCode?.let { Text(cashImportTaskErrorText(it), color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(name, { name = it }, label = { Text(localizedText("cash_import_account_name")) }, singleLine = true,
                enabled = !busy && !loading, modifier = Modifier.fillMaxWidth().testTag("cash.import.account-form.name"))
            ChoicePicker(localizedText("cash_import_account_type"), type,
                listOf("cash", "loan", "lend").map { it to localizedText(when (it) { "cash" -> "copy_ca5bd21330"; "loan" -> "copy_099f8b5c68"; else -> "copy_4a2637c23f" }) }, { type = it },
                enabled = account == null && !busy && !loading, semanticId = "cash.import.account-form.type")
            OutlinedTextField(currencies, { currencies = it.uppercase() }, label = { Text(localizedText("cash_import_currencies")) },
                singleLine = true, enabled = !busy && !loading, modifier = Modifier.fillMaxWidth().testTag("cash.import.account-form.currencies"))
            if (account != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(active, { active = it }, enabled = !busy && !loading)
                Text(localizedText("cash_import_active"))
            }
        }
    }
}
