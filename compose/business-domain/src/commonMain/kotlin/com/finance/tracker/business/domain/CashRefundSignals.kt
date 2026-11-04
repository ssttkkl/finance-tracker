package com.finance.tracker.business.domain

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

const val RELATION_CONFIDENCE_STRONG = "strong"
const val RELATION_CONFIDENCE_WEAK = "weak"
const val RELATION_RULE_REFUND_DIAMOND_V1 = "refund_offset.diamond_via_platform.v1"

data class RelationEvidence(
    val amountDelta: String = "0",
    val timeDeltaSeconds: Int? = 0,
    val sameCurrency: Boolean = true,
    val counterpartySimilarity: String = "",
    val sourcePair: Pair<String, String> = "" to "",
    val ruleId: String = "",
    val candidateCount: Int = 1,
    val signals: List<String> = emptyList(),
    val openLeg: Boolean = false,
    val anchorRole: String = "",
    val candidateFactIds: List<String> = emptyList(),
    val extras: Map<String, Any?> = emptyMap(),
) {
    fun json(): Map<String, Any?> = linkedMapOf(
        "amount_delta" to amountDelta, "time_delta_seconds" to timeDeltaSeconds, "same_currency" to sameCurrency,
        "counterparty_similarity" to counterpartySimilarity, "source_pair" to sourcePair.toList(),
        "rule_id" to ruleId, "candidate_count" to candidateCount, "signals" to signals,
        "open_leg" to openLeg, "anchor_role" to anchorRole, "candidate_fact_ids" to candidateFactIds,
        "extras" to extras)
    fun toJsonValue(encode:(Any?)->JsonElement):JsonObject = JsonObject(json().mapValues {(key,value) ->when(key) {
        "time_delta_seconds" ->JsonPrimitive(timeDeltaSeconds)
        "candidate_count" ->JsonPrimitive(candidateCount)
        else ->encode(value)
    }})
}

data class RelationProposal(
    val kind: String,
    val primaryFactId: String,
    val secondaryFactId: String?,
    val primaryFactType: String = "cash",
    val secondaryFactType: String? = "cash",
    val status: String = "pending_review",
    val ruleId: String = "",
    val confidence: String = RELATION_CONFIDENCE_WEAK,
    val evidence: RelationEvidence = RelationEvidence(),
    val createdBy: String = "system",
    val anchorFactId: String = "",
    val openLeg: Boolean = false,
) {
    val isOpenLeg: Boolean get() = openLeg || secondaryFactId.isNullOrEmpty()
    /** Python 模型在 open_leg 提案上把锚点归一为主流水；键和序列化都使用归一值。 */
    val effectiveAnchorFactId: String get() = anchorFactId.ifEmpty { if (isOpenLeg) primaryFactId else "" }
    fun json(): Map<String, Any?> = linkedMapOf(
        "kind" to kind, "primary_fact_id" to primaryFactId,
        "secondary_fact_id" to secondaryFactId, "primary_fact_type" to primaryFactType,
        "secondary_fact_type" to secondaryFactType, "status" to status,
        "rule_id" to ruleId, "confidence" to confidence, "evidence" to evidence.json(),
        "created_by" to createdBy, "anchor_fact_id" to effectiveAnchorFactId, "open_leg" to isOpenLeg)
    fun toJsonValue(encode:(Any?)->JsonElement):JsonObject = JsonObject(json().mapValues {(key,value) ->
        if(key=="evidence") evidence.toJsonValue(encode) else encode(value)
    })
}

/** 退款文本门与标题匹配；分类只读取正式 record_type，不从文本推断一级类型。 */
object CashRefundSignals {
    private val signalTokens = listOf("退款", "退货", "退回", "冲正", "消费退货", "refund", "return")
    private val genericPlatformTitles = setOf("美团App", "美团支付", "财付通", "支付宝", "微信支付", "银行卡")
    private val p2pFamilyTokens = listOf("群收款", "二维码收款", "收款方备注", "转账备注", "微信转账", "微信红包",
        "红包（单发）", "红包(单发)", "提现", "实时提现", "零钱提现", "银联入账", "转账支取", "转账存入", "电子汇入")

    fun <A> isPlatformImportRefundSource(fact: RelationFact<A>): Boolean {
        val blob = cashRelationTextBlob(fact.billSource, fact.source)
        return listOf("alipay", "wechat", "支付宝", "微信").any { it in blob }
    }
    fun hasRefundSignal(text: String?): Boolean {
        val blob = cashRelationTextBlob(text)
        return signalTokens.any { it.lowercase() in blob }
    }
    fun <A> hasRefundSignalForFact(fact: RelationFact<A>,core:CashRelationCoreRules<A>): Boolean = fact.factType == "cash" && core.isRefundIn(fact)
    fun isP2pTransferFamily(text: String?): Boolean {
        val blob = cashRelationTextBlob(text)
        return p2pFamilyTokens.any { it.lowercase() in blob }
    }
    fun isP2pStyleRefund(text: String?): Boolean = hasRefundSignal(text) && isP2pTransferFamily(text)
    fun p2pSubtype(text: String?): String {
        val blob = cashRelationTextBlob(text)
        if (listOf("微信红包", "红包（单发）", "红包(单发)", "口令红包", "红包-退款", "红包").any { it in blob }) return "redpacket"
        if (listOf("转账备注", "微信转账", "转账支取", "转账存入").any { it in blob }) return "transfer"
        if (listOf("群收款", "二维码收款", "收款方备注").any { it in blob }) return "receipt"
        if (listOf("提现", "实时提现", "零钱提现", "银联入账", "电子汇入").any { it in blob }) return "withdraw"
        return if (isP2pTransferFamily(text)) "p2p_other" else ""
    }
    fun isRefundExcludedLeg(text: String?): Boolean {
        if (hasRefundSignal(text)) return false
        return isP2pTransferFamily(text)
    }
    fun stripRefundDescriptionPrefix(description: String?): String {
        var text = description.orEmpty().trim { it.code in pythonWhitespace }
        repeat(3) {
            when {
                text.startsWith("退款-") -> text = text.removePrefix("退款-").trim { it.code in pythonWhitespace }
                text.startsWith("退款：") -> text = text.removePrefix("退款：").trim { it.code in pythonWhitespace }
                text.startsWith("退款:") -> text = text.removePrefix("退款:").trim { it.code in pythonWhitespace }
                text.startsWith("退款 ") -> text = text.removePrefix("退款 ").trim { it.code in pythonWhitespace }
                text.startsWith("退款") && text.length > 2 -> {
                    val rest = text.substring(2).trimStart { it in "-：: " || it.code in pythonWhitespace }
                    if (rest.isEmpty()) return@repeat
                    text = rest
                }
                else -> return text.trim { it.code in pythonWhitespace }
            }
        }
        return text.trim { it.code in pythonWhitespace }
    }
    fun <A> refundTitleExactMatch(refund: RelationFact<A>, expense: RelationFact<A>): Boolean {
        val refundTitle = stripRefundDescriptionPrefix(refund.note)
        val expenseTitle = expense.note.trim { it.code in pythonWhitespace }
        if (refundTitle.isEmpty() || expenseTitle.isEmpty()) return false
        if (refundTitle in genericPlatformTitles || expenseTitle in genericPlatformTitles) return false
        return refundTitle == expenseTitle
    }
}

/** Phase D：银行退款经平台退款镜像链匹配银行原支付。 */
class CashRefundDiamondRules<A>(private val core:CashRelationCoreRules<A>,
    private val coversRefund:(A,A)->Boolean, private val absoluteText:(A)->String,
    private val timeDelta:(Any?,Any?)->Long) {
    fun match(facts: List<RelationFact<A>>, acceptedMirrors: List<Pair<String, String>> = emptyList(),
        acceptedPlatformRefunds: List<Pair<String, String>> = emptyList(),
        openOrPendingBankRefundIds: List<String>? = null): List<RelationProposal> {
        val byId = facts.filter { !it.deleted }.associateBy { it.id }
        val mirrorAdj = linkedMapOf<String, MutableSet<String>>()
        for ((a, b) in acceptedMirrors) if (a.isNotEmpty() && b.isNotEmpty()) {
            mirrorAdj.getOrPut(a) { linkedSetOf() } += b
            mirrorAdj.getOrPut(b) { linkedSetOf() } += a
        }
        val expenseToRefund = linkedMapOf<String, MutableList<String>>()
        val refundToExpense = linkedMapOf<String, String>()
        for ((a, b) in acceptedPlatformRefunds) {
            val left = byId[a]; val right = byId[b]
            if (left == null || right == null) continue
            when {
                core.signedDirection(left) < 0 && core.signedDirection(right) > 0 -> {
                    expenseToRefund.getOrPut(a) { mutableListOf() } += b; refundToExpense[b] = a
                }
                core.signedDirection(right) < 0 && core.signedDirection(left) > 0 -> {
                    expenseToRefund.getOrPut(b) { mutableListOf() } += a; refundToExpense[a] = b
                }
                else -> { expenseToRefund.getOrPut(a) { mutableListOf() } += b; refundToExpense[b] = a }
            }
        }
        val seeds = mutableListOf<RelationFact<A>>()
        if (!openOrPendingBankRefundIds.isNullOrEmpty()) {
            for (id in openOrPendingBankRefundIds) byId[id]?.let { seeds += it }
        } else {
            for (fact in byId.values) {
                if (core.sourceGroup(fact) != "bank" || core.signedDirection(fact) <= 0 ||
                    !CashRefundSignals.hasRefundSignalForFact(fact,core)) continue
                seeds += fact
            }
        }
        val used = mutableSetOf<String>()
        val result = mutableListOf<RelationProposal>()
        for (bankRefund in seeds) {
            if (bankRefund.id in used) continue
            val platformRefunds = mirrorAdj[bankRefund.id].orEmpty().filter {
                byId[it]?.let { fact -> core.sourceGroup(fact) == "platform" } == true
            }
            val bankPays = mutableListOf<String>()
            for (platformRefund in platformRefunds) {
                val expense = refundToExpense[platformRefund] ?: continue
                for (candidate in mirrorAdj[expense].orEmpty()) {
                    val bankPay = byId[candidate] ?: continue
                    if (core.sourceGroup(bankPay) != "bank" || core.signedDirection(bankPay) >= 0) continue
                    if (!coversRefund(bankPay.signedAmount,bankRefund.signedAmount)) continue
                    bankPays += candidate
                }
            }
            val distinct = bankPays.distinct()
            if (distinct.size != 1) continue
            val bankPayId = distinct.single()
            if (bankPayId in used || bankRefund.id in used) continue
            val bankPay = byId.getValue(bankPayId)
            val evidence = RelationEvidence(
                amountDelta = "0",
                timeDeltaSeconds = timeDelta(bankPay.occurredAt, bankRefund.occurredAt).toInt(),
                sameCurrency = true,
                sourcePair = (bankPay.billSource.ifEmpty { bankPay.source }) to (bankRefund.billSource.ifEmpty { bankRefund.source }),
                ruleId = RELATION_RULE_REFUND_DIAMOND_V1, candidateCount = 1,
                signals = listOf("diamond", "platform_chain", "exact_or_residual"),
                extras = linkedMapOf("via" to "platform_refund_mirror",
                    "refund_amount" to absoluteText(bankRefund.signedAmount)))
            result += RelationProposal(kind = "refund_offset", primaryFactId = bankPayId,
                secondaryFactId = bankRefund.id, status = "accepted", ruleId = RELATION_RULE_REFUND_DIAMOND_V1,
                confidence = RELATION_CONFIDENCE_STRONG, evidence = evidence, anchorFactId = bankPayId, openLeg = false)
            used += bankPayId; used += bankRefund.id
        }
        return result
    }
}

val cashRefundDiamond=CashRefundDiamondRules<CashAmount>(cashRelationCore,::coversDiamondRefund,::cashAbsolutePlainText,::relationTimeDeltaSeconds)

private fun coversDiamondRefund(expense:CashAmount,refund:CashAmount):Boolean {
    val a=LedgerDecimal.parseOrNull(expense.numericText())!!
    val b=LedgerDecimal.parseOrNull(refund.numericText())!!
    return compareUnsigned(addUnsigned(a.magnitudeAt(18),"100000000000000"),b.magnitudeAt(18))>=0
}
internal fun cashAbsolutePlainText(value:CashAmount):String {
    val parts=value.representation
    if(parts.coefficient=="0" && parts.exponent>=0) return "0"
    val exponent=parts.exponent.toInt()
    if(exponent>=0) return parts.coefficient+"0".repeat(exponent)
    val scale=-exponent
    val digits=parts.coefficient.padStart(scale+1,'0')
    return digits.dropLast(scale)+"."+digits.takeLast(scale)
}
internal fun relationTimeDeltaSeconds(a:Any?,b:Any?):Long {
    val first=parseRelationTimestampInstant(a);val second=parseRelationTimestampInstant(b)
    var seconds=second.epochSeconds-first.epochSeconds
    val nanos=second.nanosecondsOfSecond-first.nanosecondsOfSecond
    if(seconds>0 && nanos<0) seconds-- else if(seconds<0 && nanos>0) seconds++
    return kotlin.math.abs(seconds)
}
