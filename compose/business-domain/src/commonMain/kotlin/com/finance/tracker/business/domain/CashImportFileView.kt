package com.finance.tracker.business.domain

fun cashImportFileView(file: ParsedImportFile, copyValue: (Any?) -> Any?): Map<String, Any?> {
    val rows = file.document?.rows.orEmpty()
    val ordered = mutableListOf<String>()
    for (row in rows) {
        if (row.disposition == "skipped") continue
        for (code in row.issueCodes + listOfNotNull(row.errorCode)) if (code !in ordered) ordered += code
    }
    file.errorCode?.let { ordered.add(0, it) }
    val status = when {
        file.status == "needs_password" -> "password_required"
        file.status != "parsed" -> "failed"
        ordered.isNotEmpty() -> "partial"
        else -> "ready"
    }
    val result = linkedMapOf<String, Any?>(
        "file_ref" to file.fileRef, "client_file_ref" to file.clientFileRef,
        "display_name" to file.filename, "source_type" to file.sourceType,
        "status" to status,
        "recognized_row_count" to file.document?.observedCount,
        "issues" to ordered.take(16).map { item ->
            val issue = cashImportIssue(item, blockingScope = "file")
            linkedMapOf<String, Any?>("code" to issue.code, "message" to issue.message,
                "field_path" to issue.fieldPath, "blocking_scope" to issue.blockingScope,
                "next_action" to issue.nextAction) + (issue.afterImportAction?.let {
                mapOf<String, Any?>("after_import_action" to it)
            } ?: emptyMap())
        },
        "can_retry" to (file.status != "parsed"),
    )
    file.diagnostic?.let { result["diagnostic"] = copyValue(it) }
    return result
}

