package com.finance.tracker.client

import com.finance.tracker.business.domain.CashImportPlanError

enum class CashImportFileEdit { ADD, REPLACE, REMOVE }

data class CashImportFileChange<File>(val previous: File?, val selected: List<File>, val changed: Boolean)

/** Apply one explicit file edit against the exact selection being reviewed. */
fun <File> changeCashImportFiles(
    selected: List<File>, ref: String, edit: CashImportFileEdit, capture: File? = null,
    fileRef: (File) -> String, unchanged: (File, File) -> Boolean = { _, _ -> false },
): CashImportFileChange<File> {
    if (!validCashImportFileRef(ref) || capture != null && fileRef(capture) != ref)
        throw CashImportPlanError("plan_input_invalid")
    val original = selected.singleOrNull { fileRef(it) == ref }
    if (capture != null && original != null && unchanged(original, capture))
        return CashImportFileChange(original, selected, false)
    val updated = when (edit) {
        CashImportFileEdit.ADD -> {
            if (original != null || selected.size >= 20) throw CashImportPlanError("plan_changed")
            selected + checkNotNull(capture)
        }
        CashImportFileEdit.REPLACE -> {
            if (original == null) throw CashImportPlanError("file_not_found")
            selected.map { if (fileRef(it) == ref) checkNotNull(capture) else it }
        }
        CashImportFileEdit.REMOVE -> {
            if (original == null) throw CashImportPlanError("file_not_found")
            selected.filterNot { fileRef(it) == ref }
        }
    }
    return CashImportFileChange(original, updated, true)
}

suspend fun cancelPreparedCashImport(hasSubmission: Boolean, committed: Boolean, remove: suspend () -> Unit) {
    if (hasSubmission || committed) throw CashImportPlanError("plan_changed")
    remove()
}
