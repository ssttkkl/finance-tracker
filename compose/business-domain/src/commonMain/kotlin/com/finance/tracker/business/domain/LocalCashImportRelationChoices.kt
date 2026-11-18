package com.finance.tracker.business.domain

import kotlinx.serialization.json.*

/** Resolve already-validated choices on copied rendered groups. Previous confirmation data remains read-only. */
fun applyLocalCashImportRelationChoices(groups: JsonArray, choices: JsonArray, previous: JsonObject?): JsonObject {
    @Suppress("UNCHECKED_CAST")
    val copied = copyLocalCashValue(decodeLocalCashValue(groups)) as List<MutableMap<String, Any?>>
    @Suppress("UNCHECKED_CAST")
    val input = decodeLocalCashValue(choices) as List<Map<String, Any?>>
    @Suppress("UNCHECKED_CAST")
    val old = previous?.let { decodeLocalCashValue(it) as Map<String, Any?> }
    val result = applyLocalCashImportRelationChoices(copied, input, old)
    return encodeLocalCashValue(mapOf("groups" to copied, "invalidated" to result.invalidated, "sources" to result.sources,
        "generations" to result.generations)).jsonObject
}

/** Apply saved choices to computed mutable groups, preserving exact scalar types; choices and previous stay read-only. */
fun applyLocalCashImportRelationChoices(groups: List<MutableMap<String, Any?>>, choices: List<Map<String, Any?>>,
    previous: Map<String, Any?>?): CashImportRelationChoiceResult<JsonElement> {
    val result = localRelationChoices.apply(groups, choices, previous)
    return CashImportRelationChoiceResult(result.invalidated, result.sources, result.generations.mapValues { generationJson(it.value) })
}

private fun generationJson(value: LedgerArithmeticValue): JsonElement = Json.parseToJsonElement(value.toPlainString())
private val localRelationChoices = CashImportRelationChoiceRules(LedgerArithmeticValue.ZERO,
    ::localChoiceGeneration, { it.add(LedgerArithmeticValue.fromCanonical("1")) }, ::generationJson,
    ::copyLocalCashValue, ::localCashImportDigest)

/** Match the legacy BigInteger decimal-string gate, including BMP Nd digits and optional ASCII sign. */
private fun localChoiceGeneration(value: Any?): LedgerArithmeticValue {
    if (value == null) return LedgerArithmeticValue.ZERO
    return parseLocalCashIntegerText(value.toString())
}

internal fun parseLocalCashIntegerText(raw: String): LedgerArithmeticValue {
    val negative = raw.startsWith('-')
    val offset = if (raw.startsWith('+') || negative) 1 else 0
    if (raw.length == offset) throw NumberFormatException("choice_generation_invalid")
    val digits = buildString(raw.length - offset) {
        for (index in offset until raw.length) {
            val char = raw[index]
            val start = decimalDigitStarts.firstOrNull { it <= 0xffff && char.code in it until it + 10 }
                ?: throw NumberFormatException("choice_generation_invalid")
            append(('0'.code + char.code - start).toChar())
        }
    }.trimStart('0').ifEmpty { "0" }
    return LedgerArithmeticValue.fromCanonical((if (negative && digits != "0") "-" else "") + digits)
}
