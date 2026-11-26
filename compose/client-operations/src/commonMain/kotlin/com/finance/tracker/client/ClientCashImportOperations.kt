package com.finance.tracker.client

import com.finance.tracker.business.contracts.CashImportAccountInput
import com.finance.tracker.business.contracts.CashImportPreviewInput
import com.finance.tracker.business.contracts.CashImportSessionDto
import com.finance.tracker.business.contracts.CashMutationReceiptDto
import com.finance.tracker.business.contracts.StoredCashImportSubmission
import com.finance.tracker.business.domain.CashImportResultContent
import com.finance.tracker.business.domain.CashImportPlanError
import com.finance.tracker.business.contracts.CashWriteSetRequestDto
import com.finance.tracker.business.domain.LocalCashImportResultInput
import com.finance.tracker.business.domain.cashWriteSetJson
import com.finance.tracker.business.contracts.StoredCashImportFile
import com.finance.tracker.business.domain.CashStatementFormat
import com.finance.tracker.business.domain.LocalCashImportReview
import com.finance.tracker.business.domain.ParsedCashDocument
import com.finance.tracker.business.domain.StatementDocumentFailure
import com.finance.tracker.business.domain.decodeStoredCashImportFile
import com.finance.tracker.business.domain.toParsedImportFile
import com.finance.tracker.business.domain.prepareLocalCashImportView
import com.finance.tracker.business.domain.ParsedImportFile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.Serializable

class CashImportFileInput(
    val ref: String,
    val name: String,
    val modifiedAtMillis: Long?,
    val bytes: ByteArray,
    val source: String? = null,
    val password: String? = null,
    val mediaType: String? = null,
)

class CashImportFileSource(val ref: String, val name: String, val read: suspend () -> CashImportFileInput)

@Serializable
data class ClientCashImportFileRef(val ref: String, val storageId: String)

class ClientCapturedCashImportFile(val storageId: String, val payload: String)

fun interface ClientStatementParser {
    suspend fun parse(ledger: ClientLedgerRead, format: CashStatementFormat, file: CashImportFileInput): ParsedCashDocument
}

interface ClientCashImportStore {
    suspend fun batches(session: ClientStoredSession, workspaceId: String): List<ClientCashImportBatch>
    suspend fun read(session: ClientStoredSession, workspaceId: String, batchId: String): ClientCashImportBatch?
    /** Freezing an editable batch must atomically reject associated unresolved account commands. */
    suspend fun compareAndSave(
        session: ClientStoredSession,
        workspaceId: String,
        previous: ClientCashImportBatch?,
        batch: ClientCashImportBatch,
        file: ClientCapturedCashImportFile? = null,
        removedFileIds: List<String> = emptyList(),
    ): Boolean
    suspend fun readFile(session: ClientStoredSession, workspaceId: String, batchId: String, storageId: String): String?
    /** The batch and file rows may be cleared only if they still match and no associated account write is unresolved. */
    suspend fun compareAndDelete(session: ClientStoredSession, workspaceId: String, previous: ClientCashImportBatch): Boolean
    /** Commit the stored operation before the first business HTTP request. */
    suspend fun checkpoint()
}

@Serializable
data class ClientCashImportBatch(
    val id: String,
    val revision: Long,
    val files: List<ClientCashImportFileRef>,
    val planId: String? = null,
    val ledgerSeq: String? = null,
    val review: String? = null,
    val submission: StoredCashImportSubmission? = null,
    val result: String? = null,
    val attempt: Int = 0,
) {
    override fun toString(): String = "ClientCashImportBatch(id=$id, revision=$revision)"
}

@Serializable
data class CashImportPreparedFile(
    val ref: String,
    val name: String,
    val status: String,
    val code: String?,
)

data class PreparedCashImport(
    val batchId: String, val revision: Long, val files: List<CashImportPreparedFile>, val context: CashImportSessionDto,
    val interruption: String? = null, val remainingFiles: List<String> = emptyList(),
)

@Serializable
data class ClientCashImportListing(
    @kotlinx.serialization.SerialName("batch_id") val batchId: String,
    val revision: String,
    val status: String,
    @kotlinx.serialization.SerialName("file_count") val fileCount: Int,
)

sealed interface ClientCashImportResume {
    data class Editable(val preparation: PreparedCashImport) : ClientCashImportResume
    data class Committed(val result: CashImportResultContent) : ClientCashImportResume
}

internal class EditableImportBatch(val ledger: ClientLedgerRead, val batch: ClientCashImportBatch) {
    val workspaceId: String get() = ledger.ledger.snapshot.workspaceId
}

class ClientCashImportOperations(
    private val session: ClientSessionOperations,
    private val store: ClientCashImportStore,
    private val calendar: CashLedgerCalendar,
    private val nextId: () -> String,
    private val remoteParser: ClientStatementParser = ClientStatementParser(session::parseStatement),
) {
    suspend fun batches(workspaceId: String?): List<ClientCashImportListing> {
        val ledger = session.ledger(workspaceId)
        val batches = store.batches(ledger.binding, ledger.ledger.snapshot.workspaceId)
        session.requireCurrent(ledger.binding)
        return batches.map { batch ->
            val status = when {
                batch.result != null -> "committed"
                batch.submission?.receipt != null -> "committed_details_pending"
                batch.submission != null -> "result_pending"
                else -> "open"
            }
            ClientCashImportListing(batch.id, batch.revision.toString(), status, batch.files.size)
        }
    }

    suspend fun prepare(workspaceId: String?, files: List<CashImportFileSource>): PreparedCashImport {
        validateFiles(files)
        val ledger = session.ledger(workspaceId)
        val batch = ClientCashImportBatch(nextId(), 1, emptyList())
        save(ledger, null, batch)
        return appendFiles(ledger, batch, files)
    }

    suspend fun addFiles(workspaceId: String?, batchId: String, revision: Long, files: List<CashImportFileSource>): PreparedCashImport {
        validateFiles(files)
        val ledger = session.ledger(workspaceId)
        val batch = batch(ledger, batchId)
        requireEditable(batch, revision)
        if (batch.files.size + files.size > 20 || files.any { file -> batch.files.any { it.ref == file.ref } })
            throw ClientOperationFailure("import_payload_invalid")
        return appendFiles(ledger, batch, files)
    }

    private fun validateFiles(files: List<CashImportFileSource>) {
        if (!validCashImportFileSelection(files.map { it.ref })) throw ClientOperationFailure("import_payload_invalid")
    }

    private suspend fun appendFiles(
        ledger: ClientLedgerRead, original: ClientCashImportBatch, sources: List<CashImportFileSource>,
    ): PreparedCashImport {
        var batch = original
        val failures = mutableListOf<CashImportPreparedFile>()
        for ((index, source) in sources.withIndex()) {
            val captured = try {
                capture(ledger, source.read().also { check(it.ref == source.ref) })
            } catch (failure: ClientNetworkUnavailable) {
                return prepared(ledger, batch).let {
                    it.copy(files = it.files + failures, interruption = "network_unavailable",
                        remainingFiles = sources.drop(index).map { pending -> pending.name })
                }
            } catch (failure: ClientOperationFailure) {
                if (failure.code == "online_required") return prepared(ledger, batch).let {
                    it.copy(files = it.files + failures, interruption = failure.code,
                        remainingFiles = sources.drop(index).map { pending -> pending.name })
                }
                if (failure.code !in setOf("import_file_read_failed", "import_file_too_large")) throw failure
                failures += CashImportPreparedFile(source.ref, source.name, "failed", failure.code)
                continue
            }
            val file = capturePayload(captured)
            val selection = changeCashImportFiles(batch.files, source.ref, CashImportFileEdit.ADD,
                ClientCashImportFileRef(source.ref, file.storageId), ClientCashImportFileRef::ref)
            val next = invalidateFileReview(batch, selection.selected, null)
            save(ledger, batch, next, file)
            store.checkpoint()
            batch = next
        }
        val prepared = prepared(ledger, batch)
        return prepared.copy(files = prepared.files + failures)
    }

    suspend fun replaceFile(workspaceId: String?, batchId: String, revision: Long, source: CashImportFileSource): PreparedCashImport {
        val ledger = session.ledger(workspaceId)
        val batch = batch(ledger, batchId)
        requireEditable(batch, revision)
        val old = batch.files.singleOrNull { it.ref == source.ref } ?: throw ClientOperationFailure("file_not_found", 404)
        val file = capturePayload(capture(ledger, source.read().also { check(it.ref == old.ref) }))
        val selection = changeCashImportFiles(batch.files, old.ref, CashImportFileEdit.REPLACE,
            ClientCashImportFileRef(old.ref, file.storageId), ClientCashImportFileRef::ref)
        val next = invalidateFileReview(batch, selection.selected, old.ref)
        save(ledger, batch, next, file, listOf(old.storageId))
        return prepared(ledger, next)
    }

    suspend fun removeFile(workspaceId: String?, batchId: String, revision: Long, fileRef: String): PreparedCashImport {
        val ledger = session.ledger(workspaceId)
        val batch = batch(ledger, batchId)
        requireEditable(batch, revision)
        val selection = try { changeCashImportFiles(batch.files, fileRef, CashImportFileEdit.REMOVE, fileRef = ClientCashImportFileRef::ref) }
        catch (failure: CashImportPlanError) { throw ClientOperationFailure(failure.code, 404, failure) }
        val old = checkNotNull(selection.previous)
        val next = invalidateFileReview(batch, selection.selected, old.ref)
        save(ledger, batch, next, removedFileIds = listOf(old.storageId))
        return prepared(ledger, next)
    }

    private fun invalidateFileReview(
        batch: ClientCashImportBatch, files: List<ClientCashImportFileRef>, obsoleteRef: String?,
    ): ClientCashImportBatch {
        val retained = batch.review?.let(LocalCashImportReview::decode)?.afterFilesChanged(setOfNotNull(obsoleteRef))
        return batch.copy(revision = nextRevision(batch), files = files, planId = null, ledgerSeq = null, review = retained?.encode())
    }

    private fun capturePayload(file: StoredCashImportFile): ClientCapturedCashImportFile =
        ClientCapturedCashImportFile(nextId(), clientJson.encodeToString(StoredCashImportFile.serializer(), file))

    internal suspend fun editable(workspace: String?, id: String, revision: Long): EditableImportBatch {
        val ledger = session.ledger(workspace)
        val batch = batch(ledger, id)
        requireEditable(batch, revision)
        return EditableImportBatch(ledger, batch)
    }

    suspend fun cancel(workspaceId: String?, batchId: String, revision: Long) =
        cancel(editable(workspaceId, batchId, revision))

    internal suspend fun cancel(editable: EditableImportBatch) {
        val binding = editable.ledger.binding
        val batch = editable.batch
        session.requireCurrent(binding)
        cancelPreparedCashImport(batch.submission != null, batch.result != null) {
            if (!store.compareAndDelete(binding, editable.workspaceId, batch))
                throw ClientOperationFailure("plan_changed", 409)
        }
    }

    suspend fun mapAccounts(
        workspaceId: String?, batchId: String, revision: Long, choices: List<CashImportAccountInput>, timezone: String,
    ): CashImportReviewOperations.Review {
        val ledger = session.ledger(workspaceId)
        val batch = batch(ledger, batchId)
        requireEditable(batch, revision)
        val retained = choices(batch)
        val mappings = (retained.accountSelections + choices).associateBy { it.sourceAccountRef }.values.toList()
        return review(ledger, batch, timezone, retained.copy(accountSelections = mappings))
    }

    private fun requireEditable(batch: ClientCashImportBatch, revision: Long) {
        if (batch.revision != revision || batch.submission != null || batch.result != null)
            throw ClientOperationFailure("plan_changed", 409)
    }

    private fun nextRevision(batch: ClientCashImportBatch): Long {
        check(batch.revision < Long.MAX_VALUE) { "cash_import_revision_exhausted" }
        return batch.revision + 1
    }

    private suspend fun prepared(ledger: ClientLedgerRead, batch: ClientCashImportBatch): PreparedCashImport {
        val items = mutableListOf<CashImportPreparedFile>()
        val parsed = batch.files.map { ref -> loadFile(ledger, batch, ref).let { file ->
            items += CashImportPreparedFile(file.clientFileRef, file.filename, file.status, file.errorCode)
            file.toParsedImportFile()
        } }
        return PreparedCashImport(batch.id, batch.revision, items, preparation(ledger, batch, parsed))
    }

    private suspend fun loadFile(ledger: ClientLedgerRead, batch: ClientCashImportBatch, ref: ClientCashImportFileRef): StoredCashImportFile {
        val value = store.readFile(ledger.binding, ledger.ledger.snapshot.workspaceId, batch.id, ref.storageId)
            ?: throw ClientOperationFailure("local_state_unreadable")
        return decodeStoredCashImportFile(value).also {
            if (it.clientFileRef != ref.ref) throw ClientOperationFailure("local_state_unreadable")
        }
    }

    private suspend fun parsedFiles(ledger: ClientLedgerRead, batch: ClientCashImportBatch): List<ParsedImportFile> =
        batch.files.map { loadFile(ledger, batch, it).toParsedImportFile() }

    private fun preparation(ledger: ClientLedgerRead, batch: ClientCashImportBatch, files: List<ParsedImportFile>): CashImportSessionDto =
        prepareLocalCashImportView(batch.id, files, ledger.ledger.snapshot, batch.review?.let(LocalCashImportReview::decode))
            .copy(sessionRevision = batch.revision, lastPlanId = batch.planId)

    suspend fun review(
        workspaceId: String?,
        batchId: String,
        timezone: String,
        updatedChoices: CashImportPreviewInput? = null,
    ): CashImportReviewOperations.Review {
        val ledger = session.ledger(workspaceId)
        return review(ledger, batch(ledger, batchId), timezone, updatedChoices)
    }

    private suspend fun review(
        ledger: ClientLedgerRead, batch: ClientCashImportBatch,
        timezone: String, updatedChoices: CashImportPreviewInput?,
    ): CashImportReviewOperations.Review {
        val snapshot = ledger.ledger.snapshot
        if (batch.submission != null || batch.result != null) throw ClientOperationFailure("plan_changed", 409)
        if (updatedChoices != null && (updatedChoices.expectedSessionRevision != batch.revision ||
                updatedChoices.previousPlanId != batch.planId)) throw ClientOperationFailure("plan_changed", 409)
        val retained = choices(batch)
        if (updatedChoices != null) {
            val previousSelections = retained.accountSelections.associateBy { it.sourceAccountRef }
            for (selection in updatedChoices.accountSelections) {
                if (selection == previousSelections[selection.sourceAccountRef]) continue
                val accountId = selection.accountId ?: continue
                val fact = cashAccountFact(snapshot.facts.accounts, accountId) ?: throw ClientOperationFailure("account_not_found", 404)
                val account = cashAccountSelection(fact, canWrite = false)
                if (!isSelectableCashAccount(account)) throw ClientOperationFailure("account_unavailable")
                if (account.accountRevision != selection.expectedAccountRevision) throw ClientOperationFailure("account_changed", 409)
            }
        }

        val reusable = updatedChoices == null && batch.planId != null && batch.ledgerSeq == snapshot.ledgerSeq
        val revision = if (reusable) batch.revision else nextRevision(batch)
        val planId = if (reusable) checkNotNull(batch.planId) else nextId()
        val previous = batch.review?.let(LocalCashImportReview::decode)
        val choices = (updatedChoices ?: retained).copy(expectedSessionRevision = revision, timezoneName = timezone)
        val review = CashImportReviewOperations(snapshot.workspaceId, calendar).review(
            snapshot, batch.id, planId, revision, parsedFiles(ledger, batch),
            choices, previous,
        )
        session.requireCurrent(ledger.binding)
        if (!reusable) save(ledger, batch, batch.copy(revision = revision, planId = planId, ledgerSeq = snapshot.ledgerSeq,
            review = review.calculation.review.encode()))
        return review
    }

    private fun choices(batch: ClientCashImportBatch): CashImportPreviewInput {
        val input = batch.review?.let(LocalCashImportReview::decode)?.inputJson()
            ?: return CashImportPreviewInput(batch.revision, batch.planId, emptyList(), emptyList(), emptyList())
        val metadata = buildJsonObject {
            put("expected_session_revision", JsonPrimitive(batch.revision))
            put("previous_plan_id", batch.planId?.let(::JsonPrimitive) ?: JsonNull)
        }
        return clientJson.decodeFromJsonElement(CashImportPreviewInput.serializer(), JsonObject(input + metadata))
    }

    suspend fun confirm(workspaceId: String?, batchId: String, revision: Long): CashImportResultContent {
        val ledger = session.ledger(workspaceId)
        val batch = batch(ledger, batchId)
        if (batch.revision != revision || batch.planId == null) throw ClientOperationFailure("plan_changed", 409)
        result(batch)?.let { return it }
        session.requireOnlineWriter(ledger)
        if (batch.submission != null) return finish(ledger, batch)
        val review = review(ledger, batch, "UTC", updatedChoices = null)
        val expected = checkNotNull(batch.review).let(LocalCashImportReview::decode)
        if (review.plan.sessionRevision != revision || !review.plan.canCommit ||
            expected.conclusion() != review.calculation.review.conclusion())
            throw ClientOperationFailure("plan_changed", 409)
        if (ledger.ledger.snapshot.ledgerSeq != review.calculation.request.baseLedgerSeq)
            throw ClientOperationFailure("plan_changed", 409)
        val frozen = freezeCashImportConfirmation(attemptKey(batch.id, batch.attempt), review.plan.canCommit,
            review.calculation.request, review.calculation.plan)
        save(ledger, batch, batch.copy(submission = frozen))
        store.checkpoint()
        val durable = batch(ledger, batchId)
        result(durable)?.let { return it }
        return finish(ledger, durable)
    }

    suspend fun resume(workspaceId: String?, batchId: String): ClientCashImportResume {
        val ledger = session.ledger(workspaceId)
        val batch = batch(ledger, batchId)
        result(batch)?.let { return ClientCashImportResume.Committed(it) }
        if (batch.submission == null) return ClientCashImportResume.Editable(prepared(ledger, batch))
        if (ledger.offline && batch.submission.receipt == null) throw ClientOperationFailure("result_unknown", commitState = "unknown")
        return ClientCashImportResume.Committed(finish(ledger, batch))
    }

    suspend fun result(workspaceId: String?, batchId: String): CashImportResultContent {
        val ledger = session.ledger(workspaceId)
        val batch = batch(ledger, batchId)
        return result(batch) ?: batch.submission?.receipt?.let { provisionalResult(batch) }
            ?: throw ClientOperationFailure("result_not_available")
    }

    private suspend fun finish(initialLedger: ClientLedgerRead, initialBatch: ClientCashImportBatch): CashImportResultContent =
        resumeCashImportSubmission(object : ClientImportSubmissionHost<CashImportResultContent> {
            private var ledger = initialLedger
            private var batch = initialBatch

            override suspend fun current(): ClientImportSubmissionState<CashImportResultContent> =
                result(batch)?.let { ClientImportSubmissionState.Complete(it) }
                    ?: ClientImportSubmissionState.Pending(batch.submission ?: throw ClientOperationFailure("plan_changed", 409))

            override suspend fun receipt(key: String) = session.cashImportReceipt(ledger, key)

            override suspend fun submit(attempt: StoredCashImportSubmission) =
                session.submitCashImport(ledger, attempt.key, attempt.request)

            override suspend fun recordBaseRejection(attempt: StoredCashImportSubmission, reason: ClientOperationFailure) {
                val closed = batch.copy(submission = attempt.copy(baseRejected = true))
                if (closed == batch) return
                save(ledger, batch, closed)
                store.checkpoint()
                batch = closed
            }

            override suspend fun rebase(attempt: StoredCashImportSubmission, reason: ClientOperationFailure): CashMutationReceiptDto? {
                ledger = session.refreshLedger(ledger)
                batch = batch(ledger, batch.id)
                val rejected = attempt.copy(baseRejected = true)
                if (batch.submission != rejected) return null
                if (ledger.offline) throw reason
                val before = cashWriteSetJson.decodeFromString<CashWriteSetRequestDto>(attempt.request).baseLedgerSeq
                val snapshot = ledger.ledger.snapshot
                val nextAttempt = nextAttempt(batch.attempt)
                return try {
                    rebaseRejectedCashImport(before, snapshot.ledgerSeq, reason, { receipt(attempt.key) }, calculate = {
                        val original = checkNotNull(batch.review).let(LocalCashImportReview::decode)
                        rebaseCashImportReview(parsedFiles(ledger, batch), snapshot, original) {}
                    }, retire = { replacement ->
                        val next = if (replacement == null) {
                            batch.copy(revision = nextRevision(batch), planId = null, ledgerSeq = null, submission = null, attempt = nextAttempt)
                        } else {
                            batch.copy(attempt = nextAttempt, submission = rejected.copy(
                                key = attemptKey(batch.id, nextAttempt), request = cashWriteSetJson.encodeToString(replacement),
                                baseRejected = false, transport = null,
                            ))
                        }
                        save(ledger, batch, next)
                        store.checkpoint()
                        batch = batch(ledger, batch.id)
                    })
                } catch (changed: CashImportPlanError) {
                    throw ClientOperationFailure(changed.code, 409, changed, "not_committed")
                }
            }

            override suspend fun discardReplayMismatch(attempt: StoredCashImportSubmission) {
                save(ledger, batch, batch.copy(revision = nextRevision(batch), planId = null, ledgerSeq = null, submission = null,
                    attempt = nextAttempt(batch.attempt)))
            }

            override suspend fun complete(attempt: StoredCashImportSubmission, receipt: CashMutationReceiptDto) =
                archive(ledger, batch, receipt)
        })

    private fun nextAttempt(attempt: Int): Int {
        check(attempt < Int.MAX_VALUE) { "cash_import_attempt_exhausted" }
        return attempt + 1
    }

    private fun attemptKey(id: String, attempt: Int): String = if (attempt == 0) id else "$id:attempt:$attempt"

    private suspend fun archive(ledger: ClientLedgerRead, batch: ClientCashImportBatch, receipt: CashMutationReceiptDto): CashImportResultContent {
        val submission = checkNotNull(batch.submission)
        val receiptBody = cashWriteSetJson.encodeToString(receipt)
        val known = batch.copy(submission = submission.copy(receipt = receiptBody, baseRejected = false))
        if (batch != known) {
            save(ledger, batch, known)
            store.checkpoint()
        }
        val current = batch(ledger, batch.id)
        result(current)?.let { return it }
        val raw = checkNotNull(current.submission)
        val serverItems = try {
            session.cashImportResultItems(ledger, receipt.receiptId)
        } catch (_: ClientNetworkUnavailable) {
            session.requireCurrent(ledger.binding)
            return provisionalResult(current)
        } catch (failure: ClientOperationFailure) {
            if (failure.status in setOf(401, 403)) throw failure
            session.requireCurrent(ledger.binding)
            return provisionalResult(current)
        }
        val content = CashImportResultContent.assemble(batch.id, raw.key, checkNotNull(raw.receipt),
            LocalCashImportResultInput.decode(checkNotNull(raw.resultInput)), serverItems)
        save(ledger, current, current.copy(files = emptyList(), review = null, submission = null, result = content.encode()),
            removedFileIds = current.files.map { it.storageId })
        return content
    }

    private suspend fun batch(ledger: ClientLedgerRead, id: String): ClientCashImportBatch {
        session.requireCurrent(ledger.binding)
        val batch = store.read(ledger.binding, ledger.ledger.snapshot.workspaceId, id)
        session.requireCurrent(ledger.binding)
        return batch ?: throw ClientOperationFailure("batch_not_found", 404)
    }

    private fun provisionalResult(batch: ClientCashImportBatch): CashImportResultContent {
        val original = checkNotNull(batch.submission)
        return CashImportResultContent.assemble(batch.id, original.key, checkNotNull(original.receipt),
            LocalCashImportResultInput.decode(checkNotNull(original.resultInput)), null)
    }

    private fun result(batch: ClientCashImportBatch): CashImportResultContent? =
        batch.result?.let(CashImportResultContent::decode)

    private suspend fun capture(ledger: ClientLedgerRead, source: CashImportFileInput): StoredCashImportFile {
        val file = CashImportFileInput(source.ref, source.name, source.modifiedAtMillis, source.bytes.copyOf(),
            source.source?.replace('-', '_'), source.password, source.mediaType)
        return when (val result = captureCashImportFile(file, retainKnownFailure = true)) {
            is CashImportFileCapture.Prepared -> result.file
            is CashImportFileCapture.RequiresRemoteParser -> {
                if (ledger.offline) throw ClientOperationFailure("online_required")
                try {
                    completeCashImportFile(file, remoteParser.parse(ledger, result.format, file))
                } catch (failure: StatementDocumentFailure) {
                    retainCashImportFileFailure(file, true, failure.code) ?: throw failure
                } catch (failure: ClientOperationFailure) {
                    retainCashImportFileFailure(file, true, failure.code, failure.status ?: 0) ?: throw failure
                }
            }
        }
    }

    private suspend fun save(
        ledger: ClientLedgerRead, previous: ClientCashImportBatch?, batch: ClientCashImportBatch,
        file: ClientCapturedCashImportFile? = null, removedFileIds: List<String> = emptyList(),
    ) {
        session.requireCurrent(ledger.binding)
        if (!store.compareAndSave(ledger.binding, ledger.ledger.snapshot.workspaceId, previous, batch, file, removedFileIds))
            throw ClientOperationFailure("plan_changed", 409)
    }
}
