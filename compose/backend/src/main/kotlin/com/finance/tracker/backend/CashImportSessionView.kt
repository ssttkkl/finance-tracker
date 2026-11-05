package com.finance.tracker.backend

/** 仅公开会话任务所需字段，不公开解析正文或内部来源证明。 */
internal object CashImportSessionView {
    private val previewKeys = setOf("account_selections", "row_choices", "relation_choices", "entry_choices")

    fun fileView(file: ParsedImportFile): Map<String, Any?> =
        com.finance.tracker.business.domain.cashImportFileView(file, CashRecordPolicy::copyValue)

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
        val sessionGroups = groups.associate { group ->
            group.sourceAccountRef to
                if (group.sourceType in setOf("wechat", "alipay")) sourceDigest("cash-import-session-account-group-v1",
                    current.sessionId, group.sourceType, group.originKey) else null
        }
        return linkedMapOf<String, Any?>(
            "session_id" to current.sessionId, "session_revision" to current.revision,
            "expires_at" to ParsedImportCodec.timeText(current.expiresAt),
            "state" to if (current.cancelled) "cancelled" else "open",
            "files" to current.files.map(::fileView),
            "source_accounts" to groups.map { group ->
                val suggestion = suggestions[group.sourceAccountRef]
                linkedMapOf<String, Any?>(
                    "source_account_ref" to group.sourceAccountRef, "source_type" to group.sourceType,
                    "display_name" to group.displayName, "session_label" to group.sessionLabel,
                    "session_group_ref" to sessionGroups[group.sourceAccountRef],
                    "currencies" to group.currencies,
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
