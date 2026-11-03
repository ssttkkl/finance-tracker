package com.finance.tracker.backend

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.utils.io.*
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.io.readByteArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

internal class CashImportProtocolError(val code: String = "import_payload_invalid") : RuntimeException(code)

internal data class CashImportFileMetadata(val clientFileRef: String, val password: String?, val sourceHint: String?,
    val lastModifiedAtMs: Long?)

internal data class CashImportUploadMetadata(val files: List<CashImportFileMetadata>,
    val expectedSessionRevision: Long? = null, val fileRef: String? = null)

internal data class CashImportPreviewRequest(val expectedRevision: Long, val previousPlanId: String?, val input: Map<String, Any?>)

internal object CashImportProtocol {
    const val MAX_FILE_BYTES = 100 * 1024 * 1024
    const val MAX_METADATA_BYTES = 65536
    const val MAX_PLAN_INPUT_BYTES = 64 * 1024 * 1024
    const val MAX_REQUEST_BYTES = 20L * MAX_FILE_BYTES + 1024 * 1024
    private val clientReference = Regex("[A-Za-z0-9_-]{1,128}")
    private val sourceHint = Regex("[a-z][a-z0-9_-]{0,63}")
    private val revisionPattern = Regex("\"([1-9][0-9]{0,18})\"|([1-9][0-9]{0,18})")
    private fun fail(code: String = "import_payload_invalid"): Nothing = throw CashImportProtocolError(code)

    fun idempotencyKey(value: String?): String = value?.takeIf { Regex("[A-Za-z0-9_:.~-]{1,255}").matches(it) }
        ?: fail("idempotency_key_required")

    fun objectInput(bytes: ByteArray): JsonObject = try {
        strictCashJsonRequest(pythonJsonText(bytes)).value as? JsonObject ?: fail()
    } catch (_: CategoryFailure) { fail() }

    fun commitInput(text: String): Long = commitInput(text.encodeToByteArray())
    fun commitInput(bytes: ByteArray): Long {
        val root = objectInput(bytes)
        if (root.keys != setOf("expected_session_revision")) fail()
        return integer(root["expected_session_revision"], 1) ?: fail()
    }

    fun mutationCommitInput(bytes: ByteArray): Long {
        val root = objectInput(bytes)
        if (root.keys != setOf("expected_plan_revision")) fail()
        return integer(root["expected_plan_revision"], 1) ?: fail()
    }

    fun draftSaveInput(text: String): Pair<Long, JsonObject> = draftSaveInput(text.encodeToByteArray())
    fun draftSaveInput(bytes: ByteArray): Pair<Long, JsonObject> {
        val root = objectInput(bytes)
        if (root.keys != setOf("expected_revision", "patch")) fail()
        return (integer(root["expected_revision"], 1) ?: fail()) to (root["patch"] as? JsonObject ?: fail())
    }

    fun resolveInput(text: String): Pair<String, String> = resolveInput(text.encodeToByteArray())
    fun resolveInput(bytes: ByteArray): Pair<String, String> {
        val root = objectInput(bytes)
        if (root.keys != setOf("plan_id", "idempotency_key")) fail()
        val planId = (root["plan_id"] as? JsonPrimitive)?.takeIf { it.isString && Regex("[A-Za-z0-9_:.~-]{1,128}").matches(it.content) }?.content ?: fail()
        val key = (root["idempotency_key"] as? JsonPrimitive)?.takeIf { it.isString && Regex("[A-Za-z0-9_:.~-]{1,255}").matches(it.content) }?.content ?: fail()
        return planId to key
    }

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

    fun preview(text: String): CashImportPreviewRequest = preview(text.encodeToByteArray())
    fun preview(bytes: ByteArray): CashImportPreviewRequest {
        val root = objectInput(bytes)
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
        val root = try { strictCashJsonRequest(pythonJsonText(text.encodeToByteArray())).value as? JsonObject ?: fail() }
            catch (_: CategoryFailure) { fail() }
        val allowed = if (add) setOf("files", "expected_session_revision", "file_ref") else setOf("files")
        if (root.keys.any { it !in allowed } || "files" !in root) fail()
        val files = root.getValue("files") as? JsonArray ?: fail()
        if (files.size !in 1..20) fail()
        val result = mutableListOf<CashImportFileMetadata>()
        val references = mutableSetOf<String>()
        for (raw in files) {
            val item = raw as? JsonObject ?: fail()
            val fileKeys = setOf("client_file_ref", "password", "source_hint", "last_modified_at_ms")
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
            result += CashImportFileMetadata(reference, optionalText("password", 1024), hint,
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

    fun ifMatch(value: String?): Long {
        val match = value?.let { revisionPattern.matchEntire(it) } ?: fail()
        val revision = (match.groupValues[1].ifEmpty { match.groupValues[2] }).toLongOrNull() ?: fail()
        if (revision <= 0) fail()
        return revision
    }

    /** 同次请求内读取全部元数据和文件，原始流与正文资源由内部读取器管理。 */
    suspend fun receive(call: ApplicationCall, add: Boolean, maxRequestBytes: Long = MAX_REQUEST_BYTES): Pair<CashImportUploadMetadata, List<CashImportUpload>> {
        val parts = CashMultipartReader(maxRequestBytes, observe = call.attributes.getOrNull(cashMultipartObserverKey) ?: {}).read(call.receiveChannel(),
            call.request.headers[io.ktor.http.HttpHeaders.ContentType].orEmpty(),
            call.request.headers[io.ktor.http.HttpHeaders.ContentLength])
        return uploads(parts, add)
    }

    internal fun uploads(parts: CashMultipartParts, add: Boolean): Pair<CashImportUploadMetadata, List<CashImportUpload>> {
        val found = parts.files
        val names = parts.names
        val model = metadata(parts.metadata, add)
        val wanted = model.files.map { "file:" + it.clientFileRef }
        if (wanted.toSet().size != wanted.size || found.keys != wanted.toSet()) fail()
        if (model.fileRef != null && model.files.size != 1) fail()
        val uploads = model.files.map { file ->
            CashImportUpload(file.clientFileRef, names.getValue("file:" + file.clientFileRef),
                found.getValue("file:" + file.clientFileRef), password = file.password, sourceHint = file.sourceHint,
                lastModifiedAtMs = file.lastModifiedAtMs)
        }
        return model to uploads
    }

    suspend fun boundedText(call: ApplicationCall, limit: Int = MAX_METADATA_BYTES): String {
        return strictText(boundedBytes(call.receiveChannel(), limit))
    }

    private suspend fun boundedBytes(channel: ByteReadChannel, limit: Int): ByteArray {
        require(limit >= 0)
        val packet = channel.readRemaining(limit.toLong() + 1)
        val bytes = packet.readByteArray()
        if (bytes.size > limit) fail()
        return bytes
    }

    suspend fun <T> jsonInput(call: ApplicationCall, limit: Int = MAX_PLAN_INPUT_BYTES, validate: (ByteArray) -> T): T =
        jsonInput(call.receiveChannel(), limit, validate)

    suspend fun <T> jsonInput(channel: ByteReadChannel, limit: Int = MAX_PLAN_INPUT_BYTES, validate: (ByteArray) -> T): T {
        val bytes = boundedBytes(channel, limit)
        return withContext(Dispatchers.IO) { validate(bytes) }
    }
}
