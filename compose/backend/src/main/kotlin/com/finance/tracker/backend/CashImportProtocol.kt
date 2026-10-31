package com.finance.tracker.backend

import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.utils.io.*
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.io.readByteArray
import kotlinx.serialization.json.*

internal class CashImportProtocolError(val code: String = "import_payload_invalid") : RuntimeException(code)

internal data class CashImportFileMetadata(val clientFileRef: String, val password: String?, val sourceHint: String?,
    val sourceScopeLabel: String?, val scopePresent: Boolean, val lastModifiedAtMs: Long?)

internal data class CashImportUploadMetadata(val files: List<CashImportFileMetadata>,
    val expectedSessionRevision: Long? = null, val fileRef: String? = null)

internal data class CashImportPreviewRequest(val expectedRevision: Long, val previousPlanId: String?, val input: Map<String, Any?>)

internal object CashImportProtocol {
    const val MAX_FILE_BYTES = 100 * 1024 * 1024
    const val MAX_METADATA_BYTES = 65536
    const val MAX_REQUEST_BYTES = 20L * MAX_FILE_BYTES + 1024 * 1024
    private val clientReference = Regex("[A-Za-z0-9_-]{1,128}")
    private val sourceHint = Regex("[a-z][a-z0-9_-]{0,63}")
    private val revisionPattern = Regex("\"([1-9][0-9]{0,18})\"|([1-9][0-9]{0,18})")
    private const val MAX_PART_HEADER_BYTES = 16384

    private fun headersWithin(part: PartData): Boolean =
        part.headers.entries().sumOf { (name, values) -> name.length + values.sumOf { it.length } + 4 } <= MAX_PART_HEADER_BYTES

    private fun fail(code: String = "import_payload_invalid"): Nothing = throw CashImportProtocolError(code)

    private fun utf8(text: String): ByteArray = text.toByteArray(StandardCharsets.UTF_8)
    private fun strictText(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) { fail() }

    private fun integer(value: JsonElement?, minimum: Long): Long? {
        if (value == null || value == JsonNull) return null
        val primitive = value as? JsonPrimitive ?: fail()
        if (primitive.isString || primitive.booleanOrNull != null) fail()
        val number = primitive.content.toLongOrNull() ?: fail()
        if (number < minimum) fail()
        return number
    }

    fun preview(text: String): CashImportPreviewRequest {
        val root = try { strictCashJsonRequest(pythonJsonText(text.encodeToByteArray())).value as? JsonObject ?: fail() }
            catch (_: CategoryFailure) { fail() }
        val required = setOf("expected_session_revision", "previous_plan_id", "account_selections", "row_choices", "relation_choices")
        if (!root.keys.containsAll(required) || (root.keys - required - "entry_choices").isNotEmpty()) fail()
        val revision = integer(root["expected_session_revision"], 1) ?: fail()
        fun reference(value: JsonElement?, maximum: Int = 255): Boolean = value is JsonPrimitive && value.isString &&
            Regex("[A-Za-z0-9_:.~-]{1,$maximum}").matches(value.content)
        val previous = root["previous_plan_id"].takeUnless { it == JsonNull }?.let {
            if (!reference(it, 128)) fail()
            it.jsonPrimitive.content
        }
        val inputs = linkedMapOf<String, Any?>()
        for ((field, ref) in listOf("account_selections" to "source_account_ref", "row_choices" to "row_ref",
            "relation_choices" to "suggestion_id", "entry_choices" to "edit_ref")) {
            val items = if (field == "entry_choices" && field !in root) JsonArray(emptyList()) else root[field] as? JsonArray ?: fail()
            if (items.size > 100_000) fail()
            val allowed = when (field) {
                "account_selections" -> setOf(ref, "account_id", "expected_account_revision")
                "row_choices" -> setOf(ref, "action", "patch")
                "relation_choices" -> setOf(ref, "action", "candidate_id", "common_fields", "inherit_common_fields")
                else -> setOf(ref, "patch")
            }
            for (raw in items) {
                val item = raw as? JsonObject ?: fail()
                if ((item.keys - allowed).isNotEmpty() || !reference(item[ref])) fail()
                if (field == "account_selections") {
                    if (item["account_id"] != null && item["account_id"] != JsonNull && !reference(item["account_id"], 128)) fail()
                    integer(item["expected_account_revision"], 1)
                }
                if (field in setOf("row_choices", "relation_choices")) {
                    val actions = if (field == "row_choices") setOf("apply", "keep_existing", "defer") else setOf("accept", "defer", "ignore")
                    if (item["action"] !is JsonPrimitive || !item.getValue("action").jsonPrimitive.isString ||
                        item.getValue("action").jsonPrimitive.content !in actions) fail()
                }
                if (field == "entry_choices" && "patch" !in item || "patch" in item && item["patch"] !is JsonObject) fail()
                if (item["candidate_id"] != null && item["candidate_id"] != JsonNull && !reference(item["candidate_id"], 128)) fail()
                if (item["common_fields"] != null && item["common_fields"] != JsonNull && item["common_fields"] !is JsonObject) fail()
                if (item["inherit_common_fields"] != null && item["inherit_common_fields"] != JsonNull &&
                    ((item["inherit_common_fields"] as? JsonPrimitive)?.isString != false ||
                        item["inherit_common_fields"]?.jsonPrimitive?.booleanOrNull == null)) fail()
            }
            inputs[field] = cashSourceValue(items)
        }
        return CashImportPreviewRequest(revision, previous, inputs)
    }

    /** 严格解析元数据 JSON：未知键、重复客户端引用和越界修订都整体拒绝。 */
    fun metadata(text: String, add: Boolean): CashImportUploadMetadata {
        val root = try { Json.parseToJsonElement(text) } catch (_: Exception) { fail() } as? JsonObject ?: fail()
        val allowed = if (add) setOf("files", "expected_session_revision", "file_ref") else setOf("files")
        if (root.keys.any { it !in allowed } || "files" !in root) fail()
        val files = root.getValue("files") as? JsonArray ?: fail()
        if (files.size !in 1..20) fail()
        val result = mutableListOf<CashImportFileMetadata>()
        val references = mutableSetOf<String>()
        for (raw in files) {
            val item = raw as? JsonObject ?: fail()
            val fileKeys = setOf("client_file_ref", "password", "source_hint", "source_scope_label", "last_modified_at_ms")
            if (item.keys.any { it !in fileKeys } || "client_file_ref" !in item) fail()
            val reference = (item["client_file_ref"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.takeIf { clientReference.matches(it) } ?: fail()
            if (!references.add(reference)) fail()
            fun optionalText(name: String, maximum: Int): String? {
                val value = item[name] ?: return null
                if (value == JsonNull) return null
                val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail()
                if (text.codePointCount(0, text.length) > maximum) fail()
                return text
            }
            val hint = optionalText("source_hint", 64)?.also { if (!sourceHint.matches(it)) fail() }
            val scopePresent = "source_scope_label" in item
            val scope = optionalText("source_scope_label", 64)
            result += CashImportFileMetadata(reference, optionalText("password", 1024), hint, scope, scopePresent,
                integer(item["last_modified_at_ms"], 0))
        }
        if (!add) return CashImportUploadMetadata(result)
        val revision = integer(root["expected_session_revision"], 1) ?: fail()
        val fileRef = root["file_ref"]?.takeIf { it != JsonNull }?.let {
            (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content
                ?.takeIf { value -> clientReference.matches(value) } ?: fail()
        }
        return CashImportUploadMetadata(result, revision, fileRef)
    }

    fun scopeInput(text: String): Pair<Long, String?> {
        val root = try { Json.parseToJsonElement(text) } catch (_: Exception) { fail() } as? JsonObject ?: fail()
        if (root.keys != setOf("expected_session_revision", "source_scope_label")) fail()
        val revision = integer(root["expected_session_revision"], 1) ?: fail()
        val label = root["source_scope_label"] ?: fail()
        if (label == JsonNull) return revision to null
        val value = (label as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail()
        if (value.codePointCount(0, value.length) > 64) fail()
        return revision to value
    }

    fun ifMatch(value: String?): Long {
        val match = value?.let { revisionPattern.matchEntire(it) } ?: fail()
        val revision = (match.groupValues[1].ifEmpty { match.groupValues[2] }).toLongOrNull() ?: fail()
        if (revision <= 0) fail()
        return revision
    }

    private suspend fun readBytes(part: PartData.FileItem): ByteArray {
        val packet = part.provider().readRemaining((MAX_FILE_BYTES + 1).toLong())
        return packet.readByteArray()
    }

    /** 读取一次 multipart：先收集元数据文本，再按元数据读取文件；两者来自同一次请求。 */
    suspend fun receive(call: ApplicationCall, add: Boolean): Pair<CashImportUploadMetadata, List<CashImportUpload>> {
        val contentType = call.request.headers[io.ktor.http.HttpHeaders.ContentType] ?: fail()
        if (contentType.length > 1024 || !contentType.lowercase().startsWith("multipart/form-data;")) fail()
        call.request.headers[io.ktor.http.HttpHeaders.ContentLength]?.let { raw ->
            if (!raw.matches(Regex("[0-9]{1,20}"))) fail()
            if (raw.toLongOrNull()?.let { it > MAX_REQUEST_BYTES } == true) fail("upload_too_large")
        }
        val found = linkedMapOf<String, ByteArray>()
        val names = linkedMapOf<String, String>()
        var metadataText: String? = null
        var total = 0L
        try {
        call.receiveMultipart(formFieldLimit = (1024 * 1024).toLong()).forEachPart { part ->
            try {
                if (!headersWithin(part)) fail()
                when (part) {
                    is PartData.FormItem -> {
                        if (part.name != "metadata" || metadataText != null) fail()
                        metadataText = part.value
                        if (utf8(part.value).size > MAX_METADATA_BYTES) fail()
                    }
                    is PartData.FileItem -> {
                        val name = part.name ?: fail()
                        if (!name.startsWith("file:") || name in found) fail()
                        val filename = part.originalFileName?.takeIf { it.isNotEmpty() } ?: "账单"
                        val bytes = readBytes(part)
                        total += bytes.size
                        if (total > MAX_REQUEST_BYTES) fail("upload_too_large")
                        found[name] = bytes
                        names[name] = filename
                    }
                    else -> fail()
                }
            } finally {
                (part as? PartData.FileItem)?.dispose()
            }
        }
        } catch (failure: CashImportProtocolError) { throw failure }
        catch (_: Exception) { fail() }
        val model = metadata(metadataText ?: fail(), add)
        val wanted = model.files.map { "file:" + it.clientFileRef }
        if (wanted.toSet().size != wanted.size || found.keys != wanted.toSet()) fail()
        if (model.fileRef != null && model.files.size != 1) fail()
        val uploads = model.files.map { file ->
            CashImportUpload(file.clientFileRef, names.getValue("file:" + file.clientFileRef),
                found.getValue("file:" + file.clientFileRef), password = file.password, sourceHint = file.sourceHint,
                sourceScopeLabel = if (file.scopePresent && file.sourceScopeLabel == null) "" else file.sourceScopeLabel,
                lastModifiedAtMs = file.lastModifiedAtMs)
        }
        return model to uploads
    }

    suspend fun boundedText(call: ApplicationCall, limit: Int = MAX_METADATA_BYTES): String {
        val packet = call.receiveChannel().readRemaining((limit + 1).toLong())
        val bytes = packet.readByteArray()
        if (bytes.size > limit) fail()
        return strictText(bytes)
    }
}
