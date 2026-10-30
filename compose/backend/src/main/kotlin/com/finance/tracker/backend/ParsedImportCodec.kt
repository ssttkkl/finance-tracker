package com.finance.tracker.backend

import java.math.BigInteger
import java.nio.charset.CodingErrorAction
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.*

internal open class ParsedImportFailure(val code: String) : RuntimeException(code)
internal class ParsedImportDataError(code: String) : ParsedImportFailure(code)
internal class ParsedImportStorageUnavailable : ParsedImportFailure("import_session_storage_unavailable")
internal class ParsedImportNotFound : ParsedImportFailure("import_session_not_found")
internal class ParsedImportForbidden : ParsedImportFailure("import_session_forbidden")
internal class ParsedImportExpired : ParsedImportFailure("import_session_expired")
internal class ParsedImportRevisionConflict : ParsedImportFailure("import_session_revision_conflict")
internal class ParsedImportClosed : ParsedImportFailure("import_session_closed")

internal data class ParsedImportSession(
    val sessionId: String, val workspaceId: String, val userId: String, val createdAt: Instant, val expiresAt: Instant,
    val revision: BigInteger, val files: List<ParsedImportFile>, val previewInput: Map<String, Any?> = emptyMap(),
    val plan: Map<String, Any?>? = null, val cancelled: Boolean = false, val workflowKind: String = "import",
    val contextData: Map<String, Any?> = emptyMap(),
)

internal class ParsedImportCodec(val maxBytes: Int = 64 * 1024 * 1024, val maxParsedBytes: Int = 25 * 1024 * 1024) {
    init { require(maxBytes > 0 && maxParsedBytes > 0) { "import_session_storage_config_invalid" } }
    private fun invalid(code: String = "import_session_json_invalid"): Nothing = throw ParsedImportDataError(code)
    private fun String.lengthInPoints() = codePointCount(0, length)

    fun validateFile(file: ParsedImportFile) {
        validateFiles(listOf(file))
        budget(file, minOf(maxBytes, maxParsedBytes).toLong())
        val bytes = bytes(file)
        if (bytes.size > minOf(maxBytes, maxParsedBytes)) invalid("import_session_draft_too_large")
        try { validateFiles(listOf(restoreFile(parse(bytes).jsonObject))) }
        catch (_: IllegalArgumentException) { invalid("import_session_document_invalid") }
    }

    fun encode(session: ParsedImportSession, parsedFiles: ByteArray? = null): ByteArray {
        if (parsedFiles == null) validateFiles(session.files)
        validateMetadata(session)
        val maximum = minOf(maxBytes, maxParsedBytes)
        val parsed = parsedFiles ?: run {
            budget(session.files, maximum.toLong(), 1)
            bytes(session.files)
        }
        if (parsed.size > maximum) invalid("import_session_draft_too_large")
        val values = linkedMapOf<String, Any?>(
            "workspace_id" to session.workspaceId, "user_id" to session.userId,
            "created_at" to timeText(session.createdAt), "expires_at" to timeText(session.expiresAt),
            "revision" to session.revision, "preview_input" to session.previewInput, "plan" to session.plan,
            "cancelled" to session.cancelled, "workflow_kind" to session.workflowKind, "context_data" to session.contextData,
            "session_digest" to tokenDigest(session.sessionId))
        budget(values, maxBytes.toLong() - parsed.size - 9)
        val header = bytes(values)
        val total = header.size.toLong() + 9 + parsed.size
        if (total > maxBytes) invalid("import_session_draft_too_large")
        return header.copyOfRange(0, header.size - 1) + ",\"files\":".encodeToByteArray() + parsed + byteArrayOf('}'.code.toByte())
    }

    fun decode(sessionId: String, content: ByteArray): ParsedImportSession = safeDecode {
        if (content.size > maxBytes) throw ParsedImportStorageUnavailable()
        val root = parse(content).jsonObject
        budget(toValue(root), maxBytes.toLong())
        val files = root.getValue("files").jsonArray
        if (bytes(toValue(files)).size > minOf(maxBytes, maxParsedBytes)) throw ParsedImportStorageUnavailable()
        val session = restoreSession(sessionId, root, files.map { restoreFile(it.jsonObject) })
        validateMetadata(session); validateFiles(session.files)
        session
    }

    fun decodePlan(sessionId: String, content: ByteArray): ParsedImportSession = safeDecode {
        if (content.size > maxBytes) throw ParsedImportStorageUnavailable()
        val root = parse(content).jsonObject
        if (root.getValue("files").jsonArray.size > 20) throw ParsedImportStorageUnavailable()
        budget(toValue(JsonObject(root - "files" - "plan" - "session_digest")), maxBytes.toLong())
        restoreSession(sessionId, root, emptyList()).also(::validateMetadata)
    }

    fun parsedBytes(content: ByteArray): ByteArray = safeDecode { bytes(toValue(parse(content).jsonObject.getValue("files"))) }

    fun roundTrip(sessionId: String, content: ByteArray): ParsedImportSession = try { decode(sessionId, content) }
    catch (_: ParsedImportStorageUnavailable) { invalid() }

    private inline fun <T> safeDecode(block: () -> T): T = try { block() }
    catch (_: ParsedImportDataError) { throw ParsedImportStorageUnavailable() }
    catch (_: IllegalArgumentException) { throw ParsedImportStorageUnavailable() }
    catch (_: NoSuchElementException) { throw ParsedImportStorageUnavailable() }
    catch (_: java.time.DateTimeException) { throw ParsedImportStorageUnavailable() }
    catch (_: InvalidCashValue) { throw ParsedImportStorageUnavailable() }
    catch (_: ArithmeticException) { throw ParsedImportStorageUnavailable() }

    fun validateMetadata(value: ParsedImportSession) {
        if (listOf(value.createdAt, value.expiresAt).any {
            it.nano % 1000 != 0 || it.atOffset(java.time.ZoneOffset.UTC).year !in 1..9999
        }) invalid()
        val duration = Duration.between(value.createdAt, value.expiresAt)
        if (value.revision < BigInteger.ONE || duration.isNegative || duration.isZero || duration > Duration.ofSeconds(1800) ||
            value.workflowKind !in setOf("import", "mutation") || value.workflowKind == "mutation" && value.files.isNotEmpty() ||
            listOf(value.workspaceId, value.userId).any { it.isEmpty() || it.lengthInPoints() > 128 }) invalid()
    }

    private fun validateFiles(files: List<ParsedImportFile>) {
        if (files.size > 20) invalid("import_session_file_count_invalid")
        val refs = mutableSetOf<String>(); val clientRefs = mutableSetOf<String>()
        for (file in files) {
            if (listOf(file.fileRef, file.clientFileRef).any { !it.matches(Regex("[A-Za-z0-9_-]{1,128}")) })
                invalid("import_session_file_invalid")
            if (!refs.add(file.fileRef) || !clientRefs.add(file.clientFileRef)) invalid("import_session_file_reference_conflict")
            if (file.filename.isEmpty() || file.filename.lengthInPoints() > 255 || '/' in file.filename || '\\' in file.filename ||
                file.diagnostic?.let { !validDiagnostic(it) } == true || file.lastModifiedAtMs?.let { it < 0 } == true ||
                !file.digest.matches(Regex("[0-9a-f]{64}")) || file.size < 0 || file.status == "parsed" && file.size > 100 * 1024 * 1024 ||
                file.status !in setOf("parsed", "needs_password", "failed") ||
                file.sourceType?.matches(Regex("[a-z][a-z0-9_-]{0,63}")) == false ||
                file.errorCode?.matches(Regex("[a-z][a-z0-9_]{0,63}")) == false ||
                (file.status == "parsed") != (file.document != null) || file.document?.let { it.sourceType != file.sourceType } == true)
                invalid("import_session_file_invalid")
            val document = file.document
            if (document != null) {
                if (document.rows.size > 100_000) invalid("import_session_document_invalid")
                try { document.coverage.copy() } catch (_: IllegalArgumentException) { invalid("import_session_document_invalid") }
                val positions = mutableSetOf<Int>()
                for (row in document.rows) {
                    if (row.position !in 1..200_000 || !positions.add(row.position) || row.disposition !in setOf("candidate", "skipped") ||
                        row.disposition == "candidate" && row.fields == null || !validCashSourceExplanation(row.sourceExplanation) ||
                        row.providerId?.let { it.lengthInPoints() !in 1..512 } == true ||
                        row.sourceAccountIdentifier?.let { it.lengthInPoints() !in 1..256 } == true ||
                        row.documentPosition?.matches(Regex("p[1-9][0-9]{0,3}:t[0-9]{1,2}:r[1-9][0-9]{0,5}")) == false ||
                        (row.issueCodes + listOfNotNull(row.errorCode)).any { !it.matches(Regex("[a-z][a-z0-9_]{0,63}")) })
                        invalid("import_session_document_invalid")
                }
            }
            try {
                if (CashSourceScope.normalizeScopeLabel(file.sourceScopeLabel) != file.sourceScopeLabel ||
                    file.sourceScopeLabel != null && file.sourceType != "wechat") invalid("import_session_file_invalid")
            } catch (_: CashSourceScopeError) { invalid("import_session_file_invalid") }
        }
    }

    fun budget(value: Any?, maximum: Long, depth: Int = 0): Long {
        var remaining = maximum
        fun spend(size: Long) { remaining -= size; if (remaining < 0) invalid("import_session_draft_too_large") }
        fun walk(value: Any?, depth: Int) {
            if (depth > 64) invalid()
            when (value) {
                is Map<*, *> -> {
                    remaining -= 2 + 2L * value.size
                    for ((key, item) in value) {
                        if (key !is String) invalid()
                        spend(utf8(key).size.toLong())
                        if (depth == 64) invalid()
                        walk(item, depth + 1)
                    }
                }
                is List<*> -> {
                    remaining -= 2 + value.size.toLong()
                    for (item in value) { if (depth == 64) invalid(); walk(item, depth + 1) }
                }
                is String -> spend(utf8(value).size.toLong())
                null -> spend(4)
                is Boolean -> spend(if (value) 4 else 5)
                is Byte, is Short, is Int, is Long, is BigInteger -> spend(value.toString().length.toLong())
                is Double -> { if (!value.isFinite()) invalid(); spend(pythonFloatText(value).length.toLong()) }
                is Instant -> spend(timeText(value).length.toLong())
                is ParsedImportFile, is ParsedCashDocument, is ParsedCashRow, is CashSourceCoverage ->
                    objectFields(value).values.forEach { walk(it, depth + 1) }
                else -> invalid()
            }
            if (remaining < 0) invalid("import_session_draft_too_large")
        }
        walk(value, depth)
        return remaining
    }

    fun bytes(value: Any?): ByteArray = utf8(json(value).toString())
    private fun json(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> { utf8(value); JsonPrimitive(value) }
        is Boolean -> JsonPrimitive(value)
        is Byte, is Short, is Int, is Long, is BigInteger -> JsonUnquotedLiteral(value.toString())
        is Double -> { if (!value.isFinite()) invalid(); JsonUnquotedLiteral(pythonFloatText(value)) }
        is List<*> -> JsonArray(value.map(::json))
        is Map<*, *> -> JsonObject(value.entries.associate { (key, item) ->
            if (key !is String) invalid(); utf8(key); key to json(item)
        })
        is ParsedImportFile, is ParsedCashDocument, is ParsedCashRow, is CashSourceCoverage -> json(objectFields(value))
        else -> invalid()
    }

    private fun utf8(value: String): ByteArray = try {
        val buffer = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value))
        ByteArray(buffer.remaining()).also(buffer::get)
    } catch (_: java.nio.charset.CharacterCodingException) { invalid() }

    private fun parse(bytes: ByteArray): JsonElement {
        val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString() }
        catch (_: java.nio.charset.CharacterCodingException) { throw ParsedImportStorageUnavailable() }
        return ParsedImportJson(text).parse()
    }

    private fun toValue(value: JsonElement): Any? = when (value) {
        JsonNull -> null
        is JsonPrimitive -> when {
            value.isString -> value.content
            value.booleanOrNull != null -> value.boolean
            value.intOrNull != null -> value.int
            value.longOrNull != null -> value.long
            value.content.matches(Regex("-?[0-9]+")) -> BigInteger(value.content)
            value.doubleOrNull?.isFinite() == true -> value.double
            else -> invalid()
        }
        is JsonArray -> value.map(::toValue)
        is JsonObject -> value.mapValues { toValue(it.value) }
    }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.let {
        require(it.isString); it.content
    }
    private fun JsonObject.optionalText(key: String): String? = if (this[key] == null || this[key] == JsonNull) null else text(key)
    private fun JsonObject.bigInteger(key: String): BigInteger = getValue(key).jsonPrimitive.let {
        require(!it.isString && it.content.matches(Regex("-?(0|[1-9][0-9]*)"))); BigInteger(it.content)
    }
    private fun JsonObject.count(key: String, longDefault: Boolean): Number {
        val value = toValue(getValue(key)) as? Number ?: throw IllegalArgumentException("invalid count")
        return if (longDefault && value is Int) value.toLong() else value
    }
    private fun JsonObject.integer(key: String): Long = getValue(key).jsonPrimitive.let {
        require(!it.isString && it.content.matches(Regex("-?(0|[1-9][0-9]*)"))); it.content.toLong()
    }
    private fun JsonObject.flag(key: String, default: Boolean? = null): Boolean =
        if (key !in this && default != null) default else getValue(key).jsonPrimitive.let { require(!it.isString); it.boolean }
    private fun JsonObject.objectValue(key: String, default: Map<String, Any?>? = null): Map<String, Any?> =
        if (key !in this && default != null) default else getValue(key).jsonObject.mapValues { toValue(it.value) }

    private fun restoreSession(id: String, root: JsonObject, files: List<ParsedImportFile>): ParsedImportSession {
        require("plan" in root)
        require(root.keys.all { it in setOf("workspace_id", "user_id", "created_at", "expires_at", "revision", "files",
            "preview_input", "plan", "cancelled", "workflow_kind", "context_data", "session_digest") })
        require(root.text("session_digest") == tokenDigest(id))
        return ParsedImportSession(id, root.text("workspace_id"), root.text("user_id"),
            pythonCashTimestamp(root.text("created_at"), preserveZSeparator = true),
            pythonCashTimestamp(root.text("expires_at"), preserveZSeparator = true), root.bigInteger("revision"), files,
            root.objectValue("preview_input"), root["plan"]?.takeIf { it != JsonNull }?.jsonObject?.mapValues { toValue(it.value) },
            root.flag("cancelled", false), if ("workflow_kind" in root) root.text("workflow_kind") else "import",
            root.objectValue("context_data", emptyMap()))
    }

    private fun restoreFile(value: JsonObject): ParsedImportFile {
        require("source_type" in value)
        require(value.keys.all { it in fileKeys })
        val document = value["document"]?.takeIf { it != JsonNull }?.jsonObject?.let(::restoreDocument)
        return ParsedImportFile(value.text("file_ref"), value.text("digest"), value.text("status"), document,
            value.optionalText("source_scope_label"), value.flag("is_subset", false),
            value["last_modified_at_ms"]?.takeIf { it != JsonNull }?.let { value.integer("last_modified_at_ms") },
            value.text("client_file_ref"), value.text("filename"), value.integer("size"), value.optionalText("source_type"),
            value.optionalText("error_code"), value["diagnostic"]?.takeIf { it != JsonNull }?.jsonObject?.mapValues { toValue(it.value) })
    }

    private fun restoreDocument(value: JsonObject): ParsedCashDocument {
        require(value.keys == documentKeys)
        val coverage = value.getValue("coverage").jsonObject
        require(coverage.keys.all { it in coverageKeys })
        val proof = CashSourceCoverage(coverage.optionalText("start"), coverage.optionalText("end"), coverage.flag("complete", false),
            if ("evidence" in coverage) coverage.text("evidence") else "", coverage["filters"]?.jsonObject?.mapValues {
                require(it.value.jsonPrimitive.isString); it.value.jsonPrimitive.content
            } ?: emptyMap(), if ("strategy_version" in coverage) coverage.text("strategy_version") else "source-coverage-v1",
            coverage.flag("start_inclusive", true), coverage.flag("end_inclusive", false),
            if ("time_precision" in coverage) coverage.text("time_precision") else "second", coverage.optionalText("document_key"),
            coverage["page_total"]?.takeIf { it != JsonNull }?.let { Math.toIntExact(coverage.integer("page_total")) },
            coverage["page_fingerprints"]?.jsonObject?.mapValues { require(it.value.jsonPrimitive.isString); it.value.jsonPrimitive.content } ?: emptyMap())
        val rows = value.getValue("rows").jsonArray.map { raw ->
            val row = raw.jsonObject
            require("fields" in row)
            require(row.keys.all { it in rowKeys })
            ParsedCashRow(Math.toIntExact(row.integer("position")), row["fields"]?.takeIf { it != JsonNull }?.jsonObject?.mapValues { toValue(it.value) },
                if ("payment_method" in row) row.text("payment_method") else "", row.optionalText("provider_id"), row.getValue("issue_codes").jsonArray.map {
                    require(it.jsonPrimitive.isString); it.jsonPrimitive.content
                }, row.optionalText("error_code"), if ("disposition" in row) row.text("disposition") else "candidate",
                row.optionalText("source_account_identifier"), row.optionalText("document_position"), row.objectValue("source_explanation", emptyMap()))
        }
        return ParsedCashDocument(value.text("source_type"), value.text("table_key"), value.text("parser_version"), rows,
            value.optionalText("source_account_identifier"), value.optionalText("source_account_label"), proof,
            value["declared_count"]?.takeIf { it != JsonNull }?.let { value.count("declared_count", true) }, value.count("observed_count", false))
    }

    private fun objectFields(value: Any?): Map<String, Any?> = when (value) {
        is ParsedImportFile -> linkedMapOf("file_ref" to value.fileRef, "client_file_ref" to value.clientFileRef,
            "filename" to value.filename, "digest" to value.digest, "size" to value.size, "source_type" to value.sourceType,
            "status" to value.status, "document" to value.document, "error_code" to value.errorCode,
            "source_scope_label" to value.sourceScopeLabel, "is_subset" to value.isSubset,
            "last_modified_at_ms" to value.lastModifiedAtMs, "diagnostic" to value.diagnostic)
        is ParsedCashDocument -> linkedMapOf("source_type" to value.sourceType, "table_key" to value.tableKey,
            "parser_version" to value.parserVersion, "rows" to value.rows, "source_account_identifier" to value.sourceAccountIdentifier,
            "source_account_label" to value.sourceAccountLabel, "coverage" to value.coverage,
            "declared_count" to value.declaredCount, "observed_count" to value.observedCount)
        is ParsedCashRow -> linkedMapOf("position" to value.position, "fields" to value.fields, "payment_method" to value.paymentMethod,
            "provider_id" to value.providerId, "issue_codes" to value.issueCodes, "error_code" to value.errorCode,
            "disposition" to value.disposition, "source_account_identifier" to value.sourceAccountIdentifier,
            "document_position" to value.documentPosition, "source_explanation" to value.sourceExplanation)
        is CashSourceCoverage -> linkedMapOf("start" to value.start, "end" to value.end, "complete" to value.complete,
            "evidence" to value.evidence, "filters" to value.filters, "start_inclusive" to value.startInclusive,
            "end_inclusive" to value.endInclusive, "time_precision" to value.timePrecision, "strategy_version" to value.strategyVersion,
            "document_key" to value.documentKey, "page_total" to value.pageTotal, "page_fingerprints" to value.pageFingerprints)
        else -> invalid()
    }

    private fun diagnosticInteger(value: Any?): BigInteger? = when (value) {
        is Byte, is Short, is Int, is Long, is BigInteger -> BigInteger(value.toString())
        else -> null
    }
    private fun validDiagnostic(value: Map<String, Any?>): Boolean {
        if (value.keys != setOf("error_id", "stage", "scope", "locations", "total_locations")) return false
        if ((value["error_id"] as? String)?.matches(Regex("[a-f0-9]{32}")) != true || value["stage"] !in diagnosticStages ||
            value["scope"] !in setOf("row", "related", "file", "request")) return false
        val locations = value["locations"] as? List<*> ?: return false
        val total = diagnosticInteger(value["total_locations"]) ?: return false
        return locations.size <= 100 && total >= BigInteger.valueOf(locations.size.toLong()) && locations.all { location ->
            location is Map<*, *> && location.isNotEmpty() && location.all { (key, item) -> when (key) {
                "row_number" -> diagnosticInteger(item)?.signum() == 1
                "position_kind" -> item in setOf("csv_record", "worksheet_row", "document_row")
                "file_ref", "display_name", "row_ref", "document_position", "transaction_id" ->
                    item is String && item.lengthInPoints() in 1..(if (key == "display_name") 512 else 256)
                else -> false
            } }
        }
    }

    companion object {
        private val fileKeys = setOf("file_ref", "client_file_ref", "filename", "digest", "size", "source_type", "status", "document",
            "error_code", "source_scope_label", "is_subset", "last_modified_at_ms", "diagnostic")
        private val documentKeys = setOf("source_type", "table_key", "parser_version", "rows", "source_account_identifier", "source_account_label",
            "coverage", "declared_count", "observed_count")
        private val rowKeys = setOf("position", "fields", "payment_method", "provider_id", "issue_codes", "error_code", "disposition",
            "source_account_identifier", "document_position", "source_explanation")
        private val coverageKeys = setOf("start", "end", "complete", "evidence", "filters", "start_inclusive", "end_inclusive", "time_precision",
            "strategy_version", "document_key", "page_total", "page_fingerprints")
        private val diagnosticStages = setOf("request", "file_read", "row_conversion", "row_planning", "relation_discovery", "submit_revalidation",
            "source_evidence", "row_write", "batch_write", "account_mapping", "relation_save", "balances", "projection", "entry_edit", "database_commit")
        internal fun timeText(value: Instant): String {
            val time = value.atOffset(java.time.ZoneOffset.UTC)
            val base = java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss").format(time)
            val micro = value.nano / 1000
            return base + (if (micro == 0) "" else ".${micro.toString().padStart(6, '0')}") + "+00:00"
        }
    }
}

/** Python json.loads syntax, including last duplicate key winning; budget checks follow parsing. */
private class ParsedImportJson(private val source: String) {
    private var position = 0
    private fun invalid(): Nothing = throw ParsedImportStorageUnavailable()
    private fun space() { while (position < source.length && source[position] in " \t\r\n") position++ }
    private fun character(value: Char) { space(); if (position >= source.length || source[position++] != value) invalid() }
    private fun string(): JsonPrimitive {
        space(); val start = position; character('"')
        while (position < source.length) {
            val value = source[position++]
            if (value.code < 32) invalid()
            if (value == '\\') { if (position >= source.length) invalid(); position++ }
            else if (value == '"') return Json.parseToJsonElement(source.substring(start, position)) as JsonPrimitive
        }
        invalid()
    }
    private fun value(depth: Int): JsonElement {
        if (depth > 128) invalid()
        space(); if (position == source.length) invalid()
        return when (source[position]) {
            '"' -> string()
            '{' -> {
                position++; space(); val result = linkedMapOf<String, JsonElement>()
                if (position < source.length && source[position] == '}') position++
                else while (true) {
                    val key = string().content; character(':'); result[key] = value(depth + 1); space()
                    if (position < source.length && source[position] == '}') { position++; break }
                    character(',')
                }
                JsonObject(result)
            }
            '[' -> {
                position++; space(); val result = mutableListOf<JsonElement>()
                if (position < source.length && source[position] == ']') position++
                else while (true) {
                    result += value(depth + 1); space()
                    if (position < source.length && source[position] == ']') { position++; break }
                    character(',')
                }
                JsonArray(result)
            }
            else -> {
                val start = position
                while (position < source.length && source[position] !in " \t\r\n,]}") position++
                val token = source.substring(start, position)
                if (token !in setOf("true", "false", "null") &&
                    !token.matches(Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?"))) invalid()
                Json.parseToJsonElement(token)
            }
        }
    }
    fun parse(): JsonElement {
        val result = value(0); space(); if (position != source.length) invalid(); return result
    }
}
