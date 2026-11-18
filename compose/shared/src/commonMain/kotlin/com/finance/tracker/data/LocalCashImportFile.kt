package com.finance.tracker.data

import com.finance.tracker.business.contracts.NormalizedCashDocumentDto
import com.finance.tracker.business.domain.*
import com.finance.tracker.domain.BoundedFileSource
import com.finance.tracker.domain.FileReadLimitExceeded
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64

/** Frozen file input for the scoped encrypted import store; never log or send this encoding. */
internal class LocalCashImportFile private constructor(private val encoded: String) {
    val fileRef: String get() = stored().clientFileRef
    fun encode(): String = encoded

    /** Every replay gets its own document tree, including nested source evidence and issue lists. */
    fun parsedFile(): ParsedImportFile {
        val stored = stored()
        return ParsedImportFile(stored.clientFileRef, stored.digest, stored.status,
            stored.document?.let(::localNormalizedCashDocument), lastModifiedAtMs = stored.lastModifiedAtMs,
            filename = stored.filename, size = stored.size,
            sourceType = stored.document?.sourceType ?: stored.sourceHint?.replace('-', '_'), errorCode = stored.errorCode)
    }

    /** Re-parsing may require a fresh password; normal ledger replays use parsedFile instead. */
    fun originalInput(): CashImportUploadInput {
        val stored = stored()
        val bytes = Base64.decode(checkNotNull(stored.bytes) { "cash_import_file_source_missing" })
        val file = object : BoundedFileSource {
            override val name = stored.filename
            override val mediaType = stored.mediaType
            override val size = bytes.size.toLong()
            override val lastModifiedAtMillis = stored.lastModifiedAtMs
            override suspend fun read(): ByteArray = bytes.copyOf()
            override suspend fun readBounded(maxBytes: Int): ByteArray {
                if (bytes.size > maxBytes) throw FileReadLimitExceeded()
                return bytes.copyOf()
            }
        }
        return CashImportUploadInput(stored.clientFileRef, file, sourceHint = stored.sourceHint)
    }

    private fun stored(): StoredCashImportFile = Json.decodeFromString<StoredCashImportFile>(encoded)

    companion object {
        internal fun capture(input: CashImportUploadInput, bytes: ByteArray, document: ParsedCashDocument): LocalCashImportFile {
            val stored = StoredCashImportFile(1, input.clientFileRef, input.file.name, input.file.mediaType,
                input.file.lastModifiedAtMillis, input.sourceHint, bytes.size.toLong(), sha256Hex(bytes),
                Base64.encode(bytes), normalizedCashDocumentWire(document) { storedCashValue(it).jsonObject })
            return LocalCashImportFile(Json.encodeToString(stored))
        }

        /** A known parse rejection retains only metadata; the caller must supply a file again to retry. */
        internal fun captureFailure(input: CashImportUploadInput, bytes: ByteArray, code: String): LocalCashImportFile {
            require(code in localCashImportFailureCodes || code in localCashImportPasswordCodes) { "cash_import_file_error_invalid" }
            val stored = StoredCashImportFile(2, input.clientFileRef, input.file.name, input.file.mediaType,
                input.file.lastModifiedAtMillis, input.sourceHint, bytes.size.toLong(), sha256Hex(bytes), null, null,
                if (code in localCashImportPasswordCodes) "needs_password" else "failed", code)
            return decode(Json.encodeToString(stored))
        }

        fun decode(encoded: String): LocalCashImportFile {
            val stored = Json.decodeFromString<StoredCashImportFile>(encoded)
            check(stored.version == 1 || stored.version == 2) { "cash_import_file_version_unsupported" }
            require(stored.size in 0..CASH_STATEMENT_MAX_BYTES.toLong()) { "cash_import_file_size_invalid" }
            require(stored.digest.length == 64 && stored.digest.all { it in '0'..'9' || it in 'a'..'f' }) { "cash_import_file_identity_invalid" }
            if (stored.version == 1) {
                require(stored.status == "parsed" && stored.document != null && stored.errorCode == null) { "cash_import_file_state_invalid" }
                val encodedBytes = requireNotNull(stored.bytes) { "cash_import_file_bytes_invalid" }
                require(encodedBytes.length.toLong() == (stored.size + 2) / 3 * 4) { "cash_import_file_bytes_invalid" }
                val bytes = Base64.decode(encodedBytes)
                require(bytes.size.toLong() == stored.size && sha256Hex(bytes) == stored.digest) { "cash_import_file_identity_invalid" }
            } else {
                require(stored.bytes == null && stored.document == null) { "cash_import_file_state_invalid" }
                require((stored.status == "failed" && stored.errorCode in localCashImportFailureCodes) ||
                    (stored.status == "needs_password" && stored.errorCode in localCashImportPasswordCodes)) { "cash_import_file_state_invalid" }
            }
            return LocalCashImportFile(encoded)
        }
    }
}

/** Existing JSON tokens already passed their transport boundary; do not interpret them as Double. */
private fun storedCashValue(value: Any?): JsonElement = when (value) {
    is JsonElement -> value
    is Map<*, *> -> JsonObject(value.entries.associate { (key, item) ->
        (key as? String ?: throw InvalidCashValue()) to storedCashValue(item)
    })
    is List<*> -> JsonArray(value.map(::storedCashValue))
    else -> encodeLocalCashValue(value)
}

@Serializable
private data class StoredCashImportFile(
    val version: Int,
    val clientFileRef: String,
    val filename: String,
    val mediaType: String?,
    val lastModifiedAtMs: Long?,
    val sourceHint: String?,
    val size: Long,
    val digest: String,
    val bytes: String?,
    val document: NormalizedCashDocumentDto?,
    val status: String = "parsed",
    val errorCode: String? = null,
)

internal val localCashImportPasswordCodes = setOf("password_required", "password_incorrect")
internal val localCashImportFailureCodes = setOf("import_channel_unrecognized", "statement_source_unsupported",
    "statement_header_not_found", "statement_header_invalid", "statement_rows_too_large", "statement_input_invalid",
    "statement_unreadable", "statement_encoding_unsupported")
