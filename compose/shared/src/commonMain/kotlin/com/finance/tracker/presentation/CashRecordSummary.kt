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
        if (facts.isNotEmpty()) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                facts.forEach { (label, value) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = androidx.compose.ui.Alignment.Top) {
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
internal fun CashRecordStatusBanner(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    Surface(
        color = LocalFinanceColorRoles.current.warningSurface,
        contentColor = LocalFinanceColorRoles.current.warning,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(message, style = MaterialTheme.typography.bodyMedium)
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction, enabled = enabled) { Text(actionLabel) }
            }
        }
    }
}

@Composable
internal fun cashDraftIncompleteMessage(fieldPaths: List<String?>, fallbackMessages: List<String>): String {
    val missing = buildList {
        for (path in fieldPaths) {
            val field = path?.substringBefore('[')?.substringBefore('.') ?: continue
            val label = cashFieldLabel(field) ?: continue
            add(localizedText(label))
        }
    }.distinct()
    return when {
        missing.isNotEmpty() -> localizedText("cash_import_draft_incomplete_fields", missing.joinToString("、"))
        fallbackMessages.any { it.isNotBlank() } -> fallbackMessages.filter { it.isNotBlank() }.distinct().joinToString("\n")
        else -> localizedText("cash_import_draft_incomplete_fallback")
    }
}

@Composable
internal fun CashStatementDisclosure(
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        TextButton(onClick = onToggle, modifier = Modifier.fillMaxWidth().testTag(testTag)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(localizedText("cash_statement_content"), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Text(localizedText(if (expanded) "cash_import_collapse" else "cash_import_process"))
            }
        }
        if (expanded) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        }
    }
}

@Composable
internal fun CashCurrentRecordSummary(record: CashCurrentRecord, projection: CashProjection?, editingFields: Set<String>,
    accounts: List<Account>, categories: List<CashCategory>, options: LedgerOptions) {
    val fields = record.fields.fields + record.entry?.fields?.fields.orEmpty()
    val amount = projection?.amount ?: fields["amount"].cashText()
    val facts = buildList {
        for (field in listOf("account_id", "category_id", "record_subtype", "counterparty_account", "counterparty_account_attrs", "note")) {
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
        if ("occurred_at" !in editingFields) add(localDateTimeDisplayLabel(cashCurrentRecordOccurredAt(record, projection)))
    }.joinToString(" · ")
    CashRecordSummary(
        if ("amount" in editingFields) null else projection?.let(::cashProjectionAmountLabel)
            ?: cashAmountWithCurrencyLabel(amount, fields["currency"].cashText()),
        amount?.startsWith("-") == true,
        if ("counterparty" in editingFields) null else fields["counterparty"].cashText(), secondary, facts,
        amountTag = "cash.current.amount",
    )
}
