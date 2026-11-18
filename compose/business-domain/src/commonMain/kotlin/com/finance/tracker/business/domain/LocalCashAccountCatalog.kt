package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.AccountReferenceDto
import com.finance.tracker.business.contracts.LedgerAccountFactDto
import kotlinx.serialization.json.*

/** The cash account read keeps inactive accounts and the original stored-currency compatibility. */
fun buildLocalCashAccountReferences(accounts: List<LedgerAccountFactDto>): List<AccountReferenceDto> =
    accounts.filter { it.type in setOf("cash", "loan", "lend") }.sortedBy { it.id.toLong() }.map {
        AccountReferenceDto(it.id.toLong(), it.name, it.type, it.active, cashAccountCurrencies(it.currencies))
    }

private fun cashAccountCurrencies(stored: JsonElement): List<String> {
    val truthy = when (stored) {
        JsonNull -> false
        is JsonArray -> stored.isNotEmpty()
        is JsonObject -> stored.isNotEmpty()
        is JsonPrimitive -> if (stored.isString) stored.content.isNotEmpty()
            else stored.booleanOrNull ?: (parsePythonJsonBinary64(stored.content) != 0.0)
    }
    val values = when {
        !truthy -> emptyList()
        stored is JsonArray -> stored.toList()
        stored is JsonObject -> stored.keys.map(::JsonPrimitive)
        stored is JsonPrimitive && stored.isString -> stored.content.pythonCodePoints().map { code ->
            JsonPrimitive(buildString { appendPythonCodePoint(code) })
        }
        else -> error("invalid_account_currencies")
    }
    return values.map { pythonCashUpper(pythonJsonText(it)) }
}
