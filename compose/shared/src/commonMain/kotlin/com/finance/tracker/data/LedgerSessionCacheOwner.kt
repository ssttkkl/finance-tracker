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

    suspend fun currentGroup(projectionId: String) = read { it.currentGroup(projectionId) }
    suspend fun record(transactionId: String) = readWithIdentity { browsing, session -> browsing.record(transactionId, canWrite(session)) }
    suspend fun sourceRecords(projectionId: String) = readWithIdentity { browsing, session -> browsing.sourceRecords(projectionId, canWrite(session)) }
    private fun canWrite(session: SessionDto) = checkNotNull(session.workspaces.singleOrNull { it.id == scope.workspaceId }) { "ledger_cache_identity_missing" }.role.canWrite

    suspend fun categories(): CategoryDirectoryDto? = read { it.categories() }
    suspend fun options(): LedgerOptionsDto? = read { it.options() }
    suspend fun accounts(): List<AccountReferenceDto>? = read { it.accounts() }

    suspend fun importProjectionRefs(transactionIds: List<String>): com.finance.tracker.core.LocalCashProjectionReferences? {
        val frozen = transactionIds.toList()
        return read { it.projectionRefs(frozen) }
    }

    suspend fun importAccounts(): List<ImportAccountListItemDto> = checkNotNull(read { browsing ->
        browsing.accounts()?.map { ImportAccountListItemDto(it.id.toString(), it.name, it.type, it.active, it.currencies) }
    }) { "cash_import_snapshot_missing" }

    suspend fun importAccount(accountId: String) = accountRequest { it.read(accountId) }
    suspend fun importSelectionAccount(accountId: String): ImportAccountDto = withImportFiles {
        val resource = checkNotNull(owned)
        val snapshot = checkNotNull(resource.browsing.snapshot()) { "cash_import_snapshot_missing" }
        val account = snapshot.facts.accounts.singleOrNull { it.id == accountId && it.type in setOf("cash", "loan", "lend") }
            ?: throw ApiFailure("account_not_found", 404)
        val reference = com.finance.tracker.business.domain.buildLocalCashAccountReferences(listOf(account)).single()
        val session = checkNotNull(resource.storage.readOfflineSession(scope, token)) { "ledger_cache_identity_missing" }
        val role = checkNotNull(session.workspaces.singleOrNull { it.id == scope.workspaceId }) { "ledger_cache_identity_missing" }.role
        ImportAccountDto(account.id, account.name, account.type, account.active, reference.currencies.orEmpty(),
            account.revision.toLong(), false, mapOf("can_update" to role.canWrite, "can_delete" to false))
    }
    suspend fun createImportAccount(name: String, type: String, currencies: List<String>, key: String): ImportAccountDto {
        val frozen = currencies.toList()
        return accountRequest { it.create(name, type, frozen, key) }
    }
    suspend fun updateImportAccount(id: String, revision: Long, patch: JsonObject, key: String): ImportAccountDto {
        val frozen = Json.parseToJsonElement(patch.toString()).jsonObject
        return accountRequest { it.update(id, revision, frozen, key) }
    }
    suspend fun deleteImportAccount(id: String, revision: Long, key: String) = accountRequest { it.delete(id, revision, key) }
    suspend fun ensureImportAccountCurrencies(requirements: List<ImportCurrencyRequirementDto>): List<ImportCurrencyRequirementResultDto> {
        val frozen = requirements.map { it.copy(currencies = it.currencies.toList()) }
        return accountRequest { it.ensureCurrencies(frozen) }
    }
    suspend fun importAccountReceipt(receiptId: String) = accountRequest { it.receipt(receiptId) }
    suspend fun importAccountReceiptByKey(key: String) = accountRequest { it.receiptByKey(key) }

    private suspend fun <T> accountRequest(action: suspend (ImportAccountApi) -> T): T {
        withImportFiles { Unit }
        requireImportSession()
        val result = action(ImportAccountApi(api, ImportAccountScope(scope.workspaceId, credentials)))
        requireImportSession()
        return result
    }

    suspend fun importFileBatches(): List<String> = withImportFiles { it.list() }
    internal fun checkImportScope(principal: String, workspaceId: String) {
        if (scope.principal != principal || scope.workspaceId != workspaceId) throw CancellationException("ledger_import_scope_superseded")
    }

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
        return calculateImportFromSnapshot(batchId, input) { files, snapshot, frozen ->
            executeCashImportPreview(files, snapshot, frozen, previous, ::requireOpen)
        }
    }

    suspend fun openImportPreview(batchId: String, input: JsonObject, previous: LocalCashImportReview?,
        identity: CashImportPreviewIdentity): OpenedCashImportPreview = calculateImportFromSnapshot(batchId, input) { files, snapshot, frozen ->
        openCashImportPreview(files, snapshot, frozen, previous, identity, ::requireOpen)
    }

    private suspend fun <T> calculateImportFromSnapshot(batchId: String, input: JsonObject,
        calculate: suspend (List<LocalCashImportFile>, com.finance.tracker.business.contracts.LedgerSnapshotDto, JsonObject) -> T): T {
        requireOpen()
        currentCoroutineContext().ensureActive()
        val copied = Json.parseToJsonElement(input.toString()).jsonObject
        val frozen = JsonObject(listOf("account_selections", "row_choices", "relation_choices", "entry_choices")
            .associateWith { copied[it] ?: JsonArray(emptyList()) })
        return withImportFiles { batches ->
            val files = checkNotNull(batches.read(batchId)) { "cash_import_files_missing" }
            val resource = checkNotNull(owned)
            val snapshot = checkNotNull(resource.browsing.snapshot()) { "cash_import_snapshot_missing" }
            val result = calculate(files, snapshot, frozen)
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

    suspend fun importResult(receiptId: String) = withImportFiles { it.readResult(receiptId) }
    suspend fun importResultByBatchId(batchId: String) = withImportFiles { it.readResultByBatchId(batchId) }
    suspend fun importResultByKey(key: String) = withImportFiles { it.readResultByKey(key) }

    /** Batch IDs are unique operation keys. The caller must never reuse one, including after cleanup. */
    suspend fun prepareImportSubmission(batchId: String, request: CashWriteSetRequestDto, resultInput: LocalCashImportResultInput? = null) {
        val frozen = LocalCashImportSubmission.freeze(batchId, request, resultInput)
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
            LocalCashImportSubmission.freeze(batchId, CashWriteSetRequestDto(snapshot.ledgerSeq, CASH_RULES_VERSION, result.changes), original.resultInput)
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
        var displayedReceipt = receipt
        try {
            if (original.receiptBody == null) withImportFiles { it.recordReceipt(batchId, receipt) }
            val items = if (original.resultInput == null) null else {
                val existing = withImportFiles { it.readResultByBatchId(batchId) }
                existing?.serverItems() ?: api.readCashImportResultItems(scope.workspaceId, receipt, credentials, ::requireImportSession)
            }
            val archived = withImportFiles { it.archiveResult(batchId, items) }
            displayedReceipt = archived?.displayReceipt() ?: receipt
            requireImportSession()
            beforeCompletion(displayedReceipt)
            requireImportSession()
            refreshFacts(checkNotNull(receipt.ledgerSeq))
            withImportFiles { it.completeSubmission(batchId) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Throwable) {
            // This failure happened after authoritative success. Never report it as a failed write.
            if (failure is ApiFailure && failure.status == 401 && failure.rejectedToken != null) throw failure
            recoveryFailure = failure
        }
        requireImportSession()
        return LocalCashImportSubmissionResult(displayedReceipt, recoveryFailure)
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

    private suspend fun <T> read(read: suspend (LocalCashLedgerOwner) -> T?): T? = readWithIdentity { browsing, _ -> read(browsing) }

    private suspend fun <T> readWithIdentity(read: suspend (LocalCashLedgerOwner, SessionDto) -> T?): T? {
        requireOpen()
        return resourceMutex.withLock {
            requireOpen()
            requireCurrent(requirePublished = true)
            val resource = owned
            val result = if (resource == null) null else {
                val session = resource.storage.readOfflineSession(scope, token)
                if (session == null) null else read(resource.browsing, session)
            }
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
