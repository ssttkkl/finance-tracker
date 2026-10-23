package com.finance.tracker.domain

data class CashPublicEntry(
    val entryId: String, val revision: Long, val fields: StructuredObject,
    val manualFields: List<String>, val editableFields: List<String>, val supportsTransferEdit: Boolean,
)
data class CashIdentityCandidate(val transactionId: String, val rowRevision: Long, val state: String, val display: ImportDisplay)
data class CashRecordIssue(
    val issueId: String, val revision: Long, val code: String, val message: String,
    val fieldPath: String?, val blockingScope: String, val nextAction: String,
    val allowedResolutions: List<String>, val pendingFields: StructuredObject,
    val pendingSourcePayload: StructuredObject?, val candidates: List<CashIdentityCandidate>, val afterImportAction: String? = null,
)
data class CashSavedRelation(
    val relationId: String, val kind: String, val subtype: String?, val state: String,
    val active: Boolean, val rowRevision: Long, val decisionGroupId: String?,
)
data class CashCurrentProjectionRef(val transactionId: String, val projectionId: String, val attentionCount: Long, val projectionVersion: Long)
data class CashCurrentRecord(
    val recordOrigin: String, val entry: CashPublicEntry?, val transactionId: String,
    val state: String, val rowRevision: Long, val confirmationStatus: String,
    val fields: StructuredObject, val editableFields: List<String>, val issues: List<CashRecordIssue>,
    val relations: List<CashSavedRelation>, val projectionRef: CashCurrentProjectionRef?,
)
data class CashMemberPatch(val transactionId: String, val patch: StructuredObject)
data class CashIssueResolution(
    val issueId: String, val revision: Long, val resolution: String,
    val targetTransactionId: String? = null, val targetRevision: Long? = null,
)
data class CashRelationDecision(
    val suggestionId: String, val action: String, val candidateId: String? = null,
    val commonFields: StructuredObject? = null, val inheritCommonFields: Boolean? = null,
)
data class CashMaintenanceRequest(
    val intent: String, val transactionId: String, val expectedRevision: Long,
    val patch: StructuredObject = StructuredObject(emptyMap()),
    val entryId: String? = null, val expectedEntryRevision: Long? = null,
    val memberPatches: List<CashMemberPatch> = emptyList(), val relationChoices: List<CashRelationDecision> = emptyList(),
    val issueResolutions: List<CashIssueResolution>? = null,
    val editContextId: String? = null, val previousPlanId: String? = null, val expectedPlanRevision: Long? = null,
)
data class CashMaintenancePlan(
    val planId: String, val editContextId: String, val revision: Long, val expiresAt: String,
    val summary: Map<String, Long>, val itemCounts: Map<String, Long>, val canCommit: Boolean,
    val items: List<ImportProjectedItem>, val nextCursor: String?, val invalidatedChoices: List<ImportInvalidatedChoice>,
)
data class CashDraftSaveResult(val receipt: ImportReceipt, val currentRecordStatus: String, val currentRecord: CashCurrentRecord?)

interface CashMaintenanceRepository {
    suspend fun record(transactionId: String): CashCurrentRecord
    suspend fun saveDraft(transactionId: String, revision: Long, patch: StructuredObject, key: String): CashDraftSaveResult
    suspend fun preview(input: CashMaintenanceRequest): CashMaintenancePlan
    suspend fun plan(planId: String): CashMaintenancePlan
    suspend fun currentPlan(contextId: String): CashMaintenancePlan
    suspend fun items(planId: String, cursor: String? = null, confirmationStatus: String = "all"): ImportItemPage
    suspend fun item(planId: String, itemId: String): ImportItemDetail
    suspend fun commit(command: ImportFrozenCommand): ImportReceipt
    suspend fun resolve(command: ImportFrozenCommand): ImportResolveResult
    suspend fun receiptByKey(key: String): ImportReceipt
}
