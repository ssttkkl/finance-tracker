package com.finance.tracker.backend

import kotlin.test.*

class CashSourceEffectsTest {
    private fun effects(source: String, payload: Map<String, Any?>?, amount: String? = "12", patch: Map<String, Any?>? = null,
        protected: Set<String> = emptySet(), explanation: Any? = emptyMap<String, Any?>(), note: String = "旧") =
        prepareCashSourceEffects(mapOf("note" to note, "amount" to amount?.let(CashAmount::parse), "source_payload" to payload),
            patch ?: mapOf("source_payload" to payload), source, explanation, protected)

    @Test fun sourcePatchReplacesNoteAndStaleExplanationWithoutChangingFinancialFields() {
        val payload = mapOf("商品" to "  新商品\n完整保留  ", "备注" to "其他备注", "交易单号" to "new-id", "当前状态" to "支付成功", "收/支" to "收入")
        val original = mapOf("note" to "旧原文", "amount" to CashAmount.parse("12"), "record_type" to "refund", "record_subtype" to "not_applicable", "source_payload" to payload)
        val before = original.toMap() + ("source_payload" to payload.toMap())
        val result = prepareCashSourceEffects(original, mapOf("source_payload" to payload), "wechat",
            mapOf("txn_id" to "old-id", "_refund_signal" to "wechat_status", "platform_status" to "退款成功", "offset_group" to "old-choice", "_source_account_identifier" to "trusted-owner"))
        assertEquals("  新商品\n完整保留  ", result.values["note"])
        assertEquals(CashAmount.parse("12"), result.values["amount"])
        assertEquals("refund", result.values["record_type"])
        assertEquals(payload, result.values["source_payload"])
        assertEquals("new-id", result.explanation!!["txn_id"])
        assertEquals("支付成功", result.explanation["platform_status"])
        assertEquals("trusted-owner", result.explanation["_source_account_identifier"])
        assertFalse("_refund_signal" in result.explanation)
        assertFalse("offset_group" in result.explanation)
        assertEquals(before, original)
    }

    @Test fun emptyWechatProductDoesNotFallBackToRemark() {
        val payload = mapOf("商品" to "", "备注" to "不回退此备注")
        val result = effects("wechat", payload, amount = null)
        assertEquals("", result.values["note"])
        assertEquals(payload, result.values["source_payload"])
    }

    @Test fun sourceRulesNormalizeOnlyCompletePlatformAccountLabels() {
        for ((source, field, raw, expected) in listOf(
            listOf("alipay", "收/付款方式", "云闪付-中国工商银行储蓄卡（尾号 3697）", "工商银行储蓄卡(3697)"),
            listOf("wechat", "支付方式", "云闪付-中国工商银行信用卡（尾号 9166）", "工商银行信用卡(9166)"),
        )) {
            val payload = mapOf(field to raw)
            val result = effects(source, payload)
            assertEquals(payload, result.values["source_payload"])
            assertEquals(expected, result.explanation!!["payment_method"])
        }
    }

    @Test fun explicitNoteAndProtectionHavePriority() {
        for ((protected, patch, expected) in listOf(
            Triple(emptySet<String>(), mapOf("note" to ""), ""), Triple(setOf("note"), emptyMap(), "人工值"),
            Triple(setOf("note"), mapOf("note" to "本次明确值"), "本次明确值"), Triple(emptySet(), emptyMap(), ""),
        )) assertEquals(expected, effects("alipay", emptyMap(), amount = null, patch = mapOf("source_payload" to emptyMap<String, Any?>()) + patch,
            protected = protected, note = patch["note"] ?: "人工值").values["note"])
    }

    @Test fun arbitraryJsonCannotForgeIdentityOrRefundEvidence() {
        for (payload in listOf(null, emptyMap(), mapOf("摘要" to null, "交易场所" to listOf("退款"), "对方户名" to mapOf("value" to "退货")),
            mapOf("摘要" to "退货", "摘\n要" to "消费", "备注" to 42))) {
            val result = effects("icbc_credit", payload, amount = null,
                explanation = mapOf("file_account_key" to "trusted", "card_number" to "1234", "_source_account_identifier" to "trusted", "source_display_name" to "工商银行信用卡", "_is_refund" to true, "offset_type" to "merchant_refund"))
            assertEquals("trusted", result.explanation!!["_source_account_identifier"])
            assertEquals("1234", result.explanation["card_number"])
            assertFalse("_is_refund" in result.explanation)
            assertFalse("offset_type" in result.explanation)
        }
    }

    @Test fun pdfColumnNormalizationPreservesRawValuesAndTrustedIdentity() {
        val payload = mapOf("备\n注" to "  原始\n备注  ", "交易卡号" to "untrusted-replacement", "渠道" to "新渠道", "摘要" to "退款")
        val result = effects("icbc_debit", payload, explanation = mapOf("_source_account_identifier" to "trusted-owner", "file_account_key" to "trusted-owner", "card_number" to "1234"))
        assertEquals("  原始\n备注  ", result.values["note"])
        assertEquals(payload, result.values["source_payload"])
        assertEquals("trusted-owner", result.explanation!!["_source_account_identifier"])
        assertEquals("新渠道", result.explanation["payment_method"])
        assertEquals(true, result.explanation["_is_refund"])
        assertEquals("icbc_debit_refund", result.explanation["_refund_signal"])
    }

    @Test fun noteOnlyLeavesExplanationUnchangedAndTypeOnlyDropsCrossRowState() {
        val evidence = mapOf("_is_refund" to true, "platform_status" to "退款成功", "offset_role" to "refund", "offset_group" to "old")
        val values = mapOf("note" to "人工", "source_payload" to mapOf("当前状态" to "支付成功"), "record_type" to "transfer")
        assertNull(prepareCashSourceEffects(values, mapOf("note" to "人工"), "wechat", evidence).explanation)
        val result = prepareCashSourceEffects(values, mapOf("record_type" to "transfer"), "wechat", evidence)
        assertEquals(values, result.values)
        assertEquals(mapOf("_is_refund" to true, "platform_status" to "退款成功"), result.explanation)
    }

    @Test fun amountPatchRefreshesDirectionEvidenceWithoutChangingNote() {
        for (source in listOf("ccb_debit", "icbc_debit")) for ((amount, expected) in listOf("12" to true, "-12" to false)) {
            val result = effects(source, mapOf("摘要" to "退款"), amount, mapOf("amount" to amount),
                explanation = mapOf("_refund_signal" to "old-positive"), note = "已有原文")
            assertEquals("已有原文", result.values["note"])
            assertEquals(expected, "_refund_signal" in result.explanation!!)
        }
    }

    @Test fun allSourcesUseTheirNativeSingleRowRules() {
        data class Case(val source: String, val payload: Map<String, String>, val amount: String, val expected: Map<String, Any>)
        val cases = listOf(
            Case("alipay", mapOf("交易分类" to "退款", "交易状态" to "退款成功", "收/支" to "不计收支", "交易订单号" to "p1", "商家订单号" to "m1", "收/付款方式" to "余额宝", "商品说明" to "来源原文"), "12", mapOf("_refund_signal" to "alipay_status", "payment_method" to "余额宝", "txn_id" to "p1")),
            Case("wechat", mapOf("交易类型" to "零钱提现", "当前状态" to "提现成功", "收/支" to "/", "支付方式" to "尾号9999"), "-12", mapOf("payment_method" to "零钱", "_wechat_direction" to "/")),
            Case("ccb_debit", mapOf("摘要" to "退款", "交易地点/附言" to "财付通-微信支付-合成商户", "对方账号与户名" to "synthetic-account/原商户"), "12", mapOf("_ccb_location_cp" to "合成商户", "_ccb_acct_cp" to "原商户", "_refund_signal" to "ccb_debit_refund", "payment_method" to "微信支付")),
            Case("icbc_asia", mapOf("摘要" to "转入", "業務類型" to "转账", "交易場所" to "来源地点", "對方戶名" to "原商户"), "12", mapOf("summary" to "转入", "txn_type" to "转账", "location" to "来源地点", "_raw_cp" to "原商户", "payment_method" to "工银亚洲活期账户(1234)")),
            Case("icbc_credit", mapOf("摘要" to "退货", "对方户名" to "合成商户", "交易场所" to "原始地点"), "12", mapOf("offset_type" to "merchant_refund", "_is_refund" to true, "refund_signal" to "icbc_credit_return")),
            Case("icbc_debit", mapOf("摘要" to "撤销交易", "对方户名" to "原商户", "渠道" to "网银"), "-12", mapOf("_debit_offset_type" to "reversal", "_is_refund" to false, "_is_reversal" to true, "payment_method" to "网银")),
        )
        for (case in cases) {
            val result = effects(case.source, case.payload, case.amount, explanation = mapOf("card_number" to "1234"))
            case.expected.forEach { (key, value) -> assertEquals(value, result.explanation!![key], "${case.source} $key") }
        }
    }
}
