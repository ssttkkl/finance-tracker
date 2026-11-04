package com.finance.tracker.business.domain

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalCashFundingResolutionTest {
    private fun fields() = linkedMapOf<String, Any?>("amount" to "-30.00", "currency" to "CNY",
        "occurred_at" to "2026-10-01T10:00:00Z", "record_type" to "consumption", "record_subtype" to "not_applicable",
        "source_payload" to linkedMapOf("备注" to mutableListOf("原文")))
    private fun scan(fields: Map<String, Any?> = fields()) = CashFundingScan(listOf(
        CashFundingGroup("g", "alipay", "payment_method", "owner", "余额", "余额", currencies = listOf("CNY"), rowRefs = listOf("f:r1"))),
        listOf(CashFundingRow("f:r1", fields, listOf(CashFundingPart("g", null)))))
    private fun choice(revision: String) = Json.parseToJsonElement("""{"g":{"account_id":"1","expected_account_revision":$revision}}""")
    private val accounts = Json.parseToJsonElement("""{"1":{"id":"1","active":true,"type":"cash","currencies":["CNY"]}}""")
    @Test fun revisionsKeepTheirIntegerTokenAndGeneratedOrdinalHasIntegerJson() {
        for (revision in listOf("3", "9007199254740993", "99999999999999999999999999999999999999")) {
            val result = resolveLocalCashFunding(scan(), choice(revision), accounts).single()
            assertTrue(result.ready)
            val json = result.json { sourceJson(it, { null }, ::pythonFloatText) }
            assertEquals(JsonPrimitive(0), json.getValue("fields").jsonObject.getValue("components").jsonArray.single().jsonObject["ordinal"])
        }
        for (revision in listOf("1.0", "1e0", "true", "\"3\"", "0", "-1", "null"))
            assertFailsWith<CashFundingInputError>(revision) { resolveLocalCashFunding(scan(), choice(revision), accounts) }
    }
    @Test fun postingActuallyChecksDateTypesAndRequiredFieldsWithoutChangingInput() {
        for (patch in listOf(mapOf("occurred_at" to "bad"), mapOf("currency" to "cny"),
            mapOf("record_type" to "transfer_out"))) {
            val input = fields() + patch
            val result = resolveLocalCashFunding(scan(input), choice("3"), accounts).single()
            assertFalse(result.ready)
            assertTrue(result.issueCodes.any { it == "invalid_field" || it == "invalid_record_type" })
            assertFalse("components" in input)
        }
        val result = resolveLocalCashFunding(scan(fields() + ("occurred_at" to null)), choice("3"), accounts).single()
        assertTrue("incomplete_record" in result.issueCodes)
        val input = fields()
        val resolved = resolveLocalCashFunding(scan(input), choice("3"), accounts).single()
        @Suppress("UNCHECKED_CAST") val edited = (resolved.fields["source_payload"] as Map<*, *>)["备注"] as MutableList<String>
        edited[0] = "已修改"
        assertEquals(listOf("原文"), (input["source_payload"] as Map<*, *>)["备注"])
    }
    @Test fun postingComponentBridgeRejectsFloatOrdinalsAndChecksConservation() {
        fun record(ordinal: Any?, amount: String = "-30.00") = fields() + mapOf("account_id" to "1", "cash_granularity" to "atomic",
            "components" to listOf(mapOf("account_id" to "1", "amount" to amount, "currency" to "CNY", "ordinal" to ordinal)))
        LocalCashFundingPosting.validate(record(SourceJsonInteger(0)))
        for (ordinal in listOf(Json.parseToJsonElement("0.0"), JsonPrimitive("0"), JsonPrimitive(true), Json.parseToJsonElement("2147483648")))
            assertEquals("invalid_field", assertFailsWith<CashPolicyError> { LocalCashFundingPosting.validate(record(ordinal)) }.code)
        assertEquals("component_total_mismatch", assertFailsWith<CashPolicyError> {
            LocalCashFundingPosting.validate(record(SourceJsonInteger(0), "-29")) }.code)
    }
}
