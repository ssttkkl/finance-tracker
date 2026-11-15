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
    fun encode(): String = encoded

    /** Every replay gets its own document tree, including nested source evidence and issue lists. */
    fun parsedFile(): ParsedImportFile {
        val stored = stored()
        return ParsedImportFile(stored.clientFileRef, stored.digest, "parsed",
            localNormalizedCashDocument(stored.document), lastModifiedAtMs = stored.lastModifiedAtMs,
            filename = stored.filename, size = stored.size)
    }

    /** Re-parsing may require a fresh password; normal ledger replays use parsedFile instead. */
    fun originalInput(): CashImportUploadInput {
        val stored = stored()
        val bytes = Base64.decode(stored.bytes)
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

        fun decode(encoded: String): LocalCashImportFile {
            val stored = Json.decodeFromString<StoredCashImportFile>(encoded)
            check(stored.version == 1) { "cash_import_file_version_unsupported" }
            // Check the container's own identity once, without re-running statement recognition.
            require(stored.size in 0..CASH_STATEMENT_MAX_BYTES.toLong()) { "cash_import_file_size_invalid" }
            require(stored.bytes.length.toLong() == (stored.size + 2) / 3 * 4) { "cash_import_file_bytes_invalid" }
            val bytes = Base64.decode(stored.bytes)
            require(bytes.size.toLong() == stored.size && sha256Hex(bytes) == stored.digest) { "cash_import_file_identity_invalid" }
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
    val bytes: String,
    val document: NormalizedCashDocumentDto,
)
