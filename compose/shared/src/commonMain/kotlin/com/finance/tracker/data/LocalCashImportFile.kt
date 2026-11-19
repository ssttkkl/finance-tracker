package com.finance.tracker.data

import com.finance.tracker.business.contracts.StoredCashImportFile
import com.finance.tracker.business.domain.*
import com.finance.tracker.domain.BoundedFileSource
import com.finance.tracker.domain.FileReadLimitExceeded
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64

/** Frozen file input for the scoped encrypted import store; never log or send this encoding. */
internal class LocalCashImportFile private constructor(private val encoded: String, val fileRef: String) {
    fun encode(): String = encoded

    /** Every replay gets its own document tree, including nested source evidence and issue lists. */
    fun parsedFile(): ParsedImportFile {
        val stored = stored()
        return ParsedImportFile(stored.clientFileRef, stored.digest, stored.status,
            stored.document?.let(::localNormalizedCashDocument), lastModifiedAtMs = stored.lastModifiedAtMs,
            filename = stored.filename, size = stored.size,
            sourceType = stored.document?.sourceType ?: stored.sourceHint?.replace('-', '_'), errorCode = stored.errorCode)
    }

    internal fun previewInput(): JsonObject {
        val stored = stored()
        return buildJsonObject {
            put("fileRef", stored.clientFileRef)
            put("digest", stored.digest)
            put("status", stored.status)
            put("filename", stored.filename)
            put("size", stored.size)
            stored.lastModifiedAtMs?.let { put("lastModifiedAtMs", it) }
            (stored.document?.sourceType ?: stored.sourceHint?.replace('-', '_'))?.let { put("sourceType", it) }
            stored.errorCode?.let { put("errorCode", it) }
            stored.document?.let { put("document", Json.encodeToJsonElement(it)) }
        }
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
        internal fun capture(input: CashImportUploadInput, bytes: ByteArray, document: ParsedCashDocument): LocalCashImportFile =
            capture(input, bytes, document, null)

        internal fun captureFailure(input: CashImportUploadInput, bytes: ByteArray, code: String): LocalCashImportFile =
            capture(input, bytes, null, code)

        private fun capture(input: CashImportUploadInput, bytes: ByteArray, document: ParsedCashDocument?, code: String?): LocalCashImportFile {
            val stored = captureCashStatement(input.clientFileRef, input.file.name, input.file.mediaType,
                input.file.lastModifiedAtMillis, input.sourceHint, bytes, document, code)
            return LocalCashImportFile(Json.encodeToString(stored), input.clientFileRef)
        }

        // The same-origin Worker constructed and bounded this capture before returning it.
        // Durable cache reads still verify its schema, binary length, and digest.
        internal fun fromBrowserCapture(encoded: String, fileRef: String) = LocalCashImportFile(encoded, fileRef)

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
            return LocalCashImportFile(encoded, stored.clientFileRef)
        }
    }
}
