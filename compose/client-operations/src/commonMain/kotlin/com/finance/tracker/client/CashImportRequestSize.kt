package com.finance.tracker.client

import com.finance.tracker.business.contracts.CashWriteSetRequestDto
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun requiredCashImportStringsExceed(request: CashWriteSetRequestDto, limit: Int): Boolean {
    val budget = RequiredJsonStringBudget(limit)
    return request.changes.transactions.any { row ->
        budget.exceededBy(row.values.note) || budget.exceededBy(row.values.sourcePayload)
    }
}

/** Count only required string tokens. The exact encoder still checks the full envelope and UTF-8. */
internal class RequiredJsonStringBudget(private var remaining: Int) {
    fun exceededBy(value: String): Boolean {
        if (remaining < 2 || value.length > remaining - 2) return true
        remaining -= value.length + 2
        for (character in value) {
            remaining -= when (character) {
                '"', '\\', '\b', '\t', '\n', '\u000c', '\r' -> 1
                else -> if (character < ' ') 5 else 0
            }
            if (remaining < 0) return true
        }
        return false
    }

    fun exceededBy(value: JsonElement): Boolean = when (value) {
        is JsonObject -> value.any { (key, item) -> exceededBy(key) || exceededBy(item) }
        is JsonArray -> value.any(::exceededBy)
        is JsonPrimitive -> value.isString && exceededBy(value.content)
    }
}
