package com.finance.tracker.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.finance.tracker.domain.*

@Composable
internal fun CashRecordSummary(amountLabel: String?, negative: Boolean, counterparty: String?, secondary: String?,
    facts: List<Pair<String, String>>, modifier: Modifier = Modifier, amountTag: String? = null) {
    if (amountLabel == null && counterparty.isNullOrBlank() && secondary.isNullOrBlank() && facts.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (amountLabel != null || !counterparty.isNullOrBlank() || !secondary.isNullOrBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                amountLabel?.let {
                    Text(it, style = MaterialTheme.typography.headlineMedium.withTabularNumbers(),
                        color = if (negative) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = if (amountTag == null) Modifier else Modifier.testTag(amountTag))
                }
                counterparty?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.titleLarge) }
                secondary?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (facts.isNotEmpty()) ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(localizedText("ledger_detail_transaction_facts"), style = MaterialTheme.typography.titleMedium)
                HorizontalDivider()
                facts.forEach { (label, value) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                        Text(localizedText(label), Modifier.widthIn(min = 88.dp, max = 104.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
internal fun CashCurrentRecordSummary(record: CashCurrentRecord, projection: CashProjection?, editingFields: Set<String>,
    accounts: List<Account>, categories: List<CashCategory>, options: LedgerOptions) {
    val fields = record.fields.fields + record.entry?.fields?.fields.orEmpty()
    val amount = projection?.amount ?: fields["amount"].cashText()
    val facts = buildList {
        for (field in listOf("record_type", "account_id", "category_id", "record_subtype", "counterparty_account", "counterparty_account_attrs", "note")) {
            if (field in editingFields) continue
            val raw = fields[field]
            if (field in setOf("record_subtype", "counterparty_account", "note") &&
                (raw.cashText().isNullOrBlank() || raw.cashText() == "not_applicable")) continue
            if (field == "counterparty_account_attrs" && (raw as? StructuredArray)?.values.orEmpty().isEmpty()) continue
            val value = when (field) {
                "account_id" -> if (projection != null) cashProjectionAccountLabel(projection) else cashBusinessFieldValue(field, raw, accounts, categories, options)
                "category_id" -> if (projection?.category != null) cashCategoryDisplayPath(projection.category) else cashBusinessFieldValue(field, raw, accounts, categories, options)
                else -> cashBusinessFieldValue(field, raw, accounts, categories, options)
            }
            cashFieldLabel(field)?.let { add(it to value) }
        }
    }
    val secondary = buildList {
        if ("record_type" !in editingFields) fields["record_type"].cashText()?.let { add(cashRecordTypeLabel(it, options)) }
        if ("occurred_at" !in editingFields) add(localDateTimeDisplayLabel(fields["occurred_at"].cashText()))
    }.joinToString(" · ")
    CashRecordSummary(
        if ("amount" in editingFields) null else projection?.let(::cashProjectionAmountLabel)
            ?: cashAmountWithCurrencyLabel(amount, fields["currency"].cashText()),
        amount?.startsWith("-") == true,
        if ("counterparty" in editingFields) null else fields["counterparty"].cashText(), secondary, facts,
        amountTag = "cash.current.amount",
    )
}
