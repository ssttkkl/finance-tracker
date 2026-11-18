package com.finance.tracker.business.domain

import kotlinx.serialization.json.*

data class CashFundingGroup(val sourceAccountRef: String, val sourceType: String, val identityKind: String,
    val originKey: String, val identityKey: String, val displayName: String, val sessionLabel: String? = null,
    val currencies: List<String>, val rowRefs: List<String>)
class CashFundingInputError : IllegalArgumentException("funding_selection_invalid")
class CashFundingPartsTooLarge : IllegalArgumentException("funding_parts_too_large")
data class CashFundingPart(val groupRef: String?, val amount: String?) {
    fun json() = buildJsonObject { put("group_ref", groupRef?.let(::JsonPrimitive) ?: JsonNull)
        put("amount", amount?.let(::JsonPrimitive) ?: JsonNull) }
}
data class CashFundingRow(val rowRef: String, val fields: Map<String, Any?>, val parts: List<CashFundingPart>,
    val issueCodes: List<String> = emptyList(), val errorCode: String? = null) {
    val groupRefs get() = parts.mapNotNull { it.groupRef }.distinct()
    fun json(encode: (Any?) -> JsonElement) = buildJsonObject { put("row_ref", rowRef); put("fields", encode(fields))
        put("parts", JsonArray(parts.map { it.json() })); put("issue_codes", encode(issueCodes))
        put("error_code", errorCode?.let(::JsonPrimitive) ?: JsonNull) }
}
data class CashFundingScan(val groups: List<CashFundingGroup>, val rows: List<CashFundingRow>) {
    fun json(encode: (Any?) -> JsonElement) = buildJsonObject { put("groups", JsonArray(groups.map { g -> buildJsonObject {
        put("source_account_ref", g.sourceAccountRef); put("source_type", g.sourceType); put("identity_kind", g.identityKind)
        put("origin_key", g.originKey); put("identity_key", g.identityKey); put("display_name", g.displayName)
        put("session_label", g.sessionLabel?.let(::JsonPrimitive) ?: JsonNull)
        put("currencies", encode(g.currencies)); put("row_refs", encode(g.rowRefs))
    } })); put("rows", JsonArray(rows.map { it.json(encode) })) }
}
data class CashFundingResolution(val rowRef: String, val fields: Map<String, Any?>, val groupRefs: List<String>,
    val issueCodes: List<String>, val ready: Boolean, val errorCode: String?, val selectedGroups: List<Pair<String, String>>) {
    fun json(encode: (Any?) -> JsonElement) = buildJsonObject { put("row_ref", rowRef); put("fields", encode(fields)); put("group_refs", encode(groupRefs))
        put("issue_codes", encode(issueCodes)); put("ready", ready); put("error_code", errorCode?.let(::JsonPrimitive) ?: JsonNull)
        put("selected_groups", JsonArray(selectedGroups.map { JsonArray(listOf(JsonPrimitive(it.first), JsonPrimitive(it.second))) })) }
}
