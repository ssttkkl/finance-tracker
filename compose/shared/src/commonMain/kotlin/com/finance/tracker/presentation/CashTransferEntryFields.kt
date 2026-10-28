package com.finance.tracker.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.finance.tracker.core.*
import com.finance.tracker.domain.*

@Composable
internal fun CashTransferEntryFields(outgoing: Map<String, String>, incoming: Map<String, String>, accounts: List<Account>,
    sizeClass: WindowSizeClass, busy: Boolean, onOutgoing: (Map<String, String>) -> Unit, onIncoming: (Map<String, String>) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("cash.entry.transfer"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(localizedText("cash_transfer_out"), style = MaterialTheme.typography.titleSmall)
        CashTransferMemberFields(outgoing, accounts, sizeClass, busy, "outgoing", true, onOutgoing)
        HorizontalDivider()
        Text(localizedText("cash_transfer_in"), style = MaterialTheme.typography.titleSmall)
        CashTransferMemberFields(incoming, accounts, sizeClass, busy, "incoming", outgoing["currency"] != incoming["currency"], onIncoming)
        if (outgoing["currency"] == incoming["currency"]) Text(listOfNotNull(outgoing["amount"], incoming["currency"]).joinToString(" "),
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("cash.entry.incoming-linked-amount"))
        HorizontalDivider()
    }
}

@Composable
private fun CashTransferMemberFields(values: Map<String, String>, accounts: List<Account>, sizeClass: WindowSizeClass, busy: Boolean,
    role: String, amountEditable: Boolean, onChange: (Map<String, String>) -> Unit) {
    val accountId = values["account_id"].orEmpty()
    val currency = values["currency"].orEmpty()
    val account = accounts.firstOrNull { it.id.toString() == accountId }
    val choices = accounts.filter { it.active || it.id.toString() == accountId }.map { it.id.toString() to it.name }
    ChoicePicker(localizedText("copy_c3d92b20c8"), accountId,
        if (choices.any { it.first == accountId }) choices else choices + (accountId to localizedText("copy_d041bb6737")), { value ->
            val currencies = accounts.firstOrNull { it.id.toString() == value }?.currencies.orEmpty()
            onChange(values + ("account_id" to value) + ("currency" to (currency.takeIf { it in currencies } ?: currencies.firstOrNull().orEmpty())))
        }, enabled = !busy, semanticId = "cash.entry.$role-account")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (amountEditable) OutlinedTextField(values["amount"].orEmpty(), { onChange(values + ("amount" to it)) },
            label = { Text(localizedText(if (role == "outgoing") "cash_from_amount" else "cash_to_amount")) }, enabled = !busy, singleLine = true,
            modifier = Modifier.weight(if (sizeClass == WindowSizeClass.COMPACT) 2f else 1f).testTag("cash.entry.$role-amount"))
        ChoicePicker(localizedText("copy_a81ab5e100"), currency, (account?.currencies.orEmpty() + currency).distinct().map { it to it },
            { onChange(values + ("currency" to it)) }, modifier = Modifier.weight(1f), enabled = !busy, semanticId = "cash.entry.$role-currency")
    }
    DateTimePickerInput(values["occurred_at"].orEmpty(), { onChange(values + ("occurred_at" to it)) }, localizedText("copy_51f85a78ca"),
        sizeClass, enabled = !busy, modifier = Modifier.testTag("cash.entry.$role-time"))
}
