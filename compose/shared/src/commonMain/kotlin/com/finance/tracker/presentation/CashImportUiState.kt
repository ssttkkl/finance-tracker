package com.finance.tracker.presentation

import com.finance.tracker.domain.*

enum class ImportStage { SELECT, MAPPING, PREVIEW, RELATIONS, SUCCESS }

data class PickedImportFile(
    val source: FileSource,
    val identity: ImportFileIdentity,
)

data class ImportAccountDraft(
    val accountId: Long? = null,
    val newAccount: ImportNewAccount? = null,
)

data class ImportRelationDraft(
    val kind: String,
    val status: String,
    val secondary: ImportRelationRecord? = null,
    val restoreStatus: String? = null,
    val restoreSecondary: ImportRelationRecord? = null,
)

data class CashImportUiState(
    val stage: ImportStage = ImportStage.SELECT,
    val selectedFiles: List<PickedImportFile> = emptyList(),
    val scan: ImportScan? = null,
    val accountDrafts: Map<String, ImportAccountDraft> = emptyMap(),
    val preview: ImportPreview? = null,
    val allocationDrafts: Map<String, List<String>> = emptyMap(),
    val previewFilter: String = "all",
    val relationDrafts: Map<String, ImportRelationDraft> = emptyMap(),
    val relationFilter: String = "all",
    val result: ImportCommitResult? = null,
    val passwords: Map<String, String> = emptyMap(),
    val importToken: String? = null,
    val idempotencyKey: String? = null,
    val busy: Boolean = false,
    val errorCode: String? = null,
)
