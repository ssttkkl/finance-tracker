package com.finance.tracker.presentation

import com.finance.tracker.business.domain.CashAmount
import com.finance.tracker.core.instantForLocalDateTime
import com.finance.tracker.domain.Account
import com.finance.tracker.domain.CashOrdinaryInput
import com.finance.tracker.domain.CashTransferInput
import com.finance.tracker.domain.isExactDecimalString

data class CashFormError(val field: String, val messageKey: String)

private fun amountError(value: String, positive: Boolean): String? {
    val amount = value.removePrefix("+")
    if (amount.isEmpty() || !Regex("\\d+(?:\\.\\d+)?").matches(amount) || positive && amount.none { it in '1'..'9' }) {
        return "invalid_amount"
    }
    return if (CashAmount.parseOrNull(amount) == null) "cash_amount_out_of_range" else null
}

private fun signedAmountError(value: String): String? =
    if (!isExactDecimalString(value)) "copy_d9ea7affdf"
    else if (CashAmount.parseOrNull(value) == null) "cash_create_amount_out_of_range" else null

private fun currencyError(value: String, account: Account?): String? {
    if (!Regex("[A-Z]{3}").matches(value)) return "invalid_currency"
    if (account != null && value !in account.currencies.orEmpty()) return "invalid_currency"
    return null
}

private fun account(value: String, accounts: List<Account>): Account? =
    accounts.singleOrNull { it.id.toString() == value && it.active }

internal fun validateCashOrdinaryCreation(input: CashOrdinaryInput, accounts: List<Account>, timeZoneId: String): List<CashFormError> {
    val values = input.values
    val errors = mutableListOf<CashFormError>()
    amountError(values["amount"].orEmpty(), positive = false)?.let {
        errors += CashFormError("amount", if (it == "invalid_amount") "copy_d9ea7affdf" else "cash_create_amount_out_of_range")
    }
    val account = account(values["account_id"].orEmpty(), accounts)
    currencyError(values["currency"].orEmpty(), account)?.let { errors += CashFormError("currency", it) }
    if (account == null) errors += CashFormError("account_id", "invalid_account")
    if (cashInstantForLocalDateTime(values["occurred_at"].orEmpty(), timeZoneId) == null) {
        errors += CashFormError("occurred_at", "invalid_datetime")
    }
    if (values["record_type"] !in setOf("consumption", "income")) {
        errors += CashFormError("record_type", "copy_245533bbc7")
    }
    return errors
}

internal fun validateCashTransferCreation(input: CashTransferInput, accounts: List<Account>, timeZoneId: String): List<CashFormError> {
    val errors = mutableListOf<CashFormError>()
    val from = account(input.fromAccountId, accounts)
    val to = account(input.toAccountId, accounts)
    if (from == null) errors += CashFormError("transfer", "invalid_account")
    if (to == null) errors += CashFormError("transfer", "invalid_account")
    if (from != null && to != null && from.id == to.id && input.fromCurrency == input.toCurrency) {
        errors += CashFormError("transfer", "invalid_account")
    }
    currencyError(input.fromCurrency, from)?.let { errors += CashFormError("transfer", it) }
    currencyError(input.toCurrency, to)?.let { errors += CashFormError("transfer", it) }
    amountError(input.amount, positive = true)?.let {
        errors += CashFormError("transfer", if (it == "invalid_amount") "cash_create_invalid_amount" else "cash_create_amount_out_of_range")
    }
    if (input.fromCurrency != input.toCurrency) {
        amountError(input.toAmount, positive = true)?.let {
            errors += CashFormError("transfer", if (it == "invalid_amount") "cash_create_invalid_amount" else "cash_create_amount_out_of_range")
        }
    }
    val investmentOnly = from?.type in setOf("security", "crypto") && to?.type in setOf("security", "crypto")
    val time = if (investmentOnly) instantForLocalDateTime(input.occurredAt, timeZoneId)
        else cashInstantForLocalDateTime(input.occurredAt, timeZoneId)
    if (time == null) errors += CashFormError("transfer", "invalid_datetime")
    return errors.distinctBy { it.field to it.messageKey }
}

internal fun validateCashScalarChanges(changes: Map<String, String>, timeZoneId: String): List<CashFormError> {
    val errors = mutableListOf<CashFormError>()
    changes.forEach { (field, text) ->
        when (field) {
            "occurred_at" -> if (text.isNotEmpty() && cashInstantForLocalDateTime(text, timeZoneId) == null) {
                errors += CashFormError(field, "invalid_datetime")
            }
            "amount" -> if (text.isNotEmpty()) {
                signedAmountError(text)?.let { errors += CashFormError(field, it) }
            }
            "currency" -> if (text.isNotEmpty()) {
                currencyError(text, null)?.let { errors += CashFormError(field, it) }
            }
            "cash_granularity" -> if (text !in setOf("atomic", "aggregate")) {
                errors += CashFormError(field, "copy_245533bbc7")
            }
        }
    }
    return errors
}

internal fun validateCashAllocation(parts: List<CashDraftAllocation>): CashFormError? {
    for (part in parts) {
        if (part.amount.isNotEmpty() && (!isExactDecimalString(part.amount) || CashAmount.parseOrNull(part.amount) == null)) {
            return CashFormError("components", "cash_import_invalid_allocation")
        }
        if (part.currency.isNotEmpty() && !Regex("[A-Z]{3}").matches(part.currency)) {
            return CashFormError("components", "cash_import_invalid_allocation")
        }
    }
    return null
}

internal fun validateCashTransferSides(outgoing: Map<String, String>, incoming: Map<String, String>, timeZoneId: String): List<CashFormError> {
    val errors = mutableListOf<CashFormError>()
    for (side in listOf(outgoing, incoming)) {
        amountError(side["amount"].orEmpty(), positive = true)?.let {
            errors += CashFormError("transfer", if (it == "invalid_amount") "cash_create_invalid_amount" else "cash_create_amount_out_of_range")
        }
        if (side["account_id"].isNullOrEmpty()) errors += CashFormError("transfer", "invalid_account")
        currencyError(side["currency"].orEmpty(), null)?.let { errors += CashFormError("transfer", it) }
        if (cashInstantForLocalDateTime(side["occurred_at"].orEmpty(), timeZoneId) == null) {
            errors += CashFormError("transfer", "invalid_datetime")
        }
    }
    return errors.distinctBy { it.field to it.messageKey }
}
