package com.finance.tracker.data

import com.finance.tracker.business.contracts.AccountReferenceDto
import com.finance.tracker.business.contracts.CashFiltersDto
import com.finance.tracker.business.contracts.CashPageDto
import com.finance.tracker.business.contracts.CategoryDirectoryDto
import com.finance.tracker.business.contracts.LedgerOptionsDto
import com.finance.tracker.business.contracts.SessionDto
import com.finance.tracker.business.contracts.CashWriteSetRequestDto
import com.finance.tracker.business.contracts.CashMutationReceiptDto
import com.finance.tracker.business.domain.CashImportPlanError
import com.finance.tracker.business.domain.CASH_RULES_VERSION
import com.finance.tracker.business.domain.recalculate
import com.finance.tracker.business.domain.LocalCashImportReview
import com.finance.tracker.business.domain.calculateLocalCashImportBusinessPlan
import com.finance.tracker.business.domain.freezeLocalCashImportPreviewPlan
import com.finance.tracker.business.domain.CashFundingGroup
import com.finance.tracker.business.domain.CashFundingSuggestion
import com.finance.tracker.business.domain.scanLocalCashFunding
import com.finance.tracker.business.domain.suggestLocalCashFundingAccounts
import com.finance.tracker.core.LocalCashLedgerOwner
import com.finance.tracker.domain.LedgerCacheScope
import com.finance.tracker.domain.LedgerSnapshotSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonArray
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock

/** One fixed recovery operation. Retain this instance, including after failure, until close succeeds. */
internal class LedgerSessionCacheOwner(
    private val api: FinanceApiClient,
    private val directory: LedgerSessionDirectory,
    private val ticket: LedgerSessionTicket,
    private val scope: LedgerCacheScope,
    private val credentials: ApiCredentialSnapshot,
    private val source: LedgerSnapshotSource = KtorLedgerSnapshotSource(api, credentials),
    private val openCache: suspend (existingOnly: Boolean, registration: LedgerSessionRegistration) -> LedgerStorage,
) {
    private val token = requireNotNull(credentials.token?.takeIf { it.isNotBlank() }) { "ledger_session_token_invalid" }
    private val resourceMutex = Mutex()
    private val submissionMutex = Mutex()
    // Also fences an open which has not returned its storage yet; the browsing owner cannot fence that interval.
    private val closing = MutableStateFlow(false)
    private class Resource(val storage: LedgerStorage, val browsing: LocalCashLedgerOwner)
    private var owned: Resource? = null // Only under resourceMutex; failed closure must retain this reference.

    init {
        directory.validateScope(scope)
        require(credentials.apiOrigin == scope.apiOrigin && ticket.apiOrigin == scope.apiOrigin) { "ledger_session_scope_invalid" }
    }

    /** No HTTP and no creation of substitute materials. Null means no eligible bound identity. */
    suspend fun readOfflineSession(): SessionDto? {
        requireOpen()
        return resourceMutex.withLock {
            requireOpen()
            withContext(NonCancellable) {
                requireCurrent(requirePublished = true)
                val resource = acquire(existingOnly = true)
                val session = resource.storage.readOfflineSession(scope, token)
                requireCurrent(requirePublished = true)
                requireOpen()
                session
            }
        }
    }

    /** The caller has verified this session with the original credentials; network work holds no resource mutex. */
    suspend fun refresh(session: SessionDto, committedLedgerSeq: String? = null) {
        requireOpen()
        val resource = resourceMutex.withLock {
            requireOpen()
            withContext(NonCancellable) { acquire(existingOnly = false) }
        }
        resource.browsing.refresh(committedLedgerSeq)
        resourceMutex.withLock {
            requireOpen()
            requireCurrent(requirePublished = false)
            api.publishVerifiedLedgerSession(credentials, directory, ticket, scope, resource.storage, session)
            requireCurrent(requirePublished = true)
            requireOpen()
        }
    }

    /** Refresh facts after a confirmed write; identity is already bound and is not republished. */
    suspend fun refreshFacts(committedLedgerSeq: String? = null) {
        requireOpen()
        val resource = resourceMutex.withLock {
            requireOpen()
            withContext(NonCancellable) { acquire(existingOnly = false) }
        }
        resource.browsing.refresh(committedLedgerSeq)
        resourceMutex.withLock {
            requireOpen()
            requireCurrent(requirePublished = false)
            requireOpen()
        }
    }

    /** Only complete committed facts are read; authorization and operation fences are checked around derivation. */
    suspend fun page(filters: CashFiltersDto = CashFiltersDto(), timeZoneId: String? = null,
        cursor: String? = null, startIndex: Int? = null, expectedLedgerSeq: Long? = null, limit: Int = 20): CashPageDto? =
        read { it.page(filters, timeZoneId, cursor, startIndex, expectedLedgerSeq, limit) }

    suspend fun categories(): CategoryDirectoryDto? = read { it.categories() }
    suspend fun options(): LedgerOptionsDto? = read { it.options() }
    suspend fun accounts(): List<AccountReferenceDto>? = read { it.accounts() }

    suspend fun importFileBatches(): List<String> = withImportFiles { it.list() }
    suspend fun checkImportAccess() = withImportFiles { Unit }
    suspend fun importSubmission(batchId: String) = withImportFiles { it.readSubmission(batchId) }
    suspend fun importFiles(batchId: String): List<LocalCashImportFile>? = withImportFiles { it.read(batchId) }
    suspend fun saveImportFiles(batchId: String, files: List<LocalCashImportFile>) {
        val frozen = files.toList()
        withImportFiles { it.save(batchId, frozen) }
    }
    /** Capture a successful batch atomically; file reads and parse-only HTTP never hold the resource lock. */
    suspend fun captureImportFiles(batchId: String, inputs: List<CashImportUploadInput>): List<LocalCashImportFile> {
        require(batchId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "cash_import_batch_id_invalid" }
        val captured = captureFiles(inputs)
        saveImportFiles(batchId, captured)
        return captured
    }

    suspend fun appendImportFiles(batchId: String, inputs: List<CashImportUploadInput>): List<LocalCashImportFile> {
        val frozen = inputs.toList()
        withImportFiles {
            check(it.readSubmission(batchId) == null) { "cash_import_submission_pending" }
            checkNotNull(it.read(batchId)) { "cash_import_files_missing" }
        }
        val captured = captureFiles(frozen)
        withImportFiles { it.append(batchId, captured) }
        return captured
    }

    suspend fun replaceImportFile(batchId: String, expected: LocalCashImportFile, input: CashImportUploadInput): LocalCashImportFile {
        if (input.clientFileRef != expected.fileRef) throw ApiFailure("import_payload_invalid", 0)
        withImportFiles {
            check(it.readSubmission(batchId) == null) { "cash_import_submission_pending" }
            checkNotNull(it.read(batchId)?.find { file -> file.fileRef == expected.fileRef }) { "cash_import_file_missing" }
        }
        val captured = captureFiles(listOf(input)).single()
        withImportFiles { it.replaceFile(batchId, expected, captured) }
        return captured
    }

    suspend fun removeImportFile(batchId: String, expected: LocalCashImportFile) = withImportFiles { it.removeFile(batchId, expected) }

    /** Parse outside the resource lock; known file rejections retain metadata only. */
    suspend fun parseImportFile(input: CashImportUploadInput): LocalCashImportFile {
        if (!input.clientFileRef.matches(Regex("[A-Za-z0-9_-]{1,128}"))) throw ApiFailure("import_payload_invalid", 0)
        withImportFiles { Unit }
        return withContext(Dispatchers.Default) {
            requireImportSession()
            api.captureLocalCashImportFileState(input, credentials).also { requireImportSession() }
        }
    }

    suspend fun savePreparedImportFile(batchId: String, file: LocalCashImportFile, expected: LocalCashImportFile? = null) =
        withImportFiles { batches ->
            check(batches.readSubmission(batchId) == null) { "cash_import_submission_pending" }
            if (expected != null) batches.replaceFile(batchId, expected, file)
            else if (batches.read(batchId) == null) batches.save(batchId, listOf(file))
            else batches.append(batchId, listOf(file))
        }

    /** Files, retained choices and account suggestions come from one scoped committed snapshot. */
    suspend fun importPreparation(batchId: String): CashImportSessionDto = withImportFiles { batches ->
        check(batches.readSubmission(batchId) == null) { "cash_import_submission_pending" }
        val files = checkNotNull(batches.read(batchId)) { "cash_import_files_missing" }
        val resource = checkNotNull(owned)
        val snapshot = checkNotNull(resource.browsing.snapshot()) { "cash_import_snapshot_missing" }
        val review = batches.readReview(batchId)
        val result = withContext(Dispatchers.Default) { localCashImportPreparationView(batchId, files, snapshot, review) }
        check(resource.storage.readOfflineSession(scope, token) != null) { "ledger_cache_identity_missing" }
        result
    }

    private suspend fun captureFiles(inputs: List<CashImportUploadInput>): List<LocalCashImportFile> {
        requireOpen()
        currentCoroutineContext().ensureActive()
        val frozen = inputs.toList()
        if (frozen.size !in 1..20 || frozen.map { it.clientFileRef }.toSet().size != frozen.size ||
            frozen.any { !it.clientFileRef.matches(Regex("[A-Za-z0-9_-]{1,128}")) }) throw ApiFailure("import_payload_invalid", 0)
        withImportFiles { Unit }
        return withContext(Dispatchers.Default) {
            frozen.map { input ->
                requireImportSession()
                api.captureLocalCashImportFile(input, credentials).also { requireImportSession() }
            }
        }
    }
    suspend fun removeImportFiles(batchId: String) = withImportFiles { it.remove(batchId) }
    /** Read the saved files and preselect accounts from the same eligible committed snapshot. */
    suspend fun importFunding(batchId: String): Pair<List<CashFundingGroup>, Map<String, CashFundingSuggestion>> = withImportFiles { batches ->
        val files = checkNotNull(batches.read(batchId)) { "cash_import_files_missing" }
        val resource = checkNotNull(owned)
        val snapshot = checkNotNull(resource.browsing.snapshot()) { "cash_import_snapshot_missing" }
        val result = withContext(Dispatchers.Default) {
            val groups = scanLocalCashFunding(files.map { it.parsedFile() }).groups
            groups to suggestLocalCashFundingAccounts(groups, snapshot)
        }
        check(resource.storage.readOfflineSession(scope, token) != null) { "ledger_cache_identity_missing" }
        result
    }
    suspend fun importReview(batchId: String): com.finance.tracker.business.domain.LocalCashImportReview? =
        withImportFiles { it.readReview(batchId) }
    suspend fun saveImportReview(batchId: String, input: com.finance.tracker.business.domain.LocalCashImportReview) =
        withImportFiles { it.saveReview(batchId, input) }

    /** Calculate only from this owner's saved files and committed facts, without HTTP or persistence. */
    suspend fun calculateImport(batchId: String, input: JsonObject, previous: LocalCashImportReview? = null): LocalCashImportCalculation {
        requireOpen()
        currentCoroutineContext().ensureActive()
        val copied = Json.parseToJsonElement(input.toString()).jsonObject
        val frozen = JsonObject(listOf("account_selections", "row_choices", "relation_choices", "entry_choices")
            .associateWith { copied[it] ?: JsonArray(emptyList()) })
        return withImportFiles { batches ->
            val files = checkNotNull(batches.read(batchId)) { "cash_import_files_missing" }
            val resource = checkNotNull(owned)
            val snapshot = checkNotNull(resource.browsing.snapshot()) { "cash_import_snapshot_missing" }
            val result = withContext(Dispatchers.Default) {
                val result = productionCalculator(currentCoroutineContext())(files.map { it.parsedFile() }, snapshot,
                    frozen, previous?.previousPlan().orEmpty(), previous?.recordIds().orEmpty())
                LocalCashImportCalculation(freezeLocalCashImportPreviewPlan(result.first),
                    LocalCashImportReview.freeze(frozen, result.first, snapshot, result.second),
                    CashWriteSetRequestDto(snapshot.ledgerSeq, CASH_RULES_VERSION, result.second))
            }
            check(resource.storage.readOfflineSession(scope, token) != null) { "ledger_cache_identity_missing" }
            result
        }
    }

    /** Recover and rebase using the complete production calculation; original receipt verification always runs first. */
    suspend fun resumeLocalCashImport(batchId: String,
        beforeCompletion: suspend (CashMutationReceiptDto) -> Unit = {}): LocalCashImportSubmissionResult = withContext(Dispatchers.Default) {
        submissionMutex.withLock {
            resumeImportSubmissionLocked(batchId, productionCalculator(currentCoroutineContext()), beforeCompletion)
        }
    }

    private fun productionCalculator(context: CoroutineContext): LocalCashImportCalculator = { files, source, input, previous, ids ->
        calculateLocalCashImportBusinessPlan(files, source, input, previous, ids,
            { context.ensureActive(); requireOpen() }, Clock.System::now)
    }

    /** Batch IDs are unique operation keys. The caller must never reuse one, including after cleanup. */
    suspend fun prepareImportSubmission(batchId: String, request: CashWriteSetRequestDto) {
        val frozen = LocalCashImportSubmission.freeze(batchId, request)
        withImportFiles { it.prepareSubmission(batchId, frozen) }
    }

    /** Recovery and up to three explicit-baseline rebases. Unknown results always retain the same operation. */
    suspend fun resumeImportSubmission(batchId: String, calculate: LocalCashImportCalculator? = null): LocalCashImportSubmissionResult =
        submissionMutex.withLock { resumeImportSubmissionLocked(batchId, calculate) }

    private suspend fun resumeImportSubmissionLocked(batchId: String, calculate: LocalCashImportCalculator?,
        beforeCompletion: suspend (CashMutationReceiptDto) -> Unit = {}): LocalCashImportSubmissionResult {
        var retries = 0
        while (true) {
            val original = checkNotNull(withImportFiles { it.readSubmission(batchId) }) { "cash_import_submission_missing" }
            val receipt = try { resolveImportSubmission(batchId, original) }
            catch (failed: BaseRejection) {
                if (calculate == null) throw failed.rejection
                if (retries >= 3) throw LocalCashImportRetryLimitReached(failed.rejection)
                val confirmed = rebaseImportSubmission(batchId, original, failed.rejection, calculate)
                if (confirmed == null) {
                    delay(250L shl retries)
                    retries++
                    continue
                }
                confirmed
            }
            return finishImportSubmission(batchId, original, receipt, beforeCompletion)
        }
    }

    private class BaseRejection(val rejection: ApiFailure) : Exception(rejection)

    private suspend fun resolveImportSubmission(batchId: String, original: LocalCashImportSubmission): CashMutationReceiptDto =
        original.receipt() ?: run {
            requireImportSession()
            try { api.readCashImportWriteSetReceiptByKey(scope.workspaceId, original.idempotencyKey, credentials) }
            catch (failure: ApiFailure) {
                if (failure.status != 404 || failure.code != "receipt_not_found") throw failure
                // Absence is not a non-commit proof. Only the exact original operation is retried.
                requireImportSession()
                if (original.baseRejected) throw BaseRejection(ApiFailure("ledger_base_changed", 409, commitState = "not_committed"))
                try { api.submitFrozenCashImportWriteSet(scope.workspaceId, original, credentials) }
                catch (rejection: ApiFailure) {
                    if (rejection.status == 409 && rejection.code == "ledger_base_changed" && rejection.commitState == "not_committed") {
                        try { withImportFiles { it.recordBaseRejection(batchId, original) } }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (storageFailure: Throwable) { throw LocalCashImportRejectionStorageFailure(rejection, storageFailure) }
                        throw BaseRejection(rejection)
                    }
                    throw rejection
                }
            }
        }

    private suspend fun rebaseImportSubmission(batchId: String, original: LocalCashImportSubmission, rejection: ApiFailure,
        calculate: LocalCashImportCalculator): CashMutationReceiptDto? {
        refreshFacts()
        val snapshot = checkNotNull(read { it.snapshot() }) { "cash_import_snapshot_missing" }
        if (snapshot.ledgerSeq.toLong() <= original.request().baseLedgerSeq.toLong()) throw rejection
        requireImportSession()
        val receipt = try { api.readCashImportWriteSetReceiptByKey(scope.workspaceId, original.idempotencyKey, credentials) }
        catch (failure: ApiFailure) { if (failure.status != 404 || failure.code != "receipt_not_found") throw failure else null }
        requireImportSession()
        if (receipt != null) return receipt
        val replacement = try {
            val review = withImportFiles { it.readReview(batchId) } ?: throw CashImportPlanError("plan_changed")
            val files = checkNotNull(withImportFiles { it.read(batchId) }) { "cash_import_files_missing" }
            val result = review.recalculate(snapshot) { source, input, previous, ids ->
                calculate(files.map { it.parsedFile() }, source, input, previous, ids)
            }
            requireImportSession()
            if (!result.conclusionUnchanged) throw CashImportPlanError("plan_changed")
            LocalCashImportSubmission.freeze(batchId, CashWriteSetRequestDto(snapshot.ledgerSeq, CASH_RULES_VERSION, result.changes))
        } catch (invalid: CashImportPlanError) {
            withImportFiles { it.retireRejectedSubmission(batchId, original, snapshot.ledgerSeq, null) }
            throw invalid
        }
        withImportFiles { it.retireRejectedSubmission(batchId, original, snapshot.ledgerSeq, replacement) }
        return null
    }

    private suspend fun finishImportSubmission(batchId: String, original: LocalCashImportSubmission, receipt: CashMutationReceiptDto,
        beforeCompletion: suspend (CashMutationReceiptDto) -> Unit): LocalCashImportSubmissionResult {
        var recoveryFailure: Throwable? = null
        try {
            if (original.receiptBody == null) withImportFiles { it.recordReceipt(batchId, receipt) }
            requireImportSession()
            beforeCompletion(receipt)
            requireImportSession()
            refreshFacts(checkNotNull(receipt.ledgerSeq))
            withImportFiles { it.completeSubmission(batchId) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Throwable) {
            // This failure happened after authoritative success. Never report it as a failed write.
            recoveryFailure = failure
        }
        requireImportSession()
        return LocalCashImportSubmissionResult(receipt, recoveryFailure)
    }

    private suspend fun requireImportSession() {
        currentCoroutineContext().ensureActive()
        requireOpen()
        requireCurrent(requirePublished = true)
        requireOpen()
        currentCoroutineContext().ensureActive()
    }

    /** No HTTP or substitute materials; a cached original identity is required for file recovery. */
    private suspend fun <T> withImportFiles(action: suspend (LedgerImportBatches) -> T): T {
        currentCoroutineContext().ensureActive()
        requireOpen()
        return resourceMutex.withLock {
            requireOpen()
            requireCurrent(requirePublished = true)
            val resource = owned ?: withContext(NonCancellable) { acquire(existingOnly = true) }
            currentCoroutineContext().ensureActive()
            check(resource.storage.readOfflineSession(scope, token) != null) { "ledger_cache_identity_missing" }
            requireCurrent(requirePublished = true)
            requireOpen()
            currentCoroutineContext().ensureActive()
            val result = action(resource.storage.imports)
            currentCoroutineContext().ensureActive()
            requireCurrent(requirePublished = true)
            requireOpen()
            result
        }
    }

    private suspend fun <T> read(read: suspend (LocalCashLedgerOwner) -> T?): T? {
        requireOpen()
        return resourceMutex.withLock {
            requireOpen()
            requireCurrent(requirePublished = true)
            val resource = owned
            val result = if (resource == null || resource.storage.readOfflineSession(scope, token) == null) null
                else read(resource.browsing)
            requireCurrent(requirePublished = true)
            requireOpen()
            result
        }
    }

    /** Preserve materials, or finish erasure already selected by the scoped browsing owner. Never reopen. */
    suspend fun close() {
        closing.value = true
        withContext(NonCancellable) {
            resourceMutex.withLock {
                owned?.browsing?.close()
                owned = null
            }
        }
    }

    /** Called under resourceMutex and NonCancellable: publish ownership before any cancellable hand-off. */
    private suspend fun acquire(existingOnly: Boolean): Resource {
        requireOpen()
        requireCurrent(requirePublished = false)
        val resource = owned ?: openCache(existingOnly, LedgerSessionRegistration(scope, directory, ticket)).let { storage ->
            Resource(storage, LocalCashLedgerOwner(scope, storage, source)).also { owned = it }
        }
        requireCurrent(requirePublished = false)
        requireOpen()
        return resource
    }

    private suspend fun requireCurrent(requirePublished: Boolean) {
        if (api.readToken() != token) superseded()
        val current = directory.current(ticket)
        if (requirePublished && current != scope) superseded()
        if (api.readToken() != token) superseded()
    }

    private fun requireOpen() { if (closing.value) throw CancellationException("ledger_cache_closed") }
    private fun superseded(): Nothing = throw CancellationException("ledger_session_operation_superseded")
}
