package com.finance.tracker.business.domain

import kotlin.time.Instant

/** 关系匹配使用的来源流水视图；金额为带符号精确十进制。 */
data class RelationFact<A>(
    val id: String,
    val amount: A,
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
    val stableSourceKey: List<String> = emptyList(),
    val recordOrigin: String = "legacy",
) {
    val signedAmount: A get() = amount
    val text: String get() = cashRelationTextBlob(counterparty, note, billSource, source)
}

class CashRelationCoreRules<A>(private val amountSign: (A)->Int, private val timestamp: (Any?)->String) {
    val PAYMENT_PLATFORM_SOURCES = setOf("alipay", "wechat", "weixin", "支付宝", "微信")
    val BANK_CHANNEL_SOURCES = setOf("ccb_debit", "ccb_credit", "icbc_debit", "icbc_credit", "icbc_asia",
        "bank", "debit", "credit")
    private val OPEN_LEG_ORDERED_B_SENTINEL: Any = 0
    private val OPEN_LEG_CANDIDATE_TOP_K = 20

    private fun payload(fact: RelationFact<A>): Map<String, Any?> =
        fact.relationMetadata?.takeIf { it.isNotEmpty() } ?: fact.rawPayload ?: emptyMap()
    fun signedDirection(fact:RelationFact<A>):Int=amountSign(fact.signedAmount)
    fun isRefundIn(fact: RelationFact<A>): Boolean = fact.recordType == "refund" && amountSign(fact.signedAmount) > 0
    fun isOriginalRefundExpense(fact: RelationFact<A>): Boolean = fact.recordType == "refund" &&
        amountSign(fact.signedAmount) < 0 && payload(fact)["offset_role"]?.toString().orEmpty() == "expense"
    fun isRefundExpenseCandidate(fact: RelationFact<A>): Boolean =
        (amountSign(fact.signedAmount) < 0 && fact.recordType == "consumption") || isOriginalRefundExpense(fact)
    fun isPaymentMirrorExpense(fact: RelationFact<A>): Boolean = isRefundExpenseCandidate(fact)
    fun isPaymentMirrorRefund(fact: RelationFact<A>): Boolean = isRefundIn(fact)
    fun isFxOutRecord(fact: RelationFact<A>): Boolean = fact.recordType == "fx_out" && amountSign(fact.signedAmount) < 0
    fun isFxInRecord(fact: RelationFact<A>): Boolean = fact.recordType == "fx_in" && amountSign(fact.signedAmount) > 0
    fun isTransferOutRecord(fact: RelationFact<A>): Boolean = fact.recordType == "transfer_out" && amountSign(fact.signedAmount) < 0
    fun isTransferInRecord(fact: RelationFact<A>): Boolean = fact.recordType == "transfer_in" && amountSign(fact.signedAmount) > 0

    fun compatiblePlatformScope(left: RelationFact<A>, right: RelationFact<A>): Boolean {
        val source = (left.billSource.ifEmpty { left.source }).trim().lowercase()
        val other = (right.billSource.ifEmpty { right.source }).trim().lowercase()
        return !(source == other && source in setOf("wechat", "alipay") && left.sourceIdentity.isNotEmpty() &&
            right.sourceIdentity.isNotEmpty() && left.sourceIdentity != right.sourceIdentity)
    }

    fun stableTimestamp(value: Any?): String = timestamp(value)

    fun stableFactReference(fact: RelationFact<A>): String {
        val source = fact.billSource.ifEmpty { fact.source }
        if (source.isNotEmpty() && fact.recordId.isNotEmpty()) {
            val suffix = if (fact.parentId != null && fact.parentId.isNotEmpty() && fact.componentOrdinal != null)
                "#component:${fact.componentOrdinal}" else ""
            return "$source:${fact.recordId}$suffix"
        }
        return fact.id
    }

    fun stableFactOrderKey(fact: RelationFact<A>): List<String> = listOf(
        stableTimestamp(fact.occurredAt), fact.billSource.ifEmpty { fact.source }, fact.recordId,
        fact.accountId, stableFactReference(fact), fact.id)

    fun relationOrderKey(fact: RelationFact<A>): Comparator<RelationFact<A>> = Comparator { left, right ->
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
            if (compareRelationIntegers(ai,bi)<=0) ai.toString() to bi.toString() else bi.toString() to ai.toString()
        } else if (pythonStringOrder(aText, b) <= 0) aText to b else b to aText
    }

    fun relationBusinessKey(workspaceId: String, kind: String, factA: String, factB: String?): List<Any?> {
        val (left, right) = orderedFactPair(factA, factB)
        return listOf(workspaceId, kind, left, right)
    }

    fun openLegBusinessKey(workspaceId: String, kind: String, anchorFactId: String): List<String> =
        listOf(workspaceId, kind, anchorFactId)

    fun isOpenLegRelation(row: Map<String, Any?>?): Boolean =
        row != null && row.isNotEmpty() && (row["secondary_fact_id"] == null || row["secondary_fact_id"].toString().isEmpty())

    fun topKCandidateIds(candidateIds: Collection<Any?>, k: Int = OPEN_LEG_CANDIDATE_TOP_K,
        key: Comparator<String> = Comparator(::pythonStringOrder)): List<String> =
        candidateIds.mapNotNull { it?.toString()?.takeIf(String::isNotEmpty) }.distinct().sortedWith(key)
            .take(maxOf(0, k))

    fun sourceGroup(fact: RelationFact<A>): String {
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

fun cashRelationTextBlob(vararg parts: Any?): String = parts.joinToString(" ") { it?.toString().orEmpty() }.lowercase()

private fun pythonRelationInt(value: String): String? {
    val text=value.trim()
    if(text.isEmpty()) return null
    val digits=text.removePrefix("+").removePrefix("-")
    if(digits.isEmpty() || digits.any {it !in '0'..'9'}) return null
    val coefficient=digits.trimStart('0').ifEmpty {"0"}
    return (if(text.startsWith("-") && coefficient!="0") "-" else "")+coefficient
}
private fun compareRelationIntegers(a:String,b:String):Int {
    val left=a.removePrefix("-");val right=b.removePrefix("-")
    val negative=a.startsWith("-");val otherNegative=b.startsWith("-")
    if(negative!=otherNegative) return if(negative) -1 else 1
    val magnitude=if(left.length!=right.length) left.length.compareTo(right.length) else left.compareTo(right)
    return if(negative) -magnitude else magnitude
}

val cashRelationCore=CashRelationCoreRules<CashAmount>(
    amountSign={ if(it.representation.coefficient=="0") 0 else if(it.representation.negative) -1 else 1 },
    timestamp=::stableCashRelationTimestamp,
)
fun stableCashRelationTimestamp(value:Any?):String {
    val parsed=when(value) {
        is Instant ->value
        null,"" ->null
        else ->runCatching {parsePythonCashTimestamp(value.toString().trim().replace("Z","+00:00"),allowNaive=true)}.getOrNull()
    }
    return if(parsed!=null) formatPythonCashTimestamp(parsed) else value?.toString().orEmpty().trim().replace("Z","+00:00")
}
