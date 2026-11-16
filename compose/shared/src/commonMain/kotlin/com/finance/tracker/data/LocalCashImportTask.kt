package com.finance.tracker.data

import com.finance.tracker.business.domain.CashImportPlanError
import com.finance.tracker.business.contracts.CashMutationReceiptDto
import com.finance.tracker.business.domain.LocalCashImportPlanReader
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

    private class Plan(val id: String, val calculation: LocalCashImportCalculation, val reader: LocalCashImportPlanReader) {
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
            files.find { it.fileRef == file.fileRef }?.let { existing ->
                if (existing.encode() != file.encode()) throw CashImportPlanError("plan_changed")
                return@withLock preparation()
            }
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
            mutableFiles()
            requireRevision(expectedRevision)
            if (original.encode() == file.encode()) return@withLock preparation()
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
        val original = files.singleOrNull { it.fileRef == fileRef } ?: throw CashImportPlanError("plan_changed")
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
            cancellationHasBatch = observed != null
            beforeCancellation = CashImportSessionDto(batchId, state = "cancelled", files = localCashImportContextFiles(files),
                sourceAccounts = emptyList(), canPreview = false)
            // Fence already parsing files even if deletion fails or its caller is cancelled.
            closing = true
            currentPlan = null
        }
        if (cancellationHasBatch) imports.removeImportFiles(batchId)
        revision++
        checkNotNull(beforeCancellation).copy(sessionRevision = revision, state = "cancelled", canPreview = false,
            lastPlanId = null).also { cancellation = it }
    }

    suspend fun preview(expectedRevision: Long, previousPlanId: String?, input: JsonObject, timezoneName: String = "UTC"): JsonObject {
        val frozen = Json.parseToJsonElement(input.toString()).jsonObject
        return mutex.withLock {
            mutableFiles()
            requireRevision(expectedRevision)
            if (previousPlanId != currentPlan?.id) throw CashImportPlanError("plan_changed")
            ensureRevisionAvailable()
            val calculation = imports.calculateImport(batchId, frozen, imports.importReview(batchId))
            val id = nextPlanId()
            check(id.isNotEmpty() && id != currentPlan?.id) { "cash_import_plan_identity_invalid" }
            val items = calculation.plan.getValue("items").jsonArray
            val plan = JsonObject(calculation.plan + mapOf(
                "plan_id" to JsonPrimitive(id), "session_id" to JsonPrimitive(batchId),
                "session_revision" to JsonPrimitive(revision + 1), "expires_at" to JsonNull,
                "item_counts" to buildJsonObject {
                    for (status in listOf("confirmed", "pending")) put(status,
                        items.count { it.jsonObject.getValue("confirmation_status").jsonPrimitive.content == status })
                },
            ))
            val reader = localCashImportPlanReader(plan)
            reader.validateReadable()
            val view = reader.view(timezoneName)
            // A failed acknowledgement cannot pair the old executable request with a newly persisted review.
            currentPlan = null
            imports.saveImportReview(batchId, calculation.review)
            revision++
            currentPlan = Plan(id, calculation, reader)
            view
        }
    }

    suspend fun plan(planId: String, timezoneName: String = "UTC"): JsonObject = read(planId) { it.view(timezoneName) }
    suspend fun items(planId: String, cursor: String? = null, limit: Int = 50, confirmationStatus: String = "all",
        startIndex: Int? = null): JsonObject = read(planId) {
        it.page("items", cursor, startIndex, limit, confirmationStatus, indexed = true)
    }
    suspend fun detail(planId: String, itemId: String): JsonObject = read(planId) { it.detail(itemId) }
    suspend fun processing(planId: String, cursor: String? = null, limit: Int = 50): JsonObject = read(planId) {
        it.page("processing_details", cursor, limit = limit)
    }

    /** The encrypted store binds this batch to the original request before any HTTP may start. */
    suspend fun freeze(planId: String, expectedRevision: Long) = mutex.withLock {
        synchronizeFiles()
        if (closing || completed != null || recovering) throw CashImportPlanError("plan_changed")
        requireRevision(expectedRevision)
        val plan = requirePlan(planId)
        if (plan.calculation.plan.getValue("can_commit").jsonPrimitive.boolean != true) throw CashImportPlanError("plan_changed")
        imports.prepareImportSubmission(batchId, plan.calculation.request, LocalCashImportResultInput.freeze(plan.calculation.plan))
    }

    /**
     * Network and bounded production rebases do not occupy the task's file/preview lock.
     * Delivery precedes refresh/cleanup and can repeat after either fails; save the same scoped receipt idempotently.
     */
    suspend fun resume(beforeCompletion: suspend (CashMutationReceiptDto) -> Unit = {}): LocalCashImportSubmissionResult = recoveryMutex.withLock {
        val previous = mutex.withLock {
            imports.checkActive()
            if (closing) throw CashImportPlanError("plan_changed")
            if (imports.importSubmission(batchId) != null) currentPlan?.recoveryStarted = true
            completed?.takeIf { it.recoveryFailure == null }.also { if (it == null) recovering = true }
        }
        if (previous != null) return@withLock previous
        try {
            val result = imports.resumeLocalCashImport(batchId, beforeCompletion)
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

    private suspend fun read(planId: String, action: (LocalCashImportPlanReader) -> JsonObject): JsonObject = mutex.withLock {
        imports.checkActive()
        invalidateRetiredPlan()
        if (closing || completed != null) throw CashImportPlanError("plan_not_found")
        action(requirePlan(planId).reader)
    }

    private fun requirePlan(id: String): Plan = currentPlan?.takeIf { it.id == id } ?: throw CashImportPlanError("plan_not_found")
    private fun requireRevision(expected: Long) {
        if (expected < 1 || revision != expected) throw CashImportPlanError("plan_changed")
    }
    private fun ensureRevisionAvailable() { check(revision < Long.MAX_VALUE) { "cash_import_revision_exhausted" } }

    private suspend fun mutableFiles(allowMissing: Boolean = false): List<LocalCashImportFile> {
        if (closing || completed != null || recovering) throw CashImportPlanError("plan_changed")
        val files = synchronizeFiles()
        if (imports.importSubmission(batchId) != null) throw CashImportPlanError("plan_changed")
        return files ?: if (allowMissing) emptyList() else throw CashImportPlanError("plan_not_found")
    }

    private suspend fun synchronizeFiles(): List<LocalCashImportFile>? {
        val files = imports.importFiles(batchId)
        val encoded = files?.map { it.encode() }
        if (!initialized || encoded != observed) {
            if (files != null || initialized) {
                ensureRevisionAvailable()
                revision++
            }
            initialized = true
            observed = encoded
            currentPlan = null
        }
        invalidateRetiredPlan()
        return files
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
