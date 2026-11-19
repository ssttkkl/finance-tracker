package com.finance.tracker.data

import com.finance.tracker.business.domain.CashImportPlanError
import com.finance.tracker.core.getLocalTimeZoneId
import com.finance.tracker.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.random.Random

/** Application imports stay on one captured cache session; no server import sessions are created. */
internal class LocalCashImportLifecycleRepository(
    private val captureImports: suspend () -> LedgerSessionImports?,
    private val nextBatchId: () -> String = ::newLocalImportId,
    private val nextPlanId: () -> String = ::newLocalImportId,
    private val expectedIdentity: Pair<String, String>? = null,
    private val captureSessionImports: (suspend (Session) -> LedgerSessionImports?)? = null,
) : CashImportLifecycleRepository {
    override fun forSession(session: Session): CashImportLifecycleRepository {
        val workspaceId = session.activeWorkspaceId?.takeIf { it.isNotBlank() } ?: invalidLocalImportInput()
        val captured = captureSessionImports?.let { capture -> suspend { capture(session) } } ?: captureImports
        return LocalCashImportLifecycleRepository(captured, nextBatchId, nextPlanId,
            ledgerOfflinePrincipal(session.user.email) to workspaceId, captureSessionImports)
    }

    private val mutex = Mutex()
    private var current: Binding? = null
    private class Binding(val imports: LedgerSessionImports) {
        val mutex = Mutex()
        val tasks = mutableMapOf<String, Task>()
        val plans = mutableMapOf<String, Task>()
    }
    private class Task(val coordinator: LocalCashImportTask) {
        val mutex = Mutex()
        var command: ImportFrozenCommand? = null
    }

    private suspend fun binding(): Binding {
        mutex.withLock { current }?.let { it.imports.checkActive(); return it }
        val imports = captureImports() ?: throw DomainFailure("request_failed", 0, FailureCategory.RECOVERABLE,
            cause = IllegalStateException("ledger_import_session_unavailable"))
        expectedIdentity?.let { (principal, workspaceId) -> imports.requireScope(principal, workspaceId) }
        return mutex.withLock { current ?: Binding(imports).also { current = it } }
            .also { it.imports.checkActive() }
    }
    private suspend fun Binding.task(batchId: String): Task = mutex.withLock {
        tasks.getOrPut(batchId) { Task(LocalCashImportTask(batchId, imports, nextPlanId)) }
    }
    private suspend fun Binding.planTask(planId: String): Task = mutex.withLock {
        plans[planId] ?: throw CashImportPlanError("plan_not_found")
    }
    private fun CashImportSessionDto.local() = toDomain().copy(origin = ImportContextOrigin.LOCAL)
    private fun committedContext(batchId: String, receipt: ImportReceipt) = ImportContext(batchId, null, null, "committed",
        emptyList(), emptyList(), null, null, false, receipt.receiptId, ImportContextOrigin.LOCAL)
    private fun emptyContext(batchId: String) = ImportContext(batchId, null, null, "open", emptyList(), emptyList(),
        null, null, false, null, ImportContextOrigin.LOCAL)

    override suspend fun upload(files: List<ImportUpload>, context: ImportContext?, replacingFileRef: String?,
        onContext: suspend (ImportContext) -> Unit): ImportUploadBatch {
        val frozen = files.map { CashImportUploadInput(it.clientFileRef, localImportFileSource(it.file), it.password, it.sourceHint) }
        return guarded {
            if (frozen.size !in 1..20 || frozen.map { it.clientFileRef }.toSet().size != frozen.size ||
                frozen.any { !it.clientFileRef.matches(Regex("[A-Za-z0-9_-]{1,128}")) } ||
                context != null && context.origin != ImportContextOrigin.LOCAL ||
                replacingFileRef != null && (context == null || frozen.size != 1)) invalidLocalImportInput()
            val binding = binding()
            val batchId = context?.sessionId ?: nextBatchId()
            val task = binding.task(batchId).coordinator
            val failures = mutableListOf<ImportLocalFileFailure>()
            var latest = context
            if (latest == null) {
                latest = emptyContext(batchId)
                onContext(latest)
                binding.imports.checkActive()
            }
            for ((index, input) in frozen.withIndex()) {
                val value = try {
                    if (replacingFileRef == null) task.append(input)
                    else task.replace(replacingFileRef, context?.revision ?: invalidLocalImportInput(), input)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: ApiFailure) {
                    if (failure.status == 0 && failure.code in localFileInputFailures) {
                        failures += ImportLocalFileFailure(input.clientFileRef, failure.code)
                        continue
                    }
                    if (failure.status == 401 || failure.code == "internal_error") throw failure
                    return@guarded ImportUploadBatch(latest, failures.toList(), ImportUploadInterruption(
                        failure.code, failure.status, failure.receiptId, frozen.drop(index).map { it.clientFileRef }, failure.diagnostic))
                } catch (failure: LocalImportFileReadFailure) {
                    failures += ImportLocalFileFailure(input.clientFileRef, "import_file_read_failed")
                    continue
                } catch (failure: LocalCashImportParseTransportFailure) {
                    return@guarded ImportUploadBatch(latest, failures.toList(), ImportUploadInterruption(
                        "network_unavailable", 0, null, frozen.drop(index).map { it.clientFileRef }))
                }
                latest = value.local()
                onContext(latest)
                binding.imports.checkActive()
            }
            ImportUploadBatch(latest, failures.toList(), null)
        }
    }

    override suspend fun context(sessionId: String): ImportContext = guarded {
        val binding = binding()
        binding.imports.importResultByBatchId(sessionId)?.let { return@guarded committedContext(sessionId, it.displayReceipt().toDomain()) }
        val task = binding.task(sessionId)
        if (binding.imports.importSubmission(sessionId) == null && binding.imports.importFiles(sessionId) == null) emptyContext(sessionId)
        else if (binding.imports.importSubmission(sessionId) == null) reviewContext(binding, task)
        else task.coordinator.context().local()
    }
    override suspend fun removeFile(sessionId: String, fileRef: String, revision: Long): ImportContext = guarded {
        val binding = binding()
        binding.task(sessionId).coordinator.remove(fileRef, revision).local()
    }
    override suspend fun cancel(sessionId: String): ImportContext = guarded {
        val binding = binding()
        if (binding.imports.importResultByBatchId(sessionId) != null) throw CashImportPlanError("session_committed")
        binding.task(sessionId).coordinator.cancel().local()
    }
    override suspend fun preview(sessionId: String, revision: Long, previousPlanId: String?, choices: ImportChoiceSet): ImportPlan {
        val input = localImportJson.encodeToJsonElement(choices.toDto(revision, previousPlanId)).jsonObject
        val timezone = getLocalTimeZoneId()
        return guarded {
            val binding = binding()
            val task = binding.task(sessionId)
            val plan = localImportJson.decodeFromJsonElement<CashImportPlanDto>(task.coordinator.preview(revision, previousPlanId, input, timezone)).toDomain()
            binding.mutex.withLock {
                binding.plans.entries.removeAll { it.value === task }
                check(binding.plans.put(plan.planId, task) == null) { "cash_import_plan_identity_reused" }
            }
            plan
        }
    }
    override suspend fun plan(planId: String): ImportPlan = guarded {
        val binding = binding()
        localImportJson.decodeFromJsonElement<CashImportPlanDto>(binding.planTask(planId).coordinator.plan(planId, getLocalTimeZoneId())).toDomain()
    }
    override suspend fun items(planId: String, cursor: String?, limit: Int, confirmationStatus: String, startIndex: Int?): ImportItemPage = guarded {
        val binding = binding()
        localImportJson.decodeFromJsonElement<CashPlanItemPageDto>(binding.planTask(planId).coordinator.items(planId, cursor, limit, confirmationStatus, startIndex)).toDomain()
    }
    override suspend fun item(planId: String, itemId: String): ImportItemDetail = guarded {
        val binding = binding()
        localImportJson.decodeFromJsonElement<CashPlanItemDetailDto>(binding.planTask(planId).coordinator.detail(planId, itemId)).toDomain()
    }
    override suspend fun processing(planId: String, cursor: String?, limit: Int): ImportProcessingPage = guarded {
        val binding = binding()
        localImportJson.decodeFromJsonElement<CashPlanProcessingPageDto>(binding.planTask(planId).coordinator.processing(planId, cursor, limit)).toDomain()
    }

    override suspend fun confirm(planId: String, revision: Long, idempotencyKey: String): ImportReceipt =
        confirm(ImportFrozenCommand(planId, revision, idempotencyKey)) {}
    override suspend fun confirm(command: ImportFrozenCommand, onCommitted: suspend (ImportReceipt) -> Unit): ImportReceipt = guarded {
        val binding = binding()
        val task = binding.planTask(command.planId)
        task.mutex.withLock {
            if (command.idempotencyKey != task.coordinator.batchId || task.command != null && task.command != command)
                throw CashImportPlanError("plan_changed")
            if (task.command == null) {
                task.coordinator.freeze(command.planId, command.revision)
                task.command = command
            }
        }
        finish(binding, task, onCommitted)
    }
    override suspend fun resumeBatch(batchId: String, onCommitted: suspend (ImportReceipt) -> Unit): ImportContext = guarded {
        val binding = binding()
        val task = binding.task(batchId)
        if (binding.imports.importSubmission(batchId) != null) {
            val receipt = finish(binding, task, onCommitted)
            committedContext(batchId, receipt)
        } else {
            val archived = binding.imports.importResultByBatchId(batchId)
            if (archived == null) {
                if (binding.imports.importFiles(batchId) == null) emptyContext(batchId) else reviewContext(binding, task)
            }
            else archived.displayReceipt().toDomain().also { onCommitted(it); binding.imports.checkActive() }
                .let { committedContext(batchId, it) }
        }
    }

    /** The owner already propagated caller cancellation and genuine rejected-token 401 before returning a secondary fault. */
    private suspend fun finish(binding: Binding, task: Task, onCommitted: suspend (ImportReceipt) -> Unit): ImportReceipt {
        val batchId = task.coordinator.batchId
        var delivered: ImportReceipt? = null
        val original = binding.imports.importSubmission(batchId)
        val command = task.mutex.withLock { task.command }
        val result = try { task.coordinator.resume { displayedReceipt ->
            val archived = checkNotNull(binding.imports.importResultByBatchId(batchId)) { "cash_import_result_missing" }
            check(archived.displayReceipt() == displayedReceipt) { "cash_import_receipt_changed" }
            val displayed = archived.displayReceipt().toDomain()
            delivered = displayed
            onCommitted(displayed)
        } } catch (changed: CashImportPlanError) {
            // Only retirement removes a rejected request while retaining its files without a result.
            // A still-frozen or successful operation must keep its submission fence.
            if (original != null && original.receipt() == null && binding.imports.importSubmission(batchId) == null &&
                binding.imports.importResultByBatchId(batchId) == null) {
                task.mutex.withLock { if (task.command == command) task.command = null }
                val context = reviewContext(binding, task)
                throw ImportReviewRequired(batchId, context, DomainFailure(changed.code, 0, FailureCategory.RECOVERABLE,
                    commitState = "not_committed", diagnostic = changed.diagnostic?.toDomain(), cause = changed))
            }
            throw changed
        }
        result.recoveryFailure?.let { failure ->
            val displayed = try {
                binding.imports.importResultByBatchId(batchId)?.displayReceipt()?.toDomain() ?: result.receipt.toDomain()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (readFailure: Exception) { failure.addSuppressed(readFailure); result.receipt.toDomain() }
            val mapped = failure.toDomainFailure()
            throw ImportCompletionFailure(batchId, displayed, if (mapped.category == FailureCategory.UNKNOWN) failure else mapped)
        }
        return delivered ?: checkNotNull(binding.imports.importResultByBatchId(batchId)) { "cash_import_result_missing" }
            .displayReceipt().toDomain().also { onCommitted(it); binding.imports.checkActive() }
    }

    private suspend fun reviewContext(binding: Binding, task: Task): ImportContext = try {
        binding.imports.prepareFactsForReview()
        task.coordinator.context().local()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) {
        val reason = if (failure is CashImportPlanError) DomainFailure(failure.code, 0, FailureCategory.RECOVERABLE,
            commitState = failure.commitState, diagnostic = failure.diagnostic?.toDomain(), cause = failure)
        else failure.toDomainFailure()
        if (reason.status in setOf(401, 403)) throw failure
        throw ImportReviewRequired(task.coordinator.batchId, null, reason)
    }

    override suspend fun resolve(planId: String, idempotencyKey: String): ImportResolveResult = guarded {
        val binding = binding()
        val task = binding.planTask(planId)
        val command = task.command?.takeIf { it.idempotencyKey == idempotencyKey } ?: throw CashImportPlanError("plan_changed")
        ImportResolveResult("committed", finish(binding, task) {})
    }
    override suspend fun resolve(command: ImportFrozenCommand, onCommitted: suspend (ImportReceipt) -> Unit): ImportResolveResult = guarded {
        val binding = binding()
        val task = binding.planTask(command.planId)
        if (task.command != command) throw CashImportPlanError("plan_changed")
        ImportResolveResult("committed", finish(binding, task, onCommitted))
    }
    override suspend fun receipt(receiptId: String): ImportReceipt = guarded {
        val binding = binding()
        binding.imports.importResult(receiptId)?.displayReceipt()?.toDomain() ?: binding.imports.accountReceipt(receiptId).toDomain()
    }
    override suspend fun receiptByKey(key: String): ImportReceipt = guarded {
        val binding = binding()
        binding.imports.importResultByKey(key)?.displayReceipt()?.toDomain() ?: binding.imports.accountReceiptByKey(key).toDomain()
    }
    override suspend fun results(receiptId: String, kind: ImportResultKind, after: String, limit: Int): ImportResultPage = guarded {
        val offset = after.toIntOrNull()?.takeIf { it >= 0 && it.toString() == after } ?: invalidLocalImportInput()
        if (limit !in 1..200) invalidLocalImportInput()
        val binding = binding()
        val archived = checkNotNull(binding.imports.importResult(receiptId)) { "cash_import_result_missing" }
        val actual = checkNotNull(archived.serverItems()) { "cash_import_results_incomplete" }
        val items = actual.filter { it.resultKind == kind.name.lowercase() }.map {
            ImportResultItem(it.resultId, it.resultKind, it.itemRef, it.outcome, it.code, it.message, it.operationRef,
                it.transactionId, it.relationId, it.accountId)
        } + if (kind == ImportResultKind.ROW) archived.rows().mapIndexed { index, raw ->
            val row = raw.jsonObject
            val action = row.getValue("planned_action").jsonPrimitive.content
            ImportResultItem("local-row:$index", "row", row.getValue("row_ref").jsonPrimitive.content,
                if (action == "skip") "skipped" else "duplicate", row.textOrNull("code"), null, row.textOrNull("operation_ref"),
                row.textOrNull("transaction_id"), null, null)
        } else archived.relationFailures().mapIndexed { index, raw ->
            val failure = raw.jsonObject
            ImportResultItem("local-relation:$index", "relation", failure.textOrNull("suggestion_id") ?: "local-relation:$index",
                "failed", failure.getValue("code").jsonPrimitive.content, null, null, null, null, null)
        }
        if (offset > items.size) invalidLocalImportInput()
        val page = items.drop(offset).take(limit)
        val current = binding.imports.importProjectionRefs(page.mapNotNull { it.transactionId })
        ImportResultPage(page, (offset + page.size).takeIf { it < items.size }?.toString(),
            current?.refs.orEmpty().map { ImportProjectionRef(it.transactionId, it.projectionId) }, current?.projectionVersion ?: 0)
    }

    override suspend fun accounts(): List<ImportAccountListItem> = guarded { binding().imports.accounts().map(ImportAccountListItemDto::toDomain) }
    override suspend fun refreshAccounts(): List<ImportAccountListItem> =
        guarded { binding().imports.refreshAccounts().map(ImportAccountListItemDto::toDomain) }
    override suspend fun account(accountId: String): ImportAccount = guarded { binding().imports.account(accountId).toDomain() }
    override suspend fun selectionAccount(accountId: String): ImportAccount = guarded { binding().imports.selectionAccount(accountId).toDomain() }
    override suspend fun createAccount(name: String, type: String, currencies: List<String>, idempotencyKey: String): ImportAccount {
        val frozen = currencies.toList()
        return guarded { binding().imports.createAccount(name, type, frozen, idempotencyKey).toDomain() }
    }
    override suspend fun updateAccount(accountId: String, revision: Long, patch: StructuredObject, idempotencyKey: String): ImportAccount {
        val frozen = Json.parseToJsonElement(patch.toJsonObject().toString()).jsonObject
        return guarded { binding().imports.updateAccount(accountId, revision, frozen, idempotencyKey).toDomain() }
    }
    override suspend fun ensureAccountCurrencies(requirements: List<ImportCurrencyRequirement>): List<ImportCurrencyRequirementResult> {
        val frozen = requirements.map { ImportCurrencyRequirementDto(it.idempotencyKey, it.accountId, it.revision, it.currencies.toList()) }
        return guarded { binding().imports.ensureAccountCurrencies(frozen).map(ImportCurrencyRequirementResultDto::toDomain) }
    }
    override suspend fun deleteAccount(accountId: String, revision: Long, idempotencyKey: String): ImportAccount = guarded {
        binding().imports.deleteAccount(accountId, revision, idempotencyKey).toDomain()
    }

    private suspend fun <T> guarded(action: suspend () -> T): T = try { action() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: ImportCompletionFailure) { throw failure }
    catch (failure: ImportReviewRequired) { throw failure }
    catch (failure: CashImportPlanError) {
        throw DomainFailure(failure.code, 0, FailureCategory.RECOVERABLE, commitState = failure.commitState,
            diagnostic = failure.diagnostic?.toDomain(), cause = failure)
    } catch (failure: Exception) {
        val mapped = failure.toDomainFailure()
        if (mapped.category == FailureCategory.UNKNOWN) throw failure
        throw mapped
    }
}

private val localImportJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private fun newLocalImportId(): String = Random.nextBytes(24).joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
private fun invalidLocalImportInput(): Nothing = throw DomainFailure("import_payload_invalid", 0, FailureCategory.RECOVERABLE)
private fun JsonObject.textOrNull(key: String): String? = get(key)?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
private val localFileInputFailures = setOf("import_payload_invalid", "import_file_unsupported_type", "import_file_size_unavailable", "import_file_too_large")

private class LocalImportFileReadFailure(cause: kotlinx.io.IOException) : Exception("import_file_read_failed", cause)
private fun localImportFileSource(original: FileSource): FileSource {
    if (original !is BoundedFileSource) return original
    return object : BoundedFileSource {
        override val name = original.name
        override val mediaType = original.mediaType
        override val size = original.size
        override val lastModifiedAtMillis = original.lastModifiedAtMillis
        override suspend fun read(): ByteArray = try { original.read() }
            catch (failure: kotlinx.io.IOException) { throw LocalImportFileReadFailure(failure) }
        override suspend fun readBounded(maxBytes: Int): ByteArray = try { original.readBounded(maxBytes) }
            catch (failure: kotlinx.io.IOException) { throw LocalImportFileReadFailure(failure) }
    }
}
