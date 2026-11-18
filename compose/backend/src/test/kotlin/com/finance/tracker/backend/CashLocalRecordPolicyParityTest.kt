package com.finance.tracker.backend

import com.finance.tracker.business.domain.encodeLocalCashValue
import com.finance.tracker.business.domain.prepareLocalCashRecord
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals

class CashLocalRecordPolicyParityTest {
    private val base = Json.parseToJsonElement("""{"state":"posted","record_origin":"import","amount":"-20.00","currency":"CNY","occurred_at":"2026-08-01T00:00:00Z","account_id":"41","record_id":"source-1","source_type":"wechat","cash_granularity":"atomic","components":[{"component_id":"501","account_id":"41","amount":"-20.00","currency":"CNY","ordinal":0}],"counterparty":"商户","counterparty_account":"1234","counterparty_account_attrs":["tail"],"note":"原始备注","category_id":null,"source_payload":{"x":1}}""").jsonObject
    private fun render(update: CashPolicyUpdate, encode: (Any?) -> JsonElement): JsonObject = buildJsonObject {
        put("values", encode(update.values)); put("normalized", encode(update.normalizedValues)); put("protected", encode(update.protectedFields))
        put("type_changed", update.typeChanged)
    }
    private fun check(current: JsonObject, patch: JsonElement, protected: Set<String> = emptySet()) {
        @Suppress("UNCHECKED_CAST") val record = cashSourceValue(current) as Map<String, Any?>
        val native = runCatching { render(CashRecordPolicy.prepare(record, cashSourceValue(patch), protected), ::sourceJson) }
        val local = runCatching { render(prepareLocalCashRecord(current, patch, protected), ::encodeLocalCashValue) }
        val label = "$current / $patch / $protected"
        fun error(value: Throwable?): Any? = when (value) {
            null -> null
            is CashPolicyError -> listOf(value::class.qualifiedName, value.code, value.fields)
            else -> listOf(value::class.qualifiedName, value.message)
        }
        assertEquals(error(native.exceptionOrNull()), error(local.exceptionOrNull()), label)
        if (native.isSuccess) assertEquals(native.getOrThrow(), local.getOrThrow(), label)
    }
    @Test fun productionJsonPolicyMatchesAllFieldsAndScalarTypes() {
        val values = listOf("null", "true", "false", "0", "1", "-1", "1.0", "0.1", "9007199254740993", "9007199254740993.0",
            "\"\"", "\"-20.000\"", "\"CNY\"", "\"USD\"", "\"41\"", "[]", "{}", "[\"tail\"]", "{\"x\":1.0}")
        val fields = listOf("amount", "currency", "occurred_at", "account_id", "cash_granularity", "components", "counterparty", "counterparty_account",
            "counterparty_account_attrs", "note", "category_id", "record_type", "record_subtype", "source_payload", "record_id", "source_type", "state", "unexpected")
        for (current in listOf(base, JsonObject(base + ("state" to JsonPrimitive("draft"))), JsonObject(base + ("record_origin" to JsonPrimitive("manual")))))
            for (field in fields) for (raw in values) check(current, JsonObject(mapOf(field to Json.parseToJsonElement(raw))))
        for (patch in listOf("""{"note":"新文","amount":"-21"}""", """{"note":"","category_id":null,"counterparty":"新商户"}""",
            """{"amount":"-20.000","occurred_at":"2026-08-01T08:00:00+08:00","account_id":41}"""))
            check(base, Json.parseToJsonElement(patch), setOf("note", "category_id"))
    }
    @Test fun componentTypesPayloadEqualityAndTimeTextMatchOriginalNativeValues() {
        val draft = JsonObject(base + ("state" to JsonPrimitive("draft")))
        val component = base.getValue("components").jsonArray.single().jsonObject
        for (field in listOf("component_id", "account_id", "amount", "currency", "ordinal", "extra"))
            for (text in listOf("null", "true", "0", "1", "0.0", "1e0", "\"41\"", "\"-20.000\"", "2147483647", "2147483648", "[]", "{}", "NaN")) {
                val patch = JsonObject(mapOf("components" to JsonArray(listOf(JsonObject(component + (field to Json.parseToJsonElement(text)))))))
                check(base, patch); check(draft, patch)
            }
        for (time in listOf("2026-08-01T00:00:00Z", "2026-08-01T00:00:00.1Z", "2026-08-01T00:00:00.123456Z", "2026-08-01T00:00:00.123456789Z",
            "2026-W01-1112Z", "2026-08-01Z12:00:00+00:00", "0001-01-01T00:00:00Z", "9999-12-31T23:59:59Z"))
            check(draft, JsonObject(mapOf("occurred_at" to JsonPrimitive(time))))
        for (text in listOf("{\"x\":1.0}", "{\"x\":true}", "{\"x\":9007199254740993}", "{\"x\":9007199254740993.0}", "{\"x\":NaN}", "{\"x\":1e400}"))
            check(base, JsonObject(mapOf("source_payload" to Json.parseToJsonElement(text))))
        val nanCurrent = JsonObject(base + ("components" to JsonArray(listOf(JsonObject(component + ("extra" to Json.parseToJsonElement("NaN")))))))
        check(nanCurrent, JsonObject(mapOf("components" to nanCurrent.getValue("components"))))
    }
}
