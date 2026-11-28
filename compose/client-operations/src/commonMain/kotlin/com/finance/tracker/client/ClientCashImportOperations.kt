package com.finance.tracker.client

import com.finance.tracker.business.contracts.CashImportAccountInput
import com.finance.tracker.business.contracts.CashImportConfirmation
import com.finance.tracker.business.contracts.CashImportConfirmationStatus
import com.finance.tracker.business.contracts.CashImportPreviewInput
import com.finance.tracker.business.contracts.CashImportPlanDto
import com.finance.tracker.business.contracts.CashImportSessionDto
import com.finance.tracker.business.contracts.CashMutationReceiptDto
import com.finance.tracker.business.contracts.StoredCashImportSubmission
import com.finance.tracker.business.domain.buildLocalCashImportBusinessPlan
import com.finance.tracker.business.domain.freezeLocalCashImportPreviewPlan
import com.finance.tracker.business.domain.identifyCashImportPreview
import kotlin.time.Clock
import com.finance.tracker.business.domain.CashImportResultContent
import com.finance.tracker.business.domain.CashImportPlanError
import com.finance.tracker.business.domain.CashImportReviewWorkspace
import com.finance.tracker.business.domain.CASH_RULES_VERSION
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
    suspend fun batches(session: ClientStoredSession, workspaceId: String): List<ClientCashImportListing>
    suspend fun read(session: ClientStoredSession, workspaceId: String, batchId: String): ClientCashImportBatch?
    suspend fun readPreview(session: ClientStoredSession, workspaceId: String, batchId: String): ClientCashImportBatch? =
        read(session, workspaceId, batchId)
    suspend fun pendingConfirmationKey(session: ClientStoredSession, workspaceId: String, batchId: String): String? = null
    suspend fun archivedConfirmation(session: ClientStoredSession, workspaceId: String, batchId: String): CashImportConfirmation? = null
    /** Save local read projections of the verified archive in the same transaction. */
    suspend fun rememberArchivedConfirmation(session: ClientStoredSession, workspaceId: String, archive: CashImportResultContent) {}
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
    fun openReviewWorkspace(session: ClientStoredSession, workspaceId: String, batchId: String): CashImportReviewWorkspace? = null
    fun finishReviewWorkspace() {}
    /** The batch and file rows may be cleared only if they still match and no associated account write is unresolved. */
    suspend fun compareAndDelete(session: ClientStoredSession, workspaceId: String, previous: ClientCashImportBatch): Boolean
    /** Commit the stored operation before the first business HTTP request. */
    suspend fun checkpoint()
}

data class ClientCashImportCancellation(
    val operation: CashImportConfirmation,
    val receipt: CashMutationReceiptDto?,
)

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
    val reviewed: ClientCashImportReviewedCommand? = null,
) {
    override fun toString(): String = "ClientCashImportBatch(id=$id, revision=$revision)"

    fun listing(): ClientCashImportListing = ClientCashImportListing(id, revision.toString(), when {
        result != null -> "committed"
        submission?.receipt != null -> "committed_details_pending"
        submission != null -> "result_pending"
        else -> "open"
    }, files.size)
}

@Serializable
data class ClientCashImportReviewedCommand(val rulesVersion: String, val request: String, val resultInput: String) {
    override fun toString(): String = "ClientCashImportReviewedCommand(rulesVersion=$rulesVersion)"
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

fun interface CashImportReviewDiagnostics {
    fun sampleReviewPhase(phase: String)
}

class ClientCashImportOperations(
    private val session: ClientSessionOperations,
    private val store: ClientCashImportStore,
    private val calendar: CashLedgerCalendar,
    private val nextId: () -> String,
    private val remoteParser: ClientStatementParser = ClientStatementParser(session::parseStatement),
    private val progress: (String) -> Unit = {},
) {
    suspend fun batches(workspaceId: String?): List<ClientCashImportListing> {
        val (binding, workspace) = session.localImportWorkspace(workspaceId)
        val batches = store.batches(binding, workspace)
        session.requireCurrent(binding)
        return batches
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
        return batch.copy(revision = nextRevision(batch), files = files, planId = null, ledgerSeq = null,
            review = retained?.encode(), reviewed = null)
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

    /** Convert a workspace-backed review into its terminal output while all blocks are available. */
    suspend fun <T> reviewForOutput(
        workspaceId: String?, batchId: String, timezone: String, updatedChoices: CashImportPreviewInput?,
        render: (String, CashImportPlanDto, String) -> T,
    ): T {
        val ledger = session.ledger(workspaceId)
        return reviewWithinWorkspace(ledger, batch(ledger, batchId), timezone, updatedChoices, true) {
            render(it.workspaceId, it.plan, it.calculation.request.baseLedgerSeq)
        }
    }

    suspend fun reviewView(workspaceId: String?, batchId: String, timezone: String): CashImportReviewView {
        val ledger = session.ledger(workspaceId)
        val snapshot = ledger.ledger.snapshot
        session.requireCurrent(ledger.binding)
        val batch = store.readPreview(ledger.binding, snapshot.workspaceId, batchId)
            ?: throw ClientOperationFailure("batch_not_found", 404)
        session.requireCurrent(ledger.binding)
        val planId = batch.planId
        val reusable = batch.submission == null && batch.result == null && planId != null &&
            batch.ledgerSeq == snapshot.ledgerSeq && snapshot.rulesVersion == CASH_RULES_VERSION
        val previous = if (reusable) batch.review?.let(LocalCashImportReview::decode) else null
        if (planId == null || previous?.conclusion() == null) {
            return review(ledger, batch(ledger, batchId), timezone, updatedChoices = null).reads
        }
        calendar.validateImportZone(timezone)
        val frozenChoices = freezeCashImportChoices(previous.inputJson())
        val plan = buildLocalCashImportBusinessPlan(
            parsedFiles(ledger, batch), snapshot, frozenChoices, previous.previousPlan(), previous.recordIds(),
            requireActive = {}, now = Clock.System::now,
        )
        val reader = cashImportPlanReader(
            identifyCashImportPreview(freezeLocalCashImportPreviewPlan(plan), planId, batchId, batch.revision), calendar,
        )
        reader.validateReadable()
        session.requireCurrent(ledger.binding)
        return CashImportReviewView(reader)
    }

    private suspend fun review(
        ledger: ClientLedgerRead, batch: ClientCashImportBatch,
        timezone: String, updatedChoices: CashImportPreviewInput?,
    ): CashImportReviewOperations.Review =
        reviewWithinWorkspace(ledger, batch, timezone, updatedChoices, false) { it }

    private suspend fun <T> reviewWithinWorkspace(
        ledger: ClientLedgerRead, batch: ClientCashImportBatch,
        timezone: String, updatedChoices: CashImportPreviewInput?, useWorkspace: Boolean,
        render: (CashImportReviewOperations.Review) -> T,
    ): T {
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
        progress("read_files")
        val files = parsedFiles(ledger, batch)
        val workspace = if (useWorkspace) store.openReviewWorkspace(ledger.binding, snapshot.workspaceId, batch.id) else null
        val diagnostics = (store as? CashImportReviewDiagnostics).takeIf { useWorkspace }
        val review = CashImportReviewOperations(snapshot.workspaceId, calendar).review(
            snapshot, batch.id, planId, revision, files,
            choices, previous, progress = { progress(it.name.lowercase()) }, requireActive = {}, workspace = workspace,
            sample = { diagnostics?.sampleReviewPhase(it) },
        )
        val publish = !reusable || (batch.reviewed == null && review.calculation.requestBody != null &&
            previous?.conclusion() == review.calculation.review.conclusion())
        val reviewed = review.calculation.requestBody?.takeIf { publish }?.let { body ->
            ClientCashImportReviewedCommand(CASH_RULES_VERSION, body, review.resultInput().encode())
        }
        val rendered = render(review)
        diagnostics?.sampleReviewPhase("rendered_preview")
        if (workspace != null) store.finishReviewWorkspace()
        session.requireCurrent(ledger.binding)
        if (publish) {
            progress("save_review")
            save(ledger, batch, batch.copy(revision = revision, planId = planId, ledgerSeq = snapshot.ledgerSeq,
                review = review.calculation.review.encode(), reviewed = reviewed))
        }
        return rendered
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

    suspend fun confirm(workspaceId: String?, batchId: String, revision: Long): CashImportResultContent =
        completedImport(confirmOperation(workspaceId, batchId, revision, waitForReceipt = true))

    suspend fun acceptConfirmation(workspaceId: String?, batchId: String, revision: Long): ClientImportConfirmationOutcome<CashImportResultContent> =
        confirmOperation(workspaceId, batchId, revision, waitForReceipt = false)

    private suspend fun confirmOperation(workspaceId: String?, batchId: String, revision: Long,
        waitForReceipt: Boolean): ClientImportConfirmationOutcome<CashImportResultContent> {
        val ledger = session.ledger(workspaceId)
        val batch = batch(ledger, batchId)
        if (batch.revision != revision || batch.planId == null) throw ClientOperationFailure("plan_changed", 409)
        result(batch)?.let { return ClientImportConfirmationOutcome.Complete(it) }
        session.requireOnlineWriter(ledger)
        if (batch.submission != null) return continueCashImportSubmission(finishHost(ledger.importWorkspace, batch), waitForReceipt)
        val key = attemptKey(batch.id, batch.attempt)
        val snapshot = ledger.ledger.snapshot
        if (snapshot.rulesVersion != CASH_RULES_VERSION) throw ClientOperationFailure("rules_version_changed", 409,
            commitState = "not_committed")
        val reviewed = batch.reviewed?.takeIf {
            it.rulesVersion == snapshot.rulesVersion && batch.ledgerSeq == snapshot.ledgerSeq
        }
        val frozen = if (reviewed != null) {
            StoredCashImportSubmission(key, reviewed.request, resultInput = reviewed.resultInput)
        } else {
            val review = review(ledger, batch, "UTC", updatedChoices = null)
            val expected = checkNotNull(batch.review).let(LocalCashImportReview::decode)
            if (review.plan.sessionRevision != revision || !review.plan.canCommit ||
                expected.conclusion() != review.calculation.review.conclusion() ||
                ledger.ledger.snapshot.ledgerSeq != review.calculation.request.baseLedgerSeq)
                throw ClientOperationFailure("plan_changed", 409)
            freezeCashImportConfirmation(key, review.plan.canCommit, review.calculation.request, review.calculation.plan)
        }
        save(ledger, batch, batch.copy(submission = frozen, reviewed = null))
        store.checkpoint()
        val durable = batch(ledger, batchId)
        result(durable)?.let { return ClientImportConfirmationOutcome.Complete(it) }
        return continueCashImportSubmission(finishHost(ledger.importWorkspace, durable), waitForReceipt)
    }

    suspend fun resume(workspaceId: String?, batchId: String): ClientCashImportResume {
        var scope = session.localImportWorkspace(workspaceId)
        var batch = batch(scope.binding, scope.id, batchId)
        result(batch)?.let { return ClientCashImportResume.Committed(it) }
        if (batch.submission == null) {
            val ledger = session.refreshLedger(scope)
            scope = ledger.importWorkspace
            batch = batch(ledger, batchId)
            result(batch)?.let { return ClientCashImportResume.Committed(it) }
            if (batch.submission == null) return ClientCashImportResume.Editable(prepared(ledger, batch))
        }
        if (scope.offline && checkNotNull(batch.submission).receipt == null)
            throw ClientOperationFailure("result_unknown", commitState = "unknown")
        return ClientCashImportResume.Committed(resumeCashImportSubmission(finishHost(scope, batch)))
    }

    suspend fun result(workspaceId: String?, batchId: String): CashImportResultContent {
        val (binding, workspace) = session.localImportWorkspace(workspaceId)
        return savedResult(binding, workspace, batchId)
    }

    suspend fun <T : Any> result(
        workspaceId: String?, batchId: String,
        readArchived: suspend (ClientStoredSession, String) -> T?,
        render: (CashImportResultContent) -> T,
    ): T {
        val (binding, workspace) = session.localImportWorkspace(workspaceId)
        session.requireCurrent(binding)
        readArchived(binding, workspace)?.let {
            session.requireCurrent(binding)
            return it
        }
        return render(savedResult(binding, workspace, batchId))
    }

    private suspend fun savedResult(binding: ClientStoredSession, workspace: String, batchId: String): CashImportResultContent {
        val batch = batch(binding, workspace, batchId)
        return result(batch) ?: batch.submission?.receipt?.let { provisionalResult(batch) }
            ?: throw ClientOperationFailure("result_not_available")
    }

    suspend fun confirmationStatus(workspaceId: String?, batchId: String): CashImportConfirmation =
        observeConfirmation(workspaceId, batchId, cancel = false).operation

    suspend fun cancelConfirmation(workspaceId: String?, batchId: String): ClientCashImportCancellation =
        observeConfirmation(workspaceId, batchId, cancel = true)

    private suspend fun observeConfirmation(workspaceId: String?, batchId: String, cancel: Boolean): ClientCashImportCancellation {
        val (binding, workspace) = session.cashImportWorkspace(workspaceId)
        session.requireCurrent(binding)
        store.pendingConfirmationKey(binding, workspace, batchId)?.let { key ->
            return observePendingConfirmation(binding, workspace, key, cancel)
        }
        if (!cancel) store.archivedConfirmation(binding, workspace, batchId)?.let {
            session.requireCurrent(binding)
            return ClientCashImportCancellation(it, null)
        }
        val batch = batch(binding, workspace, batchId)
        val outcome = result(batch)
        val key = batch.submission?.key ?: attemptKey(batch.id, batch.attempt)
        if (outcome != null) {
            val receipt = outcome.authoritativeReceipt()
            val operation = CashImportConfirmation(outcome.submissionKey, CashImportConfirmationStatus.COMMITTED, receipt.receiptId)
            return ClientCashImportCancellation(operation, receipt.takeIf { cancel })
        }
        if (batch.submission == null) throw ClientOperationFailure("import_confirmation_not_found", 404)
        return observePendingConfirmation(binding, workspace, key, cancel)
    }

    private suspend fun observePendingConfirmation(
        binding: ClientStoredSession, workspace: String, key: String, cancel: Boolean,
    ): ClientCashImportCancellation {
        val operation = session.cashImportConfirmation(binding, workspace, key, cancel)
            ?: throw ClientOperationFailure("import_confirmation_not_found", 404)
        val receipt = if (cancel && operation.status == CashImportConfirmationStatus.COMMITTED)
            receiptForConfirmation(operation) { session.cashImportReceipt(binding, workspace, key) }
        else null
        return ClientCashImportCancellation(operation, receipt)
    }

    private fun finishHost(initialScope: ClientImportWorkspace, initialBatch: ClientCashImportBatch) =
        object : ClientImportSubmissionHost<CashImportResultContent> {
            private var scope = initialScope
            private var batch = initialBatch

            override suspend fun current(): ClientImportSubmissionState<CashImportResultContent> =
                result(batch)?.let { ClientImportSubmissionState.Complete(it) }
                    ?: ClientImportSubmissionState.Pending(batch.submission ?: throw ClientOperationFailure("plan_changed", 409))

            override suspend fun receipt(key: String): CashMutationReceiptDto? {
                progress("check_receipt")
                return session.cashImportReceipt(scope.binding, scope.id, key)
            }

            override suspend fun status(key: String): CashImportConfirmation? =
                session.cashImportConfirmation(scope, key)

            override suspend fun submit(attempt: StoredCashImportSubmission): CashImportConfirmation {
                progress("submit_write_set")
                return session.submitCashImport(scope, attempt.key, attempt.request)
            }

            override suspend fun recordBaseRejection(attempt: StoredCashImportSubmission, reason: ClientOperationFailure) {
                val closed = batch.copy(submission = attempt.copy(baseRejected = true))
                if (closed == batch) return
                save(scope, batch, closed)
                store.checkpoint()
                batch = closed
            }

            override suspend fun rebase(attempt: StoredCashImportSubmission, reason: ClientOperationFailure): CashMutationReceiptDto? {
                val ledger = session.refreshLedger(scope)
                scope = ledger.importWorkspace
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
                        encodeCashImportRequest(rebaseCashImportReview(parsedFiles(ledger, batch), snapshot, original) {})
                    }, retire = { replacement ->
                        val next = if (replacement == null) {
                            batch.copy(revision = nextRevision(batch), planId = null, ledgerSeq = null, submission = null, attempt = nextAttempt)
                        } else {
                            batch.copy(attempt = nextAttempt, submission = rejected.copy(
                                key = attemptKey(batch.id, nextAttempt), request = replacement,
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

            override suspend fun discardRejectedAttempt(attempt: StoredCashImportSubmission, reason: ClientOperationFailure) {
                save(scope, batch, batch.copy(revision = nextRevision(batch), planId = null, ledgerSeq = null, submission = null,
                    attempt = nextAttempt(batch.attempt)))
                store.checkpoint()
            }

            override suspend fun complete(attempt: StoredCashImportSubmission, receipt: CashMutationReceiptDto) =
                archive(scope, batch, receipt)
        }

    private fun nextAttempt(attempt: Int): Int {
        check(attempt < Int.MAX_VALUE) { "cash_import_attempt_exhausted" }
        return attempt + 1
    }

    private fun attemptKey(id: String, attempt: Int): String = if (attempt == 0) id else "$id:attempt:$attempt"

    private suspend fun archive(scope: ClientImportWorkspace, batch: ClientCashImportBatch, receipt: CashMutationReceiptDto): CashImportResultContent {
        progress("read_result")
        val submission = checkNotNull(batch.submission)
        val receiptBody = cashWriteSetJson.encodeToString(receipt)
        val known = batch.copy(submission = submission.copy(receipt = receiptBody, baseRejected = false))
        if (batch != known) {
            save(scope, batch, known)
            store.checkpoint()
        }
        val current = batch(scope.binding, scope.id, batch.id)
        result(current)?.let { return it }
        val raw = checkNotNull(current.submission)
        val serverItems = try {
            session.cashImportResultItems(scope, receipt.receiptId)
        } catch (_: ClientNetworkUnavailable) {
            session.requireCurrent(scope.binding)
            return provisionalResult(current)
        } catch (failure: ClientOperationFailure) {
            if (failure.status in setOf(401, 403) || failure.status == null && failure.code == "workspace_conflict")
                throw failure
            session.requireCurrent(scope.binding)
            return provisionalResult(current)
        }
        val content = CashImportResultContent.assemble(batch.id, raw.key, checkNotNull(raw.receipt),
            LocalCashImportResultInput.decode(checkNotNull(raw.resultInput)), serverItems)
        save(scope, current, current.copy(files = emptyList(), review = null, submission = null, result = content.encode()),
            removedFileIds = current.files.map { it.storageId })
        store.rememberArchivedConfirmation(scope.binding, scope.id, content)
        return content
    }

    private suspend fun batch(ledger: ClientLedgerRead, id: String): ClientCashImportBatch =
        batch(ledger.binding, ledger.ledger.snapshot.workspaceId, id)

    private suspend fun batch(binding: ClientStoredSession, workspace: String, id: String): ClientCashImportBatch {
        session.requireCurrent(binding)
        val batch = store.read(binding, workspace, id)
        session.requireCurrent(binding)
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
    ) = save(ledger.importWorkspace, previous, batch, file, removedFileIds)

    private suspend fun save(
        scope: ClientImportWorkspace, previous: ClientCashImportBatch?, batch: ClientCashImportBatch,
        file: ClientCapturedCashImportFile? = null, removedFileIds: List<String> = emptyList(),
    ) {
        session.requireCurrent(scope.binding)
        if (!store.compareAndSave(scope.binding, scope.id, previous, batch, file, removedFileIds))
            throw ClientOperationFailure("plan_changed", 409)
    }
}
