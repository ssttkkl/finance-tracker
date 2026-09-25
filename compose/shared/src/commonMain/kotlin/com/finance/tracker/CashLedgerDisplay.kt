package com.finance.tracker

internal data class CashProjectionMonthGroup(
    val month: String,
    val items: List<CashProjectionDto>,
    val summary: CashMonthlySummaryDto?,
)

internal fun cashProjectionMonthGroups(
    items: List<CashProjectionDto>,
    monthlySummaries: List<CashMonthlySummaryDto>,
): List<CashProjectionMonthGroup> {
    val summaries = monthlySummaries.associateBy(CashMonthlySummaryDto::month)
    return items
        .sortedByDescending { localDateTimeSortKey(it.occurredAt) }
        .groupBy { localMonthKey(it.occurredAt) }
        .entries
        .sortedByDescending { if (it.key == "unknown") "" else it.key }
        .map { (month, rows) -> CashProjectionMonthGroup(month, rows, summaries[month]) }
}

internal fun cashCategoryDisplayPath(category: CashCategoryDto?): String =
    category?.let { (it.path.map(CashCategoryPathItemDto::name) + it.name).joinToString(" / ") }
        ?.takeIf(String::isNotBlank)
        ?: "无分类"

internal fun cashProjectionEconomicTypeLabel(item: CashProjectionDto): String = when {
    item.transferSubtype == "bank_security_transfer" -> "银证转账"
    item.economicType == "expense" -> "消费"
    item.economicType == "income" -> "收入"
    item.economicType == "internal_transfer" -> "已合并"
    else -> "未提供"
}

internal fun cashProjectionAccountLabel(item: CashProjectionDto): String = item.transfer?.let { transfer ->
    "${transfer.fromAccount.name} → ${transfer.toAccount.name}"
} ?: item.account?.name ?: "多个账户"

internal fun cashProjectionAmountLabel(item: CashProjectionDto): String {
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
    val amount = when {
        displayDecimalSign(item.amount) == 0 -> item.amount.removePrefix("-").removePrefix("+")
        item.amount.startsWith("-") || item.amount.startsWith("+") -> item.amount
        else -> "+${item.amount}"
    }
    return "$amount ${item.currency}"
}

internal fun cashProjectionSourceLabel(item: CashProjectionDto): String? = when {
    item.transferSubtype == "bank_security_transfer" -> "银证转账"
    item.memberCount == 1 && item.composition.isEmpty() -> null
    else -> "已合并"
}

internal fun cashRecordTypeLabel(value: String, options: LedgerOptionsDto): String =
    options.recordTypes.firstOrNull { it.value == value }?.label ?: "其他"

internal fun cashRelationTypeLabel(value: String, options: LedgerOptionsDto): String =
    options.relationTypes.firstOrNull { it.value == value }?.label ?: when (value) {
        "payment_mirror" -> "同笔支付"
        "refund_offset" -> "退款冲销"
        "transfer_pair" -> "个人转账"
        "cash_investment_funding" -> "银证转账"
        else -> "其他关联"
    }

internal fun cashEvidenceMemberLabel(
    member: EvidenceMemberDto,
    options: LedgerOptionsDto,
    projection: CashProjectionDto? = null,
): String = when {
    "refund" in member.roles -> "退款"
    "mirror" in member.roles -> "同笔支付"
    "transfer" in member.roles -> "个人转账"
    else -> cashRecordTypeLabel(
        member.recordType ?: when (projection?.economicType) {
            "expense" -> "consumption"
            "income" -> "income"
            else -> if (member.amount.startsWith("-")) "transfer_out" else "transfer_in"
        },
        options,
    )
}

internal fun cashEvidenceMemberImpactLabel(member: EvidenceMemberDto): String = when {
    "refund" in member.roles -> "已计入退款进度。"
    "mirror" in member.roles -> "已作为同笔支付合并。"
    "transfer" in member.roles -> "已按个人转账合并。"
    else -> "已合并到本次收支。"
}

internal fun cashEvidenceSourceLabel(evidence: EvidenceDto): String {
    val projection = evidence.projection
    if (projection.economicType != "internal_transfer" && projection.transferSubtype != "bank_security_transfer") {
        return evidence.rootRecord.sourceType?.takeIf(String::isNotBlank) ?: "-"
    }
    return evidence.members.mapNotNull(EvidenceMemberDto::sourceType).distinct().joinToString("、").ifBlank { "-" }
}
