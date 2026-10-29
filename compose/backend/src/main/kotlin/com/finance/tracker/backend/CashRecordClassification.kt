package com.finance.tracker.backend

import com.finance.tracker.business.domain.LedgerDecimal
import java.math.BigDecimal

/** 来源行记录类型/子类型与对方账号的公共规则；只读来源字段，不跨行配对。 */
internal class CashRecordRuleFailure(message: String) : RuntimeException(message)

internal class CounterpartyAccount(val value: String, val attrs: List<String>,
    val reconstructionProof: Any? = null) {
    override fun equals(other: Any?): Boolean = other is CounterpartyAccount && value == other.value && attrs == other.attrs
    override fun hashCode(): Int = 31 * value.hashCode() + attrs.hashCode()
    override fun toString(): String = "CounterpartyAccount(value=$value, attrs=$attrs)"
}

internal object CashRecordClassification {
    val validTypes = cashRecordTypes
    val validSubtypes = cashRecordSubtypes
    private val emptyAccountMarkers = setOf("", "/", "-", "--", "(空)", "（空）")
    private val accountAttrs = setOf("full", "tail", "masked", "reconstructed")
    private val accountCombinations = setOf(emptyList<String>(), listOf("full"), listOf("tail"), listOf("masked"),
        listOf("masked", "reconstructed"))
    private val proofToken = Any()
    private val pythonDigitRanges = pythonIntegerDigitStarts.joinToString("") {
        "\\x{${it.toString(16)}}-\\x{${(it + 9).toString(16)}}"
    }
    private val pythonDigitClass = "[$pythonDigitRanges]"
    private val pythonWhitespaceRanges = pythonWhitespace.joinToString("") { "\\x{${it.toString(16)}}" }
    private val pythonWhitespaceClass = "[$pythonWhitespaceRanges]"
    private val pythonWhitespaceRegex = Regex("$pythonWhitespaceClass+")
    private val accountSeparators = Regex("[$pythonWhitespaceRanges\\-()（）]")
    private val maskedRegex = Regex("($pythonDigitClass+)\\*+($pythonDigitClass+)")
    private val tailRegex = Regex("(?<!$pythonDigitClass)($pythonDigitClass{4})(?!$pythonDigitClass)")
    private val fourDigits = Regex("$pythonDigitClass{4}")

    fun defaultSubtype(recordType: Any?): String = defaultCashRecordSubtype(pythonStrOrEmpty(recordType))

    fun validateSubtype(recordType: Any?, recordSubtype: Any?) {
        val type = pythonStrOrEmpty(recordType)
        val subtype = pythonStrOrEmpty(recordSubtype)
        if (type !in validTypes) throw CashRecordRuleFailure("unknown record_type: $type")
        if (subtype !in validSubtypes) throw CashRecordRuleFailure("unknown record_subtype: $subtype")
        if (!validCashRecordPair(type, subtype))
            throw CashRecordRuleFailure("record_subtype '$subtype' is invalid for record_type '$type'")
    }

    fun classify(source: String?, row: Map<String, Any?>): Pair<String, String> {
        val recordType = classifyType(source, row)
        val effectiveSource = pythonStrOrEmpty(source).ifEmpty {
            pythonStrOrEmpty(row["bill_source"]).ifEmpty { pythonStrOrEmpty(row["source_type"]) }
        }.trim { it.code in pythonWhitespace }
        val summary = pythonStrOrEmpty(row["summary"]).trim { it.code in pythonWhitespace }
        if (recordType == "transfer_in" || recordType == "transfer_out") {
            val subtype = if (effectiveSource == "icbc_debit" && summary == "跨境汇款") "cross_border_remittance"
            else "ordinary_transfer"
            return recordType to subtype
        }
        return recordType to defaultSubtype(recordType)
    }

    fun classifyType(source: String?, row: Map<String, Any?>): String {
        val effectiveSource = pythonStrOrEmpty(source).ifEmpty {
            pythonStrOrEmpty(row["bill_source"]).ifEmpty { pythonStrOrEmpty(row["source_type"]) }
        }.trim { it.code in pythonWhitespace }
        val txnType = pythonStrOrEmpty(row["txn_type"]).trim { it.code in pythonWhitespace }
        val summary = pythonStrOrEmpty(row["summary"]).trim { it.code in pythonWhitespace }
        val note = text(row, "note", "counterparty", "location", "acct_name_raw")
        val text = "$txnType $summary $note"
        if (isReversal(row, txnType, summary)) return "reversal"
        if (isP2pTransferReturn(effectiveSource, row, txnType, note)) return "transfer_reversal"
        if (isRefund(row, txnType, summary)) return "refund"
        if (isRepayment(effectiveSource, row, txnType, summary)) return "repayment"
        val offsetType = firstTruthy(row["_debit_offset_type"], row["offset_type"]).let(::pythonStrOrEmpty).trim { it.code in pythonWhitespace }
        if (offsetType == "benefit_rebate" || offsetType == "campaign_cashback") return "income"
        if (offsetType == "fee_reversal") return "fee"
        if (effectiveSource == "wechat") {
            if (txnType == "零钱提现") return directionalWithdrawal(row)
            if (txnType in setOf("转账", "群收款", "微信红包", "微信红包（单发）", "微信红包（群红包）", "二维码收款"))
                return directionalTransfer(row)
            if (txnType in setOf("购买理财通", "理财通", "零钱通存取")) {
                if (listOf("转出", "取出", "赎回", "到账").any { it in text } || isIncome(row)) return "investment_in"
                return "investment_out"
            }
            if (txnType in setOf("商户消费", "扫二维码付款", "二维码付款", "充值", "零钱充值", "缴费", "充值缴费"))
                return "consumption"
            if (!isIncome(row)) return "consumption"
            return "income"
        }
        if (effectiveSource == "alipay") {
            if (txnType in setOf("账户存取", "账户提现") ||
                listOf("提现-", "提现到银行卡", "转出到银行卡").any { it in text }) return directionalWithdrawal(row)
            if (txnType == "转账红包") return directionalTransfer(row)
            if (txnType == "投资理财") {
                if (listOf("收益发放", "账户结息", "结息").any { it in text }) return "interest"
                if (listOf("卖出", "赎回").any { it in text } || isIncome(row)) return "investment_in"
                return "investment_out"
            }
            if (txnType == "充值缴费" || !isIncome(row)) return "consumption"
            return "income"
        }
        if (effectiveSource == "icbc_asia") {
            val sourceText = "$txnType $summary"
            if (listOf("轉賬", "轉帳", "转账", "转帐", "匯款", "汇款").any { it in sourceText }) return directionalTransfer(row)
            if (listOf("提現", "提现", "取現", "取现").any { it in sourceText }) return directionalWithdrawal(row)
            if (listOf("消費", "消费").any { it in sourceText }) return "consumption"
        }
        val bankSummary = summary
        if (bankSummary in setOf("基金购买", "买入基金", "理财")) return "investment_out"
        if (bankSummary in setOf("基金赎回", "卖出基金")) return "investment_in"
        if (bankSummary in setOf("利息", "结息", "利息存入") || "利息" in bankSummary) return "interest"
        if (listOf("手续费", "管理费").any { it in bankSummary }) return "fee"
        if (listOf("购汇", "个人购汇", "预约购汇", "外汇", "汇兑").any { it in bankSummary })
            return if (!isIncome(row)) "fx_out" else "fx_in"
        if (bankSummary in setOf("银转证", "银行转证券", "基金购买")) return "investment_out"
        if (bankSummary in setOf("证转银", "证券转银行", "基金赎回")) return "investment_in"
        if (bankSummary in setOf("转账", "转帐", "转账支取", "支付宝转账", "跨境汇款", "跨行汇款", "转账存入", "网转",
                "他行汇入", "银联入账", "电子汇入", "存款", "ATM存款")) return directionalTransfer(row)
        if (bankSummary in setOf("提现", "取现", "预约取现", "支付机构提现", "ATM取款")) return directionalWithdrawal(row)
        if (bankSummary in setOf("消费", "银联消费", "有卡自助消费", "无卡自助交易", "无卡支付", "跨行其他渠道消费",
                "充值", "缴费")) return "consumption"
        if (bankSummary in setOf("工资", "奖金", "年终奖", "年度奖金", "月日约定提取", "机构") || "工资" in bankSummary)
            return "income"
        if (isIncome(row)) return "income"
        return "other"
    }

    fun normalizeAccount(value: Any?, source: String = "", sourceAccountIdentifier: String = "",
        sourcePayload: Any? = null): CounterpartyAccount {
        val text = pythonStrOrEmpty(value).trim { it.code in pythonWhitespace }
        if (text in emptyAccountMarkers) return CounterpartyAccount("", emptyList())
        if ("*" in text || "＊" in text) {
            var maskedText = text.replace(pythonWhitespaceRegex, "").replace('＊', '*')
            val compact = maskedText.replace(accountSeparators, "")
            val masked = maskedRegex.matchEntire(compact)
            if (masked != null) maskedText = compact
            if (source == "icbc_asia") {
                val reference = pythonStrOrEmpty(sourceAccountIdentifier).replace(accountSeparators, "")
                if (masked != null && pythonRegexDigit(reference) && reference.length >= 6 &&
                    masked.groupValues[1].length >= 4 && masked.groupValues[2].length >= 2 &&
                    masked.groupValues[1].length + masked.groupValues[2].length < reference.length &&
                    maskedText.length == reference.length && reference.startsWith(masked.groupValues[1])) {
                    val reconstructed = reference.dropLast(masked.groupValues[2].length) + masked.groupValues[2]
                    val digest = sourcePayloadDigest(sourcePayload)
                    val proof = if (digest.isNotEmpty())
                        ReconstructionProof(proofToken, source.trim { it.code in pythonWhitespace }, digest) else null
                    return CounterpartyAccount(reconstructed, listOf("masked", "reconstructed"), proof)
                }
            }
            return CounterpartyAccount(maskedText, listOf("masked"))
        }
        val digits = text.replace(accountSeparators, "")
        if (pythonIsDigit(digits)) {
            if (digits.length < 4) return CounterpartyAccount("", emptyList())
            if (digits.length == 4) return CounterpartyAccount(digits, listOf("tail"))
            return CounterpartyAccount(digits, listOf("full"))
        }
        if ("@" !in text) {
            val tail = tailRegex.find(text)
            if (tail != null) return CounterpartyAccount(tail.groupValues[1], listOf("tail"))
        }
        return CounterpartyAccount(text, listOf("full"))
    }

    fun validateAccount(value: Any?, attrs: Any?) {
        val account = pythonStrOrEmpty(value).trim { it.code in pythonWhitespace }
        if (attrs !is List<*>) throw CashRecordRuleFailure("counterparty_account_attrs must be a JSON array")
        val normalized = attrs.map(::pythonStr)
        if (normalized.any { it !in accountAttrs } || normalized !in accountCombinations)
            throw CashRecordRuleFailure("counterparty_account_attrs has an invalid combination")
        if (account.isEmpty()) {
            if (normalized.isNotEmpty())
                throw CashRecordRuleFailure("counterparty_account_attrs must be empty when account is empty")
            return
        }
        if (normalized.isEmpty())
            throw CashRecordRuleFailure("counterparty_account_attrs is required for a non-empty account")
        if (normalized == listOf("tail") && !fourDigits.matches(account))
            throw CashRecordRuleFailure("counterparty_account_attrs tail requires exactly four digits")
        if (normalized == listOf("full") &&
            ("*" in account || "＊" in account || pythonIsDigit(account) && account.length <= 4))
            throw CashRecordRuleFailure("counterparty_account_attrs full conflicts with account value")
        if (normalized == listOf("masked") && "*" !in account && "＊" !in account)
            throw CashRecordRuleFailure("counterparty_account_attrs masked requires an explicit mask")
        if (normalized == listOf("masked", "reconstructed") && (!pythonIsDigit(account) || account.length <= 4))
            throw CashRecordRuleFailure("counterparty_account_attrs reconstructed requires a full numeric value")
    }

    fun validateAccountForWrite(value: Any?, attrs: Any?, reconstructionProof: Any? = null, sourceType: Any? = "",
        sourcePayload: Any? = null) {
        validateAccount(value, attrs)
        val normalized = (attrs as List<*>).map(::pythonStr)
        if (normalized != listOf("masked", "reconstructed")) return
        val account = pythonStrOrEmpty(value).trim { it.code in pythonWhitespace }
        val accountProof = reconstructionProof as? CounterpartyAccount
        val proof = accountProof?.reconstructionProof as? ReconstructionProof
        val digest = sourcePayloadDigest(sourcePayload)
        if (!(proof != null && proof.token === proofToken && accountProof.value == account &&
            accountProof.attrs == normalized &&
            proof.sourceType == pythonStrOrEmpty(sourceType).trim { it.code in pythonWhitespace } && digest.isNotEmpty() && proof.digest == digest))
            throw CashRecordRuleFailure("counterparty_account_attrs reconstructed requires verified source reconstruction " +
                "from the same source row")
    }

    private fun isIncome(row: Map<String, Any?>): Boolean {
        val direction = firstTruthy(row["_wechat_direction"], row["_alipay_direction"], row["direction"])
            .let(::pythonStrOrEmpty).trim { it.code in pythonWhitespace }
        if (direction in setOf("收入", "贷", "in", "IN")) return true
        if (direction in setOf("支出", "借", "out", "OUT")) return false
        val category = pythonStrOrEmpty(row["category"]).trim { it.code in pythonWhitespace }.lowercase()
        if (category == "income") return true
        val amount = pythonStrOrEmpty(row["amount"]).ifEmpty { "0" }
        val representation = LedgerDecimal.parseRepresentationOrNull(amount) ?: return false
        return !representation.negative || representation.coefficient == "0"
    }

    private fun isReversal(row: Map<String, Any?>, txnType: String, summary: String): Boolean {
        if (pythonTruthy(row["_is_reversal"])) return true
        if (pythonStrOrEmpty(row["_debit_offset_type"]).trim { it.code in pythonWhitespace } == "reversal") return true
        if (summary in setOf("撤销", "撤销交易", "冲正", "撤销冲正")) return true
        return listOf("撤销", "撤销交易", "撤销冲正", "冲正").any { it in txnType }
    }

    private fun isP2pTransferReturn(source: String, row: Map<String, Any?>, txnType: String, note: String): Boolean {
        if (source !in setOf("wechat", "alipay")) return false
        val isP2p = if (source == "wechat") {
            listOf("转账", "红包", "群收款").any { it in txnType } || "qq红包" in note.lowercase()
        } else {
            "转账红包" in txnType
        }
        if (!isP2p) return false
        val status = text(row, "status", "platform_status")
        if (listOf("退款", "退回", "退还").any { it in "$txnType $status" }) return true
        return pythonTruthy(row["_refund_signal"]) || pythonTruthy(row["refund_signal"])
    }

    private fun isRefund(row: Map<String, Any?>, txnType: String, summary: String): Boolean {
        if (isReversal(row, txnType, summary)) return false
        if (listOf("_is_refund", "_refund_signal", "refund_signal", "_ccb_refund_signal").any { pythonTruthy(row[it]) })
            return true
        if (pythonStrOrEmpty(row["_debit_offset_type"]).trim { it.code in pythonWhitespace } == "refund") return true
        if (summary in setOf("退货", "退款", "消费退货")) return true
        return "退款" in txnType || txnType.endsWith("-退货")
    }

    private fun isRepayment(source: String, row: Map<String, Any?>, txnType: String, summary: String): Boolean {
        if (source == "ccb_debit" && summary == "代理收款") return true
        if (txnType in setOf("信用借还", "信用卡还款")) return true
        return listOf("还款", "购汇还款", "自动还款", "主动还款").any { it in summary }
    }

    private fun directionalTransfer(row: Map<String, Any?>): String = if (isIncome(row)) "transfer_in" else "transfer_out"
    private fun directionalWithdrawal(row: Map<String, Any?>): String =
        if (isIncome(row)) "withdrawal_in" else "withdrawal_out"

    private fun text(row: Map<String, Any?>, vararg keys: String): String =
        keys.mapNotNull { key -> pythonStrOrEmpty(row[key]).trim { it.code in pythonWhitespace }.takeIf { it.isNotEmpty() } }.joinToString(" ")

    private data class ReconstructionProof(val token: Any, val sourceType: String, val digest: String)

    internal fun sourcePayloadDigest(value: Any?): String {
        if (value !is Map<*, *> || value.isEmpty()) return ""
        return try {
            tokenDigest(canonicalCashJson(value))
        } catch (_: Exception) {
            ""
        }
    }

    private fun pythonRegexDigit(text: String): Boolean = text.isNotEmpty() &&
        text.codePoints().allMatch { code -> pythonIntegerDigitStarts.any { code in it until it + 10 } }

    private fun pythonIsDigit(text: String): Boolean = text.isNotEmpty() &&
        text.codePoints().allMatch { code -> code in pythonNondecimalDigits ||
            pythonIntegerDigitStarts.any { code in it until it + 10 } }

    private fun firstTruthy(vararg values: Any?): Any? = values.firstOrNull(::pythonTruthy)

    private fun pythonTruthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is String -> value.isNotEmpty()
        is BigDecimal -> value.signum() != 0
        is Number -> value.toDouble() != 0.0
        is Collection<*> -> value.isNotEmpty()
        is Map<*, *> -> value.isNotEmpty()
        else -> true
    }

    internal fun pythonStr(value: Any?): String = when (value) {
        null -> "None"
        is Boolean -> if (value) "True" else "False"
        is String -> value
        is BigDecimal -> value.toString()
        is Double -> value.toString()
        else -> value.toString()
    }

    private fun pythonStrOrEmpty(value: Any?): String = when (value) {
        null -> ""
        is Boolean -> if (value) "True" else ""
        is String -> value
        is BigDecimal -> if (value.signum() == 0) "" else value.toString()
        is Number -> if (value.toDouble() == 0.0) "" else pythonStr(value)
        else -> value.toString()
    }
}
