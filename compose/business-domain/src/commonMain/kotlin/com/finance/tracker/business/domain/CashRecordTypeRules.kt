package com.finance.tracker.business.domain

class CashRecordRuleFailure(message: String) : RuntimeException(message)

/** Source-row type decisions; native value conversion belongs to the input adapter. */
class CashRecordTypeRules(
    private val textOrEmpty: (Any?) -> String,
    private val truthy: (Any?) -> Boolean,
) {
    val validTypes = cashRecordTypes
    val validSubtypes = cashRecordSubtypes
    private fun pythonStrOrEmpty(value: Any?): String = textOrEmpty(value)
    private fun firstTruthy(vararg values: Any?): Any? = values.firstOrNull(truthy)

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
        if (truthy(row["_is_reversal"])) return true
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
        return truthy(row["_refund_signal"]) || truthy(row["refund_signal"])
    }

    private fun isRefund(row: Map<String, Any?>, txnType: String, summary: String): Boolean {
        if (isReversal(row, txnType, summary)) return false
        if (listOf("_is_refund", "_refund_signal", "refund_signal", "_ccb_refund_signal").any { truthy(row[it]) })
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

}
