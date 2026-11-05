package com.finance.tracker.backend

import java.math.BigDecimal
import kotlin.test.*
import kotlinx.serialization.json.*

class IcbcDebitOriginalTest {
    private val columns = listOf("交易日期", "账号", "储种", "序号", "币种", "钞汇", "摘要", "地区",
        "收入/支出金额", "余额", "对方户名", "对方账号", "渠道")

    @Test fun originalDebitRowsUseTheActualNormalizationPipeline() {
        val cases = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/original-icbc-debit-assertions.json"))
            .bufferedReader().use { it.readText() }).jsonArray
        assertEquals(11, cases.size)
        assertEquals(11, cases.map { it.jsonObject.getValue("source") }.toSet().size)
        assertEquals(45, cases.sumOf { it.jsonObject.getValue("checks").jsonArray.size })
        for (case in cases) {
            val item = case.jsonObject
            val source = item.getValue("source").jsonPrimitive.content
            val values = item.getValue("row").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(13, values.size, source)
            val payload = columns.zip(values).toMap()
            val before = payload.toMap()
            val owner = item.getValue("owner").jsonPrimitive.content
            val document = CashStatementDocument("icbc_debit", "pdf:0", "icbc-pdf-tables-v1",
                listOf(RawCashRow(1, payload, documentPosition = "p1:t0:r1")), owner, owner,
                CashSourceCoverage(null, null, false, "", emptyMap(), "icbc-pdf-coverage-v1"), 1, 1)
            val observed = mutableListOf<Pair<Int, Map<String, Any?>>>()
            val parsed = CashStatementRowNormalization.normalizeIcbcDocument(document) { position, record ->
                observed += position to record
            }
            assertEquals(1, observed.size, source)
            assertEquals(1, observed.single().first, source)
            val native = observed.single().second
            for (check in item.getValue("checks").jsonArray) assertEquals(true, evaluate(check, native), "$source $check")
            val row = parsed.rows.single()
            assertNull(row.errorCode, source); assertEquals(emptyList(), row.issueCodes, source)
            val fields = assertNotNull(row.fields, source)
            assertEquals(0, assertIs<BigDecimal>(native["amount"]).compareTo(BigDecimal(fields.getValue("amount").toString())), source)
            assertEquals(native["currency"], fields["currency"], source)
            assertEquals(native["counterparty"], fields["counterparty"], source)
            assertEquals(native["payment_method"], row.paymentMethod, source)
            assertEquals(values[6], fields["note"], source)
            assertEquals(before, fields["source_payload"], source)
            assertEquals(owner, parsed.sourceAccountIdentifier, source)
            assertEquals(owner, row.sourceExplanation["_source_account_identifier"], source)
            assertEquals(before, payload, source)
            assertNotSame(payload, native["_source_payload"], source)
            @Suppress("UNCHECKED_CAST")
            val captured = native as MutableMap<String, Any?>
            assertFailsWith<UnsupportedOperationException> { captured["counterparty"] = "changed" }
            @Suppress("UNCHECKED_CAST")
            val capturedPayload = native["_source_payload"] as MutableMap<String, String>
            assertFailsWith<UnsupportedOperationException> { capturedPayload["摘要"] = "changed" }
            assertEquals(before, payload, source)
        }
    }

    private fun evaluate(expression: JsonElement, record: Map<String, Any?>): Any? {
        val node = expression.jsonObject
        return when (node.getValue("kind").jsonPrimitive.content) {
            "record" -> record
            "field" -> record.getValue(node.getValue("key").jsonPrimitive.content)
            "decimal" -> BigDecimal(node.getValue("value").jsonPrimitive.content)
            "literal" -> node.getValue("value").let { value ->
                if (value == JsonNull) null else if (value.jsonPrimitive.isString) value.jsonPrimitive.content
                else value.jsonPrimitive.boolean
            }
            "or" -> node.getValue("values").jsonArray.any { evaluate(it, record) == true }
            "compare" -> {
                val left = evaluate(node.getValue("left"), record)
                val right = evaluate(node.getValue("right"), record)
                when (node.getValue("operator").jsonPrimitive.content) {
                    "Eq" -> if (left is BigDecimal && right is BigDecimal) left.compareTo(right) == 0 else left == right
                    "Is" -> left == right
                    "IsNot" -> left != right
                    "NotIn" -> (left as String) !in (right as String)
                    else -> error("Unexpected original operator")
                }
            }
            else -> error("Unexpected original expression")
        }
    }
}
