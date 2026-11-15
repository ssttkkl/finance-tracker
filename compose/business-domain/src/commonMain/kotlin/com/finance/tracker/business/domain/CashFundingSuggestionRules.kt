package com.finance.tracker.business.domain

data class CashFundingMappingKey(val sourceType: String, val identityKind: String, val sourceAccountKey: String)
data class CashFundingSuggestion(val accountId: String?, val expectedAccountRevision: Long?, val basis: String)
data class CashFundingSuggestionAccount(val id: String, val revision: Long, val active: Boolean, val type: String)

/** Existing mapping precedence and account eligibility; callbacks read one workspace or snapshot. */
class CashFundingSuggestionRules(
    private val readMappings: (Collection<CashFundingMappingKey>) -> Map<CashFundingMappingKey, String?>,
    private val readAliases: (Collection<Pair<String, String>>) -> Map<Pair<String, String>, Set<String>>,
    private val readAccounts: (Collection<String>) -> Map<String, CashFundingSuggestionAccount>,
) {
    companion object { const val KIND = "funding_group_v1" }

    private fun legacy(group: CashFundingGroup): CashFundingMappingKey? {
        val kind = when {
            group.identityKind == "file_account" && group.sourceType in setOf("icbc_credit", "icbc_debit") -> "file_account"
            group.identityKind == "file_account" && group.sourceType == "icbc_asia" -> "account_identifier"
            else -> return null
        }
        return CashFundingMappingKey(group.sourceType, kind, group.identityKey)
    }

    fun suggest(groups: List<CashFundingGroup>): Map<String, CashFundingSuggestion> {
        val result = groups.filter { it.sourceType == "wechat" }.associate {
            it.sourceAccountRef to CashFundingSuggestion(null, null, "none")
        }.toMutableMap()
        val eligible = groups.filter { it.sourceType != "wechat" }
        if (eligible.isEmpty()) return result
        val keys = eligible.associate { it.sourceAccountRef to CashFundingMappingKey(it.sourceType, KIND, it.sourceAccountRef) }
        val legacy = eligible.associate { it.sourceAccountRef to legacy(it) }
        val mappings = readMappings(keys.values + legacy.values.filterNotNull())
        val aliasGroups = eligible.filter { it.identityKind == "file_account" && it.identityKey.matches(Regex("[0-9]{5,}")) }
            .associate { it.sourceAccountRef to ("account_identifier" to it.identityKey) }
        val aliases = readAliases(aliasGroups.values)
        val accounts = readAccounts((mappings.values.filterNotNull() + aliases.values.flatten()).distinct())
        for (group in eligible) {
            val ref = group.sourceAccountRef
            val current = keys.getValue(ref)
            val old = legacy[ref]
            val mapping = if (current in mappings) current else old?.takeIf { it in mappings }
            val candidates = if (mapping != null) setOfNotNull(mappings[mapping]) else aliases[aliasGroups[ref]].orEmpty()
            val matches = candidates.mapNotNull(accounts::get).filter { it.active && it.type in setOf("cash", "loan", "lend") }
            val account = matches.singleOrNull()
            val basis = if (current in mappings) "confirmed_mapping" else "legacy_mapping"
            result[ref] = CashFundingSuggestion(account?.id, account?.revision,
                if (account == null) "none" else if (mapping != null) basis else "account_alias")
        }
        return result
    }
}
