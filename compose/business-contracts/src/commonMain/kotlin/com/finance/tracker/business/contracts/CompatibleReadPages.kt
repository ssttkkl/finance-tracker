@file:kotlinx.serialization.UseSerializers(ExactJsonElementSerializer::class)

package com.finance.tracker.business.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

const val COMPATIBLE_READ_PAGES_HEADER = "X-FT-Read-Pages"
const val COMPATIBLE_READ_PAGES_VERSION = "1"
const val LEDGER_SNAPSHOT_PAGE_PATH = "/api/v1/ledger/snapshot/pages"
const val CASH_IMPORT_RECEIPT_PAGE_PATH = "/api/v1/cash-import/write-set/receipt/pages"
const val LEDGER_SNAPSHOT_PAGE_MAX_BYTES = 32 * 1024 * 1024
const val CASH_IMPORT_RECEIPT_PAGE_MAX_BYTES = 2 * 1024 * 1024

enum class LedgerSnapshotSection(val wireName: String) {
    ACCOUNTS("accounts"),
    ACCOUNT_ALIASES("account_aliases"),
    STATEMENT_ACCOUNT_MAPPINGS("statement_account_mappings"),
    CASH_CATEGORIES("cash_categories"),
    CASH_TRANSACTIONS("cash_transactions"),
    CASH_TRANSACTION_COMPONENTS("cash_transaction_components"),
    CASH_SOURCE_BINDINGS("cash_source_bindings"),
    CASH_SOURCE_EMPTY_PAGES("cash_source_empty_pages"),
    TRANSACTION_RELATIONS("transaction_relations"),
    CASH_RELATION_CANDIDATES("cash_relation_candidates"),
    CASH_TRANSACTION_ISSUES("cash_transaction_issues"),
    CASH_ISSUE_CANDIDATES("cash_issue_candidates"),
    CASH_ENTRIES("cash_entries"),
    CASH_ENTRY_MEMBERS("cash_entry_members"),
    CASH_INVESTMENT_FUNDING_RELATIONS("cash_investment_funding_relations"),
}

enum class CashImportReceiptSection(val wireName: String) {
    HEADER("header"),
    TRANSACTIONS("transactions"),
    SOURCE_BINDINGS("source_bindings"),
    ISSUES("issues"),
    STATEMENT_ACCOUNT_MAPPINGS("statement_account_mappings"),
    RELATIONS("relations"),
    ENTRIES("entries"),
    RESOLVED_ISSUE_IDS("resolved_issue_ids"),
    RETIRED_ENTRY_IDS("retired_entry_ids"),
    PENDING_TRANSACTION_IDS("pending_transaction_ids"),
}

@Serializable
data class ReadPagePosition(
    val section: String,
    val offset: Int,
    @SerialName("fragment_offset") val fragmentOffset: Int = 0,
)

@Serializable
data class ReadPageFragment(
    @SerialName("total_bytes") val totalBytes: Int,
    val base64: String,
)

@Serializable
data class ReadPageChunk(
    val position: ReadPagePosition,
    val total: Int,
    val items: List<JsonElement>,
    val fragment: ReadPageFragment?,
    val next: ReadPagePosition?,
)

@Serializable
data class LedgerSnapshotReadPage(
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("ledger_seq") val ledgerSeq: String,
    @SerialName("rules_version") val rulesVersion: String,
    @SerialName("category_directory_revision") val categoryDirectoryRevision: String,
    val chunk: ReadPageChunk,
)

@Serializable
data class CashImportReceiptReadPage(
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("receipt_id") val receiptId: String,
    @SerialName("ledger_seq") val ledgerSeq: String,
    val chunk: ReadPageChunk,
)
