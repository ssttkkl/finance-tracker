package com.finance.tracker.cli

import com.finance.tracker.business.contracts.LedgerSnapshotSection
import com.finance.tracker.client.ClientStoredLedger
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

internal fun ledgerCacheText(generation: String, ledger: ClientStoredLedger): Sequence<String> = sequence {
    val snapshot = ledger.snapshot
    val facts = snapshot.facts
    yield("{\"generation\":")
    yield(Json.encodeToString(generation))
    yield(",\"ledger\":{\"snapshot\":{\"workspace_id\":")
    yield(Json.encodeToString(snapshot.workspaceId))
    yield(",\"ledger_seq\":")
    yield(Json.encodeToString(snapshot.ledgerSeq))
    yield(",\"rules_version\":")
    yield(Json.encodeToString(snapshot.rulesVersion))
    yield(",\"facts\":{")
    yieldAll(sectionText(LedgerSnapshotSection.ACCOUNTS, facts.accounts))
    yieldAll(sectionText(LedgerSnapshotSection.ACCOUNT_ALIASES, facts.accountAliases))
    yieldAll(sectionText(LedgerSnapshotSection.STATEMENT_ACCOUNT_MAPPINGS, facts.statementAccountMappings))
    yieldAll(sectionText(LedgerSnapshotSection.CASH_CATEGORIES, facts.cashCategories))
    yieldAll(sectionText(LedgerSnapshotSection.CASH_TRANSACTIONS, facts.cashTransactions))
    yieldAll(sectionText(LedgerSnapshotSection.CASH_TRANSACTION_COMPONENTS, facts.cashTransactionComponents))
    yieldAll(sectionText(LedgerSnapshotSection.CASH_SOURCE_BINDINGS, facts.cashSourceBindings))
    yieldAll(sectionText(LedgerSnapshotSection.CASH_SOURCE_EMPTY_PAGES, facts.cashSourceEmptyPages))
    yieldAll(sectionText(LedgerSnapshotSection.TRANSACTION_RELATIONS, facts.transactionRelations))
    yieldAll(sectionText(LedgerSnapshotSection.CASH_RELATION_CANDIDATES, facts.cashRelationCandidates))
    yieldAll(sectionText(LedgerSnapshotSection.CASH_TRANSACTION_ISSUES, facts.cashTransactionIssues))
    yieldAll(sectionText(LedgerSnapshotSection.CASH_ISSUE_CANDIDATES, facts.cashIssueCandidates))
    yieldAll(sectionText(LedgerSnapshotSection.CASH_ENTRIES, facts.cashEntries))
    yieldAll(sectionText(LedgerSnapshotSection.CASH_ENTRY_MEMBERS, facts.cashEntryMembers))
    yieldAll(sectionText(LedgerSnapshotSection.CASH_INVESTMENT_FUNDING_RELATIONS, facts.cashInvestmentFundingRelations))
    yield("\"category_directory_revision\":")
    yield(Json.encodeToString(facts.categoryDirectoryRevision))
    yield("}},\"synced_at\":")
    yield(Json.encodeToString(ledger.syncedAt))
    yield("}}")
}

private inline fun <reified T> sectionText(section: LedgerSnapshotSection, rows: List<T>): Sequence<String> = sequence {
    yield("\"${section.wireName}\":[")
    val encoder = ListSerializer(serializer<T>())
    val rowsPerBlock = 64
    for (offset in rows.indices step rowsPerBlock) {
        if (offset > 0) yield(",")
        val part = Json.encodeToString(encoder, rows.subList(offset, minOf(offset + rowsPerBlock, rows.size)))
        yield(part.substring(1, part.length - 1))
    }
    yield("],")
}
