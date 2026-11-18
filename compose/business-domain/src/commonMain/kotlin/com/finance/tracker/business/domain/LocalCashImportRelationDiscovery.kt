package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.LedgerSnapshotDto
import kotlin.time.Instant

/** Discover actual proposals against one complete snapshot and an exclusively owned preview. */
fun discoverLocalCashImportRelations(source: LedgerSnapshotDto,
    snapshot: CashImportPreviewSnapshot<CashImportPreviewInteger, Instant>, seedIds: Set<String>,
    accounts: Map<String, Map<String, Any?>>, requireActive: () -> Unit): List<RelationProposal> {
    val rules = CashImportRelationDiscoveryRules(requireActive, ::copyLocalCashValue, ::localCashImportOriginalExpense,
        LocalCashRelationArithmetic::amount, { localCashIntegerText(it)!!.toInt() }, LocalCashRelationPipeline.rules::needsRelationDiscovery,
        { facts, seeds, accepted, tails, identifiers, workspace ->
            LocalCashRelationPlanning.rules.planRelationProposals(facts, seedIds = seeds, acceptedRelations = accepted,
                aliasesByTail = tails, accountIdentifiers = identifiers, workspaceId = workspace, requireUniqueMirrors = true).proposals
        })
    return rules.discover(snapshot, seedIds, accounts, source.workspaceId) {
        source.facts.accountAliases.map { mapOf("alias_type" to it.aliasType, "alias_value" to it.aliasValue, "account_id" to it.accountId) }
    }
}
