package com.finance.tracker.backend

import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal typealias WeChatRefundMatch=com.finance.tracker.business.domain.WeChatRefundMatch

internal object CashPlatformRefund {
    const val WECHAT_FULL_REFUND_STATUS = com.finance.tracker.business.domain.WECHAT_FULL_REFUND_STATUS
    const val WECHAT_TRANSFER_RETURN_STATUS = com.finance.tracker.business.domain.WECHAT_TRANSFER_RETURN_STATUS
    private val rules=com.finance.tracker.business.domain.CashPlatformRefundRules<BigDecimal,LocalDateTime>(
        ::toDecimal,{it.abs()},{it.signum()},{a,b ->a.compareTo(b)},::parseDt,
        {from,to ->Duration.between(from,to).toNanos()/1_000_000_000.0})
    val sharedRules get()=rules
    fun toDecimal(value:Any?):BigDecimal?=runCatching {BigDecimal(value.toString())}.getOrNull()
    fun parseDt(value: Any?): LocalDateTime? {
        var text = value?.toString().orEmpty().trim().replace("/", "-")
        if (text.isEmpty()) return null
        runCatching {
            val parsed = java.time.OffsetDateTime.parse(text.replace("Z", "+00:00"))
            return parsed.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime()
        }
        runCatching { return LocalDateTime.parse(text.replace("Z", "")) }
        text = text.take(19)
        for (format in listOf("uuuu-MM-dd HH:mm:ss", "uuuu-MM-dd HH:mm", "uuuu-MM-dd")) {
            runCatching {
                val formatter = DateTimeFormatter.ofPattern(format)
                return if (format == "uuuu-MM-dd") LocalDate.parse(text, formatter).atStartOfDay()
                else LocalDateTime.parse(text, formatter)
            }
        }
        return null
    }


    fun alipayOrderMatch(refundTxnId:String?,originTxnId:String?)=rules.alipayOrderMatch(refundTxnId,originTxnId)
    fun alipayFindOriginIndex(refundTxnId:String?,originTxnIds:List<String?>)=rules.alipayFindOriginIndex(refundTxnId,originTxnIds)
    fun alipayIsUnpaidClosed(status:String?,direction:String?,paymentMethod:String?)=rules.alipayIsUnpaidClosed(status,direction,paymentMethod)
    fun alipayIsFailedRepay(status:String?,direction:String?,paymentMethod:String?)=rules.alipayIsFailedRepay(status,direction,paymentMethod)
    fun alipayIsPaidClosedExpense(status:String?,direction:String?)=rules.alipayIsPaidClosedExpense(status,direction)
    fun alipayIsAuthHold(status:String?)=rules.alipayIsAuthHold(status)
    fun alipayIsUnfreeze(status:String?)=rules.alipayIsUnfreeze(status)
    fun wechatEmbeddedRefundAmount(status:String?)=rules.wechatEmbeddedRefundAmount(status)
    fun wechatIsRefundOriginExpense(direction:String?,status:String?)=rules.wechatIsRefundOriginExpense(direction,status)
    fun wechatIsRefundIncomeLeg(direction:String?,status:String?,txnType:String?="")=rules.wechatIsRefundIncomeLeg(direction,status,txnType)
    fun wechatRefundPayCompatible(expensePay:String?,incomePay:String?)=rules.wechatRefundPayCompatible(expensePay,incomePay)
    fun wechatMerchantKeyMatch(expenseMerchantOrderId:String?,incomeTxnId:String?)=rules.wechatMerchantKeyMatch(expenseMerchantOrderId,incomeTxnId)
    fun wechatCounterpartyCompatible(expenseCp:String?,incomeCp:String?,incomeType:String?="")=rules.wechatCounterpartyCompatible(expenseCp,incomeCp,incomeType)
    fun wechatFindExpenseForRefund(income:Map<String,Any?>,expenses:List<Map<String,Any?>>,usedExpenseIndexes:Set<Int> = emptySet(),
        maxWindow:Duration=Duration.ofDays(60))=rules.wechatFindExpenseForRefund(income,expenses,usedExpenseIndexes,maxWindow.seconds)
    fun pairWechatRefunds(rows:List<Map<String,Any?>>)=rules.pairWechatRefunds(rows)
    fun pairWechatTransferReturns(rows:List<Map<String,Any?>>,maxWindow:Duration=Duration.ofDays(60))=
        rules.pairWechatTransferReturns(rows,maxWindow.seconds)
}
