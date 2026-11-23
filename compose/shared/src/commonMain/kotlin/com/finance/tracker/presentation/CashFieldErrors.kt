package com.finance.tracker.presentation

import androidx.compose.runtime.Composable

internal data class CashFieldError(val field: String, val message: String)

internal data class CashFieldErrorKey(val field: String, val messageKey: String)

/**
 * Maps a cash validation error to the field that caused it. Errors without a
 * stable field mapping stay form-level, so the UI never guesses a field.
 */
internal fun cashFieldErrorKeyFor(code: String?, transfer: Boolean = false, split: Boolean = false): CashFieldErrorKey? = when (code) {
    "invalid_amount", "cash_create_invalid_amount" -> CashFieldErrorKey(
        field = when { transfer -> "transfer"; split -> "components"; else -> "amount" },
        messageKey = if (transfer) "cash_create_invalid_amount" else "copy_d9ea7affdf",
    )
    "cash_amount_out_of_range" -> CashFieldErrorKey(
        field = when { transfer -> "transfer"; split -> "components"; else -> "amount" },
        messageKey = "cash_create_amount_out_of_range",
    )
    "invalid_currency" -> CashFieldErrorKey(
        field = when { transfer -> "transfer"; split -> "components"; else -> "currency" },
        messageKey = "cash_invalid_currency",
    )
    "invalid_account" -> CashFieldErrorKey(
        field = when { transfer -> "transfer"; split -> "components"; else -> "account_id" },
        messageKey = "cash_invalid_account",
    )
    "account_changed" -> CashFieldErrorKey(
        field = when { transfer -> "transfer"; split -> "components"; else -> "account_id" },
        messageKey = "cash_create_choose_account_again",
    )
    "invalid_datetime" -> CashFieldErrorKey(
        field = if (transfer) "transfer" else "occurred_at",
        messageKey = "cash_invalid_local_time",
    )
    "invalid_allocation", "cash_import_invalid_allocation" -> CashFieldErrorKey(
        field = "components",
        messageKey = "cash_import_invalid_allocation",
    )
    else -> null
}

@Composable
internal fun cashFieldErrorFor(code: String?, transfer: Boolean = false, split: Boolean = false): CashFieldError? =
    cashFieldErrorKeyFor(code, transfer, split)?.let { CashFieldError(it.field, localizedText(it.messageKey)) }
