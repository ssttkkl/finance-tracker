package com.finance.tracker.business.domain

import kotlin.test.*
import kotlinx.serialization.json.*

class CashMirrorMatchOriginalReplayTest {
    private val golden=Json.parseToJsonElement(CashMirrorOriginalVectors.text).jsonObject
    private fun encode(value:Any?)=sourceJson(value,{null},::pythonFloatText)
    private fun anyValue(value: JsonElement): Any? = when (value) {
        is JsonNull -> null
        is JsonPrimitive -> if (value.isString) value.content else value.booleanOrNull ?: value.longOrNull ?: value.doubleOrNull
        is JsonArray -> value.map(::anyValue)
        is JsonObject -> value.mapValues { anyValue(it.value) }
    }
    private fun strings(value: JsonElement): List<String> = value.jsonArray.map { it.jsonPrimitive.content }
    private fun fact(raw: JsonElement): RelationFact<CashAmount> {
        val o = raw.jsonObject
        fun text(name: String) = o[name]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content ?: ""
        fun stringsOrEmpty(name: String) = (o[name] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()
        return RelationFact(
            id = text("id"), amount = CashAmount.parse(text("amount")), currency = text("currency"),
            accountId = text("account_id"), accountName = text("account_name"), accountType = text("account_type"),
            occurredAt = o["occurred_at"]?.takeIf { it !is JsonNull }?.let { anyValue(it) },
            counterparty = text("counterparty"), counterpartyAccount = text("counterparty_account"),
            counterpartyAccountAttrs = stringsOrEmpty("counterparty_account_attrs"), paymentMethod = text("payment_method"),
            note = text("note"), recordType = text("record_type").ifEmpty { "other" },
            recordSubtype = text("record_subtype").ifEmpty { "not_applicable" },
            billSource = text("bill_source"), source = text("source"), factType = text("fact_type").ifEmpty { "cash" },
            deleted = o["deleted"]?.jsonPrimitive?.booleanOrNull ?: false,
            rawRecordId = o["raw_record_id"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
            sourceIdentity = text("source_identity"), recordId = text("record_id"),
            rawPayload = (o["raw_payload"] as? JsonObject)?.let { map -> map.mapValues { anyValue(it.value) } },
            relationMetadata = (o["relation_metadata"] as? JsonObject)?.let { map -> map.mapValues { anyValue(it.value) } },
            parentId = o["parent_id"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
            componentOrdinal = o["component_ordinal"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.intOrNull,
            cashGranularity = text("cash_granularity").ifEmpty { "atomic" },
            stableSourceKey = stringsOrEmpty("stable_source_key"),
            recordOrigin = text("record_origin").ifEmpty { "legacy" },
        )
    }
    private fun mapStrings(raw: JsonElement?): Map<String, List<String>> = (raw as? JsonObject).orEmpty().mapValues { strings(it.value) }
    private fun proposal(value: RelationProposal?): JsonElement = value?.toJsonValue(::encode) ?: JsonNull
    private fun proposals(values: List<RelationProposal>): JsonElement = JsonArray(values.map { it.toJsonValue(::encode) })
    private fun call(case: JsonObject): JsonElement {
        val args = case.getValue("args").jsonArray
        val kwargs = case.getValue("kwargs").jsonObject
        fun bool(name: String) = kwargs[name]?.jsonPrimitive?.booleanOrNull ?: false
        fun aliases() = mapStrings(kwargs["aliases_by_tail"])
        fun accounts() = mapStrings(kwargs["account_identifiers_by_value"])
        fun seeds() = (kwargs["seed_ids"] as? JsonArray)?.map { it.jsonPrimitive.content }
        fun occupied() = (kwargs["occupied_fact_ids"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()
        fun blocked() = (kwargs["blocked_pairs"] as? JsonArray)?.map { pair ->
            val values = pair.jsonArray.map { it.jsonPrimitive.content }; values[0] to values[1]
        }?.toSet() ?: emptySet()
        return when (case.getValue("fn").jsonPrimitive.content) {
            "source_group" -> JsonPrimitive(cashRelationCore.sourceGroup(fact(args[0])))
            "evaluate_payment_mirror" -> proposal(cashMirrorMatch.evaluate(
                fact(args[0]), args[1].jsonArray.map(::fact), aliases(), accounts(), bool("require_unique_mirrors")))
            "match_payment_mirrors_greedy" -> proposals(cashMirrorMatch.matchGreedy(
                args[0].jsonArray.map(::fact), aliases(), accounts(), seeds(), null, occupied(), blocked(), bool("require_unique_mirrors")))
            else -> throw AssertionError("unsupported replay call")
        }
    }

    @Test fun replayMatchesEveryOriginalPureMirrorCall() {
        var calls = 0
        for ((name, caseList) in golden.getValue("tests").jsonObject) {
            for (case in caseList.jsonArray) {
                val raw = case.jsonObject
                if (raw.getValue("fn").jsonPrimitive.content !in setOf("source_group", "evaluate_payment_mirror",
                        "match_payment_mirrors_greedy")) continue
                val expected = raw.getValue("result")
                assertEquals(expected, call(raw), name); calls++
            }
        }
        assertEquals(41, calls)
    }
}
