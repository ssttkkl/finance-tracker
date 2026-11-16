package com.finance.tracker.data

import com.finance.tracker.domain.*
import com.finance.tracker.core.LocalCashSourceRecords
import kotlinx.coroutines.CancellationException

class KtorCashMaintenanceRepository(private val api: FinanceApiClient,
    private val captureRefresh: suspend () -> (suspend (String?) -> Unit) = { {} }) : CashMaintenanceRepository {
    private var operations: CashBusinessOperations? = null
    internal constructor(api: FinanceApiClient, operations: CashBusinessOperations) : this(api) { this.operations = operations }

    override suspend fun associationTargets(transactionId: String, search: CashAssociationSearch) = guarded { api, refresh ->
        val value = api.readCashAssociationTargets(transactionId, search)
        CashAssociationPage(value.items.map { it.toDomain() }, value.nextCursor)
    }
    override suspend fun currentGroup(projectionId: String) = guarded { api, refresh ->
        val group = api.readCurrentCashMembers(projectionId)
        CashCurrentGroup(group.projectionVersion, group.members.map { CashCurrentMember(it.transactionId, it.rowRevision, it.confirmationStatus) })
    }
    override suspend fun sources(projectionId: String) = guarded { api, refresh ->
        val value = api.readCashSourceEvidence(projectionId)
        CashSourceEvidence(value.projectionVersion, value.projection.toDomain().copy(projectionVersion = value.projectionVersion), value.members.map { member ->
            val source = api.readCurrentCashRecord(member.id)
            member.toDomain(source, value.projection.projectionId, value.projectionVersion)
        })
    }
    override suspend fun record(transactionId: String) = guarded { api, refresh -> api.readCurrentCashRecord(transactionId).toDomain() }
    override suspend fun saveDraft(transactionId: String, revision: Long, patch: StructuredObject, key: String): CashDraftSaveResult {
        val frozen = patch.toJsonObject()
        return guarded { api, refresh ->
        val result = api.saveCashDraft(transactionId, revision, frozen, key)
        val saved = CashDraftSaveResult(result.receipt.toDomain(), result.currentRecordStatus, result.currentRecord?.toDomain())
        if (saved.receipt.committed) refresh(saved.receipt.ledgerSeq)
        saved
        }
    }
    override suspend fun preview(input: CashMaintenanceRequest): CashMaintenancePlan {
        val frozen = input.toDto()
        return guarded { api, _ -> api.previewCashMaintenance(frozen).toDomain() }
    }
    override suspend fun plan(planId: String) = guarded { api, refresh -> api.readCashMaintenancePlan(planId).toDomain() }
    override suspend fun currentPlan(contextId: String) = guarded { api, refresh -> api.readCurrentCashMaintenancePlan(contextId).toDomain() }
    override suspend fun items(planId: String, cursor: String?, confirmationStatus: String) = guarded { api, refresh ->
        api.readCashMaintenanceItems(planId, cursor, confirmationStatus = confirmationStatus).toDomain()
    }
    override suspend fun item(planId: String, itemId: String) = guarded { api, refresh -> api.readCashMaintenanceItem(planId, itemId).toDomain() }
    override suspend fun commit(command: ImportFrozenCommand) = guarded { api, refresh ->
        val receipt = api.commitCashMaintenance(command.planId, command.revision, command.idempotencyKey).toDomain()
        if (receipt.committed) refresh(receipt.ledgerSeq)
        receipt
    }
    override suspend fun resolve(command: ImportFrozenCommand) = guarded { api, refresh ->
        val result = api.resolveCashMutation(command.planId, command.idempotencyKey).toDomain()
        if (result.status == "committed" && result.receipt?.committed == true) refresh(result.receipt.ledgerSeq)
        result
    }
    override suspend fun receiptByKey(key: String) = guarded { api, refresh ->
        api.readCashMutationReceiptByKey(key).toDomain().also { if (it.committed) refresh(it.ledgerSeq) }
    }
    private suspend fun <T> guarded(action: suspend (FinanceApiClient, suspend (String?) -> Unit) -> T): T = try {
        val bound = operations
        if (bound == null) action(api, captureRefresh()) else bound.run(action)
    }
    catch (error: CancellationException) { throw error }
    catch (error: Exception) { throw error.toDomainFailure() }
}
internal fun CashCurrentRecordDto.toDomain() = CashCurrentRecord(recordOrigin,
    entry?.let { CashPublicEntry(it.entryId, it.revision, it.fields.toStructuredObject(), it.manualFields, it.editableFields, it.supportsTransferEdit, it.originalNote, it.originalNoteToken) },
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
        it.targetComponentId, it.kind, it.commonFields?.toJsonObject()) }, restoreNoteToken)

internal fun LocalCashSourceRecords.toDomain() = CashSourceEvidence(evidence.projectionVersion,
    evidence.projection.toDomain().copy(projectionVersion = evidence.projectionVersion), evidence.members.map { member ->
        member.toDomain(records.getValue(member.id), evidence.projection.projectionId, evidence.projectionVersion)
    })

private fun CashSourceMemberDto.toDomain(source: CashCurrentRecordDto, projectionId: String, version: Long): CashSourceMember {
    if (source.transactionId != id || source.rowRevision != rowRevision || source.projectionRef?.projectionId != projectionId ||
        source.projectionRef?.projectionVersion != version) throw ApiFailure("record_changed", 409)
    return CashSourceMember(id, occurredAt, amount, currency, counterparty, note, counterpartyAccount,
        account?.name ?: accountName, components.map { part -> CashSourceComponent(part.id, part.amount, part.currency, part.account?.name ?: part.accountName, part.label) },
        source.fields["source_payload"]?.toStructuredValue() as? StructuredObject,
        source.fields.toStructuredObject(), source.toDomain().relations)
}
