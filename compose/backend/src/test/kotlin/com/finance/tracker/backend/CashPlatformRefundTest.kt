package com.finance.tracker.backend

import kotlin.test.*
import kotlinx.serialization.json.*

class CashPlatformRefundTest {
    private val golden: JsonObject by lazy {
        Json.parseToJsonElement(javaClass.getResourceAsStream("/python-cash-platform-refund.json")!!
            .bufferedReader().use { it.readText() }).jsonObject
    }
    private fun expense(id: String = "e1", amount: String = "-100.00", currency: String = "CNY",
        recordType: String = "consumption", status: String = "", pay: String = "零钱", cp: String = "商户",
        date: String = "2026-10-01 10:00:00", mer: String = "M1", txn: String = "T1") = linkedMapOf<String, Any?>(
        "id" to id, "amount" to amount, "currency" to currency, "record_type" to recordType, "status" to status,
        "pay" to pay, "cp" to cp, "date" to date, "mer" to mer, "txn" to txn)
    private fun income(amount: String = "100.00", status: String = "已退款", type: String = "微信支付",
        txn: String = "T2", date: String = "2026-10-02 10:00:00", cp: String = "商户", extra: Map<String, Any?> = emptyMap()) =
        linkedMapOf<String, Any?>("id" to "i1", "amount" to amount, "currency" to "CNY", "record_type" to "refund",
            "status" to status, "pay" to "零钱", "cp" to cp, "date" to date, "txn" to txn, "type" to type).apply { putAll(extra) }
    private fun match(expenses: List<Map<String, Any?>>, income: Map<String, Any?>, used: Set<Int> = emptySet()): Any? =
        CashPlatformRefund.wechatFindExpenseForRefund(income, expenses, used)?.let {
            linkedMapOf("index" to it.expenseIndex, "rule" to it.ruleId)
        }

    @Test fun platformRefundRulesMatchPython() {
        val alipay = golden.getValue("alipay").jsonObject
        assertEquals(alipay.getValue("order").jsonArray.map { it.jsonPrimitive.boolean }, listOf(
            CashPlatformRefund.alipayOrderMatch("A_1", "A"), CashPlatformRefund.alipayOrderMatch("A*1", "A"),
            CashPlatformRefund.alipayOrderMatch("A_1", "B"), CashPlatformRefund.alipayOrderMatch("", "A")))
        assertEquals(alipay.getValue("index").jsonPrimitive.int,
            CashPlatformRefund.alipayFindOriginIndex("A_1", listOf("B", "A", "A_2")))
        assertEquals(alipay.getValue("statuses").jsonArray.map { it.jsonPrimitive.boolean }, listOf(
            CashPlatformRefund.alipayIsUnpaidClosed("交易关闭", "不计收支", ""),
            CashPlatformRefund.alipayIsFailedRepay("还款失败", "不计收支", ""),
            CashPlatformRefund.alipayIsPaidClosedExpense("交易关闭", "支出"),
            CashPlatformRefund.alipayIsAuthHold("芝麻免押下单成功"),
            CashPlatformRefund.alipayIsUnfreeze("解冻成功")))

        val wechat = golden.getValue("wechat").jsonObject
        assertEquals(wechat.getValue("embedded").jsonArray.map {
            if (it == JsonNull) null else it.jsonPrimitive.content
        }, listOf("已退款(¥18.00)", "已退款¥18", "已全额退款", "其他").map {
            CashPlatformRefund.wechatEmbeddedRefundAmount(it)?.toPlainString()
        })
        assertEquals(wechat.getValue("origin").jsonArray.map { it.jsonPrimitive.boolean }, listOf(
            CashPlatformRefund.wechatIsRefundOriginExpense("支出", "已全额退款"),
            CashPlatformRefund.wechatIsRefundOriginExpense("收入", "已全额退款"),
            CashPlatformRefund.wechatIsRefundOriginExpense("支出", "已退款(¥1.00)")))
        assertEquals(wechat.getValue("income_leg").jsonArray.map { it.jsonPrimitive.boolean }, listOf(
            CashPlatformRefund.wechatIsRefundIncomeLeg("收入", "已退款", ""),
            CashPlatformRefund.wechatIsRefundIncomeLeg("支出", "已退款", ""),
            CashPlatformRefund.wechatIsRefundIncomeLeg("收入", "", "微信支付-退款")))
        assertEquals(wechat.getValue("pay").jsonArray.map { it.jsonPrimitive.boolean }, listOf(
            CashPlatformRefund.wechatRefundPayCompatible("零钱", "零钱"),
            CashPlatformRefund.wechatRefundPayCompatible("零钱", "银行卡"),
            CashPlatformRefund.wechatRefundPayCompatible("/", "零钱")))
        assertEquals(wechat.getValue("merchant").jsonArray.map { it.jsonPrimitive.boolean }, listOf(
            CashPlatformRefund.wechatMerchantKeyMatch("M1", "M1"),
            CashPlatformRefund.wechatMerchantKeyMatch("", "M1")))
        assertEquals(wechat.getValue("cp").jsonArray.map { it.jsonPrimitive.boolean }, listOf(
            CashPlatformRefund.wechatCounterpartyCompatible("商户", "商户"),
            CashPlatformRefund.wechatCounterpartyCompatible("京东", "京东商城"),
            CashPlatformRefund.wechatCounterpartyCompatible("/", "商户", "微信转账"),
            CashPlatformRefund.wechatCounterpartyCompatible("商户", "其他", "微信支付-退款")))

        val expenses = listOf(
            expense("merchant", mer = "M1"),
            expense("txn", mer = "X", txn = "TX"),
            expense("full", status = "已全额退款", mer = "A", txn = "A"),
            expense("full2", status = "已全额退款", mer = "B", txn = "B", date = "2026-10-02 09:00:00"),
            expense("partial", status = "已退款(¥50.00)", mer = "C", txn = "C"),
            expense("residual", status = "已退款(¥150.00)", amount = "-200.00", mer = "D", txn = "D"),
            expense("other", mer = "E", txn = "E", recordType = "transfer_reversal"))
        val scenarios = wechat.getValue("scenarios").jsonObject
        assertEquals(scenarios.getValue("r1_merchant"), sourceJson(match(expenses, income(txn = "M1"))))
        assertEquals(scenarios.getValue("r1_txn"), sourceJson(match(expenses, income(txn = "TX"))))
        assertEquals(scenarios.getValue("r1_ambiguous"), sourceJson(match(expenses, income(txn = ""))))
        assertEquals(scenarios.getValue("r2"), sourceJson(match(expenses.take(3), income(txn = "Z"))))
        assertEquals(scenarios.getValue("r2_closest"), sourceJson(match(listOf(expenses[2], expenses[3]), income(txn = "Z"))))
        assertEquals(scenarios.getValue("r3"), sourceJson(match(listOf(expenses[4]), income(amount = "50.00"))))
        assertEquals(scenarios.getValue("r4"), sourceJson(match(listOf(expenses[5]), income(amount = "50.00"))))
        assertEquals(scenarios.getValue("transfer_type"), sourceJson(match(expenses, income(type = "微信转账"))))
        assertEquals(scenarios.getValue("none"), sourceJson(match(listOf(expenses[6]), income())))

        val refundRows = listOf<Map<String, Any?>>(
            linkedMapOf("id" to "e1", "amount" to "-100.00", "record_type" to "consumption", "date" to "2026-10-01",
                "merchant_order_id" to "M1", "status" to "已全额退款", "payment_method" to "零钱"),
            linkedMapOf("id" to "i1", "amount" to "100.00", "record_type" to "refund", "date" to "2026-10-02",
                "status" to "已退款", "payment_method" to "零钱"),
            linkedMapOf("id" to "i2", "amount" to "30.00", "record_type" to "refund", "date" to "2026-10-03",
                "status" to "已退款", "txn_type" to "微信支付-退款"),
            linkedMapOf("id" to "e2", "amount" to "5.00", "record_type" to "refund", "date" to "2026-10-04",
                "status" to "已退款"),
            linkedMapOf("id" to "other", "amount" to "1.00", "record_type" to "income", "date" to "2026-10-05"))
        assertEquals(wechat.getValue("pairs"), sourceJson(CashPlatformRefund.pairWechatRefunds(refundRows)
            .map { listOf(it.first, it.second, it.third) }))

        val transferRows = listOf<Map<String, Any?>>(
            linkedMapOf("id" to "e1", "amount" to "-20.00", "currency" to "CNY", "record_type" to "transfer_reversal",
                "date" to "2026-10-01 10:00:00", "merchant_order_id" to "M1", "payment_method" to "零钱", "account_id" to "a"),
            linkedMapOf("id" to "i1", "amount" to "20.00", "currency" to "CNY", "record_type" to "transfer_reversal",
                "date" to "2026-10-02 10:00:00", "txn_id" to "M1", "status" to "已退款", "account_id" to "a"),
            linkedMapOf("id" to "i2", "amount" to "20.00", "currency" to "USD", "record_type" to "transfer_reversal",
                "date" to "2026-10-02 10:00:00", "txn_id" to "M1", "status" to "退款", "account_id" to "a"))
        assertEquals(wechat.getValue("transfer_pairs"), sourceJson(CashPlatformRefund.pairWechatTransferReturns(transferRows)
            .map { listOf(it.first, it.second, it.third) }))
    }
}
