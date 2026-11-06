@file:kotlinx.serialization.UseSerializers(ExactJsonElementSerializer::class)

package com.finance.tracker.business.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

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
    @SerialName("issue_resolutions") val issueResolutions: List<CashIssueResolutionDto> = emptyList(),
    val entries: List<CashEntryWriteDto> = emptyList(),
    @SerialName("entry_dependencies") val entryDependencies: List<CashWriteVersionedReferenceDto> = emptyList(),
    @SerialName("source_bindings") val sourceBindings: List<CashSourceBindingWriteDto> = emptyList(),
    @SerialName("source_empty_pages") val sourceEmptyPages: List<LedgerSourceEmptyPageFactDto> = emptyList(),
    @SerialName("statement_account_mappings") val statementAccountMappings: List<CashStatementMappingWriteDto> = emptyList(),
    @SerialName("entry_split_choices") val entrySplitChoices: List<CashEntrySplitChoiceDto> = emptyList(),
)

@Serializable
data class CashEntrySplitChoiceDto(
    val entry: CashWriteVersionedReferenceDto,
    @Serializable(with = CashSplitInheritanceSerializer::class)
    @SerialName("inherit_common_fields") val inheritCommonFields: Boolean,
)

private object CashSplitInheritanceSerializer : KSerializer<Boolean> {
    override val descriptor = Boolean.serializer().descriptor
    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)
    override fun deserialize(decoder: Decoder): Boolean {
        if (decoder !is JsonDecoder) return decoder.decodeBoolean()
        val token = decoder.decodeJsonElement()
        if (token !is JsonPrimitive || token.isString || token.booleanOrNull == null) {
            throw SerializationException("inherit_common_fields must be a JSON boolean")
        }
        return requireNotNull(token.booleanOrNull)
    }
}

@Serializable
data class CashTransactionWriteDto(
    val target: CashWriteVersionedReferenceDto,
    val values: CashTransactionWriteValuesDto,
    val components: List<CashComponentWriteDto>,
    @SerialName("edited_fields") val editedFields: List<String> = emptyList(),
    @SerialName("entry_edit") val entryEdit: CashEntryBeforeRelationEditDto? = null,
)

@Serializable
data class CashEntryBeforeRelationEditDto(
    val entry: CashWriteVersionedReferenceDto?,
    val patch: JsonObject,
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
sealed interface CashIssueResolutionDto {
    val issue: CashWriteVersionedReferenceDto
}

@Serializable
@SerialName("apply_source")
data class ApplySourceIssueResolutionDto(override val issue: CashWriteVersionedReferenceDto) : CashIssueResolutionDto

@Serializable
@SerialName("keep_existing")
data class KeepExistingIssueResolutionDto(override val issue: CashWriteVersionedReferenceDto) : CashIssueResolutionDto

@Serializable
@SerialName("create_independent")
data class CreateIndependentIssueResolutionDto(override val issue: CashWriteVersionedReferenceDto) : CashIssueResolutionDto

@Serializable
@SerialName("merge_into")
data class MergeIntoIssueResolutionDto(
    override val issue: CashWriteVersionedReferenceDto,
    @SerialName("target_transaction") val targetTransaction: CashWriteVersionedReferenceDto,
) : CashIssueResolutionDto

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
    @SerialName("root_transaction") val rootTransaction: CashWriteVersionedReferenceDto,
    @SerialName("category_id") val categoryId: String?,
    val note: String,
    val counterparty: String,
    @SerialName("counterparty_account") val counterpartyAccount: String,
    @SerialName("counterparty_account_attrs") val counterpartyAccountAttrs: JsonElement,
    @SerialName("manual_fields") val manualFields: List<String>,
    val members: List<CashEntryMemberWriteDto>,
    @SerialName("edited_fields") val editedFields: List<String> = emptyList(),
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
