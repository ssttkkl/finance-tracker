package com.finance.tracker.domain

enum class ImportRowDecision { APPLY, KEEP_EXISTING, DEFER }
enum class ImportPairDecision { ACCEPT, DEFER, IGNORE }
enum class ImportResultKind { ROW, RELATION }
data class ImportFrozenCommand(val planId: String, val revision: Long, val idempotencyKey: String)

data class ImportAccountTarget(val accountId: String?, val revision: Long?)
data class ImportAccountSelection(val sourceAccountRef: String, val target: ImportAccountTarget)
data class ImportRecordChoice(val rowRef: String, val action: ImportRowDecision, val patch: StructuredObject = StructuredObject(emptyMap()))
data class ImportPairChoice(val suggestionId: String, val action: ImportPairDecision, val candidateId: String? = null,
    val commonFields: StructuredObject? = null, val inheritCommonFields: Boolean? = null)
data class ImportChoiceSet(
    val accountSelections: List<ImportAccountSelection> = emptyList(),
    val rows: List<ImportRecordChoice> = emptyList(),
    val relations: List<ImportPairChoice> = emptyList(),
)
data class ImportIssue(
    val code: String, val message: String, val fieldPath: String?, val blockingScope: String,
    val nextAction: String, val afterImportAction: String? = null,
)
data class ImportContextFile(
    val fileRef: String, val clientFileRef: String, val displayName: String,
    val sourceType: String?, val sourceScopeLabel: String?, val status: String,
    val recognizedRowCount: Int?, val issues: List<ImportIssue>, val canRetry: Boolean,
)
data class ImportSourceAccount(
    val sourceAccountRef: String, val sourceType: String, val displayName: String,
    val currencies: List<String>, val rowCount: Int, val suggestion: ImportAccountTarget?,
    val suggestionBasis: String, val selection: ImportAccountTarget?,
)
data class ImportContext(
    val sessionId: String, val revision: Long?, val expiresAt: String?, val state: String,
    val files: List<ImportContextFile>, val sourceAccounts: List<ImportSourceAccount>,
    val lastChoices: ImportChoiceSet?, val lastPlanId: String?, val canPreview: Boolean, val receiptId: String?,
)

// 密码和平台文件句柄仅留在本次任务内存，不参与默认 data class 诊断输出。
class ImportUpload(
    val clientFileRef: String, val file: FileSource, val password: String? = null,
    val sourceHint: String? = null, val sourceScope: ImportSourceScope? = null,
)
data class ImportSourceScope(val label: String?)
data class ImportLocalFileFailure(val clientFileRef: String, val code: String)
data class ImportUploadInterruption(val code: String, val status: Int, val receiptId: String?, val unprocessedFileRefs: List<String>)
data class ImportUploadBatch(val context: ImportContext?, val localFailures: List<ImportLocalFileFailure>, val interruption: ImportUploadInterruption?)

data class ImportMemberRef(val rowRef: String?, val transactionId: String?)
data class ImportDisplay(
    val occurredAt: String?, val amount: String?, val currency: String?, val counterparty: String?,
    val note: String?, val accounts: List<String>,
    val categoryId: String? = null, val counterpartyAccount: String? = null, val counterpartyAccountAttrs: List<String>? = null,
)
data class ImportProjectedItem(
    val itemId: String, val confirmationStatus: String, val anticipatedState: String, val display: ImportDisplay,
    val statisticsEligible: Boolean, val members: List<ImportMemberRef>, val attentionReasons: List<ImportIssue>, val detailAvailable: Boolean,
)
data class ImportPlanFile(
    val fileRef: String, val displayName: String, val status: String, val recognizedRowCount: Int?,
    val issues: List<ImportIssue>, val code: String?, val canRetry: Boolean,
)
data class ImportInvalidatedChoice(val kind: String, val reference: String, val code: String)
data class ImportPlan(
    val planId: String, val sessionId: String, val sessionRevision: Long, val expiresAt: String,
    val summary: Map<String, Long>, val itemCounts: Map<String, Long>, val canCommit: Boolean,
    val items: List<ImportProjectedItem>, val files: List<ImportPlanFile>, val nextCursor: String?,
    val invalidatedChoices: List<ImportInvalidatedChoice>,
)
data class ImportItemPage(val items: List<ImportProjectedItem>, val nextCursor: String?)
data class ImportProcessingDetail(
    val rowRef: String?, val transactionId: String?, val fileRef: String?, val plannedAction: String,
    val operationRef: String?, val duplicateOf: String?, val code: String?, val message: String?,
    val fieldPath: String?, val blockingScope: String?, val nextAction: String?,
)
data class ImportProcessingPage(val items: List<ImportProcessingDetail>, val nextCursor: String?)
data class ImportMemberDetail(
    val rowRef: String?, val transactionId: String?, val plannedAction: String, val fields: StructuredObject,
    val editableFields: List<String>, val allowedActions: List<String>, val rowRevision: Long?,
)
data class ImportFieldDiff(
    val rowRef: String?, val transactionId: String?, val field: String, val current: StructuredValue,
    val incoming: StructuredValue, val effective: StructuredValue, val resolution: String,
)
data class ImportPairCandidate(val candidateId: String, val display: ImportDisplay?, val members: List<ImportMemberRef>, val code: String?)
data class ImportPairOption(
    val suggestionId: String, val kind: String?, val subtype: String?, val action: String, val candidateId: String?,
    val candidates: List<ImportPairCandidate>, val members: List<ImportMemberRef>, val allowedActions: List<String>, val code: String?,
    val commonFieldConflicts: Map<String, List<StructuredValue>> = emptyMap(),
    val commonFields: StructuredObject? = null, val inheritCommonFields: Boolean? = null,
)
data class ImportPairEffect(val relationId: String, val kind: String, val subtype: String?, val effect: String, val members: List<ImportMemberRef>, val code: String?)
data class ImportEntryFields(val fields: StructuredObject, val editableFields: List<String>)
data class ImportItemDetail(
    val itemId: String, val members: List<ImportMemberDetail>, val fieldDiffs: List<ImportFieldDiff>,
    val issues: List<ImportIssue>, val relationChoices: List<ImportPairOption>, val relationEffects: List<ImportPairEffect>,
    val entry: ImportEntryFields? = null,
)
data class ImportAccountListItem(val id: String, val name: String, val type: String, val active: Boolean, val currencies: List<String>?)
data class ImportAccount(
    val accountId: String, val name: String, val type: String, val active: Boolean, val currencies: List<String>,
    val revision: Long, val deleted: Boolean, val capabilities: Map<String, Boolean>, val receiptId: String?, val committed: Boolean?,
)
data class ImportReceipt(
    val receiptId: String, val planId: String?, val operationKind: String, val committed: Boolean,
    val createdAt: String, val status: String?, val summary: Map<String, Long>, val legacyByAccount: Map<String, Long>,
    val needsAttentionCount: Long?, val pendingItemCount: Long?, val projectionVersion: Long?,
    val retryContextStatus: String?, val retryContextExpiresAt: String?, val retainedTransactionId: String?,
    val accountId: String?, val accountRevision: Long?, val account: ImportAccount?,
)
data class ImportResultItem(
    val resultId: String, val resultKind: String, val itemRef: String, val outcome: String, val code: String?,
    val message: String?, val operationRef: String?, val transactionId: String?, val relationId: String?, val accountId: String?,
)
data class ImportProjectionRef(val transactionId: String, val projectionId: String)
data class ImportResultPage(val items: List<ImportResultItem>, val nextAfter: String?, val projectionRefs: List<ImportProjectionRef>, val projectionVersion: Long)
data class ImportResolveResult(val status: String, val receipt: ImportReceipt?)

interface CashImportLifecycleRepository {
    suspend fun upload(files: List<ImportUpload>, context: ImportContext? = null, replacingFileRef: String? = null, onContext: suspend (ImportContext) -> Unit = {}): ImportUploadBatch
    suspend fun context(sessionId: String): ImportContext
    suspend fun setSourceScope(sessionId: String, fileRef: String, revision: Long, label: String?): ImportContext
    suspend fun removeFile(sessionId: String, fileRef: String, revision: Long): ImportContext
    suspend fun cancel(sessionId: String): ImportContext
    suspend fun preview(sessionId: String, revision: Long, previousPlanId: String?, choices: ImportChoiceSet): ImportPlan
    suspend fun plan(planId: String): ImportPlan
    suspend fun items(planId: String, cursor: String? = null, limit: Int = 50, confirmationStatus: String = "all"): ImportItemPage
    suspend fun item(planId: String, itemId: String): ImportItemDetail
    suspend fun processing(planId: String, cursor: String? = null, limit: Int = 50): ImportProcessingPage
    suspend fun confirm(planId: String, revision: Long, idempotencyKey: String): ImportReceipt
    suspend fun receipt(receiptId: String): ImportReceipt
    suspend fun receiptByKey(key: String): ImportReceipt
    suspend fun results(receiptId: String, kind: ImportResultKind, after: String = "0", limit: Int = 50): ImportResultPage
    suspend fun resolve(planId: String, idempotencyKey: String): ImportResolveResult
    suspend fun retry(receiptId: String, idempotencyKey: String, rowRefs: List<String>? = null): ImportContext
    suspend fun accounts(): List<ImportAccountListItem>
    suspend fun account(accountId: String): ImportAccount
    suspend fun createAccount(name: String, type: String, currencies: List<String>, idempotencyKey: String): ImportAccount
    suspend fun updateAccount(accountId: String, revision: Long, patch: StructuredObject, idempotencyKey: String): ImportAccount
    suspend fun deleteAccount(accountId: String, revision: Long, idempotencyKey: String): ImportAccount
}
