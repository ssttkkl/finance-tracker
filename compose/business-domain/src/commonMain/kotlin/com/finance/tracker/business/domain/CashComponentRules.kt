package com.finance.tracker.business.domain

import kotlinx.serialization.json.*

data class CashComponent(val accountId: String?, val amount: String?, val currency: String?, val ordinal: Long?,
    val ordinalPresent: Boolean = ordinal != null, val componentId: String? = null)
data class CashFinancialSnapshot(val amount: String?, val currency: String?, val accountId: String?,
    val cashGranularity: String? = "atomic", val components: List<CashComponent>? = emptyList())
data class CashRuleError(val code: String, val fields: List<String>)

/** Checks known values; incomplete but non-contradictory drafts remain valid. */
fun cashComponentError(record: CashFinancialSnapshot): CashRuleError? {
    fun invalid(field: String) = CashRuleError("invalid_field", listOf(field))
    fun mismatch(code: String, field: String = "components") = CashRuleError(code, listOf(field))
    val amount = record.amount?.let { LedgerDecimal.parseOrNull(it) ?: return invalid("amount") }
    fun validCurrency(value: String?) = value == null || value.length == 3 && value.all { it in 'A'..'Z' }
    if (!validCurrency(record.currency)) return invalid("currency")
    if (record.accountId == "") return invalid("account_id")
    if (record.cashGranularity !in setOf("atomic", "aggregate")) return invalid("cash_granularity")
    val components = record.components ?: return invalid("components")
    val amounts = components.map { part ->
        if (part.accountId == "" || part.componentId == "") return invalid("components")
        val value = part.amount?.let { LedgerDecimal.parseOrNull(it) ?: return invalid("components") }
        if (part.ordinalPresent && part.ordinal == null || part.ordinal != null && part.ordinal !in 0L..2147483647L || !validCurrency(part.currency)) return invalid("components")
        value
    }
    val ordinals = components.mapNotNull { it.ordinal }
    if (ordinals.distinct().size != ordinals.size) return mismatch("duplicate_component_ordinal")
    if (record.currency != null && components.any { it.currency != null && it.currency != record.currency })
        return mismatch("component_currency_mismatch")
    if (amount != null && amounts.any { it != null && it.sign != 0 && it.sign != amount.sign })
        return mismatch("component_direction_mismatch")
    if (record.cashGranularity == "aggregate" && record.accountId != null)
        return mismatch("component_account_mismatch", "account_id")
    if (record.cashGranularity == "atomic" && components.isNotEmpty() && (components.size != 1 ||
            components.single().accountId != null && record.accountId != null && components.single().accountId != record.accountId))
        return mismatch("component_account_mismatch")
    if (amount != null && components.isNotEmpty() && amounts.all { it != null }) {
        val scale = maxOf(amount.scale, amounts.maxOf { it!!.scale })
        val total = amounts.fold("0") { sum, value -> addUnsigned(sum, value!!.magnitudeAt(scale)) }
        if (compareUnsigned(total, amount.magnitudeAt(scale)) != 0) return mismatch("component_total_mismatch")
    }
    return null
}

/** Preserves JSON scalar types before applying known-value consistency rules. */
fun cashComponentError(record: JsonObject): CashRuleError? {
    val integer = Regex("^-?(?:0|[1-9][0-9]*)$")
    class InvalidInput : RuntimeException()
    fun text(value: JsonElement?): String? {
        if (value == null || value == JsonNull) return null
        if (value !is JsonPrimitive || !value.isString) throw InvalidInput()
        return value.content
    }
    fun amount(value: JsonElement?): String? {
        if (value == null || value == JsonNull) return null
        if (value !is JsonPrimitive || !value.isString && !integer.matches(value.content)) throw InvalidInput()
        return value.content.also { if (LedgerDecimal.parseOrNull(it) == null) throw InvalidInput() }
    }
    fun reference(value: JsonElement?): String? {
        if (value == null || value == JsonNull) return null
        if (value !is JsonPrimitive) throw InvalidInput()
        if (value.isString) return value.content.takeIf { it.isNotEmpty() } ?: throw InvalidInput()
        if (!integer.matches(value.content) || value.content.startsWith('-') || value.content == "0") throw InvalidInput()
        return value.content
    }
    fun currency(value: JsonElement?): String? = text(value)?.also {
        if (it.length != 3 || it.any { c -> c !in 'A'..'Z' }) throw InvalidInput()
    }
    var field = "amount"
    return try {
        val amount = amount(record[field])
        field = "currency"; val currency = currency(record[field])
        field = "account_id"; val account = reference(record[field])
        field = "cash_granularity"
        val granularity = if (field !in record) "atomic" else text(record[field])
        if (granularity !in setOf("atomic", "aggregate")) throw InvalidInput()
        field = "components"
        val raw = if (field !in record) JsonArray(emptyList()) else record[field]
        if (raw !is JsonArray) throw InvalidInput()
        val components = raw.map { value ->
            if (value !is JsonObject) throw InvalidInput()
            val id = reference(value["component_id"])
            val partAccount = reference(value["account_id"])
            val partAmount = amount(value["amount"])
            val ordinal = if ("ordinal" in value) {
                val item = value["ordinal"]
                if (item !is JsonPrimitive || item.isString || !integer.matches(item.content)) throw InvalidInput()
                item.content.toLongOrNull()?.takeIf { it in 0..2147483647L } ?: throw InvalidInput()
            } else null
            CashComponent(partAccount, partAmount, currency(value["currency"]), ordinal, "ordinal" in value, id)
        }
        cashComponentError(CashFinancialSnapshot(amount, currency, account, granularity, components))
    } catch (_: InvalidInput) { CashRuleError("invalid_field", listOf(field)) }
}
