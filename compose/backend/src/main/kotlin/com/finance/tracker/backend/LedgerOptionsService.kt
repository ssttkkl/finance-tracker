package com.finance.tracker.backend

import com.finance.tracker.business.contracts.*
import java.time.Instant

private val recordTypeLabels=mapOf(
    "consumption" to "消费","refund" to "退款","reversal" to "冲正","transfer_reversal" to "转账退回",
    "withdrawal_in" to "提现入账","withdrawal_out" to "提现","transfer_in" to "转账入账","transfer_out" to "转账转出",
    "repayment" to "还款","income" to "收入","investment_in" to "投资转入","investment_out" to "投资转出",
    "interest" to "利息","fee" to "费用","fx_in" to "换汇转入","fx_out" to "换汇转出","other" to "其他",
)
private val recordSubtypeLabels=mapOf(
    "ordinary_transfer" to "普通转账","cross_border_remittance" to "跨境汇款","internal_account_transfer" to "账户间转账",
    "currency_exchange" to "币种兑换","withdraw_to_bank" to "提现到银行","credit_repayment" to "信用卡还款","not_applicable" to "",
)
internal fun buildLedgerOptions()=LedgerOptionsDto(
    recordTypes=cashRecordTypes.map {type ->RecordTypeOptionDto(type,recordTypeLabels.getValue(type),
        cashRecordSubtypes.filter {validCashRecordPair(type,it)}.map {SimpleOptionDto(it,recordSubtypeLabels.getValue(it))})},
    relationTypes=listOf(SimpleOptionDto("payment_mirror","同笔支付"),SimpleOptionDto("transfer_pair","个人转账"),SimpleOptionDto("refund_offset","退款冲销")),
)

class LedgerOptionsService(database: Database,now: ()->Instant=Instant::now) {
    private val reads=ReadServices(database,now)
    fun options(token: String?): LedgerOptionsDto {
        reads.requireContext(token)
        return buildLedgerOptions()
    }
}
