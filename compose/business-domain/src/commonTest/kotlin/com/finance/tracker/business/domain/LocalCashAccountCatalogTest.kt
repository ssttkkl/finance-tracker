package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.AccountReferenceDto
import com.finance.tracker.business.contracts.LedgerAccountFactDto
import kotlinx.serialization.json.*
import kotlin.test.*

class LocalCashAccountCatalogTest {
    @Test fun cashTypesIncludeInactiveAccountsAndUseExactNumericIdentityOrder() {
        val wide = 9007199254740993L
        val facts = listOf(account(wide.toString(), "lend", false, "[\"usd\",\"cny\"]"),
            account("10", "loan", true, "[]"), account("2", "cash", true, "[\"hkd\"]"),
            account("1", "security", true, "[\"USD\"]"), account("3", "crypto", true, "[\"USD\"]"))
        assertEquals(listOf(AccountReferenceDto(2, "Account:2", "cash", true, listOf("HKD")),
            AccountReferenceDto(10, "Account:10", "loan", true, emptyList()),
            AccountReferenceDto(wide, "Account:$wide", "lend", false, listOf("USD", "CNY"))),
            buildLocalCashAccountReferences(facts))
    }

    @Test fun currencyContainersRetainOriginalIterationAndTextRules() {
        val cases = listOf("null" to emptyList(), "false" to emptyList(), "0" to emptyList(),
            "-0.0" to emptyList(), "1e-999" to emptyList(), "[]" to emptyList(), "{}" to emptyList(),
            "\"\"" to emptyList(), "\"cß𐐨\"" to listOf("C", "SS", "𐐀"),
            "{\"usd\":null,\"hkd\":false}" to listOf("USD", "HKD"),
            "[\"usd\",\" usd \",\"usd\",null,true,false,-0,1.0,1e-6,[\"ß\"],{\"a\":null}]" to
                listOf("USD", " USD ", "USD", "NONE", "TRUE", "FALSE", "0", "1.0", "1E-06", "['SS']", "{'A': NONE}"))
        for ((raw, expected) in cases) assertEquals(expected,
            buildLocalCashAccountReferences(listOf(account("1", "cash", true, raw))).single().currencies, raw)
    }

    @Test fun truthyNonIterableCurrencyValuesAreErrorsAndInvestmentValuesAreNotRead() {
        for (raw in listOf("true", "1", "0.1")) {
            assertEquals("invalid_account_currencies", assertFailsWith<IllegalStateException> {
                buildLocalCashAccountReferences(listOf(account("1", "cash", true, raw)))
            }.message)
            assertTrue(buildLocalCashAccountReferences(listOf(account("1", "security", true, raw))).isEmpty())
        }
    }

    @Test fun accountIdentityNeverRoundsOrDefaultsAndEmptyFactsAreARealEmptyList() {
        assertTrue(buildLocalCashAccountReferences(emptyList()).isEmpty())
        assertEquals(Long.MAX_VALUE, buildLocalCashAccountReferences(listOf(account(Long.MAX_VALUE.toString(), "cash", true, "[]"))).single().id)
        for (id in listOf("not-an-id", "9223372036854775808")) assertFailsWith<NumberFormatException> {
            buildLocalCashAccountReferences(listOf(account(id, "cash", true, "[]")))
        }
    }

    private fun account(id: String, type: String, active: Boolean, currencies: String) =
        LedgerAccountFactDto(id, "Account:$id", type, active, Json.parseToJsonElement(currencies), "unused row revision")
}
