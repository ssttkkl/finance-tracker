package com.finance.tracker.data

import com.finance.tracker.client.ClientImportSubmissionHost
import com.finance.tracker.client.ClientImportSubmissionState
import com.finance.tracker.client.ClientNetworkUnavailable
import com.finance.tracker.client.ClientOperationFailure
import com.finance.tracker.client.cashAccountSelection
import com.finance.tracker.client.freezeCashImportChoices
import com.finance.tracker.client.lookupImportReceipt
import com.finance.tracker.client.rebaseRejectedCashImport
import com.finance.tracker.client.resumeCashImportSubmission
import com.finance.tracker.business.contracts.StoredCashImportSubmission
import com.finance.tracker.business.contracts.AccountReferenceDto
import com.finance.tracker.business.contracts.CashFiltersDto
import com.finance.tracker.business.contracts.CashPageDto
import com.finance.tracker.business.contracts.CategoryDirectoryDto
import com.finance.tracker.business.contracts.LedgerOptionsDto
import com.finance.tracker.business.contracts.SessionDto
import com.finance.tracker.business.contracts.CashWriteSetRequestDto
import com.finance.tracker.business.contracts.CashMutationReceiptDto
import com.finance.tracker.business.domain.CashImportPlanError
import com.finance.tracker.business.domain.cashWriteSetJson
import com.finance.tracker.business.domain.compactCashWriteSetTransport
import com.finance.tracker.business.domain.LocalCashImportReview
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
    suspend fun ledgerVersion(): Long? = read { it.ledgerVersion() }

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
        val account = resource.browsing.importSelectionAccount(accountId)
            ?: throw ApiFailure("account_not_found", 404)
        val session = checkNotNull(resource.storage.readOfflineSession(scope, token)) { "ledger_cache_identity_missing" }
        val role = checkNotNull(session.workspaces.singleOrNull { it.id == scope.workspaceId }) { "ledger_cache_identity_missing" }.role
        cashAccountSelection(account, role.canWrite)
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
    suspend fun importFileEncodings(batchId: String): List<String>? = withImportFiles { it.readEncoded(batchId) }
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
            else if (batches.readEncoded(batchId) == null) batches.save(batchId, listOf(file))
            else batches.append(batchId, listOf(file))
        }

    /** Files, retained choices and account suggestions come from one scoped committed snapshot. */
    suspend fun importPreparation(batchId: String): CashImportSessionDto = withImportFiles { batches ->
        check(batches.readSubmission(batchId) == null) { "cash_import_submission_pending" }
        val resource = checkNotNull(owned)
        val source = CashImportPreviewSource(batches, resource.browsing, batchId, scope.workspaceId)
        val result = prepareCashImportView(batchId, source, batches.readEncodedReview(batchId))
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
        identity: CashImportPreviewIdentity): OpenedCashImportPreview = withImportFiles { batches ->
        val resource = checkNotNull(owned)
        val source = CashImportPreviewSource(batches, resource.browsing, batchId, scope.workspaceId)
        val result = openCashImportPreview(source, freezeCashImportChoices(input), previous, identity, ::requireOpen)
        check(resource.storage.readOfflineSession(scope, token) != null) { "ledger_cache_identity_missing" }
        result
    }

    private suspend fun <T> calculateImportFromSnapshot(batchId: String, input: JsonObject,
        calculate: suspend (List<LocalCashImportFile>, com.finance.tracker.business.contracts.LedgerSnapshotDto, JsonObject) -> T): T {
        requireOpen()
        currentCoroutineContext().ensureActive()
        val frozen = freezeCashImportChoices(input)
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
            resumeImportSubmissionLocked(batchId, ::rebaseCashImport, beforeCompletion)
        }
    }

    suspend fun importResult(receiptId: String) = withImportFiles { it.readResult(receiptId) }
    suspend fun importResultByBatchId(batchId: String) = withImportFiles { it.readResultByBatchId(batchId) }
    suspend fun importResultByKey(key: String) = withImportFiles { it.readResultByKey(key) }

    /** Batch IDs are unique operation keys. The caller must never reuse one, including after cleanup. */
    suspend fun prepareImportSubmission(batchId: String, request: CashWriteSetRequestDto, resultInput: LocalCashImportResultInput? = null) {
        prepareImportSubmission(batchId, LocalCashImportSubmission.freeze(batchId, request, resultInput))
    }

    suspend fun prepareImportSubmission(batchId: String, frozen: LocalCashImportSubmission) =
        withImportFiles { it.prepareSubmission(batchId, frozen) }

    suspend fun recordImportTransport(batchId: String, expected: LocalCashImportSubmission, transport: String) =
        withImportFiles { it.recordTransport(batchId, expected, transport) }

    suspend fun discardUnprovableImport(batchId: String, expected: LocalCashImportSubmission) =
        withImportFiles { it.discardUnprovableSubmission(batchId, expected) }

    /** Recovery and up to three explicit-baseline rebases. Unknown results always retain the same operation. */
    suspend fun resumeImportSubmission(batchId: String, calculate: LocalCashImportCalculator? = null): LocalCashImportSubmissionResult =
        submissionMutex.withLock {
            val rebase: CashImportRebase? = calculate?.let { calculator ->
                { source, id, input, requireOpen -> rebaseCashImportLocally(source, id, input, requireOpen, calculator) }
            }
            resumeImportSubmissionLocked(batchId, rebase, captureSnapshot = { ledger ->
                ledger.snapshot()?.let { CashImportRebaseSnapshot.Decoded(it) }
            })
        }

    private suspend fun resumeImportSubmissionLocked(batchId: String, rebase: CashImportRebase?,
        beforeCompletion: suspend (CashMutationReceiptDto) -> Unit = {},
        captureSnapshot: suspend (LocalCashLedgerOwner) -> CashImportRebaseSnapshot? = ::captureCashImportRebaseSnapshot): LocalCashImportSubmissionResult =
        try {
            resumeCashImportSubmission(object : ClientImportSubmissionHost<LocalCashImportSubmissionResult> {
                private lateinit var selected: LocalCashImportSubmission

                private fun original(expected: StoredCashImportSubmission): LocalCashImportSubmission {
                    check(selected.idempotencyKey == expected.key && selected.requestBody == expected.request) {
                        "cash_import_submission_changed"
                    }
                    return selected
                }

                override suspend fun current(): ClientImportSubmissionState<LocalCashImportSubmissionResult> {
                    selected = checkNotNull(withImportFiles { it.readSubmission(batchId) }) { "cash_import_submission_missing" }
                    return ClientImportSubmissionState.Pending(selected.storageValue())
                }

                override suspend fun receipt(key: String) = sharedImportRequest {
                    requireImportSession()
                    readReceiptOrNull(key)
                }

                override suspend fun submit(attempt: StoredCashImportSubmission) = sharedImportRequest {
                    requireImportSession()
                    selected = prepareImportTransport(batchId, original(attempt))
                    submitImportCommand(batchId, selected)
                }

                override suspend fun recordBaseRejection(attempt: StoredCashImportSubmission, reason: ClientOperationFailure) {
                    try { withImportFiles { it.recordBaseRejection(batchId, original(attempt)) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (storageFailure: Throwable) { throw LocalCashImportRejectionStorageFailure(reason.importApiFailure(), storageFailure) }
                }

                override suspend fun rebase(attempt: StoredCashImportSubmission, reason: ClientOperationFailure): CashMutationReceiptDto? {
                    val calculate = rebase ?: throw reason.importApiFailure()
                    return rebaseImportSubmission(batchId, original(attempt), reason, calculate, captureSnapshot)
                }

                override suspend fun discardRejectedAttempt(attempt: StoredCashImportSubmission, reason: ClientOperationFailure) {
                    withImportFiles { it.discardUnprovableSubmission(batchId, original(attempt)) }
                    throw CashImportPlanError(reason.code, "not_committed")
                }

                override suspend fun complete(attempt: StoredCashImportSubmission, receipt: CashMutationReceiptDto) =
                    finishImportSubmission(batchId, original(attempt), receipt, beforeCompletion)

                override suspend fun awaitNextAttempt(attempt: Int) = delay(250L shl attempt)

                override fun retriesExhausted(reason: ClientOperationFailure): Nothing =
                    throw LocalCashImportRetryLimitReached(reason.importApiFailure())
            })
        } catch (failure: ClientOperationFailure) {
            val network = generateSequence<Throwable>(failure.cause) { it.cause }.filterIsInstance<FinanceTransportFailure>().firstOrNull()
            if (network != null) throw network
            throw failure.importApiFailure()
        }

    private fun ClientOperationFailure.importApiFailure(): ApiFailure {
        val original = generateSequence<Throwable>(cause) { it.cause }.filterIsInstance<ApiFailure>().firstOrNull()
        return if (original != null && original.commitState == commitState) original
        else ApiFailure(original?.code ?: code, status ?: original?.status ?: 0, receiptId = original?.receiptId,
            commitState = commitState, diagnostic = original?.diagnostic, cause = this)
    }

    private suspend fun <T> sharedImportRequest(call: suspend () -> T): T = try {
        call()
    } catch (failure: ApiFailure) {
        if (failure.status in setOf(401, 403)) throw failure
        throw ClientOperationFailure(failure.code, failure.status, failure, failure.commitState)
    } catch (failure: FinanceTransportFailure) {
        throw ClientNetworkUnavailable(failure)
    }

    private suspend fun readReceiptOrNull(key: String): CashMutationReceiptDto? = try {
        api.readCashImportWriteSetReceiptByKey(scope.workspaceId, key, credentials)
    } catch (failure: ApiFailure) {
        if (failure.status == 404 && failure.code == "receipt_not_found") null else throw failure
    }

    /**
     * A mixed-version deployment may reject an encoding or compact command after advertising it.
     * Only a rejection that cannot have written may be retried; the same key and logical command stay frozen.
     */
    private suspend fun submitImportCommand(batchId: String, prepared: LocalCashImportSubmission): CashMutationReceiptDto {
        var current = prepared
        var encodingRetried = false
        var commandRetried = false
        while (true) {
            try {
                return api.submitFrozenCashImportWriteSet(scope.workspaceId, current, credentials, ::requireImportSession)
            } catch (rejection: ApiFailure) {
                val encodingRejected = rejection.status == 415 ||
                    rejection.status == 400 && rejection.code in setOf("content_encoding_invalid", "unsupported_content_encoding")
                if (!encodingRetried && encodingRejected) {
                    encodingRetried = true
                    CashImportTransportCache.invalidate(credentials.apiOrigin, credentials.token)
                    continue
                }
                val commandRejected = rejection.status == 422 &&
                    rejection.code in setOf("write_set_payload_invalid", "import_payload_invalid") &&
                    rejection.commitState == "not_committed" && current.transportBody != null
                if (!commandRetried && commandRejected) {
                    commandRetried = true
                    lookupImportReceipt {
                        sharedImportRequest {
                            requireImportSession()
                            readReceiptOrNull(current.idempotencyKey).also { requireImportSession() }
                        }
                    }?.let { return it }
                    withImportFiles { it.clearTransport(batchId, current) }
                    current = current.withoutTransport()
                    continue
                }
                throw rejection
            }
        }
    }

    /**
     * Freeze the transport representation before the first send so a restart replays the exact bytes.
     * A failed or absent capability probe leaves the original full command in place.
     */
    private suspend fun prepareImportTransport(batchId: String, original: LocalCashImportSubmission): LocalCashImportSubmission {
        if (original.transportBody != null) {
            // Refresh the encoding cache after a restart; the frozen transport bytes never change.
            api.cashImportTransportCapabilities(scope.workspaceId, credentials)
            return original
        }
        val capabilities = api.cashImportTransportCapabilities(scope.workspaceId, credentials) ?: return original
        if (!capabilities.supportsCompactCommand) return original
        val snapshot = read { it.snapshot() }?.takeIf { it.ledgerSeq == original.baseLedgerSeq }
        val compact = compactCashWriteSetTransport(original.request(), snapshot?.facts) ?: return original
        val transport = cashWriteSetJson.encodeToString(compact)
        return try {
            withImportFiles { it.recordTransport(batchId, original, transport) }
            original.withTransport(transport)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // The compact form is an optimization; never block a command that identity JSON can still carry.
            original
        }
    }

    private suspend fun rebaseImportSubmission(batchId: String, original: LocalCashImportSubmission, rejection: ClientOperationFailure,
        rebase: CashImportRebase, captureSnapshot: suspend (LocalCashLedgerOwner) -> CashImportRebaseSnapshot?): CashMutationReceiptDto? {
        refreshFacts()
        val snapshot = checkNotNull(read(captureSnapshot)) { "cash_import_snapshot_missing" }
        return rebaseRejectedCashImport(original.baseLedgerSeq, snapshot.base, rejection, {
            sharedImportRequest {
                requireImportSession()
                readReceiptOrNull(original.idempotencyKey).also { requireImportSession() }
            }
        }, calculate = {
            val captured = withImportFiles { batches ->
                val review = batches.readEncodedReview(batchId) ?: throw CashImportPlanError("plan_changed")
                val files = checkNotNull(batches.readEncoded(batchId)) { "cash_import_files_missing" }
                CashImportRebaseInput(snapshot, review, files)
            }
            rebase(captured, batchId, original.resultInput, ::requireOpen).also { requireImportSession() }
        }, retire = { replacement ->
            withImportFiles { it.retireRejectedSubmission(batchId, original, snapshot.base, replacement) }
        })
    }

    private suspend fun finishImportSubmission(batchId: String, original: LocalCashImportSubmission, receipt: CashMutationReceiptDto,
        beforeCompletion: suspend (CashMutationReceiptDto) -> Unit): LocalCashImportSubmissionResult {
        var recoveryFailure: Throwable? = null
        var displayedReceipt = receipt
        try {
            if (original.receiptBody == null) withImportFiles { it.recordReceipt(batchId, receipt) }
            val archived = archiveImportResults(batchId, original, receipt)
            displayedReceipt = archived?.displayReceipt(receipt) ?: receipt
            requireImportSession()
            beforeCompletion(displayedReceipt)
            requireImportSession()
            refreshFacts(checkNotNull(receipt.ledgerSeq))
            withImportFiles { it.completeSubmission(batchId, archived) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Throwable) {
            // This failure happened after authoritative success. Never report it as a failed write.
            if (failure is ApiFailure && failure.status == 401 && failure.rejectedToken != null) throw failure
            recoveryFailure = failure
        }
        requireImportSession()
        return LocalCashImportSubmissionResult(displayedReceipt, recoveryFailure)
    }

    private suspend fun archiveImportResults(batchId: String, original: LocalCashImportSubmission,
        receipt: CashMutationReceiptDto): LocalCashImportResult? {
        if (original.resultInput == null) return null
        val existing = withImportFiles { it.readResultByBatchId(batchId) }
        if (existing?.hasServerItems == true) {
            check(existing.matchesSubmission(original)) { "cash_import_result_changed" }
            return existing
        }
        if (archiveCashImportPagesInWorker) {
            val pages = api.readCashImportResultPages(scope.workspaceId, receipt, credentials, ::requireImportSession)
            return withImportFiles { it.archiveResultPages(batchId, pages) }
        }
        val items = api.readCashImportResultItems(scope.workspaceId, receipt, credentials, ::requireImportSession)
        return withImportFiles { it.archiveResult(batchId, items) }
    }

    private suspend fun requireImportSession() {
        currentCoroutineContext().ensureActive()
        requireOpen()
        requireCurrent(requirePublished = true)
        requireOpen()
        currentCoroutineContext().ensureActive()
    }

    /** No HTTP or substitute materials; a cached original identity is required for file recovery. */
    fun cashCreationJournal(): CashCreationJournal = object : CashCreationJournal {
        override suspend fun read() = withLedgerStorage { storage ->
            checkNotNull(storage.cashCreationJournal(token)) { "cash_creation_storage_missing" }.read()
        }
        override suspend fun swap(previous: com.finance.tracker.domain.CashCreationRecovery?, next: com.finance.tracker.domain.CashCreationRecovery?) =
            withLedgerStorage { storage ->
                checkNotNull(storage.cashCreationJournal(token)) { "cash_creation_storage_missing" }.swap(previous, next)
            }
    }

    fun assistantConversations(): LedgerAssistantConversations = object : LedgerAssistantConversations {
        override suspend fun list() = withLedgerStorage { storage ->
            checkNotNull(storage.assistantConversations()).list()
        }

        override suspend fun delete(previous: LedgerAssistantConversation): Boolean =
            withLedgerStorage { storage -> checkNotNull(storage.assistantConversations()).delete(previous) }

        override suspend fun swap(previous: LedgerAssistantConversation?, next: LedgerAssistantConversation) =
            withLedgerStorage { storage -> checkNotNull(storage.assistantConversations()).swap(previous, next) }
        override suspend fun purgeExpiredSkillFiles(now: Long) =
            withLedgerStorage { storage -> checkNotNull(storage.assistantConversations()).purgeExpiredSkillFiles(now) }

        override suspend fun skillFiles(conversationId: String, now: Long) =
            withLedgerStorage { storage -> checkNotNull(storage.assistantConversations()).skillFiles(conversationId, now) }

        override suspend fun readSkillFile(conversationId: String, fileId: String, now: Long) =
            withLedgerStorage { storage -> checkNotNull(storage.assistantConversations()).readSkillFile(conversationId, fileId, now) }

        override suspend fun skillReceipt(conversationId: String, callId: String) =
            withLedgerStorage { storage -> checkNotNull(storage.assistantConversations()).skillReceipt(conversationId, callId) }

        override suspend fun commitSkillRun(
            previous: LedgerAssistantConversation, next: LedgerAssistantConversation, removed: Set<String>,
            files: List<LedgerAssistantFileContent>, receipt: LedgerAssistantFileReceipt, now: Long, requiredSource: String?,
            expectedLedgerVersion: Long?,
        ) = withLedgerStorage { storage ->
            checkNotNull(storage.assistantConversations()).commitSkillRun(previous, next, removed, files, receipt, now, requiredSource, expectedLedgerVersion)
        }
    }

    private suspend fun <T> withImportFiles(action: suspend (LedgerImportBatches) -> T): T =
        withLedgerStorage { action(it.imports) }

    private suspend fun <T> withLedgerStorage(action: suspend (LedgerStorage) -> T): T {
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
            val result = action(resource.storage)
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
