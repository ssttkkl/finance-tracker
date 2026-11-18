package com.finance.tracker.backend

import com.finance.tracker.business.domain.resolveLocalCashFunding
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals

class CashLocalFundingParityTest {
    private fun fields() = mapOf<String, Any?>("amount" to "-30.00", "currency" to "CNY", "occurred_at" to "2026-10-01T10:00:00Z",
        "record_type" to "consumption", "record_subtype" to "not_applicable", "source_payload" to mapOf("备注" to "  原始值  "))
    private fun scan(patch: Map<String, Any?>) = CashFundingScan(listOf(CashFundingGroup("g", "alipay", "payment_method", "owner", "余额", "余额",
        currencies = listOf("CNY"), rowRefs = listOf("f:r1"))), listOf(CashFundingRow("f:r1", fields() + patch, listOf(CashFundingPart("g", null)))))
    @Test fun productionLocalResolveUsesTheSamePostingDecisionsAsNative() {
        val accounts = Json.parseToJsonElement("""{"1":{"id":"1","active":true,"type":"cash","currencies":["CNY"]}}""")
        val patches = listOf(emptyMap(), mapOf("amount" to "NaN"), mapOf("amount" to "-0.0000000000000000001"),
            mapOf("currency" to "cny"), mapOf("currency" to null), mapOf("occurred_at" to null), mapOf("occurred_at" to "bad"),
            mapOf("occurred_at" to "2026-10-01T10:00:00"), mapOf("record_type" to "transfer_out"), mapOf("record_type" to false),
            mapOf("record_subtype" to "unknown"), mapOf("amount" to "-12345678901234567890.123456789012345678"))
        for (revision in listOf("3", "9007199254740993", "99999999999999999999999999999999999999", "1.0", "1e0", "true", "0", "-1", "null")) {
            val choices = Json.parseToJsonElement("""{"g":{"account_id":"1","expected_account_revision":$revision}}""")
            for (patch in patches) {
                val scan = scan(patch)
                val native = runCatching { JsonArray(CashImportFunding.resolve(scan, cashSourceValue(choices), cashSourceValue(accounts)).map { it.json() }) }
                val local = runCatching { JsonArray(resolveLocalCashFunding(scan, choices, accounts).map { it.json(::sourceJson) }) }
                val message = "$revision / $patch"
                assertEquals(native.exceptionOrNull()?.let { it::class.qualifiedName to it.message },
                    local.exceptionOrNull()?.let { it::class.qualifiedName to it.message }, message)
                if (native.isSuccess) assertEquals(native.getOrThrow(), local.getOrThrow(), message)
            }
        }
    }
}
