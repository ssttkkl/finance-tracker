package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlin.test.*

class CashFundingSuggestionRulesTest {
    private fun group(ref: String = "ref", source: String = "ccb_debit", kind: String = "file_account", identity: String = "123456") =
        CashFundingGroup(ref, source, kind, "owner", identity, "Synthetic", currencies = listOf("CNY"), rowRefs = listOf("a:r1"))
    private class Reads {
        val mappings = mutableMapOf<CashFundingMappingKey, String?>()
        val aliases = mutableMapOf<Pair<String, String>, Set<String>>()
        val accounts = mutableMapOf("10" to CashFundingSuggestionAccount("10", 1, true, "cash"))
        val calls = mutableListOf<String>()
        val rules = CashFundingSuggestionRules(
            { keys -> calls += "mappings"; mappings.filterKeys { it in keys } },
            { keys -> calls += "aliases"; aliases.filterKeys { it in keys } },
            { ids -> calls += "accounts"; accounts.filterKeys { it in ids } })
    }
    private val none = CashFundingSuggestion(null, null, "none")

    @Test fun emptyAndWechatGroupsDoNotReadMappingsAliasesOrAccounts() {
        val r = Reads()
        assertTrue(r.rules.suggest(emptyList()).isEmpty())
        assertEquals(mapOf("ref" to none), r.rules.suggest(listOf(group(source = "wechat"))))
        assertTrue(r.calls.isEmpty())
    }

    @Test fun currentMappingWinsAndNullMissingOrIneligibleTargetsNeverFallThrough() {
        val r = Reads(); val group = group(source = "icbc_debit")
        val current = CashFundingMappingKey("icbc_debit", "funding_group_v1", "ref")
        r.mappings[CashFundingMappingKey("icbc_debit", "file_account", "123456")] = "10"
        r.aliases["account_identifier" to "123456"] = setOf("10")
        for (target in listOf(null, "missing", "20")) {
            r.mappings[current] = target
            r.accounts["20"] = CashFundingSuggestionAccount("20", 4, false, "cash")
            assertEquals(none, r.rules.suggest(listOf(group)).getValue("ref"))
        }
        r.mappings[current] = "10"
        assertEquals(CashFundingSuggestion("10", 1, "confirmed_mapping"), r.rules.suggest(listOf(group)).getValue("ref"))
    }

    @Test fun legacyFallbackKeepsTheOriginalChannelAndIdentityDomain() {
        for ((source, legacyKind) in listOf("icbc_debit" to "file_account", "icbc_credit" to "file_account", "icbc_asia" to "account_identifier")) {
            val r = Reads(); r.mappings[CashFundingMappingKey(source, legacyKind, "123456")] = "10"
            assertEquals(CashFundingSuggestion("10", 1, "legacy_mapping"), r.rules.suggest(listOf(group(source = source))).getValue("ref"))
            assertEquals(none, r.rules.suggest(listOf(group(source = source, kind = "payment_method"))).getValue("ref"))
        }
        val r = Reads(); r.mappings[CashFundingMappingKey("ccb_debit", "file_account", "123456")] = "10"
        assertEquals(none, r.rules.suggest(listOf(group())).getValue("ref"))
    }

    @Test fun aliasesUseOnlyCompleteAsciiAccountNumbersAndOneEligibleAccount() {
        val r = Reads()
        for (value in listOf("123456", "1234", "１２３４５６")) r.aliases["account_identifier" to value] = setOf("10")
        assertEquals(CashFundingSuggestion("10", 1, "account_alias"), r.rules.suggest(listOf(group())).getValue("ref"))
        for (invalid in listOf(group(identity = "1234"), group(identity = "１２３４５６"), group(kind = "payment_method")))
            assertEquals(none, r.rules.suggest(listOf(invalid)).getValue("ref"))
        r.accounts["20"] = CashFundingSuggestionAccount("20", 4, true, "loan")
        r.aliases["account_identifier" to "123456"] = setOf("10", "20")
        assertEquals(none, r.rules.suggest(listOf(group())).getValue("ref"))
        r.accounts["20"] = r.accounts.getValue("20").copy(type = "investment")
        assertEquals(CashFundingSuggestion("10", 1, "account_alias"), r.rules.suggest(listOf(group())).getValue("ref"))
    }

    @Test fun revisionsAndIdsRemainExactAndEligibleLendingAccountsAreRetained() {
        val r = Reads(); val id = "9007199254740993"
        r.mappings[CashFundingMappingKey("ccb_debit", "funding_group_v1", "ref")] = id
        for (type in listOf("cash", "loan", "lend")) {
            r.accounts[id] = CashFundingSuggestionAccount(id, 9007199254740993L, true, type)
            assertEquals(CashFundingSuggestion(id, 9007199254740993L, "confirmed_mapping"), r.rules.suggest(listOf(group())).getValue("ref"))
        }
    }

    @Test fun readFailuresPreserveTheOriginalException() {
        val fault = IllegalStateException("synthetic read fault")
        for (stage in listOf("mappings", "aliases", "accounts")) {
            val rules = CashFundingSuggestionRules(
                { if (stage == "mappings") throw fault else emptyMap() },
                { if (stage == "aliases") throw fault else emptyMap() },
                { if (stage == "accounts") throw fault else emptyMap() })
            assertSame(fault, assertFailsWith<IllegalStateException> { rules.suggest(listOf(group())) })
        }
    }

    @Test fun localSuggestionsReadTheSameSnapshotAndDoNotApplyCurrencyOrCardTailFallbacks() {
        val initial = LocalCashImportBusinessPlanTest().source()
        val time = "2026-10-01T00:00:00Z"
        val alias = LedgerAccountAliasFactDto("1", "account_identifier", "123456", "10", time, time)
        val mapping = LedgerStatementMappingFactDto("1", "ccb_debit", "funding_group_v1", "ref", null, "user", "1", time, time)
        val source = initial.copy(facts = initial.facts.copy(accountAliases = listOf(alias), statementAccountMappings = listOf(mapping)))
        assertEquals(none, suggestLocalCashFundingAccounts(listOf(group()), source).getValue("ref"))
        val aliasOnly = source.copy(facts = source.facts.copy(statementAccountMappings = emptyList(),
            accounts = source.facts.accounts.map { it.copy(currencies = kotlinx.serialization.json.JsonArray(emptyList())) }))
        assertEquals(CashFundingSuggestion("10", 1, "account_alias"), suggestLocalCashFundingAccounts(listOf(group()), aliasOnly).getValue("ref"))
        val tailOnly = aliasOnly.copy(facts = aliasOnly.facts.copy(accountAliases = listOf(alias.copy(aliasType = "card_tail"))))
        assertEquals(none, suggestLocalCashFundingAccounts(listOf(group()), tailOnly).getValue("ref"))
    }
}
