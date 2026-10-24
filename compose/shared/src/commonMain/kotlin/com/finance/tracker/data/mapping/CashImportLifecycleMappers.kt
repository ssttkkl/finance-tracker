package com.finance.tracker.data

import com.finance.tracker.domain.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable
private data class RestoredImportChoicesDto(
    @SerialName("account_selections") val accounts: List<CashImportAccountInput>,
    @SerialName("row_choices") val rows: List<CashImportRowInput>,
    @SerialName("relation_choices") val relations: List<CashImportRelationInput>,
    @SerialName("entry_choices") val entries: List<CashImportEntryInput> = emptyList(),
)

internal fun CashImportIssueDto.toDomain() = ImportIssue(code, message, fieldPath, blockingScope, nextAction, afterImportAction)
internal fun CashImportSessionDto.toDomain() = ImportContext(
    sessionId, sessionRevision, expiresAt, state,
    files.map { ImportContextFile(it.fileRef, it.clientFileRef, it.displayName, it.sourceType, it.sourceScopeLabel, it.status, it.recognizedRowCount, it.issues.map(CashImportIssueDto::toDomain), it.canRetry) },
    sourceAccounts.map { group -> ImportSourceAccount(group.sourceAccountRef, group.sourceType, group.displayName, group.currencies, group.rowCount,
        group.suggestion.accountId?.let { ImportAccountTarget(it, group.suggestion.expectedAccountRevision) }, group.suggestion.basis,
        group.selection?.let { ImportAccountTarget(it.accountId, it.expectedAccountRevision) }) },
    lastPreviewInput?.let { input ->
        val value = apiJson.decodeFromJsonElement<RestoredImportChoicesDto>(input)
        ImportChoiceSet(value.accounts.map { ImportAccountSelection(it.sourceAccountRef, ImportAccountTarget(it.accountId, it.expectedAccountRevision)) },
            value.rows.map { ImportRecordChoice(it.rowRef, ImportRowDecision.valueOf(it.action.name), it.patch.toStructuredObject()) },
            value.relations.map { ImportPairChoice(it.suggestionId, ImportPairDecision.valueOf(it.action.name), it.candidateId, it.commonFields?.toStructuredObject(), it.inheritCommonFields) }, value.entries.map { ImportEntryChoice(it.editRef, it.patch.toStructuredObject()) })
    }, lastPlanId, canPreview, receiptId,
)
internal fun ImportChoiceSet.toDto(revision: Long, previousPlanId: String?) = CashImportPreviewInput(
    revision, previousPlanId,
    accountSelections.map { CashImportAccountInput(it.sourceAccountRef, it.target.accountId, it.target.revision) },
    rows.map { CashImportRowInput(it.rowRef, CashImportRowAction.valueOf(it.action.name), it.patch.toJsonObject()) },
    relations.map { CashImportRelationInput(it.suggestionId, CashImportRelationAction.valueOf(it.action.name), it.candidateId, it.commonFields?.toJsonObject(), it.inheritCommonFields) },
    entries.map { CashImportEntryInput(it.editRef, it.patch.toJsonObject()) },
)
internal fun CashPlanMemberRefDto.toDomain() = ImportMemberRef(rowRef, transactionId)
internal fun CashPlanDisplayDto.toDomain() = ImportDisplay(occurredAt, amount, currency, counterparty, note, accounts, categoryId, counterpartyAccount, counterpartyAccountAttrs)
internal fun CashPlanItemDto.toDomain() = ImportProjectedItem(previewItemId, confirmationStatus, anticipatedState, display.toDomain(), statisticsEligible,
    memberRefs.map(CashPlanMemberRefDto::toDomain), attentionReasons.map(CashImportIssueDto::toDomain), detailAvailable)
internal fun CashImportPlanDto.toDomain() = ImportPlan(planId, sessionId, sessionRevision, expiresAt, summary, itemCounts, canCommit,
    items.map(CashPlanItemDto::toDomain), fileResults.map { ImportPlanFile(it.fileRef, it.displayName, it.status, it.recognizedRowCount, it.issues.map(CashImportIssueDto::toDomain), it.code, it.canRetry) },
    pages.items, invalidatedChoices.map { ImportInvalidatedChoice(it.kind, it.reference, it.code) })
internal fun CashPlanItemPageDto.toDomain() = ImportItemPage(items.map(CashPlanItemDto::toDomain), nextCursor)
internal fun CashPlanProcessingPageDto.toDomain() = ImportProcessingPage(items.map { ImportProcessingDetail(it.rowRef, it.transactionId, it.fileRef, it.plannedAction, it.operationRef, it.duplicateOf, it.code, it.message, it.fieldPath, it.blockingScope, it.nextAction) }, nextCursor)
internal fun CashPlanItemDetailDto.toDomain() = ImportItemDetail(previewItemId,
    members.map { ImportMemberDetail(it.rowRef, it.transactionId, it.plannedAction, it.fields.toStructuredObject(), it.editableFields, it.allowedActions, it.rowRevision) },
    fieldDiffs.map { ImportFieldDiff(it.rowRef, it.transactionId, it.field, it.current.toStructuredValue(), it.incoming.toStructuredValue(), it.effective.toStructuredValue(), it.resolution) },
    issues.map(CashImportIssueDto::toDomain),
    relationChoices.map { option -> ImportPairOption(option.suggestionId, option.kind, option.subtype, option.action, option.candidateId,
        option.candidates.map { ImportPairCandidate(it.candidateId, it.display?.toDomain(), it.memberRefs.map(CashPlanMemberRefDto::toDomain), it.code) },
        option.memberRefs.map(CashPlanMemberRefDto::toDomain), option.allowedActions, option.code,
        option.commonFieldConflicts.mapValues { (_, values) -> values.map { it.toStructuredValue() } }, option.commonFields?.toStructuredObject(), option.inheritCommonFields) },
    relationEffects.map { ImportPairEffect(it.relationId, it.kind, it.subtype, it.effect, it.memberRefs.map(CashPlanMemberRefDto::toDomain), it.code) },
    entry?.let { ImportEntryFields(it.fields.toStructuredObject(), it.editableFields, it.editRef) })
internal fun ImportAccountListItemDto.toDomain() = ImportAccountListItem(id, name, type, active, currencies)
internal fun ImportAccountDto.toDomain() = ImportAccount(accountId, name, type, active, currencies, accountRevision, deleted, capabilities, receiptId, committed)
internal fun CashMutationReceiptDto.toDomain() = ImportReceipt(receiptId, planId, operationKind, committed, createdAt, status, summary, legacyByAccount,
    needsAttentionCount, pendingItemCount, projectionVersion, retryContextStatus, retryContextExpiresAt, retainedTransactionId, accountId, accountRevision, account?.toDomain(),
    commitOutcome?.let { ImportCommitOutcome(it.hasNewFailures, it.hasBusinessWrites, it.reasonCodes) })
internal fun CashMutationResultPageDto.toDomain() = ImportResultPage(items.map { ImportResultItem(it.resultId, it.resultKind, it.itemRef, it.outcome, it.code, it.message, it.operationRef, it.transactionId, it.relationId, it.accountId) }, nextAfter,
    projectionRefs.map { ImportProjectionRef(it.transactionId, it.projectionId) }, projectionVersion)
internal fun CashMutationResolveDto.toDomain() = ImportResolveResult(status, receipt?.toDomain())
