package com.finance.tracker.business.domain

import kotlinx.serialization.json.JsonElement

/** Validate saved user input without losing JSON integer tokens on JS/Wasm. */
fun prepareLocalCashImportInput(accountSelections: JsonElement?, rowChoices: JsonElement?, relationChoices: JsonElement?,
    entryChoices: JsonElement? = null): Map<String, Any?> = localCashImportInput.previewInput(
    decodeLocalCashValue(accountSelections), decodeLocalCashValue(rowChoices), decodeLocalCashValue(relationChoices),
    decodeLocalCashValue(entryChoices),
)

private val localCashImportInput by lazy {
    CashImportInputRules(
        { value -> localCashIntegerText(value)?.let { it != "0" && !it.startsWith('-') } == true },
        ::copyLocalCashValue,
        { LocalCashRecordValues.prepare(mapOf("state" to "draft"), it) },
    )
}
