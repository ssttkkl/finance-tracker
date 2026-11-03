package com.finance.tracker.backend

import kotlin.test.*

class ConvertNormalizeOriginalTest {
    @Test fun crossBorderRemittanceIsTransferButFxPurchaseStaysFx() {
        for ((summary, amount, expected) in listOf(Triple("跨境汇款", "-100.00", "transfer_out"),
            Triple("跨境汇款", "100.00", "transfer_in"), Triple("个人购汇", "-100.00", "fx_out"),
            Triple("预约购汇", "100.00", "fx_in"))) {
            assertEquals(expected, CashRecordClassification.classify("icbc_debit", mapOf("summary" to summary, "amount" to amount)).first)
        }
    }
    @Test fun meituanAppPrefixStripped() { assertEquals("麦当劳", stripPlatformPrefix("美团App麦当劳")) }
    @Test fun elemePrefixStripped() { assertEquals("麦当劳", stripPlatformPrefix("饿了么麦当劳")) }
    @Test fun dianpingPrefixStripped() { assertEquals("商户A", stripPlatformPrefix("大众点评商户A")) }
    @Test fun gaodeGroupPrefixStripped() { assertEquals("商户B", stripPlatformPrefix("高德团购商户B")) }
    @Test fun prefixNoMatchUnchanged() { assertEquals("麦当劳", stripPlatformPrefix("麦当劳")) }
    @Test fun prefixEmptyReturnsEmpty() { assertEquals("", stripPlatformPrefix("")) }
    @Test fun onlyPrefixReturnsOriginal() { assertEquals("美团App", stripPlatformPrefix("美团App")) }
    @Test fun nonO2oPrefixUnchanged() { assertEquals("京东支付-商家", stripPlatformPrefix("京东支付-商家")) }
    @Test fun brandMatchExtractLeftover() {
        val (cp, desc) = normalizeSourceCounterparty("安尔雅家具京东自营旗舰店", "")
        assertEquals("京东", cp); assertEquals("安尔雅家具", desc)
    }
    @Test fun brandMatchThroughO2oPrefix() {
        val (cp, desc) = normalizeSourceCounterparty("美团App麦当劳麦咖啡(北京武圣", "")
        assertEquals("麦当劳", cp); assertEquals("麦咖啡(北京武圣", desc)
    }
    @Test fun brandMatchPreservesOriginalDescription() {
        val (cp, desc) = normalizeSourceCounterparty("luckin coffee", "订单付款")
        assertEquals("瑞幸咖啡", cp); assertEquals("订单付款", desc)
    }
    @Test fun o2oPrefixStripWithoutBrand() {
        val (cp, _) = normalizeSourceCounterparty("美团App渝八两重庆鸡公煲", "")
        assertEquals("渝八两重庆鸡公煲", cp)
    }
    @Test fun rideBeforePaymentSpecialCase() {
        val (cp, desc) = normalizeSourceCounterparty("先骑后付", "")
        assertEquals("美团", cp); assertEquals("先骑后付", desc)
    }
    @Test fun counterpartyNoMatchUnchanged() {
        val (cp, desc) = normalizeSourceCounterparty("先享后付订单到期扣款", "")
        assertEquals("先享后付订单到期扣款", cp); assertEquals("", desc)
    }
    @Test fun noMatchWithDescription() {
        val (cp, desc) = normalizeSourceCounterparty("北京屏芯科技有限公司", "工资")
        assertEquals("北京屏芯科技有限公司", cp); assertEquals("工资", desc)
    }
    @Test fun emptyCounterparty() {
        val (cp, desc) = normalizeSourceCounterparty("", "")
        assertEquals("", cp); assertEquals("", desc)
    }
    @Test fun paymentPrefixStrippedBeforeBrandMatch() {
        val (cp, _) = normalizeSourceCounterparty("财付通-麦当劳", "")
        assertEquals("麦当劳", cp)
    }
    @Test fun brandKeywordIsEntireCounterparty() {
        val (cp, desc) = normalizeSourceCounterparty("麦当劳", "")
        assertEquals("麦当劳", cp); assertEquals("", desc)
    }
}
