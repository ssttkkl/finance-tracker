package com.finance.tracker.backend

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.sql.Connection
import kotlinx.serialization.json.*

/** 上传命令的工作区事务边界：解析在锁外，发布与永久收据核实在同一把工作区锁内。 */
internal class CashImportHttpService(private val database: Database, private val staging: ParsedImportStore,
    private val parser: (CashImportUpload, ByteArray) -> ParsedCashDocument = { upload, content ->
        CashStatementRowNormalization.parseCashStatement(content, upload.sourceHint, upload.password)
    }) {
    private val reads = ReadServices(database)
    private val sessionIdPattern = Regex("[A-Za-z0-9_-]{43}")

    private fun <T> locked(token: String?, write: Boolean, block: (Principal, Connection) -> T): T =
        database.write { connection ->
            val initial = reads.context(connection, token, write)
            val workspace = initial.workspaceId ?: throw AccessFailure(true)
            if (!connection.lockWorkspace(database.sqlite, workspace)) throw AccessFailure(true)
            block(reads.context(connection, token, write), connection)
        }

    private fun context(principal: Principal) =
        CashImportContextService(staging, principal.workspaceId!!, principal.userId, parser)

    private fun digest(id: String) = ParsedImportStore.digest(id.toByteArray(StandardCharsets.UTF_8))

    private fun receipt(connection: Connection, principal: Principal, sessionId: String): JsonObject? {
        if (!sessionIdPattern.matches(sessionId)) throw ParsedImportNotFound()
        return ReceiptRepository(connection, principal.workspaceId!!, database.sqlite)
            .forContext(principal.userId, sessionDigest = digest(sessionId))
    }

    private fun receiptId(receipt: JsonObject): String? = receipt["receipt_id"]?.jsonPrimitive?.contentOrNull

    private fun committedView(sessionId: String, receipt: JsonObject): Map<String, Any?> = linkedMapOf(
        "session_id" to sessionId, "session_revision" to null, "expires_at" to null, "state" to "committed",
        "files" to emptyList<Any?>(), "source_accounts" to emptyList<Any?>(), "last_preview_input" to null,
        "last_plan_id" to receipt["plan_id"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.contentOrNull,
        "can_preview" to false, "receipt_id" to receiptId(receipt),
    )

    private fun read(principal: Principal, sessionId: String): ParsedImportSession =
        staging.get(sessionId, principal.workspaceId!!, principal.userId).also {
            if (it.workflowKind != "import") throw ParsedImportNotFound()
        }

    private fun revision(value: Long): BigInteger = BigInteger.valueOf(value)

    private fun current(connection: Connection, principal: Principal, sessionId: String,
        expectedRevision: Long): ParsedImportSession {
        receipt(connection, principal, sessionId)?.let { throw ReceiptConflict("session_committed", receiptId(it)) }
        val current = read(principal, sessionId)
        if (current.revision != revision(expectedRevision)) throw ParsedImportRevisionConflict()
        if (current.cancelled) throw ParsedImportClosed()
        return current
    }

    private fun checkCapacity(current: ParsedImportSession, uploads: List<CashImportUpload>) {
        if (current.files.size + uploads.size > 20 ||
            current.files.map { it.clientFileRef }.toSet().intersect(uploads.map { it.clientFileRef }.toSet()).isNotEmpty())
            throw CashImportInputError()
    }

    fun create(token: String?, uploads: List<CashImportUpload>): Map<String, Any?> {
        val service = locked(token, true) { principal, _ -> context(principal).also { it.validateUploads(uploads) } }
        val prepared = uploads.map { parserService().parse(it) }.toMutableList()
        val created = locked(token, true) { principal, _ ->
            val current = context(principal)
            current.fit(prepared, prepared.map { it.fileRef }.toSet()) {
                staging.create(principal.workspaceId!!, principal.userId, it)
            }
        }
        return view(token, created.sessionId)
    }

    fun addFiles(token: String?, sessionId: String, uploads: List<CashImportUpload>,
        expectedRevision: Long): Map<String, Any?> {
        val initial = locked(token, true) { principal, connection ->
            context(principal).validateUploads(uploads)
            current(connection, principal, sessionId, expectedRevision).also { checkCapacity(it, uploads) }
        }
        val added = uploads.map { parserService().parse(it) }
        val updated = locked(token, true) { principal, connection ->
            val service = context(principal)
            val current = current(connection, principal, sessionId, expectedRevision)
            checkCapacity(current, uploads)
            service.fit((current.files + added).toMutableList(), added.map { it.fileRef }.toSet()) {
                staging.replace(sessionId, principal.workspaceId!!, principal.userId, expectedRevision, it,
                    service.invalidateInput(current), null, CashImportChoiceContext.retained(current))!!
            }
        }
        return view(token, updated.sessionId)
    }

    fun replaceFile(token: String?, sessionId: String, fileRef: String, upload: CashImportUpload,
        expectedRevision: Long): Map<String, Any?> {
        val old = locked(token, true) { principal, connection ->
            context(principal).validateUploads(listOf(upload))
            val current = current(connection, principal, sessionId, expectedRevision)
            current.files.firstOrNull { it.fileRef == fileRef } ?: throw CashImportInputError("import_file_not_found")
        }
        val parsed = parserService().parse(upload.copy(clientFileRef = old.clientFileRef), old.sourceScopeLabel)
        val updated = locked(token, true) { principal, connection ->
            val service = context(principal)
            val current = current(connection, principal, sessionId, expectedRevision)
            service.fit(current.files.map { if (it.fileRef == fileRef) parsed else it }.toMutableList(),
                setOf(parsed.fileRef)) {
                staging.replace(sessionId, principal.workspaceId!!, principal.userId, expectedRevision, it,
                    service.invalidateInput(current, setOf(fileRef)), null, CashImportChoiceContext.retained(current))!!
            }
        }
        return view(token, updated.sessionId)
    }

    fun setFileSourceScope(token: String?, sessionId: String, fileRef: String, label: String?,
        expectedRevision: Long): Map<String, Any?> {
        val updated = locked(token, true) { principal, connection ->
            val service = context(principal)
            val current = current(connection, principal, sessionId, expectedRevision)
            val file = current.files.firstOrNull { it.fileRef == fileRef } ?: throw CashImportInputError("import_file_not_found")
            if (file.status != "parsed" || file.sourceType != "wechat") throw CashSourceScopeError("source_scope_unsupported")
            val normalized = CashSourceScope.normalizeScopeLabel(label)
            staging.replace(sessionId, principal.workspaceId!!, principal.userId, expectedRevision,
                current.files.map { if (it.fileRef == fileRef) it.copy(sourceScopeLabel = normalized) else it },
                service.invalidateInput(current, setOf(fileRef)), null, CashImportChoiceContext.retained(current))!!
        }
        return view(token, updated.sessionId)
    }

    fun removeFile(token: String?, sessionId: String, fileRef: String, expectedRevision: Long): Map<String, Any?> {
        val updated = locked(token, true) { principal, connection ->
            val service = context(principal)
            val current = current(connection, principal, sessionId, expectedRevision)
            if (current.files.none { it.fileRef == fileRef }) throw CashImportInputError("import_file_not_found")
            staging.replace(sessionId, principal.workspaceId!!, principal.userId, expectedRevision,
                current.files.filter { it.fileRef != fileRef },
                service.invalidateInput(current, setOf(fileRef)), null, CashImportChoiceContext.retained(current))!!
        }
        return view(token, updated.sessionId)
    }

    fun cancel(token: String?, sessionId: String): Map<String, Any?> {
        val cancelled = locked(token, true) { principal, connection ->
            receipt(connection, principal, sessionId)?.let { throw ReceiptConflict("session_committed", receiptId(it)) }
            val current = read(principal, sessionId)
            staging.cancel(sessionId, principal.workspaceId!!, principal.userId, current.revision)
        }
        return view(token, cancelled.sessionId)
    }

    fun view(token: String?, sessionId: String): Map<String, Any?> =
        locked(token, false) { principal, connection ->
            receipt(connection, principal, sessionId)?.let { return@locked committedView(sessionId, it) }
            val current = read(principal, sessionId)
            val scan = CashImportFunding.scan(current.files)
            val suggestions = CashImportAccountMappings(connection, principal.workspaceId!!, database.sqlite).suggest(scan.groups)
            CashImportSessionView.view(current, scan.groups, suggestions)
        }

    private fun readPlan(token: String?, planId: String?, projectionKey: List<Any?>? = null,
        project: (Map<String, Any?>) -> Map<String, Any?>): Map<String, Any?> {
        val currentPlanId = planId ?: throw CashImportPlanError("plan_not_found")
        val id = CashImportPlanViews.sessionId(currentPlanId)
        return locked(token, false) { principal, connection ->
            receipt(connection, principal, id)?.let { throw ReceiptConflict("session_committed", receiptId(it)) }
            val render: (ParsedImportSession) -> Map<String, Any?> = { current ->
                if (current.workflowKind != "import") throw CashImportPlanError("plan_expired")
                if (current.cancelled || current.plan == null || current.plan["plan_id"] != currentPlanId)
                    throw CashImportPlanError("plan_changed")
                project(current.plan)
            }
            try {
                if (projectionKey == null) render(staging.getPlanSnapshot(id, principal.workspaceId!!, principal.userId))
                else staging.readPlanProjection(id, principal.workspaceId!!, principal.userId,
                    listOf(currentPlanId) + projectionKey, render)
            } catch (_: ParsedImportNotFound) { throw CashImportPlanError("plan_expired") }
            catch (_: ParsedImportExpired) { throw CashImportPlanError("plan_expired") }
        }
    }

    fun plan(token: String?, planId: String?): Map<String, Any?> =
        readPlan(token, planId, listOf("plan")) { CashImportPlanViews.view(it) }

    fun planItems(token: String?, planId: String?, cursor: String?, startIndex: Int?, limit: Int,
        confirmationStatus: String?): Map<String, Any?> = readPlan(token, planId,
        listOf("items", cursor, startIndex, limit, confirmationStatus)) {
        CashImportPlanViews.page(it, "items", cursor, startIndex, limit, confirmationStatus, indexed = true)
    }

    fun planItem(token: String?, planId: String?, previewItemId: String?): Map<String, Any?> = readPlan(token, planId) { plan ->
        val details = plan["item_details"] as? Map<*, *> ?: throw CashImportPlanError("plan_build_failed")
        val detail = details[previewItemId] ?: throw CashImportPlanError("plan_item_not_found")
        CashImportPlanViews.publicDetail(CashImportPlanViews.resolveDetail(plan, detail)) +
            ("preview_item_id" to previewItemId)
    }

    fun planProcessing(token: String?, planId: String?, cursor: String?, limit: Int): Map<String, Any?> =
        readPlan(token, planId, listOf("processing", cursor, limit)) {
            CashImportPlanViews.page(it, "processing_details", cursor, limit = limit)
        }

    private fun parserService(): CashImportContextService {
        // 解析只依赖文件自身，不读取会话；发布始终在锁内用当前成员重建。
        return CashImportContextService(staging, "", "", parser)
    }
}
