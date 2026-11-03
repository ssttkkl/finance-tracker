package com.finance.tracker.backend

import java.math.BigDecimal
import kotlin.test.*

/**
 * 原 `tests/test_platform_refund_matchers.py` 的 21 个定义。
 *
 * 纯匹配定义直接调用当前 `CashPlatformRefund`；平台硬键定义调用当前 `CashRefundHardKey`。
 * 输入与原断言来自原定义字面量，预期关系只核对事实与类型，不重算旧内部辅助结构。
 */
class PlatformRefundMatcherOriginalTest {
    private fun wechatRow(id: String, index: Int, amount: String, direction: String, status: String,
        cp: String, occurred: String, txn: String, mer: String, type: String, recordType: String = "") =
        linkedMapOf<String, Any?>("id" to id, "amount" to amount, "direction" to direction, "status" to status,
            "pay" to "零钱", "cp" to cp, "occurred_at" to occurred, "txn" to txn, "mer" to mer, "type" to type,
            "record_type" to recordType, "currency" to "CNY", "account_id" to "a", "index" to index)

    private fun fact(id: String, amount: String, recordType: String, account: String = "wallet",
        occurred: String = "2026-01-01 10:00:00", billSource: String = "alipay", source: String = "alipay",
        recordId: String = "") = RelationFact(id = id, amount = BigDecimal(amount), currency = "CNY",
        accountId = account, accountName = "现金", occurredAt = occurred, counterparty = "", note = "",
        recordType = recordType, billSource = billSource, source = source, recordId = recordId)

    private fun row(fact: RelationFact, vararg extra: Pair<String, Any?>) = linkedMapOf<String, Any?>(
        "id" to fact.id, "amount" to fact.signedAmount.toPlainString(), "bill_source" to fact.billSource,
        "source" to fact.source, "record_type" to fact.recordType, "account_id" to fact.accountId,
        "currency" to fact.currency, "txn_id" to fact.recordId).apply { putAll(extra) }

    private fun proposals(value: List<RelationProposal>) =
        value.map { Triple(it.primaryFactId, it.secondaryFactId, it.subtype) }

    @Test fun alipayOrderKeyMatchesPython() {
        assertTrue(CashPlatformRefund.alipayOrderMatch("ABC", "ABC"))
        val origin = "2025101322001112651418454050"
        assertTrue(CashPlatformRefund.alipayOrderMatch(origin + "_2991492337631815681_advance", origin))
        assertFalse(CashPlatformRefund.alipayOrderMatch(origin + "9", origin))
        val steam = "2023102222001312651442164096"
        assertTrue(CashPlatformRefund.alipayOrderMatch(steam + "*355627977", steam))
        assertEquals(1, CashPlatformRefund.alipayFindOriginIndex("B_x", listOf("A", "B", "C")))
        assertNull(CashPlatformRefund.alipayFindOriginIndex("Z", listOf("A", "B", "C")))
        assertNull(CashPlatformRefund.alipayFindOriginIndex("A", listOf("A", "A")))
    }

    @Test fun alipaySkipPredicatesMatchPython() {
        assertTrue(CashPlatformRefund.alipayIsUnpaidClosed("交易关闭", "不计收支", ""))
        assertTrue(CashPlatformRefund.alipayIsUnpaidClosed("已关闭", "收入", ""))
        assertFalse(CashPlatformRefund.alipayIsUnpaidClosed("交易关闭", "支出", "花呗"))
        assertFalse(CashPlatformRefund.alipayIsUnpaidClosed("交易关闭", "支出", ""))
        assertTrue(CashPlatformRefund.alipayIsFailedRepay("还款失败", "不计收支", ""))
        assertFalse(CashPlatformRefund.alipayIsFailedRepay("还款成功", "不计收支", "工行"))
        assertTrue(CashPlatformRefund.alipayIsPaidClosedExpense("交易关闭", "支出"))
    }

    @Test fun platformRefundRegressionMatchesPython() {
        val partial = listOf(
            fact("expense", "-47.90", "consumption", occurred = "2023-06-20 10:00:00", recordId = "2023062022001111"),
            fact("refund-a", "27.00", "refund", occurred = "2023-06-23 01:33:00",
                recordId = "2023062022001111_merchant_a"),
            fact("refund-b", "20.90", "refund", occurred = "2023-06-23 01:34:00",
                recordId = "2023062022001111_merchant_b"),
            fact("refund-over", "1.00", "refund", occurred = "2023-06-23 01:35:00",
                recordId = "2023062022001111_merchant_over"))
        val partialRows = partial.map { row(it, "txn_id" to it.recordId) }
        val partialPairs = CashRefundHardKey.match(partialRows, partial.associateBy { it.id })
            .map { Pair<String?, String?>(it.primaryFactId, it.secondaryFactId) }.toSet()
        assertEquals(setOf(Pair<String?, String?>("expense", "refund-a"),
            Pair<String?, String?>("expense", "refund-b")), partialPairs)

        val investment = listOf(
            fact("investment-out", "-300", "investment_out", occurred = "2025-01-28 14:10:00",
                recordId = "2025012822001111"),
            fact("refund", "300", "refund", occurred = "2025-01-28 14:14:00",
                recordId = "2025012822001111_300"))
        assertEquals(listOf(Triple("investment-out", "refund", "")),
            proposals(CashRefundHardKey.match(investment.map { row(it) }, investment.associateBy { it.id })))

        val transfer = listOf(
            fact("expense", "-50", "transfer_reversal", occurred = "2025-05-15 17:09:34",
                billSource = "wechat", source = "wechat", recordId = "wechat-out"),
            fact("refund", "50", "transfer_reversal", occurred = "2025-05-16 17:09:37",
                billSource = "wechat", source = "wechat", recordId = "wechat-return"))
        val transferRows = listOf(
            row(transfer[0], "status" to "对方已退还", "merchant_order_id" to "wechat-return",
                "txn_type" to "微信红包（单发）", "payment_method" to "零钱"),
            row(transfer[1], "status" to "已全额退款", "txn_type" to "微信红包-退款", "payment_method" to "零钱"))
        assertEquals(listOf(Triple("expense", "refund", "p2p_return")),
            proposals(CashRefundHardKey.match(transferRows, transfer.associateBy { it.id })))

        val unlinked = listOf(
            fact("expense", "-50", "transfer_reversal", occurred = "2025-05-15 17:09:34",
                billSource = "wechat", source = "wechat", recordId = "wechat-out"),
            fact("refund", "50", "transfer_reversal", occurred = "2025-05-16 17:09:37",
                billSource = "wechat", source = "wechat", recordId = "other-return"))
        val unlinkedRows = listOf(
            row(unlinked[0], "status" to "对方已退还", "txn_type" to "微信红包（单发）"),
            row(unlinked[1], "status" to "已全额退款", "txn_type" to "微信红包-退款"))
        assertEquals(emptyList(), CashPlatformRefund.pairWechatTransferReturns(unlinkedRows))
        assertEquals(emptyList(), CashRefundHardKey.match(unlinkedRows, unlinked.associateBy { it.id }))

        val reversal = listOf(
            fact("out", "-100", "transfer_reversal", occurred = "2026-01-01 10:00:00", recordId = "P2P"),
            fact("in", "100", "transfer_reversal", occurred = "2026-01-01 10:01:00",
                recordId = "P2P_advance"))
        val reversalRows = listOf(
            row(reversal[0], "record_id" to "P2P", "status" to "芝麻免押下单成功"),
            row(reversal[1], "record_id" to "P2P_advance", "status" to "解冻成功"))
        assertEquals(emptyList(), CashRefundHardKey.match(reversalRows, reversal.associateBy { it.id }))
    }

    @Test fun wechatDualRowMatchesPython() {
        assertEquals(BigDecimal("18.00"), CashPlatformRefund.wechatEmbeddedRefundAmount("已退款(¥18.00)"))
        assertEquals(BigDecimal("0.73"), CashPlatformRefund.wechatEmbeddedRefundAmount("已退款¥0.73"))
        assertNull(CashPlatformRefund.wechatEmbeddedRefundAmount("已全额退款"))

        assertTrue(CashPlatformRefund.wechatIsRefundOriginExpense("支出", "已全额退款"))
        assertTrue(CashPlatformRefund.wechatIsRefundOriginExpense("支出", "已退款(¥1.00)"))
        assertTrue(CashPlatformRefund.wechatIsRefundOriginExpense("支出", "对方已退还"))
        assertTrue(CashPlatformRefund.wechatIsRefundIncomeLeg("收入", "已退款¥1.00", "自助侠-退款"))
        assertFalse(CashPlatformRefund.wechatIsRefundIncomeLeg("收入", "已存入零钱", "转账"))

        val fullExpenses = listOf(linkedMapOf<String, Any?>("direction" to "支出", "status" to "已全额退款",
            "amount" to BigDecimal("-50"), "pay" to "零钱", "cp" to "北中医三院",
            "occurred_at" to "2024-03-11 07:36:21", "txn" to "4200a", "mer" to "m1", "type" to "商户消费"))
        val fullIncome = linkedMapOf<String, Any?>("direction" to "收入", "status" to "已全额退款",
            "amount" to BigDecimal("50"), "pay" to "零钱", "cp" to "北中医三院",
            "occurred_at" to "2024-03-11 09:17:05", "txn" to "5030b", "mer" to "", "type" to "北中医三院-退款")
        assertEquals(0, CashPlatformRefund.wechatFindExpenseForRefund(fullIncome, fullExpenses)?.expenseIndex)

        val isoExpenses = listOf(
            linkedMapOf<String, Any?>("direction" to "支出", "status" to "已全额退款", "amount" to BigDecimal("-9.90"),
                "pay" to "零钱", "cp" to "瑞幸咖啡", "occurred_at" to "2023-10-09T05:51:23+00:00",
                "txn" to "near", "mer" to "m-near", "type" to "商户消费"),
            linkedMapOf<String, Any?>("direction" to "支出", "status" to "已全额退款", "amount" to BigDecimal("-9.90"),
                "pay" to "零钱", "cp" to "瑞幸咖啡", "occurred_at" to "2023-10-09T05:50:23+00:00",
                "txn" to "far", "mer" to "m-far", "type" to "商户消费"))
        val isoIncome = linkedMapOf<String, Any?>("direction" to "收入", "status" to "已全额退款",
            "amount" to BigDecimal("9.90"), "pay" to "零钱", "cp" to "瑞幸咖啡",
            "occurred_at" to "2023-10-09T05:51:43+00:00", "txn" to "refund", "mer" to "", "type" to "瑞幸咖啡-退款")
        assertEquals(0, CashPlatformRefund.wechatFindExpenseForRefund(isoIncome, isoExpenses)?.expenseIndex)

        val partial = listOf(linkedMapOf<String, Any?>("direction" to "支出", "status" to "已退款(¥18.00)",
            "amount" to BigDecimal("-45"), "pay" to "零钱", "cp" to "味多美",
            "occurred_at" to "2024-06-27 19:51:18", "txn" to "e1", "mer" to "xc", "type" to "商户消费"))
        val partialIncome = linkedMapOf<String, Any?>("direction" to "收入", "status" to "已退款¥18.00",
            "amount" to BigDecimal("18"), "pay" to "零钱", "cp" to "味多美",
            "occurred_at" to "2024-07-28 01:04:54", "txn" to "r1", "mer" to "", "type" to "味多美-退款")
        assertNotNull(CashPlatformRefund.wechatFindExpenseForRefund(partialIncome, partial))

        val redpacket = listOf(linkedMapOf<String, Any?>("direction" to "支出", "status" to "已全额退款",
            "amount" to BigDecimal("-50"), "pay" to "零钱", "cp" to "发给某人",
            "occurred_at" to "2025-05-15 17:09:34", "txn" to "exp1",
            "mer" to "1000039801202505157184950651034", "type" to "微信红包（单发）"))
        val redpacketIncome = linkedMapOf<String, Any?>("direction" to "收入", "status" to "已全额退款",
            "amount" to BigDecimal("50"), "pay" to "零钱", "cp" to "/", "occurred_at" to "2025-05-16 17:09:37",
            "txn" to "1000039801202505157184950651034", "mer" to "", "type" to "微信红包-退款")
        assertNull(CashPlatformRefund.wechatFindExpenseForRefund(redpacketIncome, redpacket))

        val transferExpenses = listOf(linkedMapOf<String, Any?>("direction" to "支出", "status" to "对方已退还",
            "amount" to BigDecimal("-200"), "pay" to "零钱", "cp" to "是我小转转啊",
            "occurred_at" to "2025-05-10 16:35:30", "txn" to "e", "mer" to "m", "type" to "转账"))
        val transferIncome = linkedMapOf<String, Any?>("direction" to "收入", "status" to "已全额退款",
            "amount" to BigDecimal("200"), "pay" to "零钱", "cp" to "/", "occurred_at" to "2025-05-10 18:06:58",
            "txn" to "r", "mer" to "", "type" to "转账-退款")
        assertNull(CashPlatformRefund.wechatFindExpenseForRefund(transferIncome, transferExpenses))

        assertEquals(emptyList(), CashPlatformRefund.pairWechatRefunds(listOf(
            wechatRow("out", 0, "-200", "支出", "对方已退还", "收款人", "2025-05-10 16:35:30", "out", "",
                "转账", "transfer_reversal"),
            wechatRow("in", 1, "200", "收入", "已全额退款", "/", "2025-05-10 18:06:58", "in", "",
                "转账-退款", "transfer_reversal"))))

        val jdExpense = wechatRow("expense", 0, "-557.92", "支出", "已退款(¥470.72)", "京东",
            "2024-11-11 01:15:51", "e", "m", "商户消费", "consumption")
        val jd = listOf(
            jdExpense,
            wechatRow("r1", 1, "341.3", "收入", "已退款¥470.72", "京东商城平台商户",
                "2024-11-11 01:16:12", "r1", "", "京东商城平台商户-退款", "refund"),
            wechatRow("r2", 2, "32.56", "收入", "已退款¥470.72", "京东商城平台商户",
                "2024-11-11 01:16:17", "r2", "", "京东商城平台商户-退款", "refund"),
            wechatRow("r3", 3, "96.86", "收入", "已退款¥470.72", "京东商城平台商户",
                "2024-11-11 01:16:25", "r3", "", "京东商城平台商户-退款", "refund"))
        val pairs = CashPlatformRefund.pairWechatRefunds(jd)
        assertEquals(3, pairs.size)
        assertTrue(pairs.all { it.first == 0 })
    }
}
