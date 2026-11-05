@file:kotlinx.serialization.UseSerializers(ExactJsonElementSerializer::class)

package com.finance.tracker.business.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class LedgerSnapshotDto(
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("ledger_seq") val ledgerSeq: String,
    @SerialName("rules_version") val rulesVersion: String,
    val facts: LedgerFactsDto,
)

@Serializable
data class LedgerFactsDto(
    @SerialName("accounts") val accounts: List<LedgerAccountFactDto>,
    @SerialName("account_aliases") val accountAliases: List<LedgerAccountAliasFactDto>,
    @SerialName("statement_account_mappings") val statementAccountMappings: List<LedgerStatementMappingFactDto>,
    @SerialName("cash_categories") val cashCategories: List<LedgerCategoryFactDto>,
    @SerialName("cash_transactions") val cashTransactions: List<LedgerCashTransactionFactDto>,
    @SerialName("cash_transaction_components") val cashTransactionComponents: List<LedgerCashComponentFactDto>,
    @SerialName("cash_source_bindings") val cashSourceBindings: List<LedgerSourceBindingFactDto>,
    @SerialName("cash_source_empty_pages") val cashSourceEmptyPages: List<LedgerSourceEmptyPageFactDto>,
    @SerialName("transaction_relations") val transactionRelations: List<LedgerCashRelationFactDto>,
    @SerialName("cash_relation_candidates") val cashRelationCandidates: List<LedgerRelationCandidateFactDto>,
    @SerialName("cash_transaction_issues") val cashTransactionIssues: List<LedgerCashIssueFactDto>,
    @SerialName("cash_issue_candidates") val cashIssueCandidates: List<LedgerIssueCandidateFactDto>,
    @SerialName("cash_entries") val cashEntries: List<LedgerCashEntryFactDto>,
    @SerialName("cash_entry_members") val cashEntryMembers: List<LedgerCashEntryMemberFactDto>,
    @SerialName("cash_investment_funding_relations") val cashInvestmentFundingRelations: List<LedgerInvestmentFundingFactDto>,
    @SerialName("category_directory_revision") val categoryDirectoryRevision: String,
)

@Serializable
data class LedgerAccountFactDto(
    val id: String,
    val name: String,
    val type: String,
    val active: Boolean,
    val currencies: JsonElement,
    val revision: String,
)

@Serializable
data class LedgerAccountAliasFactDto(
    val id: String,
    @SerialName("alias_type") val aliasType: String,
    @SerialName("alias_value") val aliasValue: String,
    @SerialName("account_id") val accountId: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class LedgerStatementMappingFactDto(
    val id: String,
    @SerialName("source_type") val sourceType: String,
    @SerialName("identity_kind") val identityKind: String,
    @SerialName("source_account_key") val sourceAccountKey: String,
    @SerialName("account_id") val accountId: String?,
    @SerialName("confirmed_by") val confirmedBy: String,
    val revision: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class LedgerCategoryFactDto(
    val id: String,
    @SerialName("parent_id") val parentId: String?,
    @SerialName("parent_scope_key") val parentScopeKey: String,
    val name: String,
    @SerialName("normalized_name") val normalizedName: String,
    val description: String?,
    @SerialName("category_path") val categoryPath: String,
    val depth: Int,
    @SerialName("sort_order") val sortOrder: String,
    val revision: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class LedgerCashTransactionFactDto(
    val id: String,
    @SerialName("account_id") val accountId: String?,
    val state: String,
    @SerialName("record_origin") val recordOrigin: String,
    @SerialName("manual_transfer_id") val manualTransferId: String?,
    @SerialName("row_revision") val rowRevision: String,
    @SerialName("cash_granularity") val cashGranularity: String,
    @SerialName("source_type") val sourceType: String?,
    @SerialName("record_id") val recordId: String,
    @SerialName("source_payload") val sourcePayload: JsonElement,
    @SerialName("relation_metadata") val relationMetadata: JsonElement,
    @SerialName("source_fingerprint") val sourceFingerprint: String?,
    @SerialName("manual_overrides") val manualOverrides: JsonElement,
    @SerialName("occurred_at") val occurredAt: String?,
    val amount: String?,
    val currency: String?,
    val counterparty: String,
    @SerialName("counterparty_account") val counterpartyAccount: String,
    @SerialName("counterparty_account_attrs") val counterpartyAccountAttrs: JsonElement,
    val note: String,
    @SerialName("category_id") val categoryId: String?,
    @SerialName("record_type") val recordType: String?,
    @SerialName("record_subtype") val recordSubtype: String?,
    @SerialName("created_at") val createdAt: String,
    @SerialName("deleted_at") val deletedAt: String?,
    @SerialName("deleted_by") val deletedBy: String,
    @SerialName("delete_reason") val deleteReason: String,
)

@Serializable
data class LedgerCashComponentFactDto(
    val id: String,
    @SerialName("cash_transaction_id") val cashTransactionId: String,
    @SerialName("account_id") val accountId: String?,
    val amount: String?,
    val currency: String?,
    val ordinal: String,
    val label: String,
    @SerialName("source_key") val sourceKey: String,
    @SerialName("metadata_json") val metadataJson: JsonElement,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class LedgerSourceBindingFactDto(
    val id: String,
    @SerialName("transaction_id") val transactionId: String,
    @SerialName("source_type") val sourceType: String,
    @SerialName("source_scope_kind") val sourceScopeKind: String,
    @SerialName("source_scope_key") val sourceScopeKey: String,
    @SerialName("identity_kind") val identityKind: String,
    @SerialName("identity_key") val identityKey: String,
    @SerialName("identity_strength") val identityStrength: String,
    @SerialName("stable_evidence_key") val stableEvidenceKey: String?,
    @SerialName("strategy_version") val strategyVersion: String,
    val coverage: JsonElement,
    val revision: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class LedgerSourceEmptyPageFactDto(
    @SerialName("source_type") val sourceType: String,
    @SerialName("parser_version") val parserVersion: String,
    @SerialName("document_key") val documentKey: String,
    @SerialName("page_number") val pageNumber: Int,
    val coverage: JsonElement,
)

@Serializable
data class LedgerCashRelationFactDto(
    val id: String,
    val kind: String,
    @SerialName("primary_transaction_id") val primaryTransactionId: String,
    @SerialName("secondary_transaction_id") val secondaryTransactionId: String?,
    @SerialName("primary_component_id") val primaryComponentId: String?,
    @SerialName("secondary_component_id") val secondaryComponentId: String?,
    @SerialName("anchor_transaction_id") val anchorTransactionId: String,
    @SerialName("anchor_component_id") val anchorComponentId: String?,
    @SerialName("primary_role") val primaryRole: String?,
    @SerialName("secondary_role") val secondaryRole: String?,
    val state: String,
    val active: Boolean,
    @SerialName("row_revision") val rowRevision: String,
    @SerialName("decision_group_id") val decisionGroupId: String?,
    @SerialName("decision_source") val decisionSource: String,
    val evidence: JsonElement,
    @SerialName("applied_amount") val appliedAmount: String,
    @SerialName("rule_id") val ruleId: String,
    @SerialName("created_by") val createdBy: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("decided_by") val decidedBy: String,
    @SerialName("decided_at") val decidedAt: String?,
    @SerialName("decision_reason") val decisionReason: String,
)

@Serializable
data class LedgerRelationCandidateFactDto(
    val id: String,
    @SerialName("relation_id") val relationId: String,
    @SerialName("candidate_transaction_id") val candidateTransactionId: String,
    @SerialName("candidate_component_id") val candidateComponentId: String?,
    @SerialName("expected_row_revision") val expectedRowRevision: String,
    val evidence: JsonElement,
    val validity: String,
)

@Serializable
data class LedgerCashIssueFactDto(
    val id: String,
    @SerialName("transaction_id") val transactionId: String,
    @SerialName("issue_key") val issueKey: String,
    val code: String,
    @SerialName("field_path") val fieldPath: String?,
    @SerialName("pending_source_payload") val pendingSourcePayload: JsonElement,
    @SerialName("pending_fields") val pendingFields: JsonElement,
    val origin: JsonElement,
    val revision: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class LedgerIssueCandidateFactDto(
    val id: String,
    @SerialName("issue_id") val issueId: String,
    @SerialName("candidate_transaction_id") val candidateTransactionId: String,
    @SerialName("expected_row_revision") val expectedRowRevision: String,
)

@Serializable
data class LedgerCashEntryFactDto(
    val id: String,
    @SerialName("root_transaction_id") val rootTransactionId: String,
    @SerialName("identity_transaction_id") val identityTransactionId: String,
    val revision: String,
    @SerialName("category_id") val categoryId: String?,
    val note: String,
    val counterparty: String,
    @SerialName("counterparty_account") val counterpartyAccount: String,
    @SerialName("counterparty_account_attrs") val counterpartyAccountAttrs: JsonElement,
    @SerialName("manual_fields") val manualFields: JsonElement,
    @SerialName("split_inherit") val splitInherit: Boolean?,
)

@Serializable
data class LedgerCashEntryMemberFactDto(
    @SerialName("transaction_id") val transactionId: String,
    @SerialName("entry_id") val entryId: String,
    @SerialName("standalone_values") val standaloneValues: JsonElement,
    @SerialName("standalone_manual_fields") val standaloneManualFields: JsonElement,
)

@Serializable
data class LedgerInvestmentFundingFactDto(
    val id: String,
    @SerialName("cash_transaction_component_id") val cashTransactionComponentId: String,
    @SerialName("investment_event_id") val investmentEventId: String,
    val direction: String,
    val status: String,
    @SerialName("rule_id") val ruleId: String,
    val evidence: JsonElement,
    @SerialName("active_slot") val activeSlot: String,
    @SerialName("created_by") val createdBy: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("decided_by") val decidedBy: String,
    @SerialName("decided_at") val decidedAt: String?,
    @SerialName("decision_reason") val decisionReason: String,
)
