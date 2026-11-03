package com.finance.tracker.backend

import java.math.BigDecimal
import kotlin.test.*
import kotlinx.serialization.json.*

/**
 * 原 `tests/test_transaction_relations_refund.py` 中使用 `evaluate_refund_offset` 的 34 个定义。
 *
 * 导出器以真实 Python 测试运行并记录每次调用的 FactView 与提案结果；这里用相同输入回放
 * `CashRefundMatch.evaluate`，逐字段比较返回提案，不重新解释旧断言。
 */
class RefundOffsetOriginalTest {
    private val cases: JsonArray by lazy {
        Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/original-refund-offset.json"))
            .bufferedReader().use { it.readText() }).jsonArray
    }

    @Test fun refundOffsetDefinitionsMatchPython() {
        val definitions = cases.map { it.jsonObject }
        assertEquals(34, definitions.size)
        assertEquals(34, definitions.map { it.getValue("source").jsonPrimitive.content }.toSet().size)
        assertEquals(36, definitions.sumOf { it.getValue("calls").jsonArray.size })
        for (definition in definitions) {
            val source = definition.getValue("source").jsonPrimitive.content
            assertTrue(definition.getValue("assertions").jsonArray.isNotEmpty(), source)
            for (call in definition.getValue("calls").jsonArray) {
                val value = call.jsonObject
                val seed = toFact(value.getValue("seed").jsonObject)
                val candidates = value.getValue("candidates").jsonArray.map { toFact(it.jsonObject) }
                val remaining = value["remaining"]?.takeIf { it !is JsonNull }?.jsonObject
                    ?.mapValues { BigDecimal(it.value.jsonPrimitive.content) }
                val events = value["events"]?.takeIf { it !is JsonNull }?.jsonObject
                    ?.mapValues { it.value.jsonPrimitive.content }
                val proposal = CashRefundMatch.evaluate(seed, candidates, remaining.orEmpty(), events.orEmpty())
                val actual = proposal?.let { sourceJson(it.json()) } ?: JsonNull
                assertEquals(value.getValue("expected"), actual, "$source ${seed.id}")
            }
        }
        assertEquals("美团订单-ABC", CashRefundSignals.stripRefundDescriptionPrefix("退款-美团订单-ABC"))
    }

    private fun toFact(value: JsonObject): RelationFact = RelationFact(
        id = value.getValue("id").jsonPrimitive.content,
        amount = BigDecimal(value.getValue("amount").jsonPrimitive.content),
        currency = value.getValue("currency").jsonPrimitive.content,
        accountId = value.getValue("account_id").jsonPrimitive.content,
        accountName = value.getValue("account_name").jsonPrimitive.content,
        accountType = value.getValue("account_type").jsonPrimitive.content,
        occurredAt = value.getValue("occurred_at").jsonPrimitive.content,
        counterparty = value.getValue("counterparty").jsonPrimitive.content,
        counterpartyAccount = value.getValue("counterparty_account").jsonPrimitive.content,
        counterpartyAccountAttrs = value.getValue("counterparty_account_attrs").jsonArray.map { it.jsonPrimitive.content },
        paymentMethod = value.getValue("payment_method").jsonPrimitive.content,
        note = value.getValue("note").jsonPrimitive.content,
        recordType = value.getValue("record_type").jsonPrimitive.content,
        recordSubtype = value.getValue("record_subtype").jsonPrimitive.content,
        billSource = value.getValue("bill_source").jsonPrimitive.content,
        source = value.getValue("source").jsonPrimitive.content,
        factType = value.getValue("fact_type").jsonPrimitive.content,
        deleted = value.getValue("deleted").jsonPrimitive.boolean,
        rawRecordId = value["raw_record_id"]?.jsonPrimitive?.contentOrNull,
        sourceIdentity = value.getValue("source_identity").jsonPrimitive.content,
        recordId = value.getValue("record_id").jsonPrimitive.content,
        rawPayload = value["raw_payload"]?.takeIf { it !is JsonNull }?.let { jsonToAny(it) as Map<String, Any?> },
        relationMetadata = value["relation_metadata"]?.takeIf { it !is JsonNull }?.let {
            jsonToAny(it) as Map<String, Any?>
        },
        parentId = value["parent_id"]?.jsonPrimitive?.contentOrNull,
        componentOrdinal = value["component_ordinal"]?.jsonPrimitive?.intOrNull,
        cashGranularity = value.getValue("cash_granularity").jsonPrimitive.content,
    )

    private fun jsonToAny(value: JsonElement): Any? = when (value) {
        is JsonNull -> null
        is JsonObject -> value.mapValues { jsonToAny(it.value) }
        is JsonArray -> value.map { jsonToAny(it) }
        is JsonPrimitive -> when {
            value.isString -> value.content
            value.booleanOrNull != null -> value.boolean
            value.longOrNull != null -> value.long
            value.doubleOrNull != null -> value.double
            else -> value.content
        }
    }
}
