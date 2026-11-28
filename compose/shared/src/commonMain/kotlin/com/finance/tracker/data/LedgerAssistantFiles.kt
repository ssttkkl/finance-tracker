package com.finance.tracker.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

internal const val LEDGER_ASSISTANT_FILE_TTL_MS = 24L * 60 * 60 * 1000
internal const val LEDGER_ASSISTANT_ATTACHMENT_TTL_MS = 30L * LEDGER_ASSISTANT_FILE_TTL_MS
internal const val LEDGER_ASSISTANT_TEMP_BYTES = 16L * 1024 * 1024
private const val LEDGER_ASSISTANT_FILE_BYTES = 2 * 1024 * 1024

@Serializable
internal data class LedgerAssistantFile(
    val id: String,
    val path: String,
    val mimeType: String,
    val size: Long,
    val attached: Boolean = false,
    val expiresAtMs: Long? = null,
) {
    fun canSave(now: Long): Boolean = attached && expiresAtMs?.let { now < it } == true

    val name: String get() = path.substringAfterLast('/')
    override fun toString() = "LedgerAssistantFile(size=$size, attached=$attached)"
}

internal class LedgerAssistantFileContent(val metadata: LedgerAssistantFile, val text: String)

@Serializable
internal data class LedgerAssistantFileReceipt(val callId: String, val fingerprint: String, val result: JsonObject) {
    override fun toString() = "LedgerAssistantFileReceipt"
}

internal fun newLedgerAssistantTemporaryFile(path: String, text: String): LedgerAssistantFileContent {
    val mime = ledgerAssistantFileMime(path)
    if (text.length > LEDGER_ASSISTANT_FILE_BYTES || text.encodeToByteArray().size > LEDGER_ASSISTANT_FILE_BYTES) {
        throw LedgerAssistantScriptFailure("file_too_large")
    }
    val clean = if (mime == "text/csv") stringifyLedgerAssistantCsv(parseLedgerAssistantCsv(text)) else text
    val size = clean.encodeToByteArray().size.toLong()
    return LedgerAssistantFileContent(LedgerAssistantFile(newAssistantId(), path, mime, size), clean)
}

internal fun ledgerAssistantFileMime(path: String): String {
    if (path.length !in 1..240 || path.any { it.isISOControl() || it in "\\:?#" } ||
        path.split('/').any { it.isBlank() || it != it.trim() || it == "." || it == ".." }
    ) throw LedgerAssistantScriptFailure("invalid_file_path")
    return when (path.substringAfterLast('.', "").lowercase()) {
        "csv" -> "text/csv"
        "md" -> "text/markdown"
        "txt" -> "text/plain"
        else -> throw LedgerAssistantScriptFailure("unsupported_file_type")
    }
}
