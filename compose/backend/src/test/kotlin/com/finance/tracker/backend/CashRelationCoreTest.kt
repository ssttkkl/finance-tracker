package com.finance.tracker.backend

import java.math.BigDecimal
import kotlin.test.*
import kotlinx.serialization.json.*

class CashRelationCoreTest {
    private val golden: JsonObject by lazy {
        Json.parseToJsonElement(javaClass.getResourceAsStream("/python-cash-relation-core.json")!!
            .bufferedReader().use { it.readText() }).jsonObject
    }
    private fun fact(id: String, amount: String = "-10.00", recordType: String = "consumption",
        billSource: String = "alipay", source: String = "alipay", note: String = "备注",
        occurredAt: Any? = "2026-10-01T10:00:00+00:00", recordId: String = "ALI-1",
        sourceIdentity: String = "", payload: Map<String, Any?>? = null, parentId: String? = null,
        componentOrdinal: Int? = null, accountName: String = "账户") = RelationFact(
        id = id, amount = BigDecimal(amount), currency = "CNY", accountId = "a", accountName = accountName,
        occurredAt = occurredAt, counterparty = "商户", note = note, recordType = recordType,
        billSource = billSource, source = source, recordId = recordId, sourceIdentity = sourceIdentity,
        rawPayload = payload, parentId = parentId, componentOrdinal = componentOrdinal)

    private fun facts(): List<RelationFact> = listOf(
        fact("1"),
        fact("2", amount = "10.00", recordType = "refund"),
        fact("3", recordType = "refund", payload = mapOf("offset_role" to "expense")),
        fact("4", amount = "10.00", recordType = "refund", billSource = "wechat", source = "wechat",
            sourceIdentity = "named", occurredAt = "2026-10-01T10:00:00Z", recordId = "W-1"),
        fact("5", recordType = "transfer_out", billSource = "icbc_debit"),
        fact("6", amount = "10.00", recordType = "transfer_in", billSource = "ccb_debit"),
        fact("7", recordType = "fx_out", billSource = ""),
        fact("8", amount = "10.00", recordType = "fx_in", billSource = "", note = "支付宝换汇"),
        fact("9", recordType = "other", billSource = "", source = "", note = "工行储蓄卡 尾号1234", accountName = ""),
        fact("10", recordType = "other", billSource = "unknown", source = "", note = "支付宝", parentId = "1",
            componentOrdinal = 2, occurredAt = "2026-10-01 10:00:00"),
        fact("11", amount = "0.00", recordType = "refund", occurredAt = "bad-time", recordId = ""),
    )

    @Test fun relationCoreMatchesPython() {
        val values = facts()
        val cases = golden.getValue("cases").jsonArray
        for ((index, fact) in values.withIndex()) {
            val expected = cases[index].jsonObject
            assertEquals(expected.getValue("refund_in").jsonPrimitive.boolean, CashRelationCore.isRefundIn(fact), "refund $index")
            assertEquals(expected.getValue("original_refund").jsonPrimitive.boolean,
                CashRelationCore.isOriginalRefundExpense(fact), "original $index")
            assertEquals(expected.getValue("refund_candidate").jsonPrimitive.boolean,
                CashRelationCore.isRefundExpenseCandidate(fact), "candidate $index")
            assertEquals(expected.getValue("mirror_expense").jsonPrimitive.boolean,
                CashRelationCore.isPaymentMirrorExpense(fact), "mirror expense $index")
            assertEquals(expected.getValue("mirror_refund").jsonPrimitive.boolean,
                CashRelationCore.isPaymentMirrorRefund(fact), "mirror refund $index")
            assertEquals(expected.getValue("fx_out").jsonPrimitive.boolean, CashRelationCore.isFxOutRecord(fact), "fx out $index")
            assertEquals(expected.getValue("fx_in").jsonPrimitive.boolean, CashRelationCore.isFxInRecord(fact), "fx in $index")
            assertEquals(expected.getValue("transfer_out").jsonPrimitive.boolean, CashRelationCore.isTransferOutRecord(fact), "transfer out $index")
            assertEquals(expected.getValue("transfer_in").jsonPrimitive.boolean, CashRelationCore.isTransferInRecord(fact), "transfer in $index")
            assertEquals(expected.getValue("source_group").jsonPrimitive.content, CashRelationCore.sourceGroup(fact), "group $index")
            assertEquals(expected.getValue("reference").jsonPrimitive.content, CashRelationCore.stableFactReference(fact), "reference $index")
            assertEquals(expected.getValue("order").jsonArray.map { it.jsonPrimitive.content },
                CashRelationCore.stableFactOrderKey(fact), "order $index")
        }
        val compatibility = golden.getValue("compatibility").jsonArray.map { it.jsonPrimitive.boolean }
        assertEquals(listOf(
            CashRelationCore.compatiblePlatformScope(values[3], values[3].copy(id = "12")),
            CashRelationCore.compatiblePlatformScope(values[3], values[3].copy(id = "12", sourceIdentity = "other")),
            CashRelationCore.compatiblePlatformScope(values[0], values[3])), compatibility)
        val pairs = golden.getValue("pairs").jsonArray.map { pair ->
            pair.jsonArray.map { if (it is JsonPrimitive && !it.isString) it.int else it.jsonPrimitive.content }
        }
        assertEquals(pairs, listOf(
            CashRelationCore.orderedFactPair("10", "2"), CashRelationCore.orderedFactPair("2", "10"),
            CashRelationCore.orderedFactPair("010", "2"), CashRelationCore.orderedFactPair("abc", "abd"),
            CashRelationCore.orderedFactPair(null, ""), CashRelationCore.orderedFactPair("5", null),
            CashRelationCore.orderedFactPair("+5", "5")).map { listOf(it.first, it.second) })
        assertEquals(golden.getValue("business"), sourceJson(CashRelationCore.relationBusinessKey("w", "payment_mirror", "10", "2")))
        assertEquals(golden.getValue("open_leg"), sourceJson(CashRelationCore.openLegBusinessKey("w", "transfer_pair", "7")))
        assertEquals(golden.getValue("open_leg_row").jsonArray.map { it.jsonPrimitive.boolean }, listOf(
            CashRelationCore.isOpenLegRelation(mapOf("secondary_fact_id" to null)),
            CashRelationCore.isOpenLegRelation(mapOf("secondary_fact_id" to "")),
            CashRelationCore.isOpenLegRelation(emptyMap()),
            CashRelationCore.isOpenLegRelation(mapOf("secondary_fact_id" to "2"))))
        assertEquals(golden.getValue("top_k").jsonArray.map { it.jsonPrimitive.content },
            CashRelationCore.topKCandidateIds(listOf("3", "", "1", "3", "2"), k = 2))
    }
}
