package com.finance.tracker.core

enum class DiagnosticFeature {
    AUTHENTICATION,
    WORKSPACE,
    INVITATION,
    CASH_LEDGER,
    CASH_IMPORT,
    CASH_CATEGORIES,
    INVESTMENT_HOLDINGS,
    INVESTMENT_EVENTS,
}

enum class DiagnosticAction {
    RESTORE_SESSION,
    AUTHENTICATE,
    SELECT_WORKSPACE,
    CREATE_WORKSPACE,
    LOAD,
    FILTER,
    CREATE,
    UPDATE,
    DELETE,
    COPY_LINK,
    LOAD_DETAILS,
    CREATE_INVITATION,
    ACCEPT_INVITATION,
    PICK_FILES,
    VALIDATE_INPUT,
    SCAN_FILES,
    LOAD_PREVIEW,
    MAP_ACCOUNTS,
    REVIEW_RELATIONS,
    CONFIRM_IMPORT,
    LOAD_EVIDENCE,
    LOAD_NEXT_PAGE,
    SIGN_OUT,
}

interface PlatformDiagnosticLogStore {
    fun writeDeveloperLog(line: String)
    fun readLocalLog(): String?
    fun writeLocalLog(contents: String)
}

interface DiagnosticLogger {
    fun record(
        feature: DiagnosticFeature,
        action: DiagnosticAction,
        errorCode: String?,
        status: Int?,
        exceptionType: String?,
    )
}

private data class DiagnosticEntry(
    val timestamp: Long,
    val feature: DiagnosticFeature,
    val action: DiagnosticAction,
    val errorCode: String,
    val status: Int?,
    val exceptionType: String,
) {
    fun encode(): String = listOf(
        timestamp.toString(),
        feature.name,
        action.name,
        errorCode,
        status?.toString().orEmpty(),
        exceptionType,
    ).joinToString("\t")

    companion object {
        fun decode(line: String): DiagnosticEntry? {
            val fields = line.split('\t')
            if (fields.size != 6) return null
            val timestamp = fields[0].toLongOrNull() ?: return null
            val feature = DiagnosticFeature.entries.firstOrNull { it.name == fields[1] } ?: return null
            val action = DiagnosticAction.entries.firstOrNull { it.name == fields[2] } ?: return null
            if (fields[3] !in SAFE_ERROR_CODES) return null
            val status = fields[4].takeIf(String::isNotEmpty)?.toIntOrNull()
            if (fields[4].isNotEmpty() && (status == null || status !in 100..599)) return null
            if (!SAFE_EXCEPTION_TYPE.matches(fields[5])) return null
            return DiagnosticEntry(timestamp, feature, action, fields[3], status, fields[5])
        }
    }
}

class SanitizedDiagnosticLogger(
    private val platformStore: PlatformDiagnosticLogStore,
    private val nowMillis: () -> Long = ::diagnosticEpochMillis,
) : DiagnosticLogger {
    override fun record(
        feature: DiagnosticFeature,
        action: DiagnosticAction,
        errorCode: String?,
        status: Int?,
        exceptionType: String?,
    ) {
        val now = nowMillis()
        val entry = DiagnosticEntry(
            timestamp = now,
            feature = feature,
            action = action,
            errorCode = errorCode?.takeIf { it in SAFE_ERROR_CODES } ?: "unknown_error",
            status = status?.takeIf { it in 100..599 },
            exceptionType = exceptionType?.takeIf(SAFE_EXCEPTION_TYPE::matches) ?: "Throwable",
        )
        val line = entry.encode()
        runCatching { platformStore.writeDeveloperLog(line) }
        runCatching { writeRetainedLog(entry, now) }
    }

    private fun writeRetainedLog(incoming: DiagnosticEntry, now: Long) {
        val cutoff = now - RETENTION_MILLIS
        val entries = platformStore.readLocalLog()
            .orEmpty()
            .lineSequence()
            .mapNotNull(DiagnosticEntry::decode)
            .filter { it.timestamp in cutoff..now }
            .plus(incoming)
            .sortedBy(DiagnosticEntry::timestamp)
            .toMutableList()

        var byteCount = entries.sumOf { it.encode().encodeToByteArray().size + 1 }
        while (entries.size > 1 && byteCount > SanitizedDiagnosticLogger.MAX_LOG_BYTES) {
            byteCount -= entries.removeAt(0).encode().encodeToByteArray().size + 1
        }
        platformStore.writeLocalLog(entries.joinToString(separator = "\n", postfix = if (entries.isEmpty()) "" else "\n") { it.encode() })
    }

    companion object {
        const val RETENTION_MILLIS: Long = 30L * 24 * 60 * 60 * 1000
        const val MAX_LOG_BYTES: Int = 1024 * 1024
    }
}

object NoOpDiagnosticLogger : DiagnosticLogger {
    override fun record(feature: DiagnosticFeature, action: DiagnosticAction, errorCode: String?, status: Int?, exceptionType: String?) = Unit
}

fun createDiagnosticLogger(): DiagnosticLogger = SanitizedDiagnosticLogger(createPlatformDiagnosticLogStore())

private val SAFE_EXCEPTION_TYPE = Regex("^[A-Za-z][A-Za-z0-9_.$]{0,79}$")

private val SAFE_ERROR_CODES = setOf(
    "unknown_error",
    "temporarily_unavailable",
    "network_unavailable",
    "authentication_required",
    "workspace_forbidden",
    "api_request_failed",
    "api_origin_invalid",
    "invalid_credentials",
    "invalid_portfolio_decimal",
    "investment_refresh_unavailable",
    "investment_refresh_timeout",
    "conflict",
    "projection_version_conflict",
    "revision_conflict",
    "invalid_record",
    "invalid_category",
    "invalid_workspace_name",
    "relation_impact_required",
    "invalid_filter",
    "invalid_cursor",
    "investment.updated",
    "valuation.invalid_display_currency",
    "category.duplicate_name",
    "category.depth_limit",
    "category.invalid_name",
    "category.invalid_description",
    "category.has_children",
    "category.revision_conflict",
    "import_password_required",
    "import_password_invalid",
    "import_token_missing",
    "import_file_picker_failed",
    "import_file_read_failed",
    "import_file_size_unavailable",
    "import_file_unsupported_type",
    "import_file_too_large",
    "import_file_too_many",
    "import_account_unavailable",
    "import_account_name_conflict",
    "import_account_draft_invalid",
    "import_mapping_incomplete",
    "import_composite_payment_unresolved",
    "import_component_allocation_incomplete",
    "import_component_amount_invalid",
    "import_mapping_stale",
    "import_relation_reconfirmation_required",
    "import_relation_preview_stale",
    "import_relation_candidate_invalid",
    "import_channel_unrecognized",
    "import_preview_stale",
    "token_store_unavailable",
    "invitation_invalid",
    "invitation_expired",
    "invitation_already_used",
    "auth_input_invalid_emailrequired",
    "auth_input_invalid_emailinvalid",
    "auth_input_invalid_passwordrequired",
    "auth_input_invalid_passwordtooshort",
    "password_invalid",
)
