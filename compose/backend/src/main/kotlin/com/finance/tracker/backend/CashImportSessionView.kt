package com.finance.tracker.backend

/** 仅公开会话任务所需字段，不公开解析正文或内部来源证明。 */
internal object CashImportSessionView {
    private val previewKeys = setOf("account_selections", "row_choices", "relation_choices", "entry_choices")

    fun fileView(file: ParsedImportFile): Map<String, Any?> {
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
            "source_scope_label" to file.sourceScopeLabel, "status" to status,
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
        file.diagnostic?.let { result["diagnostic"] = CashRecordPolicy.copyValue(it) }
        return result
    }

    fun view(current: ParsedImportSession, groups: List<CashFundingGroup>,
        suggestions: Map<String, CashFundingSuggestion>): Map<String, Any?> {
        val inputs = linkedMapOf<String, Any?>()
        current.previewInput.forEach { (key, value) ->
            if (key in previewKeys) inputs[key] = CashRecordPolicy.copyValue(value)
        }
        @Suppress("UNCHECKED_CAST")
        val selections = when (val raw = inputs["account_selections"]) {
            null -> emptyList()
            is List<*> -> raw.map { it as Map<*, *> }
            else -> throw IllegalArgumentException("import_session_account_selections_invalid")
        }
        val selected = selections.mapNotNull { item -> (item["source_account_ref"] as? String)?.let { it to item } }.toMap()
        return linkedMapOf<String, Any?>(
            "session_id" to current.sessionId, "session_revision" to current.revision,
            "expires_at" to ParsedImportCodec.timeText(current.expiresAt),
            "state" to if (current.cancelled) "cancelled" else "open",
            "files" to current.files.map(::fileView),
            "source_accounts" to groups.map { group ->
                val suggestion = suggestions[group.sourceAccountRef]
                linkedMapOf<String, Any?>(
                    "source_account_ref" to group.sourceAccountRef, "source_type" to group.sourceType,
                    "display_name" to group.displayName, "currencies" to group.currencies,
                    "row_count" to group.rowRefs.size,
                    "suggestion" to suggestion?.let {
                        linkedMapOf<String, Any?>("account_id" to it.accountId,
                            "expected_account_revision" to it.expectedAccountRevision, "basis" to it.basis)
                    },
                    "selection" to selected[group.sourceAccountRef]?.let { item ->
                        item.filterKeys { it == "account_id" || it == "expected_account_revision" }
                    },
                )
            },
            "last_preview_input" to inputs.ifEmpty { null },
            "last_plan_id" to current.plan?.get("plan_id"),
            "can_preview" to (!current.cancelled && current.files.any { it.document != null }),
            "receipt_id" to null,
        )
    }
}
