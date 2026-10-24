package com.finance.tracker.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.finance.tracker.core.*
import com.finance.tracker.domain.*

@Composable
internal fun CashRecordFieldInputs(values: Map<String, String>, editableFields: List<String>, accounts: List<Account>,
    categories: List<CashCategory>, options: LedgerOptions, sizeClass: WindowSizeClass, busy: Boolean,
    semanticPrefix: String, bookingContent: (@Composable () -> Unit)? = null, onChange: (String, String) -> Unit) {
    val split = values["cash_granularity"] == "aggregate"
    val unavailable = localizedText("copy_d041bb6737")
    fun optionsWithCurrent(value: String, options: List<Pair<String, String>>) =
        if (value.isEmpty() || options.any { it.first == value }) options else options + (value to unavailable)
    val selectedAccount = accounts.firstOrNull { it.id.toString() == values["account_id"] }
    val selectedType = options.recordTypes.firstOrNull { it.value == values["record_type"] }
    val visible = editableFields.filter { it in values && it in cashDraftScalarFields && !(it == "account_id" && split) }.toSet()
    @Composable
    fun Field(field: String, modifier: Modifier = Modifier) {
        val label = cashFieldLabel(field)?.let { localizedText(it) } ?: return
        val value = values[field].orEmpty()
        when (field) {
            "cash_granularity" -> ChoicePicker(label, value, listOf("atomic" to localizedText("cash_allocation_single"), "aggregate" to localizedText("cash_allocation_split")),
                { onChange(field, it) }, modifier = modifier.fillMaxWidth(), enabled = !busy, semanticId = "$semanticPrefix.cash_granularity")
            "occurred_at" -> DateTimePickerInput(value, { onChange(field, it) }, label, sizeClass,
                enabled = !busy, allowEmpty = true, modifier = modifier.fillMaxWidth().testTag("$semanticPrefix.occurred_at"))
            "account_id" -> ChoicePicker(label, value, optionsWithCurrent(value, listOf("" to localizedText("display_not_provided")) + accounts.filter { it.active || it.id.toString() == value }.map { it.id.toString() to it.name }),
                { onChange(field, it) }, modifier = modifier.fillMaxWidth(), enabled = !busy, semanticId = "$semanticPrefix.account_id")
            "currency" -> ChoicePicker(label, value, (listOf("") + selectedAccount?.currencies.orEmpty().ifEmpty { accounts.flatMap { it.currencies.orEmpty() } } + value).distinct()
                .map { it to it.ifEmpty { localizedText("display_not_provided") } }, { onChange(field, it) }, modifier = modifier.fillMaxWidth(), enabled = !busy, semanticId = "$semanticPrefix.currency")
            "category_id" -> ChoicePicker(label, value, optionsWithCurrent(value, listOf("" to localizedText("copy_f11956caf6")) + categories.map { it.id to cashCategoryDisplayPath(it) }),
                { onChange(field, it) }, modifier = modifier.fillMaxWidth(), enabled = !busy, semanticId = "$semanticPrefix.category_id")
            "record_type" -> ChoicePicker(label, value, optionsWithCurrent(value, listOf("" to localizedText("display_not_provided")) + options.recordTypes.map { it.value to cashRecordTypeLabel(it.value, options) }),
                { next -> onChange(field, next); if (next != value && "record_subtype" in values) onChange("record_subtype", "") }, modifier = modifier.fillMaxWidth(), enabled = !busy, semanticId = "$semanticPrefix.record_type")
            "record_subtype" -> ChoicePicker(label, value, optionsWithCurrent(value, listOf("" to localizedText("display_not_provided")) + selectedType?.subtypes.orEmpty().map { it.value to it.label }),
                { onChange(field, it) }, modifier = modifier.fillMaxWidth(), enabled = !busy, semanticId = "$semanticPrefix.record_subtype")
            "counterparty_account_attrs" -> {
                val choices = mutableListOf("" to localizedText("display_not_provided"))
                for (attribute in listOf("full", "tail", "masked")) choices += attribute to localizedText(cashAccountAttributeLabelKey(attribute)!!)
                if (value.isNotEmpty() && choices.none { it.first == value }) {
                    val labels = mutableListOf<String>()
                    value.split(',').mapNotNull(::cashAccountAttributeLabelKey).forEach { labels += localizedText(it) }
                    choices += value to labels.joinToString("、").ifEmpty { localizedText("display_not_provided") }
                }
                ChoicePicker(label, value, choices, { onChange(field, it) }, modifier = modifier.fillMaxWidth(), enabled = !busy, semanticId = "$semanticPrefix.counterparty_account_attrs")
            }
            else -> OutlinedTextField(value, { onChange(field, it) }, label = { Text(label) }, enabled = !busy,
                singleLine = field != "note", isError = field == "amount" && value.isNotEmpty() && !isExactDecimalString(value),
                supportingText = if (field == "amount" && value.isNotEmpty() && !isExactDecimalString(value)) ({ Text(localizedText("copy_d9ea7affdf")) }) else null,
                modifier = modifier.fillMaxWidth().testTag("$semanticPrefix.$field"))
        }
    }
    val groups = listOf(
        "cash_booking_information" to listOf("amount", "currency", "account_id", "occurred_at", "record_type", "record_subtype", "cash_granularity", "category_id", "note"),
        "cash_counterparty_information" to listOf("counterparty", "counterparty_account", "counterparty_account_attrs"),
    ).map { (title, fields) -> title to fields.filter { it in visible } }.filter { it.second.isNotEmpty() || it.first == "cash_booking_information" && bookingContent != null }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        groups.forEach { (title, fields) ->
            Column(Modifier.fillMaxWidth().testTag("$semanticPrefix.group-$title"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (groups.size > 1) Text(localizedText(title), style = MaterialTheme.typography.titleSmall)
                val moneyPair = "amount" in fields && "currency" in fields
                if (moneyPair) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("amount", Modifier.weight(if (sizeClass == WindowSizeClass.COMPACT) 2f else 1f))
                    Field("currency", Modifier.weight(1f))
                }
                fields.filterNot { moneyPair && it in setOf("amount", "currency") }.forEach { Field(it) }
                if (title == "cash_booking_information") bookingContent?.invoke()
            }
        }
    }
}
