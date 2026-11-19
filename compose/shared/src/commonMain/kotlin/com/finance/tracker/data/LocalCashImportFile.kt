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
    fun parsedFile(): ParsedImportFile = stored().toParsedImportFile()

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
            val stored = decodeStoredCashImportFile(encoded)
            return LocalCashImportFile(encoded, stored.clientFileRef)
        }
    }
}
