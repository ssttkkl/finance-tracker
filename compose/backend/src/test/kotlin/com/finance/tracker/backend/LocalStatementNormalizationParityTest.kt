package com.finance.tracker.backend

import com.finance.tracker.business.domain.normalizeLocalCashStatement
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalStatementNormalizationParityTest {
    @Test fun productionLocalValuesMatchCurrentBackendNormalization() {
        val amounts = listOf("0", "0.00", "-0.00", ".000", "0E100", "-0e-30", "0_0.0_0", "0E2147483648",
            "0E-2147483648", "1.234567890123456789", "99999999999999999999.123456789012345678", "١٢.٣٠", "𐒠.𐒠𐒠", "NaN", "1E21", "1E-19")
        val times = listOf("2025-01-01 12:34:56", "1991-04-14T02:30:00", "1900-01-01T00:00:00", "bad")
        var position = 0
        for (amount in amounts) for (direction in listOf("收入", "支出", "不计收支")) for (time in times) {
            val payload = mapOf("交易时间" to time, "金额" to amount, "收/支" to direction, "交易订单号" to "order-${++position}",
                "交易对方" to "LINLEE林里 LİNLEE林里", "商品说明" to "  完整来源原文\n不裁剪  ",
                "交易分类" to "商户消费", "交易状态" to "支付成功", "收/付款方式" to "工商银行信用卡分期(𝟙期)(1234)", "对方账号" to "1234**5678")
            val document = CashStatementDocument("alipay", "csv:0", "v1", listOf(RawCashRow(1, payload)), null, null,
                CashSourceCoverage(null, null, false, "", emptyMap(), "v1"), 1, 1)
            assertEquals(CashStatementRowNormalization.normalizeDocument(document), normalizeLocalCashStatement(document), "$amount $direction $time")
        }
    }
}
