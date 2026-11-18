package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.LedgerSnapshotDto

/** Account preselection only: publishing a mapping still requires the actual used choices. */
fun suggestLocalCashFundingAccounts(groups: List<CashFundingGroup>, source: LedgerSnapshotDto): Map<String, CashFundingSuggestion> =
    CashFundingSuggestionRules(
        { keys ->
            val requested = keys.toSet()
            source.facts.statementAccountMappings.mapNotNull { mapping ->
                val key = CashFundingMappingKey(mapping.sourceType, mapping.identityKind, mapping.sourceAccountKey)
                if (key in requested) key to mapping.accountId else null
            }.toMap()
        },
        { keys ->
            val requested = keys.toSet()
            source.facts.accountAliases.filter { (it.aliasType to it.aliasValue) in requested }
                .groupBy { it.aliasType to it.aliasValue }.mapValues { (_, aliases) -> aliases.map { it.accountId }.toSet() }
        },
        { ids ->
            val requested = ids.toSet()
            source.facts.accounts.filter { it.id in requested }.associate {
                it.id to CashFundingSuggestionAccount(it.id, it.revision.toLong(), it.active, it.type)
            }
        },
    ).suggest(groups)
