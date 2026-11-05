@file:kotlinx.serialization.UseSerializers(ExactJsonElementSerializer::class)

package com.finance.tracker.business.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class CashWriteSetRequestDto(
    @SerialName("base_ledger_seq") val baseLedgerSeq: String,
    @SerialName("rules_version") val rulesVersion: String,
    val changes: CashWriteSetDto,
)

@Serializable
sealed interface CashWriteReferenceDto

@Serializable
@SerialName("existing")
data class ExistingCashWriteReferenceDto(val id: String) : CashWriteReferenceDto

@Serializable
@SerialName("new")
data class NewCashWriteReferenceDto(val ref: String) : CashWriteReferenceDto

@Serializable
data class CashWriteVersionedReferenceDto(
    val reference: CashWriteReferenceDto,
    @SerialName("expected_revision") val expectedRevision: String?,
)

@Serializable
data class CashWriteSetDto(
    val transactions: List<CashTransactionWriteDto> = emptyList(),
    @SerialName("deleted_transactions") val deletedTransactions: List<CashTransactionDeleteDto> = emptyList(),
    val relations: List<CashRelationWriteDto> = emptyList(),
    val issues: List<CashIssueWriteDto> = emptyList(),
    @SerialName("resolved_issues") val resolvedIssues: List<CashWriteVersionedReferenceDto> = emptyList(),
    val entries: List<CashEntryWriteDto> = emptyList(),
    @SerialName("deleted_entries") val deletedEntries: List<CashWriteVersionedReferenceDto> = emptyList(),
    @SerialName("source_bindings") val sourceBindings: List<CashSourceBindingWriteDto> = emptyList(),
    @SerialName("source_empty_pages") val sourceEmptyPages: List<LedgerSourceEmptyPageFactDto> = emptyList(),
    @SerialName("statement_account_mappings") val statementAccountMappings: List<CashStatementMappingWriteDto> = emptyList(),
)

@Serializable
data class CashTransactionWriteDto(
    val target: CashWriteVersionedReferenceDto,
    val values: CashTransactionWriteValuesDto,
    val components: List<CashComponentWriteDto>,
)

@Serializable
data class CashTransactionWriteValuesDto(
    @SerialName("account_id") val accountId: String?,
    val state: String,
    @SerialName("record_origin") val recordOrigin: String,
    @SerialName("manual_transfer_id") val manualTransferId: String?,
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
)

@Serializable
data class CashComponentWriteDto(
    val reference: CashWriteReferenceDto,
    @SerialName("account_id") val accountId: String?,
    val amount: String?,
    val currency: String?,
    val ordinal: String,
    val label: String,
    @SerialName("source_key") val sourceKey: String,
    @SerialName("metadata_json") val metadataJson: JsonElement,
)

@Serializable
data class CashTransactionDeleteDto(val target: CashWriteVersionedReferenceDto, val reason: String)

@Serializable
data class CashRelationWriteDto(
    val target: CashWriteVersionedReferenceDto,
    val kind: String,
    @SerialName("primary_transaction") val primaryTransaction: CashWriteVersionedReferenceDto,
    @SerialName("secondary_transaction") val secondaryTransaction: CashWriteVersionedReferenceDto?,
    @SerialName("primary_component") val primaryComponent: CashWriteReferenceDto?,
    @SerialName("secondary_component") val secondaryComponent: CashWriteReferenceDto?,
    @SerialName("anchor_transaction") val anchorTransaction: CashWriteVersionedReferenceDto,
    @SerialName("anchor_component") val anchorComponent: CashWriteReferenceDto?,
    @SerialName("primary_role") val primaryRole: String?,
    @SerialName("secondary_role") val secondaryRole: String?,
    val state: String,
    val active: Boolean,
    @SerialName("decision_group_id") val decisionGroupId: String?,
    @SerialName("decision_source") val decisionSource: String,
    val evidence: JsonElement,
    @SerialName("applied_amount") val appliedAmount: String,
    @SerialName("rule_id") val ruleId: String,
    @SerialName("decision_reason") val decisionReason: String,
    val candidates: List<CashRelationCandidateWriteDto>,
)

@Serializable
data class CashRelationCandidateWriteDto(
    val transaction: CashWriteVersionedReferenceDto,
    val component: CashWriteReferenceDto?,
    val evidence: JsonElement,
    val validity: String,
)

@Serializable
data class CashIssueWriteDto(
    val target: CashWriteVersionedReferenceDto,
    val transaction: CashWriteVersionedReferenceDto,
    @SerialName("issue_key") val issueKey: String,
    val code: String,
    @SerialName("field_path") val fieldPath: String?,
    @SerialName("pending_source_payload") val pendingSourcePayload: JsonElement,
    @SerialName("pending_fields") val pendingFields: JsonElement,
    val origin: JsonElement,
    val candidates: List<CashWriteVersionedReferenceDto>,
)

@Serializable
data class CashEntryWriteDto(
    val target: CashWriteVersionedReferenceDto,
    @SerialName("root_transaction") val rootTransaction: CashWriteVersionedReferenceDto,
    @SerialName("identity_transaction") val identityTransaction: CashWriteVersionedReferenceDto,
    @SerialName("category_id") val categoryId: String?,
    val note: String,
    val counterparty: String,
    @SerialName("counterparty_account") val counterpartyAccount: String,
    @SerialName("counterparty_account_attrs") val counterpartyAccountAttrs: JsonElement,
    @SerialName("manual_fields") val manualFields: List<String>,
    @SerialName("split_inherit") val splitInherit: Boolean?,
    val members: List<CashEntryMemberWriteDto>,
)

@Serializable
data class CashEntryMemberWriteDto(
    val transaction: CashWriteVersionedReferenceDto,
    @SerialName("standalone_values") val standaloneValues: JsonElement,
    @SerialName("standalone_manual_fields") val standaloneManualFields: List<String>,
)

@Serializable
data class CashSourceBindingWriteDto(
    val target: CashWriteVersionedReferenceDto,
    val transaction: CashWriteReferenceDto,
    @SerialName("source_type") val sourceType: String,
    @SerialName("source_scope_kind") val sourceScopeKind: String,
    @SerialName("source_scope_key") val sourceScopeKey: String,
    @SerialName("identity_kind") val identityKind: String,
    @SerialName("identity_key") val identityKey: String,
    @SerialName("identity_strength") val identityStrength: String,
    @SerialName("stable_evidence_key") val stableEvidenceKey: String?,
    @SerialName("strategy_version") val strategyVersion: String,
    val coverage: JsonElement,
)

@Serializable
data class CashStatementMappingWriteDto(
    val target: CashWriteVersionedReferenceDto,
    @SerialName("source_type") val sourceType: String,
    @SerialName("identity_kind") val identityKind: String,
    @SerialName("source_account_key") val sourceAccountKey: String,
    @SerialName("account_id") val accountId: String?,
)
