package com.finance.tracker.presentation

import androidx.compose.runtime.Composable
import com.finance.tracker.core.*
import com.finance.tracker.domain.*

internal data class CashProjectionMonthGroup(
    val month: String,
    val items: List<CashProjection>,
    val summary: CashMonthlySummary?,
)

internal fun cashProjectionMonthGroups(
    items: List<CashProjection>,
    monthlySummaries: List<CashMonthlySummary>,
): List<CashProjectionMonthGroup> {
    val summaries = monthlySummaries.associateBy(CashMonthlySummary::month)
    return items
        .sortedByDescending { localDateTimeSortKey(it.occurredAt) }
        .groupBy { localMonthKey(it.occurredAt) }
        .entries
        .sortedByDescending { if (it.key == "unknown") "" else it.key }
        .map { (month, rows) -> CashProjectionMonthGroup(month, rows, summaries[month]) }
}

internal fun cashCategoryDisplayPathValue(category: CashCategory?): String? =
    category?.let { (it.path.map(CashCategoryPathItem::name) + it.name).joinToString(" / ") }
        ?.takeIf(String::isNotBlank)

@Composable
internal fun cashCategoryDisplayPath(category: CashCategory?): String =
    cashCategoryDisplayPathValue(category) ?: localizedText("copy_f11956caf6")

internal fun cashProjectionEconomicTypeResourceKey(item: CashProjection): String = when {
    item.transferSubtype == "bank_security_transfer" -> "cash_source_bank_security_transfer"
    item.economicType == "expense" -> "cash_type_expense"
    item.economicType == "income" -> "cash_type_income"
    item.economicType == "internal_transfer" -> "ledger_quick_transfer"
    else -> "display_not_provided"
}

@Composable
internal fun cashProjectionEconomicTypeLabel(item: CashProjection): String = localizedText(cashProjectionEconomicTypeResourceKey(item))

internal fun cashProjectionAccountValue(item: CashProjection): String? = item.transfer?.let { transfer ->
    "${transfer.fromAccount.name} → ${transfer.toAccount.name}"
} ?: item.account?.name

@Composable
internal fun cashProjectionAccountLabel(item: CashProjection): String =
    cashProjectionAccountValue(item) ?: localizedText(if (item.memberCount > 1) "cash_account_multiple" else "display_not_provided")

internal fun cashProjectionAmountLabel(item: CashProjection): String {
    val transfer = item.transfer.takeIf { item.economicType == "internal_transfer" }
    if (transfer != null) {
        val fromAmount = transfer.fromAmount.removePrefix("-").removePrefix("+")
        val toAmount = transfer.toAmount.removePrefix("-").removePrefix("+")
        return if (transfer.fromCurrency == transfer.toCurrency) {
            "$fromAmount ${transfer.fromCurrency}"
        } else {
            "$fromAmount ${transfer.fromCurrency} → $toAmount ${transfer.toCurrency}"
        }
    }
    return cashAmountWithCurrencyLabel(item.amount, item.currency)
}

internal fun cashAmountWithCurrencyLabel(value: String?, currency: String?): String {
    if (value == null) return listOfNotNull("—", currency).joinToString(" ")
    val amount = when {
        displayDecimalSign(value) == 0 -> value.removePrefix("-").removePrefix("+")
        value.startsWith("-") || value.startsWith("+") -> value
        else -> "+$value"
    }
    return listOfNotNull(amount, currency).joinToString(" ")
}

internal fun cashProjectionSourceResourceKey(item: CashProjection): String? = when {
    item.transferSubtype == "bank_security_transfer" -> "cash_source_bank_security_transfer"
    item.memberCount == 1 && item.composition.isEmpty() -> null
    else -> "cash_source_merged"
}

@Composable
internal fun cashProjectionSourceLabel(item: CashProjection): String? = cashProjectionSourceResourceKey(item)?.let { localizedText(it) }

internal fun cashRecordTypeResourceKey(value: String): String? = when (value) {
    "consumption", "expense" -> "cash_record_consumption"
    "refund" -> "cash_record_refund"
    "reversal" -> "cash_record_reversal"
    "transfer_reversal" -> "cash_record_transfer_reversal"
    "withdrawal_in" -> "cash_record_withdrawal_in"
    "withdrawal_out" -> "cash_record_withdrawal_out"
    "transfer_in" -> "cash_record_transfer_in"
    "transfer_out" -> "cash_record_transfer_out"
    "repayment" -> "cash_record_repayment"
    "income" -> "cash_record_income"
    "investment_in" -> "cash_record_investment_in"
    "investment_out" -> "cash_record_investment_out"
    "interest" -> "cash_record_interest"
    "fee" -> "cash_record_fee"
    "fx_in" -> "cash_record_fx_in"
    "fx_out" -> "cash_record_fx_out"
    "other" -> "cash_record_other"
    else -> null
}

@Composable
internal fun cashRecordTypeLabel(value: String, options: LedgerOptions): String =
    cashRecordTypeResourceKey(value)?.let { localizedText(it) }
        ?: options.recordTypes.firstOrNull { it.value == value }?.label
        ?: localizedText("cash_record_other")

internal fun cashRelationTypeResourceKey(value: String): String? = when (value) {
    "payment_mirror" -> "cash_relation_payment_mirror"
    "refund" -> "cash_record_refund"
    "refund_offset" -> "cash_relation_refund_offset"
    "transfer_pair" -> "cash_relation_transfer_pair"
    "cash_investment_funding" -> "cash_relation_cash_investment_funding"
    else -> null
}

@Composable
internal fun cashRelationTypeLabel(value: String, options: LedgerOptions): String =
    cashRelationTypeResourceKey(value)?.let { localizedText(it) }
        ?: options.relationTypes.firstOrNull { it.value == value }?.label
        ?: localizedText("cash_relation_other")

internal fun cashEvidenceMemberResourceKey(member: EvidenceMember): String? = when {
    "refund" in member.roles -> "copy_b82ef83b7f"
    "mirror" in member.roles -> "copy_2b6feb78fe"
    "transfer" in member.roles -> "copy_e4aaa144b7"
    else -> null
}

@Composable
internal fun cashEvidenceMemberLabel(
    member: EvidenceMember,
    options: LedgerOptions,
    projection: CashProjection? = null,
): String {
    cashEvidenceMemberResourceKey(member)?.let { return localizedText(it) }
    return cashRecordTypeLabel(
        member.recordType ?: when (projection?.economicType) {
            "expense" -> "consumption"
            "income" -> "income"
            else -> if (member.amount.startsWith("-")) "transfer_out" else "transfer_in"
        },
        options,
    )
}

internal fun cashEvidenceMemberImpactResourceKey(member: EvidenceMember): String = when {
    "refund" in member.roles -> "cash_impact_refund"
    "mirror" in member.roles -> "cash_impact_mirror"
    "transfer" in member.roles -> "cash_impact_transfer"
    else -> "cash_impact_merged"
}

@Composable
internal fun cashEvidenceMemberImpactLabel(member: EvidenceMember): String = localizedText(cashEvidenceMemberImpactResourceKey(member))

@Composable
internal fun cashEvidenceSourceLabel(evidence: Evidence): String {
    val projection = evidence.projection
    val sources = if (projection.economicType != "internal_transfer" && projection.transferSubtype != "bank_security_transfer") {
        listOfNotNull(evidence.rootRecord.sourceType?.takeIf(String::isNotBlank))
    } else {
        evidence.members.mapNotNull(EvidenceMember::sourceType).filter(String::isNotBlank).distinct()
    }
    val labels = mutableListOf<String>()
    for (source in sources.distinct()) labels += cashEvidenceSourceTypeLabel(source)
    return labels.distinct().joinToString("、")
}

@Composable
private fun cashEvidenceSourceTypeLabel(value: String): String = when (value.lowercase()) {
    "manual" -> localizedText("cash_source_manual")
    "alipay" -> localizedText("cash_source_alipay")
    "wechat", "wechatpay", "wechat_pay" -> localizedText("cash_source_wechat")
    "bank" -> localizedText("cash_source_bank")
    "icbc_asia" -> localizedText("cash_source_icbc_asia")
    "icbc_credit" -> localizedText("cash_source_icbc_credit")
    else -> localizedText("cash_source_imported")
}
