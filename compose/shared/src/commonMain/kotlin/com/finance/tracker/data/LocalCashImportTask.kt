package com.finance.tracker.data

import com.finance.tracker.business.domain.CashImportPlanError
import com.finance.tracker.client.CashImportFileEdit
import com.finance.tracker.client.cancelPreparedCashImport
import com.finance.tracker.client.changeCashImportFiles
import com.finance.tracker.business.contracts.CashMutationReceiptDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** One coordinator per batch on its original session. Plans and revisions belong to this live task only. */
internal class LocalCashImportTask(
    val batchId: String,
    private val imports: LedgerSessionImports,
    private val nextPlanId: () -> String,
) {
    private val mutex = Mutex()
    private val recoveryMutex = Mutex()
    private var revision = 0L
    private var observed: List<String>? = null
    private var initialized = false
    private var currentPlan: Plan? = null
    private var closing = false
    private var cancellation: CashImportSessionDto? = null
    private var beforeCancellation: CashImportSessionDto? = null
    private var cancellationHasBatch = false
    private var recovering = false
    private var completed: LocalCashImportSubmissionResult? = null

    private class Plan(val id: String, val preview: OpenedCashImportPreview) {
        var recoveryStarted = false
    }

    init { require(batchId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "cash_import_batch_id_invalid" } }

    suspend fun context(): CashImportSessionDto = mutex.withLock {
        imports.checkActive()
        cancellation?.let { return@withLock it }
        completed?.let { result ->
            return@withLock CashImportSessionDto(batchId, state = "committed", files = emptyList(), sourceAccounts = emptyList(),
                canPreview = false, receiptId = result.receipt.receiptId)
        }
        mutableFiles()
        preparation()
    }

    /** Parsing may overlap other files; only the final save shares the task lock with previews. */
    suspend fun append(input: CashImportUploadInput): CashImportSessionDto {
        mutex.withLock { mutableFiles(allowMissing = true) }
        val file = imports.parseImportFile(input)
        return mutex.withLock {
            val files = mutableFiles(allowMissing = true)
            val edit = changeCashImportFiles(files, file.fileRef, CashImportFileEdit.ADD, file,
                LocalCashImportFile::fileRef) { previous, replacement -> previous.encode() == replacement.encode() }
            if (!edit.changed) return@withLock preparation()
            ensureRevisionAvailable()
            currentPlan = null
            imports.savePreparedImportFile(batchId, file)
            synchronizeFiles()
            preparation()
        }
    }

    suspend fun replace(fileRef: String, expectedRevision: Long, input: CashImportUploadInput): CashImportSessionDto {
        if (input.clientFileRef != fileRef) throw CashImportPlanError("plan_input_invalid")
        val original = mutex.withLock {
            val files = mutableFiles()
            requireRevision(expectedRevision)
            files.singleOrNull { it.fileRef == fileRef } ?: throw CashImportPlanError("plan_changed")
        }
        val file = imports.parseImportFile(input)
        return mutex.withLock {
            val files = mutableFiles()
            requireRevision(expectedRevision)
            val edit = changeCashImportFiles(files, fileRef, CashImportFileEdit.REPLACE, file,
                LocalCashImportFile::fileRef) { previous, replacement -> previous.encode() == replacement.encode() }
            if (!edit.changed) return@withLock preparation()
            ensureRevisionAvailable()
            currentPlan = null
            imports.savePreparedImportFile(batchId, file, original)
            synchronizeFiles()
            preparation()
        }
    }

    suspend fun remove(fileRef: String, expectedRevision: Long): CashImportSessionDto = mutex.withLock {
        val files = mutableFiles()
        requireRevision(expectedRevision)
        val edit = try { changeCashImportFiles(files, fileRef, CashImportFileEdit.REMOVE, fileRef = LocalCashImportFile::fileRef) }
        catch (_: CashImportPlanError) { throw CashImportPlanError("plan_changed") }
        val original = checkNotNull(edit.previous)
        ensureRevisionAvailable()
        currentPlan = null
        imports.removeImportFile(batchId, original)
        synchronizeFiles()
        preparation()
    }

    suspend fun cancel(): CashImportSessionDto = mutex.withLock {
        imports.checkActive()
        cancellation?.let { return@withLock it }
        if (!closing) {
            val files = mutableFiles(allowMissing = true)
            ensureRevisionAvailable()
            val summaries = summarizeCashImportFiles(files)
            cancellationHasBatch = observed != null
            beforeCancellation = CashImportSessionDto(batchId, state = "cancelled", files = summaries,
                sourceAccounts = emptyList(), canPreview = false)
            // Fence already parsing files even if deletion fails or its caller is cancelled.
            closing = true
            currentPlan = null
        }
        cancelPreparedCashImport(imports.importSubmission(batchId) != null, completed != null) {
            if (cancellationHasBatch) imports.removeImportFiles(batchId)
        }
        revision++
        checkNotNull(beforeCancellation).copy(sessionRevision = revision, state = "cancelled", canPreview = false,
            lastPlanId = null).also { cancellation = it }
    }

    suspend fun preview(expectedRevision: Long, previousPlanId: String?, input: JsonObject, timezoneName: String = "UTC"): JsonObject {
        val frozen = Json.parseToJsonElement(input.toString()).jsonObject
        return mutex.withLock {
            checkMutable()
            val files = imports.importFileEncodings(batchId)
            observeFileEncodings(files)
            requireNoSubmission()
            if (files == null) throw CashImportPlanError("plan_not_found")
            requireRevision(expectedRevision)
            if (previousPlanId != currentPlan?.id) throw CashImportPlanError("plan_changed")
            ensureRevisionAvailable()
            val previous = currentPlan?.preview?.previousReview(frozen) ?: imports.importReview(batchId)
            val identity = CashImportPreviewIdentity({
                nextPlanId().also { id -> check(id.isNotEmpty() && id != currentPlan?.id) { "cash_import_plan_identity_invalid" } }
            }, batchId, revision + 1, timezoneName)
            val preview = imports.openImportPreview(batchId, frozen, previous, identity)
            // A failed acknowledgement cannot pair the old executable request with a newly persisted review.
            currentPlan = null
            imports.saveImportReview(batchId, preview.review)
            revision++
            currentPlan = Plan(identity.planId, preview)
            preview.initialView
        }
    }

    suspend fun plan(planId: String, timezoneName: String = "UTC"): JsonObject = read(planId) { it.view(timezoneName) }
    suspend fun items(planId: String, cursor: String? = null, limit: Int = 50, confirmationStatus: String = "all",
        startIndex: Int? = null): JsonObject = read(planId) {
        it.page("items", cursor, startIndex, limit, confirmationStatus, indexed = true)
    }
    suspend fun detail(planId: String, itemId: String): JsonObject = read(planId) { it.detail(itemId) }
    suspend fun processing(planId: String, cursor: String? = null, limit: Int = 50): JsonObject = read(planId) {
        it.page("processing_details", cursor, null, limit, null, false)
    }

    /** The encrypted store binds this batch to the original request before any HTTP may start. */
    suspend fun freeze(planId: String, expectedRevision: Long) = mutex.withLock {
        observeFileEncodings(imports.importFileEncodings(batchId))
        if (closing || completed != null || recovering) throw CashImportPlanError("plan_changed")
        requireRevision(expectedRevision)
        val plan = requirePlan(planId)
        val frozen = plan.preview.confirmation(batchId)
        imports.prepareImportSubmission(batchId, frozen)
    }

    /**
     * Network and bounded production rebases do not occupy the task's file/preview lock.
     * Delivery precedes refresh/cleanup and can repeat after either fails; save the same scoped receipt idempotently.
     */
    suspend fun resume(onStatus: suspend (com.finance.tracker.business.contracts.CashImportConfirmation) -> Unit = {},
        beforeCompletion: suspend (CashMutationReceiptDto) -> Unit = {}): LocalCashImportSubmissionResult = recoveryMutex.withLock {
        val previous = mutex.withLock {
            imports.checkActive()
            if (closing) throw CashImportPlanError("plan_changed")
            if (imports.importSubmission(batchId) != null) currentPlan?.recoveryStarted = true
            completed?.takeIf { it.recoveryFailure == null }.also { if (it == null) recovering = true }
        }
        if (previous != null) return@withLock previous
        try {
            val result = imports.resumeLocalCashImport(batchId, onStatus, beforeCompletion)
            mutex.withLock {
                imports.checkActive()
                completed = result
            }
            result
        } catch (failure: CashImportPlanError) {
            mutex.withLock {
                // The owner retires a rejected request only after newer facts and original-key verification.
                // Never let another freeze race the gap between that retirement and this publication.
                currentPlan = null
                try {
                    if (imports.importSubmission(batchId) == null) {
                        ensureRevisionAvailable()
                        revision++
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (readFailure: Throwable) { failure.addSuppressed(readFailure) }
            }
            throw failure
        } finally {
            withContext(NonCancellable) { mutex.withLock { recovering = false } }
        }
    }

    private suspend fun read(planId: String, action: suspend (OpenedCashImportPreview) -> JsonObject): JsonObject = mutex.withLock {
        imports.checkActive()
        invalidateRetiredPlan()
        if (closing || completed != null) throw CashImportPlanError("plan_not_found")
        action(requirePlan(planId).preview).also { imports.checkActive() }
    }

    private fun requirePlan(id: String): Plan = currentPlan?.takeIf { it.id == id } ?: throw CashImportPlanError("plan_not_found")
    private fun requireRevision(expected: Long) {
        if (expected < 1 || revision != expected) throw CashImportPlanError("plan_changed")
    }
    private fun ensureRevisionAvailable() { check(revision < Long.MAX_VALUE) { "cash_import_revision_exhausted" } }

    private suspend fun mutableFiles(allowMissing: Boolean = false): List<LocalCashImportFile> {
        checkMutable()
        val files = synchronizeFiles()
        requireNoSubmission()
        return files ?: if (allowMissing) emptyList() else throw CashImportPlanError("plan_not_found")
    }

    private fun checkMutable() {
        if (closing || completed != null || recovering) throw CashImportPlanError("plan_changed")
    }

    private suspend fun requireNoSubmission() {
        if (imports.importSubmission(batchId) != null) throw CashImportPlanError("plan_changed")
    }

    private suspend fun synchronizeFiles(): List<LocalCashImportFile>? {
        val files = imports.importFiles(batchId)
        observeFileEncodings(files?.map(LocalCashImportFile::encode))
        return files
    }

    private suspend fun observeFileEncodings(encoded: List<String>?) {
        if (!initialized || encoded != observed) {
            if (encoded != null || initialized) {
                ensureRevisionAvailable()
                revision++
            }
            initialized = true
            observed = encoded
            currentPlan = null
        }
        invalidateRetiredPlan()
    }

    private suspend fun invalidateRetiredPlan() {
        // Cancellation may interrupt delivery of the owner's re-confirmation error after it retired the request.
        if (!recovering && currentPlan?.recoveryStarted == true && imports.importSubmission(batchId) == null) {
            ensureRevisionAvailable()
            revision++
            currentPlan = null
        }
    }

    private suspend fun preparation(): CashImportSessionDto = imports.importPreparation(batchId)
        .copy(sessionRevision = revision, lastPlanId = currentPlan?.id)
}
