package com.finance.tracker.backend

import java.math.BigDecimal
import java.time.Instant

/** 关系匹配使用的来源流水视图；金额为带符号精确十进制。 */
internal data class RelationFact(
    val id: String,
    val amount: BigDecimal,
    val currency: String,
    val accountId: String,
    val accountName: String = "",
    val accountType: String = "cash",
    val occurredAt: Any? = "",
    val counterparty: String = "",
    val counterpartyAccount: String = "",
    val counterpartyAccountAttrs: List<String> = emptyList(),
    val paymentMethod: String = "",
    val note: String = "",
    val recordType: String = "other",
    val recordSubtype: String = "not_applicable",
    val billSource: String = "",
    val source: String = "",
    val factType: String = "cash",
    val deleted: Boolean = false,
    val rawRecordId: String? = null,
    val sourceIdentity: String = "",
    val recordId: String = "",
    val rawPayload: Map<String, Any?>? = null,
    val relationMetadata: Map<String, Any?>? = null,
    val parentId: String? = null,
    val componentOrdinal: Int? = null,
    val cashGranularity: String = "atomic",
) {
    val signedAmount: BigDecimal get() = amount
    val text: String get() = cashRelationTextBlob(counterparty, note, billSource, source)
}

internal object CashRelationCore {
    const val SUBTYPE_NONE = ""
    val PAYMENT_PLATFORM_SOURCES = setOf("alipay", "wechat", "weixin", "支付宝", "微信")
    val BANK_CHANNEL_SOURCES = setOf("ccb_debit", "ccb_credit", "icbc_debit", "icbc_credit", "icbc_asia",
        "bank", "debit", "credit")
    private val OPEN_LEG_ORDERED_B_SENTINEL: Any = 0
    private const val OPEN_LEG_CANDIDATE_TOP_K = 20

    private fun payload(fact: RelationFact): Map<String, Any?> =
        fact.relationMetadata?.takeIf { it.isNotEmpty() } ?: fact.rawPayload ?: emptyMap()
    fun isRefundIn(fact: RelationFact): Boolean = fact.recordType == "refund" && fact.signedAmount.signum() > 0
    fun isOriginalRefundExpense(fact: RelationFact): Boolean = fact.recordType == "refund" &&
        fact.signedAmount.signum() < 0 && payload(fact)["offset_role"]?.toString().orEmpty() == "expense"
    fun isRefundExpenseCandidate(fact: RelationFact): Boolean =
        (fact.signedAmount.signum() < 0 && fact.recordType == "consumption") || isOriginalRefundExpense(fact)
    fun isPaymentMirrorExpense(fact: RelationFact): Boolean = isRefundExpenseCandidate(fact)
    fun isPaymentMirrorRefund(fact: RelationFact): Boolean = isRefundIn(fact)
    fun isFxOutRecord(fact: RelationFact): Boolean = fact.recordType == "fx_out" && fact.signedAmount.signum() < 0
    fun isFxInRecord(fact: RelationFact): Boolean = fact.recordType == "fx_in" && fact.signedAmount.signum() > 0
    fun isTransferOutRecord(fact: RelationFact): Boolean = fact.recordType == "transfer_out" && fact.signedAmount.signum() < 0
    fun isTransferInRecord(fact: RelationFact): Boolean = fact.recordType == "transfer_in" && fact.signedAmount.signum() > 0

    fun compatiblePlatformScope(left: RelationFact, right: RelationFact): Boolean {
        val source = (left.billSource.ifEmpty { left.source }).trim().lowercase()
        val other = (right.billSource.ifEmpty { right.source }).trim().lowercase()
        return !(source == other && source in setOf("wechat", "alipay") && left.sourceIdentity.isNotEmpty() &&
            right.sourceIdentity.isNotEmpty() && left.sourceIdentity != right.sourceIdentity)
    }

    fun stableTimestamp(value: Any?): String {
        val parsed: Instant? = when (value) {
            is Instant -> value
            is java.time.OffsetDateTime -> value.toInstant()
            is java.time.ZonedDateTime -> value.toInstant()
            null, "" -> null
            else -> runCatching { pythonCashTimestamp(value.toString().trim().replace("Z", "+00:00"), allowNaive = true) }
                .getOrNull()
        }
        return if (parsed != null) ParsedImportCodec.timeText(parsed)
        else value?.toString().orEmpty().trim().replace("Z", "+00:00")
    }

    fun stableFactReference(fact: RelationFact): String {
        val source = fact.billSource.ifEmpty { fact.source }
        if (source.isNotEmpty() && fact.recordId.isNotEmpty()) {
            val suffix = if (fact.parentId != null && fact.parentId.isNotEmpty() && fact.componentOrdinal != null)
                "#component:${fact.componentOrdinal}" else ""
            return "$source:${fact.recordId}$suffix"
        }
        return fact.id
    }

    fun stableFactOrderKey(fact: RelationFact): List<String> = listOf(
        stableTimestamp(fact.occurredAt), fact.billSource.ifEmpty { fact.source }, fact.recordId,
        fact.accountId, stableFactReference(fact), fact.id)

    fun relationOrderKey(fact: RelationFact): Comparator<RelationFact> = Comparator { left, right ->
        val a = stableFactOrderKey(left); val b = stableFactOrderKey(right)
        for (index in a.indices) {
            val result = pythonStringOrder(a[index], b[index])
            if (result != 0) return@Comparator result
        }
        0
    }

    fun orderedFactPair(factA: Any?, factB: Any? = null): Pair<Any?, Any?> {
        val a: Any = factA?.toString().orEmpty()
        if (factB == null || factB.toString().isEmpty()) return a to OPEN_LEG_ORDERED_B_SENTINEL
        val b = factB.toString()
        val aText = a.toString(); val ai = pythonRelationInt(aText); val bi = pythonRelationInt(b)
        return if (ai != null && bi != null) {
            if (ai <= bi) ai.toString() to bi.toString() else bi.toString() to ai.toString()
        } else if (pythonStringOrder(aText, b) <= 0) aText to b else b to aText
    }

    fun relationBusinessKey(workspaceId: String, kind: String, factA: String, factB: String?,
        subtype: String = SUBTYPE_NONE): List<Any?> {
        val (left, right) = orderedFactPair(factA, factB)
        return listOf(workspaceId, kind, left, right, subtype.ifEmpty { SUBTYPE_NONE })
    }

    fun openLegBusinessKey(workspaceId: String, kind: String, anchorFactId: String,
        subtype: String = SUBTYPE_NONE): List<String> =
        listOf(workspaceId, kind, subtype.ifEmpty { SUBTYPE_NONE }, anchorFactId)

    fun isOpenLegRelation(row: Map<String, Any?>?): Boolean =
        row != null && row.isNotEmpty() && (row["secondary_fact_id"] == null || row["secondary_fact_id"].toString().isEmpty())

    fun topKCandidateIds(candidateIds: Collection<Any?>, k: Int = OPEN_LEG_CANDIDATE_TOP_K,
        key: Comparator<String> = Comparator(::pythonStringOrder)): List<String> =
        candidateIds.mapNotNull { it?.toString()?.takeIf(String::isNotEmpty) }.distinct().sortedWith(key)
            .take(maxOf(0, k))

    fun sourceGroup(fact: RelationFact): String {
        val formal = cashRelationTextBlob(fact.billSource, fact.source)
        val textFallback = cashRelationTextBlob(fact.counterparty, fact.note, fact.accountName)
        val blob = if (formal.trim().isNotEmpty()) formal else textFallback
        fun classify(value: String): String? {
            if (listOf("alipay", "支付宝", "wechat", "weixin", "微信").any { it in value }) return "platform"
            if (listOf("icbc", "ccb", "bank", "debit", "credit", "工行", "建行", "工商", "建设", "储蓄", "信用卡",
                    "unionpay", "银联").any { it in value }) return "bank"
            if (PAYMENT_PLATFORM_SOURCES.any { it in value }) return "platform"
            if (BANK_CHANNEL_SOURCES.any { it in value }) return "bank"
            return null
        }
        classify(blob)?.let { return it }
        if (formal.trim().isNotEmpty()) return "other"
        return classify(textFallback) ?: "other"
    }
}

internal fun cashRelationTextBlob(vararg parts: Any?): String = parts.joinToString(" ") { it?.toString().orEmpty() }.lowercase()

private fun pythonRelationInt(value: String): java.math.BigInteger? {
    val text = value.trim()
    if (text.isEmpty()) return null
    val negative = text.startsWith("-")
    val digits = text.removePrefix("+").removePrefix("-")
    if (digits.isEmpty() || digits.any { it !in '0'..'9' }) return null
    val number = digits.toBigInteger()
    return if (negative) number.negate() else number
}
