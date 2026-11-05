package com.finance.tracker.business.domain

import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days

class CashImportCandidateQueryTest {
    private fun operation(date: String = "2026-10-02T10:00:00Z", amount: String = "-5.00",
        source: String = "alipay", scope: String? = "owner", explanation: JsonElement = JsonObject(emptyMap()),
        refs: List<String> = emptyList(), accounts: List<String> = listOf("9201"), type: String = "consumption") = buildJsonObject {
        putJsonObject("values") {
            put("source_type", source); put("record_type", type); put("amount", amount); put("occurred_at", date)
            put("components", JsonArray(accounts.map { buildJsonObject { put("account_id", it) } }))
        }
        put("source_scope_key", scope?.let(::JsonPrimitive) ?: JsonNull)
        put("row_refs", JsonArray(refs.map(::JsonPrimitive))); put("source_explanation", explanation)
    }

    @Test fun windowsMergeInclusivelyAndAmountsUseNumericSign() {
        val first = parsePythonCashTimestamp("2026-10-02T10:00:00Z")
        val last = first + 400.days
        val input = JsonArray(listOf(operation(), operation(formatPythonCashTimestamp(first + 122.days)),
            operation(formatPythonCashTimestamp(last))))
        val query = prepareLocalCashImportCandidateQuery(input)
        assertEquals(listOf(first - 61.days to first + 183.days, last - 61.days to last + 61.days), query.windows)
        assertEquals(setOf("consumption", "refund", "investment_out"), query.recordTypes)
        assertTrue(query.expenseOnly)
        for (amount in listOf("-0.00", "0", "5", "0E-18"))
            assertFalse(prepareLocalCashImportCandidateQuery(JsonArray(listOf(operation(amount = amount)))).expenseOnly, amount)
        assertFalse(prepareLocalCashImportCandidateQuery(JsonArray(listOf(operation(type = "refund")))).expenseOnly)
        val empty = prepareLocalCashImportCandidateQuery(JsonArray(emptyList()))
        assertEquals(emptyList(), empty.windows); assertEquals(emptyList(), empty.hardKeys)
    }

    @Test fun hardKeysKeepScopePrecedenceExplicitNullAndAccountGrouping() {
        val source = SourceOccurrence("alipay", null, "a".repeat(64), "v1", "table", 0, emptyMap(), providerId = "ALI-1")
        val explicitNull = JsonObject(mapOf("txn_id" to JsonNull))
        val input = JsonArray(listOf(
            operation(refs = listOf("row"), accounts = listOf("9007199254740993", "9201", "9201")),
            operation(refs = listOf("row"), accounts = listOf("9201", "9007199254740993"), explanation = explicitNull),
            operation(explanation = JsonObject(mapOf("txn_id" to JsonPrimitive(" ROOT_1*2 "),
                "merchant_order_id" to JsonPrimitive(" 商户单号 ")))),
            operation(source = "wechat", scope = "wechat-owner", explanation = JsonObject(mapOf("txn_id" to JsonPrimitive("WX_1*2")))),
            operation(source = "bank", explanation = JsonObject(mapOf("txn_id" to JsonPrimitive("BANK"))))))
        val original = input.toString()
        val queries = prepareLocalCashImportCandidateQuery(input, mapOf("row" to source)).hardKeys
        assertEquals(3, queries.size)
        val fallback = queries[0]
        assertNull(fallback.scope); assertEquals(listOf(9201L, 9007199254740993L), fallback.accounts)
        assertEquals(setOf("ALI-1"), fallback.transactionKeys)
        val ali = queries[1]
        assertEquals("owner", ali.scope); assertEquals(setOf("ROOT_1*2", "ROOT", "ROOT_1"), ali.expandedTransactionKeys)
        assertEquals(setOf("ROOT_1*2", "ROOT", "ROOT_1", "商户单号"), ali.allKeys)
        assertEquals(setOf("WX_1*2"), queries[2].expandedTransactionKeys)
        assertEquals(original, input.toString())
        val none = prepareLocalCashImportCandidateQuery(JsonArray(listOf(operation(refs = listOf("row"), explanation = explicitNull))), mapOf("row" to source))
        assertTrue(none.hardKeys.single().transactionKeys.isEmpty())
    }

    @Test fun keyWhitespaceMatchesTheNativeTrimBoundary() {
        val whitespace = listOf(0x9, 0xa, 0xb, 0xc, 0xd, 0x1c, 0x1d, 0x1e, 0x1f, 0x20, 0xa0, 0x1680,
            *IntArray(11) { 0x2000 + it }.toTypedArray(), 0x2028, 0x2029, 0x202f, 0x205f, 0x3000)
        for (code in whitespace + listOf(0x85, 0x180e, 0x200b, 0xfeff)) {
            val raw = code.toChar() + "ID" + code.toChar()
            val query = prepareLocalCashImportCandidateQuery(JsonArray(listOf(operation(
                explanation = JsonObject(mapOf("txn_id" to JsonPrimitive(raw)))))))
            assertEquals(setOf(if (code in whitespace) "ID" else raw), query.hardKeys.single().transactionKeys,
                "U+" + code.toString(16))
        }
    }

    @Test fun explanationSelectionUsesPresenceAndHardKeyRequestsKeepEmptyAccounts() {
        val fallback = operation(explanation = JsonNull, accounts = emptyList()) + mapOf(
            "matching_source_explanation" to JsonObject(mapOf("txn_id" to JsonPrimitive("fallback"))))
        val selected = prepareLocalCashImportCandidateQuery(JsonArray(listOf(JsonObject(fallback)))).hardKeys.single()
        assertEquals(setOf("fallback"), selected.transactionKeys); assertTrue(selected.accounts.isEmpty())
        val empty = JsonObject(fallback + ("source_explanation" to JsonObject(emptyMap())))
        assertTrue(prepareLocalCashImportCandidateQuery(JsonArray(listOf(empty))).hardKeys.single().allKeys.isEmpty())
    }
}
