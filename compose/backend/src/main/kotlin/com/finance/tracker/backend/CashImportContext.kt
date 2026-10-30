package com.finance.tracker.backend

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.util.UUID

/** 可预期的上传文件命令输入拒绝。 */
internal class CashImportInputError(val code: String = "import_files_invalid") : RuntimeException(code)

/** 解析文件所需的一次上传；正文已由调用方按上限单次读取。 */
internal data class CashImportUpload(
    val clientFileRef: String,
    val filename: String,
    val content: ByteArray,
    val password: String? = null,
    val sourceHint: String? = null,
    val sourceScopeLabel: String? = null,
    val lastModifiedAtMs: Long? = null,
)

internal object CashImportContext {
    const val MAX_UPLOAD_BYTES = 100 * 1024 * 1024
    private val extensions = setOf("csv", "xls", "xlsx", "pdf", "tsv", "txt")
    private val passwordPattern = Regex("(?U)(?:密码|password|pwd)\\s*[:：=_-]?\\s*(?:[（(\\[【]\\s*[^)）\\]】]*[)）\\]】]?|[^\\s()（）\\[\\]【】]+)",
        RegexOption.IGNORE_CASE)
    private val emptyBrackets = Regex("(?U)\\(\\s*\\)|（\\s*）|\\[\\s*\\]|【\\s*】")
    private val longDigits = Regex("(?U)[0-9](?:[\\s\\-－—·]*[0-9]){11,}")
    internal val fileErrors = setOf("statement_source_unsupported", "statement_header_not_found", "statement_header_invalid",
        "statement_rows_too_large", "statement_input_invalid", "statement_unreadable", "statement_encoding_unsupported")

    private fun codePointTail(value: String, limit: Int): String =
        if (value.codePointCount(0, value.length) <= limit) value
        else value.substring(0, value.offsetByCodePoints(0, limit))

    /** 文件名只保留用户可辨认的部分：去掉路径、密码、空括号并遮掩长账号。 */
    fun displayFilename(filename: String, password: String?): String {
        var name = filename.replace('\\', '/').substringAfterLast('/').ifEmpty { "账单" }
        name = String(name.toByteArray(StandardCharsets.UTF_8), StandardCharsets.UTF_8)
        if (!password.isNullOrEmpty()) name = name.replace(password, "")
        val dot = name.lastIndexOf('.')
        var stem = if (dot < 0) "" else name.substring(0, dot)
        val extension = if (dot < 0) name else name.substring(dot + 1)
        val hasExtension = dot >= 0 && extension.lowercase() in extensions
        if (!hasExtension) stem = name
        stem = passwordPattern.replace(stem, "")
        stem = emptyBrackets.replace(stem, "")
        stem = stem.trim { it.code in pythonWhitespace }.ifEmpty { "账单" }
        stem = longDigits.replace(stem) { match -> "尾号" + match.value.filter { it in '0'..'9' }.takeLast(4) }
        return codePointTail(if (hasExtension) "$stem.$extension" else stem, 255)
    }

    internal fun diagnostic(file: ParsedImportFile): Map<String, Any?> = linkedMapOf(
        "error_id" to UUID.randomUUID().toString().replace("-", ""),
        "stage" to "file_read", "scope" to "file",
        "locations" to listOf(linkedMapOf<String, Any?>("file_ref" to file.fileRef, "display_name" to file.filename)),
        "total_locations" to 1,
    )
}

/**
 * 上传文件准备：按当前会话修订解析、替换、失效旧选择；不写财务数据，不重读旧正文。
 * 权限复核、永久消费收据和 HTTP 接口由调用方在同一工作区事务内另行接入。
 */
internal class CashImportContextService(
    private val store: ParsedImportStore,
    private val workspaceId: String,
    private val userId: String,
    private val parser: (CashImportUpload, ByteArray) -> ParsedCashDocument = { upload, content ->
        CashStatementRowNormalization.parseCashStatement(content, upload.sourceHint, upload.password)
    },
    private val maxUploadBytes: Int = CashImportContext.MAX_UPLOAD_BYTES,
) {
    private val clientReference = Regex("[A-Za-z0-9_-]{1,128}")
    private val sourceHint = Regex("[a-z][a-z0-9_-]{0,63}")
    private val fileErrors = CashImportContext.fileErrors

    private fun validateUploads(uploads: List<CashImportUpload>) {
        if (uploads.size !in 1..20) throw CashImportInputError()
        val references = mutableSetOf<String>()
        for (upload in uploads) {
            if (!clientReference.matches(upload.clientFileRef) || !references.add(upload.clientFileRef) ||
                upload.lastModifiedAtMs?.let { it < 0 } == true) throw CashImportInputError()
        }
    }

    /** 解析一份上传；已知格式、密码和预算错误成为文件结果，内部程序错误继续抛出。 */
    fun parse(upload: CashImportUpload, previousScopeLabel: String? = null): ParsedImportFile {
        val filename = CashImportContext.displayFilename(upload.filename, upload.password)
        val hint = upload.sourceHint?.takeIf { sourceHint.matches(it) }
        var file = ParsedImportFile(
            fileRef = "f_" + UUID.randomUUID().toString().replace("-", ""),
            digest = ParsedImportStore.digest(ByteArray(0)), status = "failed", sourceType = hint,
            errorCode = "statement_input_invalid", lastModifiedAtMs = upload.lastModifiedAtMs,
            clientFileRef = upload.clientFileRef, filename = filename)
        if (previousScopeLabel != null) file = file.copy(sourceType = "wechat", sourceScopeLabel = previousScopeLabel)
        try {
            var scope = if (upload.sourceScopeLabel != null) CashSourceScope.normalizeScopeLabel(upload.sourceScopeLabel)
            else previousScopeLabel
            file = file.copy(sourceScopeLabel = scope, sourceType = if (scope != null) "wechat" else hint)
            val raw = upload.content
            file = file.copy(digest = ParsedImportStore.digest(raw), size = raw.size.toLong())
            if (raw.size > maxUploadBytes) return file.copy(errorCode = "statement_file_too_large")
            if (raw.isEmpty()) return file
            val document = parser(upload, raw)
            if (document.sourceType != "wechat") {
                file = file.copy(sourceType = document.sourceType, sourceScopeLabel = null)
                if (upload.sourceScopeLabel != null && scope != null) throw CashSourceScopeError("source_scope_unsupported")
                scope = null
            }
            val parsed = file.copy(sourceType = document.sourceType, status = "parsed", document = document,
                errorCode = null, sourceScopeLabel = scope)
            store.validateFile(parsed)
            return parsed
        } catch (error: StatementPdfPasswordFailure) {
            return file.copy(status = "needs_password", errorCode = if (error.required) "password_required" else "password_incorrect")
        } catch (error: CashSourceScopeError) {
            return file.copy(errorCode = error.code)
        } catch (error: StatementDocumentFailure) {
            return file.copy(errorCode = if (error.code in fileErrors) error.code else "statement_unreadable",
                diagnostic = CashImportContext.diagnostic(file))
        } catch (error: ParsedImportDataError) {
            return file.copy(errorCode = if (error.code == "import_session_draft_too_large") "statement_rows_too_large"
                else "statement_unreadable", diagnostic = CashImportContext.diagnostic(file))
        }
    }

    private fun revision(value: Number): BigInteger = when (value) {
        is Byte, is Short, is Int, is Long, is BigInteger -> BigInteger(value.toString())
        else -> throw ParsedImportRevisionConflict()
    }

    private fun readImport(sessionId: String): ParsedImportSession {
        val current = store.get(sessionId, workspaceId, userId)
        if (current.workflowKind != "import") throw ParsedImportNotFound()
        return current
    }

    private fun current(sessionId: String, expectedRevision: Number): ParsedImportSession {
        val current = readImport(sessionId)
        if (current.revision != revision(expectedRevision)) throw ParsedImportRevisionConflict()
        if (current.cancelled) throw ParsedImportClosed()
        return current
    }

    private fun fit(files: MutableList<ParsedImportFile>, newRefs: Set<String>,
        write: (List<ParsedImportFile>) -> ParsedImportSession): ParsedImportSession {
        while (true) {
            try {
                return write(files.toList())
            } catch (error: ParsedImportDataError) {
                if (error.code != "import_session_draft_too_large") throw error
                val index = files.indices.lastOrNull { files[it].fileRef in newRefs && files[it].document != null } ?: throw error
                files[index] = files[index].copy(status = "failed", document = null, errorCode = "statement_rows_too_large")
            }
        }
    }

    /** 文件变化后旧计划不可执行；行选择按文件命名空间过滤，关系证明单独保留。 */
    fun invalidateInput(current: ParsedImportSession, obsoleteRefs: Set<String> = emptySet()): Map<String, Any?> {
        val inputs = linkedMapOf<String, Any?>()
        current.previewInput.forEach { (key, value) -> inputs[key] = CashRecordPolicy.copyValue(value) }
        inputs.remove("entry_choices")
        val choices = inputs["row_choices"]
        if (choices is List<*>) inputs["row_choices"] = choices.filter { choice ->
            choice is Map<*, *> && obsoleteRefs.none { reference ->
                (choice["row_ref"] ?: "").toString().startsWith("$reference:")
            }
        }
        if ("previous_plan_id" in inputs) inputs["previous_plan_id"] = null
        if ("expected_session_revision" in inputs) inputs["expected_session_revision"] = current.revision + BigInteger.ONE
        return inputs
    }

    fun create(uploads: List<CashImportUpload>): ParsedImportSession {
        validateUploads(uploads)
        val files = uploads.map { parse(it) }.toMutableList()
        return fit(files, files.map { it.fileRef }.toSet()) { items ->
            store.create(workspaceId, userId, items)
        }
    }

    fun addFiles(sessionId: String, uploads: List<CashImportUpload>, expectedRevision: Number): ParsedImportSession {
        validateUploads(uploads)
        val current = current(sessionId, expectedRevision)
        if (current.files.size + uploads.size > 20 ||
            current.files.map { it.clientFileRef }.toSet().intersect(uploads.map { it.clientFileRef }.toSet()).isNotEmpty())
            throw CashImportInputError()
        val added = uploads.map { parse(it) }
        val files = (current.files + added).toMutableList()
        return fit(files, added.map { it.fileRef }.toSet()) { items ->
            store.replace(sessionId, workspaceId, userId, expectedRevision, items,
                invalidateInput(current), null, CashImportChoiceContext.retained(current))!!
        }
    }

    fun replaceFile(sessionId: String, fileRef: String, upload: CashImportUpload,
        expectedRevision: Number): ParsedImportSession {
        validateUploads(listOf(upload))
        val current = current(sessionId, expectedRevision)
        val old = current.files.firstOrNull { it.fileRef == fileRef } ?: throw CashImportInputError("import_file_not_found")
        val parsed = parse(upload.copy(clientFileRef = old.clientFileRef), old.sourceScopeLabel)
        val files = current.files.map { if (it.fileRef == fileRef) parsed else it }.toMutableList()
        return fit(files, setOf(parsed.fileRef)) { items ->
            store.replace(sessionId, workspaceId, userId, expectedRevision, items,
                invalidateInput(current, setOf(fileRef)), null, CashImportChoiceContext.retained(current))!!
        }
    }

    fun setFileSourceScope(sessionId: String, fileRef: String, sourceScopeLabel: String?,
        expectedRevision: Number): ParsedImportSession {
        val current = current(sessionId, expectedRevision)
        val file = current.files.firstOrNull { it.fileRef == fileRef } ?: throw CashImportInputError("import_file_not_found")
        if (file.status != "parsed" || file.sourceType != "wechat") throw CashSourceScopeError("source_scope_unsupported")
        val label = CashSourceScope.normalizeScopeLabel(sourceScopeLabel)
        return store.replace(sessionId, workspaceId, userId, expectedRevision,
            current.files.map { if (it.fileRef == fileRef) it.copy(sourceScopeLabel = label) else it },
            invalidateInput(current, setOf(fileRef)), null, CashImportChoiceContext.retained(current))!!
    }

    fun removeFile(sessionId: String, fileRef: String, expectedRevision: Number): ParsedImportSession {
        val current = current(sessionId, expectedRevision)
        if (current.files.none { it.fileRef == fileRef }) throw CashImportInputError("import_file_not_found")
        return fit(current.files.filter { it.fileRef != fileRef }.toMutableList(), emptySet()) { items ->
            store.replace(sessionId, workspaceId, userId, expectedRevision, items,
                invalidateInput(current, setOf(fileRef)), null, CashImportChoiceContext.retained(current))!!
        }
    }

    fun cancel(sessionId: String): ParsedImportSession {
        val current = readImport(sessionId)
        return store.cancel(sessionId, workspaceId, userId, current.revision)
    }

    fun get(sessionId: String): ParsedImportSession = readImport(sessionId)
}
