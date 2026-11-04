package com.finance.tracker.business.domain

import kotlin.test.*
import kotlinx.serialization.json.*

class CashPlatformRefundTest {
    private val golden=Json.parseToJsonElement("""{"alipay":{"order":[true,true,false,false],"index":1,"statuses":[true,true,true,true,true]},"wechat":{"embedded":["18.00","18",null,null],"origin":[true,false,true],"income_leg":[true,false,true],"pay":[true,false,true],"merchant":[true,false],"cp":[true,true,true,false],"scenarios":{"r1_merchant":{"index":0,"rule":"import.wechat.mer_txn.v1"},"r1_txn":{"index":1,"rule":"import.wechat.mer_txn.v1"},"r1_ambiguous":{"index":3,"rule":"import.wechat.full_status_pay.v1"},"r2":{"index":2,"rule":"import.wechat.full_status_pay.v1"},"r2_closest":{"index":1,"rule":"import.wechat.full_status_pay.v1"},"r3":{"index":0,"rule":"import.wechat.partial_embedded.v1"},"r4":{"index":0,"rule":"import.wechat.residual.v1"},"transfer_type":null,"none":null},"pairs":[[0,1,"import.wechat.full_status_pay.v1"]],"transfer_pairs":[[0,1,"import.wechat.transfer_return.v1"]]}}""").jsonObject
    private val rules=PlatformRefundTestAdapters.platform
    private fun expense(id: String = "e1", amount: String = "-100.00", currency: String = "CNY",
        recordType: String = "consumption", status: String = "", pay: String = "零钱", cp: String = "商户",
        date: String = "2026-10-01 10:00:00", mer: String = "M1", txn: String = "T1") = linkedMapOf<String, Any?>(
        "id" to id, "amount" to amount, "currency" to currency, "record_type" to recordType, "status" to status,
        "pay" to pay, "cp" to cp, "date" to date, "mer" to mer, "txn" to txn)
    private fun income(amount: String = "100.00", status: String = "已退款", type: String = "微信支付",
        txn: String = "T2", date: String = "2026-10-02 10:00:00", cp: String = "商户", extra: Map<String, Any?> = emptyMap()) =
        linkedMapOf<String, Any?>("id" to "i1", "amount" to amount, "currency" to "CNY", "record_type" to "refund",
            "status" to status, "pay" to "零钱", "cp" to cp, "date" to date, "txn" to txn, "type" to type).apply { putAll(extra) }
    private fun match(expenses: List<Map<String, Any?>>, income: Map<String, Any?>, used: Set<Int> = emptySet()): JsonElement =
        rules.wechatFindExpenseForRefund(income, expenses, used)?.let {
            buildJsonObject {put("index",it.expenseIndex);put("rule",it.ruleId)}
        } ?: JsonNull

    @Test fun platformRefundRulesMatchPython() {
        val alipay = golden.getValue("alipay").jsonObject
        assertEquals(alipay.getValue("order").jsonArray.map { it.jsonPrimitive.boolean }, listOf(
            rules.alipayOrderMatch("A_1", "A"), rules.alipayOrderMatch("A*1", "A"),
            rules.alipayOrderMatch("A_1", "B"), rules.alipayOrderMatch("", "A")))
        assertEquals(alipay.getValue("index").jsonPrimitive.int,
            rules.alipayFindOriginIndex("A_1", listOf("B", "A", "A_2")))
        assertEquals(alipay.getValue("statuses").jsonArray.map { it.jsonPrimitive.boolean }, listOf(
            rules.alipayIsUnpaidClosed("交易关闭", "不计收支", ""),
            rules.alipayIsFailedRepay("还款失败", "不计收支", ""),
            rules.alipayIsPaidClosedExpense("交易关闭", "支出"),
            rules.alipayIsAuthHold("芝麻免押下单成功"),
            rules.alipayIsUnfreeze("解冻成功")))

        val wechat = golden.getValue("wechat").jsonObject
        assertEquals(wechat.getValue("embedded").jsonArray.map {
            if (it == JsonNull) null else it.jsonPrimitive.content
        }, listOf("已退款(¥18.00)", "已退款¥18", "已全额退款", "其他").map {
            rules.wechatEmbeddedRefundAmount(it)?.let(::cashLegacyDecimalText)
        })
        assertEquals(wechat.getValue("origin").jsonArray.map { it.jsonPrimitive.boolean }, listOf(
            rules.wechatIsRefundOriginExpense("支出", "已全额退款"),
            rules.wechatIsRefundOriginExpense("收入", "已全额退款"),
            rules.wechatIsRefundOriginExpense("支出", "已退款(¥1.00)")))
        assertEquals(wechat.getValue("income_leg").jsonArray.map { it.jsonPrimitive.boolean }, listOf(
            rules.wechatIsRefundIncomeLeg("收入", "已退款", ""),
            rules.wechatIsRefundIncomeLeg("支出", "已退款", ""),
            rules.wechatIsRefundIncomeLeg("收入", "", "微信支付-退款")))
        assertEquals(wechat.getValue("pay").jsonArray.map { it.jsonPrimitive.boolean }, listOf(
            rules.wechatRefundPayCompatible("零钱", "零钱"),
            rules.wechatRefundPayCompatible("零钱", "银行卡"),
            rules.wechatRefundPayCompatible("/", "零钱")))
        assertEquals(wechat.getValue("merchant").jsonArray.map { it.jsonPrimitive.boolean }, listOf(
            rules.wechatMerchantKeyMatch("M1", "M1"),
            rules.wechatMerchantKeyMatch("", "M1")))
        assertEquals(wechat.getValue("cp").jsonArray.map { it.jsonPrimitive.boolean }, listOf(
            rules.wechatCounterpartyCompatible("商户", "商户"),
            rules.wechatCounterpartyCompatible("京东", "京东商城"),
            rules.wechatCounterpartyCompatible("/", "商户", "微信转账"),
            rules.wechatCounterpartyCompatible("商户", "其他", "微信支付-退款")))

        val expenses = listOf(
            expense("merchant", mer = "M1"),
            expense("txn", mer = "X", txn = "TX"),
            expense("full", status = "已全额退款", mer = "A", txn = "A"),
            expense("full2", status = "已全额退款", mer = "B", txn = "B", date = "2026-10-02 09:00:00"),
            expense("partial", status = "已退款(¥50.00)", mer = "C", txn = "C"),
            expense("residual", status = "已退款(¥150.00)", amount = "-200.00", mer = "D", txn = "D"),
            expense("other", mer = "E", txn = "E", recordType = "transfer_reversal"))
        val scenarios = wechat.getValue("scenarios").jsonObject
        assertEquals(scenarios.getValue("r1_merchant"), match(expenses, income(txn = "M1")))
        assertEquals(scenarios.getValue("r1_txn"), match(expenses, income(txn = "TX")))
        assertEquals(scenarios.getValue("r1_ambiguous"), match(expenses, income(txn = "")))
        assertEquals(scenarios.getValue("r2"), match(expenses.take(3), income(txn = "Z")))
        assertEquals(scenarios.getValue("r2_closest"), match(listOf(expenses[2], expenses[3]), income(txn = "Z")))
        assertEquals(scenarios.getValue("r3"), match(listOf(expenses[4]), income(amount = "50.00")))
        assertEquals(scenarios.getValue("r4"), match(listOf(expenses[5]), income(amount = "50.00")))
        assertEquals(scenarios.getValue("transfer_type"), match(expenses, income(type = "微信转账")))
        assertEquals(scenarios.getValue("none"), match(listOf(expenses[6]), income()))

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
        assertEquals(wechat.getValue("pairs"), JsonArray(rules.pairWechatRefunds(refundRows)
            .map { JsonArray(listOf(JsonPrimitive(it.first),JsonPrimitive(it.second),JsonPrimitive(it.third))) }))

        val transferRows = listOf<Map<String, Any?>>(
            linkedMapOf("id" to "e1", "amount" to "-20.00", "currency" to "CNY", "record_type" to "transfer_reversal",
                "date" to "2026-10-01 10:00:00", "merchant_order_id" to "M1", "payment_method" to "零钱", "account_id" to "a"),
            linkedMapOf("id" to "i1", "amount" to "20.00", "currency" to "CNY", "record_type" to "transfer_reversal",
                "date" to "2026-10-02 10:00:00", "txn_id" to "M1", "status" to "已退款", "account_id" to "a"),
            linkedMapOf("id" to "i2", "amount" to "20.00", "currency" to "USD", "record_type" to "transfer_reversal",
                "date" to "2026-10-02 10:00:00", "txn_id" to "M1", "status" to "退款", "account_id" to "a"))
        assertEquals(wechat.getValue("transfer_pairs"), JsonArray(rules.pairWechatTransferReturns(transferRows)
            .map { JsonArray(listOf(JsonPrimitive(it.first),JsonPrimitive(it.second),JsonPrimitive(it.third))) }))
    }

    @Test fun embeddedMoneyAndExplicitWindowsKeepExactBoundaries() {
        assertNull(rules.wechatEmbeddedRefundAmount("已退款\u300018"))
        assertNull(rules.wechatEmbeddedRefundAmount("已退款(¥\u300018.00)"))
        assertEquals("18.00",rules.wechatEmbeddedRefundAmount("已退款(¥\t18.00)")?.let(::cashLegacyDecimalText))
        val row=expense(amount="-99999999999999999999.123456789012345678",status="已全额退款",mer="x",txn="x")
        val refund=income(amount="99999999999999999999.123456789012345678",txn="different")
        assertEquals(0,rules.wechatFindExpenseForRefund(refund,listOf(row),maxWindowSeconds=86400)?.expenseIndex)
        assertNull(rules.wechatFindExpenseForRefund(refund,listOf(row),maxWindowSeconds=86399))
        assertNull(rules.wechatFindExpenseForRefund(refund+mapOf("amount" to "99999999999999999999.123456789012345677"),listOf(row)))
    }
}
