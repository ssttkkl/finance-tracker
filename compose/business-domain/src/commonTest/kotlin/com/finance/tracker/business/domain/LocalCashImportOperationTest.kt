package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*
import kotlin.test.*

class LocalCashImportOperationTest {
    private fun snapshot() = LedgerSnapshotDto("w", "9007199254740993", CASH_RULES_VERSION,
        LedgerFactsDto(listOf(LedgerAccountFactDto("10", "现金", "cash", true, JsonArray(listOf(JsonPrimitive("CNY"))), "9007199254740993")),
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "0"))
    private fun files(): List<ParsedImportFile> {
        val fields = mapOf<String, Any?>("amount" to "-12.50", "currency" to "CNY", "occurred_at" to "2026-08-15T02:00:00Z",
            "record_type" to "consumption", "record_subtype" to "not_applicable", "counterparty" to "商户", "note" to "",
            "source_payload" to mapOf("订单号" to "0001", "付款方式" to "支付宝余额"))
        return listOf(ParsedImportFile("f", "f".repeat(64), "parsed", ParsedCashDocument("alipay", "csv", "p",
            listOf(ParsedCashRow(1, fields, paymentMethod = "支付宝余额", providerId = "0001")), "owner", "支付宝",
            CashSourceCoverage(null, null, false, "", emptyMap(), "v"), 1, 1)))
    }
    @Test fun completeLocalCompositionPostsWithoutAPlatformRuleAdapter() {
        val files = files(); val snapshot = snapshot()
        val group = scanLocalCashFunding(files).groups.single().sourceAccountRef
        val selections = Json.parseToJsonElement("""[{"source_account_ref":"$group","account_id":"10","expected_account_revision":9007199254740993}]""")
        val result = prepareLocalCashImportOperations(files, snapshot, selections)
        assertEquals("create_posted", result.rows.single()["planned_action"])
        val operation = encodeLocalCashValue(result.operations.single()).jsonObject
        val values = operation.getValue("values").jsonObject
        assertEquals("-12.50", values.getValue("amount").jsonPrimitive.content)
        assertEquals("posted", values.getValue("state").jsonPrimitive.content)
        assertEquals(JsonPrimitive(0), values.getValue("components").jsonArray.single().jsonObject.getValue("ordinal"))
        assertTrue(values.getValue("record_id").jsonPrimitive.content.startsWith("provider-v1:"))
        assertEquals("9007199254740993", snapshot.ledgerSeq)
        assertEquals("-12.50", files.single().document!!.rows.single().fields!!["amount"])
        val deferred = prepareLocalCashImportOperations(files, snapshot, selections,
            Json.parseToJsonElement("""[{"row_ref":"f:r1","action":"defer"}]"""))
        assertEquals("create_draft", deferred.rows.single()["planned_action"])
        assertEquals("posting_incomplete", (deferred.operations.single().getValue("issues") as List<*>).single().let { (it as Map<*, *>)["code"] })
    }
}
