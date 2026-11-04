package com.finance.tracker.business.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CashRecordTypesTest {
    @Test
    fun recordTypesAndDefaultSubtypesKeepTheExistingCatalogueOrder() {
        val expected = listOf("consumption", "refund", "reversal", "transfer_reversal", "withdrawal_in", "withdrawal_out",
            "transfer_in", "transfer_out", "repayment", "income", "investment_in", "investment_out", "interest", "fee", "fx_in", "fx_out", "other")
        assertEquals(expected, cashRecordTypes.toList())
        assertEquals(listOf("ordinary_transfer", "cross_border_remittance", "internal_account_transfer", "currency_exchange",
            "withdraw_to_bank", "credit_repayment", "not_applicable"), cashRecordSubtypes.toList())
        for (type in expected) {
            val subtype = when (type) {
                "transfer_in", "transfer_out" -> "ordinary_transfer"
                "fx_in", "fx_out" -> "currency_exchange"
                "repayment" -> "credit_repayment"
                "withdrawal_in", "withdrawal_out" -> "withdraw_to_bank"
                else -> "not_applicable"
            }
            assertEquals(subtype, defaultCashRecordSubtype(type))
            for (candidate in cashRecordSubtypes) {
                val accepted = if (type in listOf("transfer_in", "transfer_out")) {
                    candidate in listOf("ordinary_transfer", "cross_border_remittance", "internal_account_transfer")
                } else candidate == subtype
                assertEquals(accepted, validCashRecordPair(type, candidate), "$type/$candidate")
            }
        }
    }

    @Test
    fun unknownTypesAndNonStringValuesAreNotValidPairs() {
        assertEquals("not_applicable", defaultCashRecordSubtype(null))
        assertEquals("not_applicable", defaultCashRecordSubtype("unknown"))
        for (value in listOf(null, "unknown", 0, true, emptyList<String>())) {
            assertFalse(validCashRecordPair(value, "not_applicable"))
            assertFalse(validCashRecordPair("consumption", value))
        }
    }
}
