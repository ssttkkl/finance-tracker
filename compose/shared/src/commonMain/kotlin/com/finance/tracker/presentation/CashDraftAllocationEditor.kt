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
internal fun CashDraftAllocationEditor(split: Boolean, allocations: List<CashDraftAllocation>, accounts: List<Account>, busy: Boolean,
    singleNeedsRepair: Boolean, onChange: (List<CashDraftAllocation>) -> Unit, onUseSingle: () -> Unit) {
    if (!split && !singleNeedsRepair) return
    Column(Modifier.fillMaxWidth().testTag("cash.draft.allocations"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(localizedText("cash_account_allocations"), style = MaterialTheme.typography.titleSmall)
        if (!split) {
            OutlinedButton(onClick = onUseSingle, enabled = !busy, modifier = Modifier.testTag("cash.draft.use-single")) { Text(localizedText("cash_allocation_use_fields")) }
        } else {
            allocations.forEachIndexed { index, part ->
                key(part.ordinal) {
                    fun update(value: CashDraftAllocation) = onChange(allocations.toMutableList().apply { set(index, value) })
                    val account = accounts.firstOrNull { it.id.toString() == part.accountId }
                    val choices = listOf("" to localizedText("display_not_provided")) + accounts.filter { it.active || it.id.toString() == part.accountId }.map { it.id.toString() to it.name }
                    ChoicePicker(localizedText("copy_c3d92b20c8"), part.accountId,
                        if (part.accountId.isNotEmpty() && choices.none { it.first == part.accountId }) choices + (part.accountId to localizedText("copy_d041bb6737")) else choices,
                        { update(part.copy(accountId = it)) }, enabled = !busy, semanticId = "cash.draft.allocation-${part.ordinal}-account")
                    OutlinedTextField(part.amount, { update(part.copy(amount = it)) }, label = { Text(localizedText("copy_34943c40c9")) },
                        enabled = !busy, singleLine = true, isError = part.amount.isNotEmpty() && !isExactDecimalString(part.amount),
                        modifier = Modifier.fillMaxWidth().testTag("cash.draft.allocation-${part.ordinal}-amount"))
                    ChoicePicker(localizedText("copy_a81ab5e100"), part.currency,
                        (listOf("") + account?.currencies.orEmpty().ifEmpty { accounts.flatMap { it.currencies.orEmpty() } } + part.currency).distinct().map { it to it.ifEmpty { localizedText("display_not_provided") } },
                        { update(part.copy(currency = it)) }, enabled = !busy, semanticId = "cash.draft.allocation-${part.ordinal}-currency")
                    TextButton(onClick = { onChange(allocations.filterIndexed { position, _ -> position != index }) }, enabled = !busy,
                        modifier = Modifier.testTag("cash.draft.allocation-${part.ordinal}-remove")) { Text(localizedText("cash_allocation_remove")) }
                    HorizontalDivider()
                }
            }
            OutlinedButton(onClick = {
                val ordinal = (0..allocations.size).first { value -> allocations.none { it.ordinal == value } }
                onChange(allocations + CashDraftAllocation(null, ordinal, "", "", ""))
            }, enabled = !busy, modifier = Modifier.testTag("cash.draft.allocation-add")) { Text(localizedText("cash_allocation_add")) }
        }
    }
}
