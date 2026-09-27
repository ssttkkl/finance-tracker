package com.finance.tracker.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.finance.tracker.core.DiagnosticAction
import com.finance.tracker.core.DiagnosticFeature
import com.finance.tracker.core.DiagnosticLogger
import com.finance.tracker.core.NoOpDiagnosticLogger
import com.finance.tracker.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

class CashImportViewModel(
    private val repository: CashImportRepository,
    private val diagnostics: DiagnosticLogger = NoOpDiagnosticLogger,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CashImportUiState())
    val state: StateFlow<CashImportUiState> = mutableState.asStateFlow()

    fun reset() {
        mutableState.value = CashImportUiState()
    }

    fun setPickerError() {
        diagnostics.record(DiagnosticFeature.CASH_IMPORT, DiagnosticAction.PICK_FILES, "import_file_picker_failed", null, "FilePickerError")
        mutableState.value = mutableState.value.copy(errorCode = "import_file_picker_failed")
    }

    fun setErrorCode(code: String) {
        diagnostics.record(DiagnosticFeature.CASH_IMPORT, DiagnosticAction.VALIDATE_INPUT, code, null, "ImportValidationError")
        mutableState.value = mutableState.value.copy(errorCode = code)
    }

    fun stage(stage: ImportStage) {
        val current = mutableState.value
        if (current.busy) return
        if (stage != ImportStage.SELECT && current.scan == null) return
        if (stage in setOf(ImportStage.PREVIEW, ImportStage.RELATIONS) && current.preview == null) return
        mutableState.value = current.copy(stage = stage)
    }

    fun receiveFiles(files: List<FileSource>?) {
        if (files.isNullOrEmpty() || mutableState.value.busy) return
        viewModelScope.launch {
            var selectionError: String? = null
            val accepted = mutableListOf<PickedImportFile>()
            for (file in files) {
                try {
                    val reportedSize = file.size
                    val bytes = if (reportedSize == null || reportedSize < 0) file.read() else null
                    val size = bytes?.size?.toLong() ?: reportedSize ?: -1L
                    val validation = validateImportFile(file.name, size)
                    if (validation != ImportFileValidation.Valid) {
                        selectionError = selectionError ?: validation.errorCode()
                        continue
                    }
                    val digest = sha1Hex(bytes ?: file.read())
                    accepted += PickedImportFile(file, ImportFileIdentity(file.name, digest, size))
                } catch (cause: CancellationException) {
                    throw cause
                } catch (cause: Throwable) {
                    cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.CASH_IMPORT, DiagnosticAction.PICK_FILES)
                    selectionError = selectionError ?: "import_file_read_failed"
                }
            }
            val current = mutableState.value
            val selection = addImportFiles(
                existing = current.selectedFiles.map(PickedImportFile::identity),
                selected = accepted.map(PickedImportFile::identity),
            )
            val newDigests = selection.files.drop(current.selectedFiles.size).map(ImportFileIdentity::digest).toSet()
            val selectedFiles = (current.selectedFiles + accepted.filter { it.identity.digest in newDigests }.distinctBy { it.identity.digest })
            val added = selectedFiles.size != current.selectedFiles.size
            val errorCode = selectionError ?: selection.rejections.firstOrNull()?.errorCode()
            if (errorCode != null) diagnostics.record(DiagnosticFeature.CASH_IMPORT, DiagnosticAction.PICK_FILES, errorCode, null, "ImportFileSelectionError")
            mutableState.value = current.copy(selectedFiles = selectedFiles, errorCode = errorCode)
            if (added) {
                clearProgress()
                mutableState.value = mutableState.value.copy(errorCode = errorCode)
            }
        }
    }

    fun removeFile(digest: String) {
        val current = mutableState.value
        if (current.busy) return
        mutableState.value = current.copy(selectedFiles = current.selectedFiles.filterNot { it.identity.digest == digest })
        clearProgress()
    }

    fun updatePassword(index: Int, value: String) {
        val current = mutableState.value
        mutableState.value = current.copy(passwords = current.passwords + (index.toString() to value), errorCode = null)
    }

    fun scanSelectedFiles() {
        val current = mutableState.value
        if (current.selectedFiles.isEmpty() || current.busy) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(busy = true, errorCode = null)
            try {
                val value = repository.scanCashImport(
                    files = current.selectedFiles.map(PickedImportFile::source),
                    passwords = current.passwords,
                    importToken = current.importToken,
                )
                val token = value.importToken ?: current.importToken
                val ready = value.ready != false && value.files.all { it.status == "ready" }
                value.files.filter { it.status != "ready" || it.errorCode != null }.forEach { fileError ->
                    diagnostics.record(DiagnosticFeature.CASH_IMPORT, DiagnosticAction.SCAN_FILES, fileError.errorCode, null, "ImportScanError")
                }
                mutableState.value = mutableState.value.copy(
                    scan = value,
                    importToken = token,
                    idempotencyKey = token?.let { current.idempotencyKey ?: newImportIdempotencyKey() },
                    accountDrafts = if (ready) value.groups.associate { it.groupId to ImportAccountDraft(accountId = it.suggestion.accountId) } else current.accountDrafts,
                    stage = if (ready) ImportStage.MAPPING else current.stage,
                )
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.CASH_IMPORT, DiagnosticAction.SCAN_FILES)
                val failure = cause.importFailure()
                val token = failure?.importToken ?: current.importToken
                mutableState.value = mutableState.value.copy(
                    importToken = token,
                    idempotencyKey = token?.let { mutableState.value.idempotencyKey ?: newImportIdempotencyKey() },
                    errorCode = failure?.code?.takeIf(::isRecoverableImportCode) ?: "unknown_error",
                )
            } finally {
                mutableState.value = mutableState.value.copy(busy = false)
            }
        }
    }

    fun selectAccount(group: ImportSourceGroup, value: String) {
        val current = mutableState.value
        val drafts = when {
            value == "__create__" -> current.accountDrafts + (group.groupId to ImportAccountDraft(newAccount = defaultAccount(group)))
            value.startsWith("__draft__") -> {
                val draftId = value.removePrefix("__draft__")
                val selected = current.accountDrafts.values.mapNotNull(ImportAccountDraft::newAccount).firstOrNull { it.draftId == draftId }
                    ?: return
                val merged = selected.copy(currencies = (selected.currencies + group.currencies).distinct().sorted())
                current.accountDrafts.mapValues { (_, draft) ->
                    if (draft.newAccount?.draftId == draftId) draft.copy(newAccount = merged) else draft
                } + (group.groupId to ImportAccountDraft(newAccount = merged))
            }
            value.isBlank() -> current.accountDrafts - group.groupId
            else -> current.accountDrafts + (group.groupId to ImportAccountDraft(accountId = value.toLongOrNull()))
        }
        mutableState.value = current.copy(accountDrafts = drafts, errorCode = null).resetDerivedImportState()
    }

    fun updateNewAccount(groupId: String, update: (ImportNewAccount) -> ImportNewAccount) {
        val current = mutableState.value
        val existing = current.accountDrafts[groupId]?.newAccount
            ?: current.scan?.groups?.firstOrNull { it.groupId == groupId }?.let(::defaultAccount)
            ?: return
        val changed = update(existing)
        val drafts = current.accountDrafts.mapValues { (_, draft) ->
            if (draft.newAccount?.draftId == existing.draftId) draft.copy(newAccount = changed) else draft
        }
        mutableState.value = current.copy(accountDrafts = drafts, errorCode = null).resetDerivedImportState()
    }

    fun setPreviewFilter(value: String) {
        mutableState.value = mutableState.value.copy(previewFilter = value)
    }

    fun updateAllocation(itemKey: String, index: Int, value: String) {
        val current = mutableState.value
        val amounts = current.allocationDrafts[itemKey].orEmpty().toMutableList()
        while (amounts.size <= index) amounts += ""
        amounts[index] = value
        mutableState.value = current.copy(allocationDrafts = current.allocationDrafts + (itemKey to amounts), errorCode = null)
    }

    fun loadPreview(nextStage: ImportStage = ImportStage.PREVIEW) {
        val current = mutableState.value
        if (!current.mappingComplete() || current.busy) return
        viewModelScope.launch { loadPreviewRequest(nextStage) }
    }

    fun setRelationDraft(relation: ImportRelation, draft: ImportRelationDraft) {
        mutableState.value = mutableState.value.copy(relationDrafts = mutableState.value.relationDrafts + (relation.id to draft))
    }

    fun setRelationFilter(value: String) {
        mutableState.value = mutableState.value.copy(relationFilter = value)
    }

    fun toggleRelationRejected(relation: ImportRelation, draft: ImportRelationDraft) {
        val next = if (draft.status == "rejected") {
            draft.copy(
                status = draft.restoreStatus ?: if (relation.automatic) "automatic" else "pending",
                secondary = draft.restoreSecondary,
                restoreStatus = null,
                restoreSecondary = null,
            )
        } else {
            draft.copy(status = "rejected", restoreStatus = draft.status, restoreSecondary = draft.secondary)
        }
        setRelationDraft(relation, next)
    }

    fun commitImport() {
        val current = mutableState.value
        val preview = current.preview ?: return
        if (current.busy || current.hasIncompleteAllocations() || current.importAllocationCount() > 0 || current.importUnsupportedCount() > 0) return
        viewModelScope.launch {
            val idempotencyKey = current.idempotencyKey ?: newImportIdempotencyKey()
            mutableState.value = mutableState.value.copy(busy = true, errorCode = null, idempotencyKey = idempotencyKey)
            try {
                val result = repository.commitCashImport(
                    importToken = current.importToken ?: throw DomainFailure("import_token_missing", 0, FailureCategory.UNKNOWN),
                    passwords = current.passwords,
                    previewDigest = preview.file.digest,
                    previewRelationDigest = preview.relationDigest,
                    previewChannel = preview.channel,
                    relations = buildImportRelationPayload(preview.relations, current.relationDrafts),
                    mapping = current.mappingPayload(),
                    idempotencyKey = idempotencyKey,
                    batch = true,
                )
                mutableState.value = mutableState.value.copy(
                    result = result,
                    stage = ImportStage.SUCCESS,
                    passwords = emptyMap(),
                    importToken = null,
                    idempotencyKey = null,
                )
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.CASH_IMPORT, DiagnosticAction.CONFIRM_IMPORT)
                val failure = cause.importFailure()
                val token = failure?.importToken ?: mutableState.value.importToken
                val code = failure?.code ?: "unknown_error"
                if (code in IMPORT_MAPPING_ERROR_CODES) {
                    mutableState.value = mutableState.value.copy(
                        preview = null,
                        relationDrafts = emptyMap(),
                        stage = ImportStage.MAPPING,
                        importToken = token,
                        errorCode = code,
                    )
                } else if (code in IMPORT_RELATION_RECONFIRMATION_CODES) {
                    mutableState.value = mutableState.value.copy(importToken = token)
                    if (loadPreviewRequest(ImportStage.RELATIONS)) {
                        mutableState.value = mutableState.value.copy(errorCode = "import_relation_reconfirmation_required")
                    }
                } else if (returnToPasswordEntry(failure)) {
                    // Password entry state and the recoverable error are restored by the helper.
                } else {
                    mutableState.value = mutableState.value.copy(
                        importToken = token,
                        errorCode = failure?.code?.takeIf(::isRecoverableImportCode) ?: "unknown_error",
                    )
                }
            } finally {
                mutableState.value = mutableState.value.copy(busy = false)
            }
        }
    }

    private suspend fun loadPreviewRequest(nextStage: ImportStage): Boolean {
        val current = mutableState.value
        mutableState.value = current.copy(busy = true, errorCode = null)
        try {
            val value = repository.previewCashImport(
                importToken = current.importToken ?: throw DomainFailure("import_token_missing", 0, FailureCategory.UNKNOWN),
                passwords = current.passwords,
                mapping = current.mappingPayload(),
                batch = true,
            )
            val token = value.importToken ?: current.importToken
            mutableState.value = mutableState.value.copy(
                preview = value,
                importToken = token,
                idempotencyKey = mutableState.value.idempotencyKey ?: newImportIdempotencyKey(),
                allocationDrafts = value.items.filter { it.components.orEmpty().size > 1 }.associate { item ->
                    importItemKey(item) to item.components.orEmpty().map { it.amount?.removePrefix("+")?.removePrefix("-").orEmpty() }
                },
                relationDrafts = value.relations.associate { relation ->
                    relation.id to ImportRelationDraft(
                        relation.kind,
                        if (relation.automatic) "automatic" else "pending",
                        if (relation.automatic) relation.secondary else null,
                    )
                },
                relationFilter = "all",
                stage = nextStage,
            )
            return true
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Throwable) {
            cause.recordDiagnosticFailure(diagnostics, DiagnosticFeature.CASH_IMPORT, DiagnosticAction.LOAD_PREVIEW)
            val failure = cause.importFailure()
            val token = failure?.importToken ?: mutableState.value.importToken
            mutableState.value = mutableState.value.copy(importToken = token)
            if (returnToPasswordEntry(failure)) return false
            if (failure?.code == "import_mapping_stale") {
                mutableState.value = mutableState.value.copy(
                    scan = null,
                    accountDrafts = emptyMap(),
                    stage = ImportStage.SELECT,
                    errorCode = "import_mapping_stale",
                )
            } else {
                mutableState.value = mutableState.value.copy(errorCode = failure?.code?.takeIf(::isRecoverableImportCode) ?: "unknown_error")
            }
            return false
        } finally {
            mutableState.value = mutableState.value.copy(busy = false)
        }
    }

    private fun returnToPasswordEntry(failure: DomainFailure?): Boolean {
        val recoverableFailure = failure ?: return false
        if (recoverableFailure.code !in setOf("import_password_required", "import_password_invalid")) return false
        val current = mutableState.value
        val files = current.scan?.files?.ifEmpty {
            current.selectedFiles.mapIndexed { index, picked ->
                ImportFileScan(
                    index = index,
                    name = picked.identity.name,
                    filename = picked.identity.name,
                    digest = picked.identity.digest,
                    size = picked.identity.size,
                    status = "password_required",
                    errorCode = "password_invalid",
                )
            }
        }?.map { it.copy(status = "password_required", errorCode = "password_invalid") }.orEmpty()
        mutableState.value = current.copy(
            scan = current.scan?.copy(ready = false, files = files),
            accountDrafts = emptyMap(),
            preview = null,
            allocationDrafts = emptyMap(),
            previewFilter = "all",
            relationDrafts = emptyMap(),
            relationFilter = "all",
            passwords = emptyMap(),
            stage = ImportStage.SELECT,
            errorCode = recoverableFailure.code,
        )
        return true
    }

    private fun clearProgress() {
        mutableState.value = mutableState.value.copy(
            scan = null,
            accountDrafts = emptyMap(),
            preview = null,
            allocationDrafts = emptyMap(),
            previewFilter = "all",
            relationDrafts = emptyMap(),
            relationFilter = "all",
            result = null,
            passwords = emptyMap(),
            importToken = null,
            idempotencyKey = null,
            busy = false,
            errorCode = null,
            stage = ImportStage.SELECT,
        )
    }

    private fun CashImportUiState.mappingPayload(): List<ImportMappingDecision> {
        val decisions = scan?.groups.orEmpty().map { group ->
            val draft = accountDrafts[group.groupId]
            ImportMappingDecision(
                groupId = group.groupId,
                accountId = if (draft?.newAccount == null) draft?.accountId else null,
                mappingRevision = group.suggestion.mappingRevision,
                newAccount = draft?.newAccount,
            )
        }.toMutableList()
        val allocations = allocationDrafts.filterValues { it.size > 1 }
            .mapValues { (_, amounts) -> amounts.map(::ImportAmount) }
        if (allocations.isNotEmpty() && decisions.isNotEmpty()) {
            decisions[0] = decisions[0].copy(componentAllocations = allocations)
        }
        return decisions
    }

    private fun CashImportUiState.resetDerivedImportState(): CashImportUiState = copy(
        preview = null,
        allocationDrafts = emptyMap(),
        previewFilter = "all",
        relationDrafts = emptyMap(),
        relationFilter = "all",
    )

    private fun defaultAccount(group: ImportSourceGroup): ImportNewAccount {
        val name = group.displayName
        val type = if (name.contains("花呗") || name.contains("信用卡")) "loan" else "cash"
        return ImportNewAccount(draftId = "draft-${group.groupId}", name = name, type = type, currencies = group.currencies)
    }
}

fun buildImportRelationPayload(
    relations: List<ImportRelation>,
    drafts: Map<String, ImportRelationDraft>,
): List<StructuredObject> = relations.mapNotNull { relation ->
    val draft = drafts[relation.id] ?: ImportRelationDraft(
        relation.kind,
        if (relation.automatic) "automatic" else "pending",
        relation.secondary,
    )
    if (draft.status == "pending" || (draft.secondary == null && draft.status != "rejected")) return@mapNotNull null
    val fields = linkedMapOf<String, StructuredValue>(
        "proposal_key" to StructuredText(relation.id),
        "kind" to StructuredText(draft.kind),
        "subtype" to StructuredText(relation.subtype),
        "rule_id" to StructuredText(relation.ruleId),
    )
    relation.primary.addEndpoint(fields, "primary")
    if (draft.status == "rejected") {
        fields["status"] = StructuredText("rejected")
    } else if (draft.status != "pending" && draft.secondary != null) {
        draft.secondary.addEndpoint(fields, "secondary")
        fields["status"] = StructuredText("accepted")
    }
    StructuredObject(fields)
}

fun CashImportUiState.mappingComplete(): Boolean = scan?.groups?.isNotEmpty() == true && scan.groups.all { group ->
    val draft = accountDrafts[group.groupId]
    draft?.accountId != null || draft?.newAccount?.name?.trim()?.isNotEmpty() == true
}

fun CashImportUiState.hasIncompleteAllocations(): Boolean = preview?.items.orEmpty()
    .filter { it.components.orEmpty().size > 1 }
    .any { item -> allocationBalance(item.amount, allocationDrafts[importItemKey(item)].orEmpty()).state != "complete" }

fun CashImportUiState.importAllocationCount(): Int = preview?.let { value ->
    value.summary.requiresAllocation.takeIf { it > 0 } ?: value.items.count { it.status == "requires_allocation" }
} ?: 0

fun CashImportUiState.importUnresolvedCount(): Int = preview?.let { value ->
    value.summary.unresolved.takeIf { it > 0 } ?: value.items.count { it.status == "unresolved" }
} ?: 0

fun CashImportUiState.importUnsupportedCount(): Int = preview?.let { value ->
    (value.summary.unsupported - importUnresolvedCount()).coerceAtLeast(0)
} ?: 0

fun CashImportUiState.canCommit(): Boolean = !busy && !hasIncompleteAllocations() && importAllocationCount() == 0 && importUnsupportedCount() == 0

fun importItemKey(item: ImportPreviewItem): String = item.relationRef ?: item.recordId
fun importItemKey(item: ImportRelationRecord): String = item.relationRef ?: item.recordId

private fun ImportRelationRecord.addEndpoint(fields: MutableMap<String, StructuredValue>, prefix: String) {
    when {
        factId != null -> fields["${prefix}_fact_id"] = StructuredNumber(factId.toString())
        relationRef != null -> fields["${prefix}_record_ref"] = StructuredText(relationRef)
        else -> fields["${prefix}_record_id"] = StructuredText(recordId)
    }
}

private fun Throwable.importFailure(): DomainFailure? = this as? DomainFailure

private fun isRecoverableImportCode(code: String): Boolean = code in IMPORT_RECOVERABLE_CODES

private fun ImportFileValidation.errorCode(): String = when (this) {
    ImportFileValidation.UnsupportedType -> "import_file_unsupported_type"
    ImportFileValidation.TooLarge -> "import_file_too_large"
    ImportFileValidation.SizeUnavailable -> "import_file_size_unavailable"
    ImportFileValidation.TooManyFiles -> "import_file_too_many"
    ImportFileValidation.Valid -> ""
}

private fun newImportIdempotencyKey(): String = "cash-import-${Random.nextLong().toString(16)}-${Random.nextLong().toString(16)}"

private val IMPORT_MAPPING_ERROR_CODES = setOf(
    "import_account_unavailable",
    "import_account_name_conflict",
    "import_account_draft_invalid",
    "import_mapping_incomplete",
    "import_composite_payment_unresolved",
    "import_component_allocation_incomplete",
    "import_component_amount_invalid",
)

private val IMPORT_RELATION_RECONFIRMATION_CODES = setOf(
    "import_relation_reconfirmation_required",
    "import_relation_preview_stale",
    "import_relation_candidate_invalid",
)

private val IMPORT_RECOVERABLE_CODES = IMPORT_MAPPING_ERROR_CODES + IMPORT_RELATION_RECONFIRMATION_CODES + setOf(
    "import_password_required",
    "import_password_invalid",
    "import_channel_unrecognized",
    "import_preview_stale",
    "relation_impact_required",
    "network_unavailable",
    "temporarily_unavailable",
    "conflict",
    "authentication_required",
    "workspace_forbidden",
    "import_file_unsupported_type",
    "import_file_too_large",
    "import_file_size_unavailable",
    "import_file_too_many",
    "import_file_read_failed",
    "import_file_picker_failed",
)
