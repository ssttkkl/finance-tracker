package com.finance.tracker.data

import com.finance.tracker.domain.*
import kotlinx.coroutines.CancellationException

class KtorCashMaintenanceRepository(private val api: FinanceApiClient) : CashMaintenanceRepository {
    override suspend fun associationTargets(transactionId: String, search: CashAssociationSearch) = guarded {
        val value = api.readCashAssociationTargets(transactionId, search)
        CashAssociationPage(value.items.map { it.toDomain() }, value.nextCursor)
    }
    override suspend fun currentGroup(projectionId: String) = guarded {
        val group = api.readCurrentCashMembers(projectionId)
        CashCurrentGroup(group.projectionVersion, group.members.map { CashCurrentMember(it.transactionId, it.rowRevision, it.confirmationStatus) })
    }
    override suspend fun sources(projectionId: String) = guarded {
        val value = api.readCashSourceEvidence(projectionId)
        CashSourceEvidence(value.projectionVersion, value.projection.toDomain().copy(projectionVersion = value.projectionVersion), value.members.map { member ->
            val source = api.readCurrentCashRecord(member.id)
            if (source.transactionId != member.id || source.rowRevision != member.rowRevision ||
                source.projectionRef?.projectionId != value.projection.projectionId || source.projectionRef?.projectionVersion != value.projectionVersion) {
                throw ApiFailure("record_changed", 409)
            }
            CashSourceMember(member.id, member.occurredAt, member.amount, member.currency, member.counterparty, member.note, member.counterpartyAccount,
                member.account?.name ?: member.accountName, member.components.map { part -> CashSourceComponent(part.id, part.amount, part.currency, part.account?.name ?: part.accountName, part.label) },
                source.fields["source_payload"]?.toStructuredValue() as? StructuredObject,
                source.fields.toStructuredObject(), source.toDomain().relations)
        })
    }
    override suspend fun record(transactionId: String) = guarded { api.readCurrentCashRecord(transactionId).toDomain() }
    override suspend fun saveDraft(transactionId: String, revision: Long, patch: StructuredObject, key: String) = guarded {
        val result = api.saveCashDraft(transactionId, revision, patch.toJsonObject(), key)
        CashDraftSaveResult(result.receipt.toDomain(), result.currentRecordStatus, result.currentRecord?.toDomain())
    }
    override suspend fun preview(input: CashMaintenanceRequest) = guarded { api.previewCashMaintenance(input.toDto()).toDomain() }
    override suspend fun plan(planId: String) = guarded { api.readCashMaintenancePlan(planId).toDomain() }
    override suspend fun currentPlan(contextId: String) = guarded { api.readCurrentCashMaintenancePlan(contextId).toDomain() }
    override suspend fun items(planId: String, cursor: String?, confirmationStatus: String) = guarded {
        api.readCashMaintenanceItems(planId, cursor, confirmationStatus = confirmationStatus).toDomain()
    }
    override suspend fun item(planId: String, itemId: String) = guarded { api.readCashMaintenanceItem(planId, itemId).toDomain() }
    override suspend fun commit(command: ImportFrozenCommand) = guarded { api.commitCashMaintenance(command.planId, command.revision, command.idempotencyKey).toDomain() }
    override suspend fun resolve(command: ImportFrozenCommand) = guarded { api.resolveCashMutation(command.planId, command.idempotencyKey).toDomain() }
    override suspend fun receiptByKey(key: String) = guarded { api.readCashMutationReceiptByKey(key).toDomain() }
    private suspend fun <T> guarded(action: suspend () -> T): T = try { action() }
    catch (error: CancellationException) { throw error }
    catch (error: Exception) { throw error.toDomainFailure() }
}
internal fun CashCurrentRecordDto.toDomain() = CashCurrentRecord(recordOrigin,
    entry?.let { CashPublicEntry(it.entryId, it.revision, it.fields.toStructuredObject(), it.manualFields, it.editableFields, it.supportsTransferEdit) },
    transactionId, state, rowRevision, confirmationStatus, fields.toStructuredObject(), editableFields,
    issues.map { issue -> CashRecordIssue(issue.issueId, issue.revision, issue.code, issue.message, issue.fieldPath, issue.blockingScope, issue.nextAction,
        issue.allowedResolutions, issue.pendingFields.toStructuredObject(), issue.pendingSourcePayload?.toStructuredObject(),
        issue.candidates.map { CashIdentityCandidate(it.transactionId, it.rowRevision, it.state, it.display.toDomain()) }, issue.afterImportAction, issue.pendingSourcePayloadPresent) },
    relations.map { CashSavedRelation(it.relationId, it.kind, it.state, it.active, it.rowRevision, it.decisionGroupId) },
    projectionRef?.let { CashCurrentProjectionRef(it.transactionId, it.projectionId, it.attentionCount, it.projectionVersion) },
    componentChoices.map { CashAssociationComponentChoice(it.anchorComponentId, it.targetComponentId) }, unavailableReason)
internal fun CashMaintenancePlanDto.toDomain() = CashMaintenancePlan(planId, editContextId, planRevision, expiresAt, summary, itemCounts, canCommit,
    items.map(CashPlanItemDto::toDomain), pages.items, invalidatedChoices.map { ImportInvalidatedChoice(it.kind, it.reference, it.code) },
    additionalEffects?.map { CashAdditionalEffect(it.kind, it.reference, it.field) })
internal fun CashMaintenanceRequest.toDto() = CashMaintenanceInput(intent, transactionId, expectedRevision, patch.toJsonObject(), entryId, expectedEntryRevision,
    memberPatches.map { CashMemberPatchInput(it.transactionId, it.patch.toJsonObject()) },
    relationChoices.map { CashMaintenanceRelationInput(it.suggestionId, it.action, it.candidateId, it.commonFields?.toJsonObject(), it.inheritCommonFields) },
    issueResolutions?.map { CashIssueResolutionInput(it.issueId, it.revision, it.resolution, it.targetTransactionId, it.targetRevision) },
    editContextId, previousPlanId, expectedPlanRevision,
    relationAdditions.map { CashRelationAdditionInput(it.targetTransactionId, it.expectedTargetRevision, it.anchorComponentId,
        it.targetComponentId, it.kind, it.commonFields?.toJsonObject()) })
