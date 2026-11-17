package com.finance.tracker.backend

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.sql.Connection
import kotlinx.serialization.json.*

internal fun interface CashImportPlanBuilder {
    fun build(context: CashSqlContext, files: List<ParsedImportFile>, input: Map<String, Any?>,
        previous: Map<String, Any?>?): Map<String, Any?>
}

/** Remaining legacy plan coordinator; no upload, file editing or session presentation. */
internal class CashImportHttpService(private val database: Database, private val staging: ParsedImportStore) {
    private var builder = CashImportPlanBuilder { context, files, input, previous ->
        CashImportBusinessPlanBuilder(context).build(files, input, previous)
    }
    internal constructor(database: Database, staging: ParsedImportStore, builder: CashImportPlanBuilder) : this(database, staging) {
        this.builder = builder
    }
    private var commands = CashImportCommandService(database, staging)
    internal constructor(database: Database, staging: ParsedImportStore, commands: CashImportCommandService) : this(database, staging) {
        this.commands = commands
    }
    private val reads = ReadServices(database)
    private val sessionIdPattern = Regex("[A-Za-z0-9_-]{43}")

    private fun <T> locked(token: String?, write: Boolean, block: (Principal, Connection) -> T): T =
        database.write { connection ->
            val initial = reads.context(connection, token, write)
            val workspace = initial.workspaceId ?: throw AccessFailure(true)
            if (!connection.lockWorkspace(database.sqlite, workspace)) throw AccessFailure(true)
            block(reads.context(connection, token, write), connection)
        }

    private fun digest(id: String) = ParsedImportStore.digest(id.toByteArray(StandardCharsets.UTF_8))

    private fun receipt(connection: Connection, principal: Principal, sessionId: String): JsonObject? {
        if (!sessionIdPattern.matches(sessionId)) throw ParsedImportNotFound()
        return ReceiptRepository(connection, principal.workspaceId!!, database.sqlite)
            .forContext(principal.userId, sessionDigest = digest(sessionId))
    }

    private fun receiptId(receipt: JsonObject): String? = receipt["receipt_id"]?.jsonPrimitive?.contentOrNull

    private fun revision(value: Long): BigInteger = BigInteger.valueOf(value)

    private fun readPlan(token: String?, planId: String?, projectionKey: List<Any?>? = null,
        project: (Map<String, Any?>) -> Map<String, Any?>): Map<String, Any?> {
        val currentPlanId = planId ?: throw CashImportPlanError("plan_not_found")
        val id = CashImportPlanViews.sessionId(currentPlanId)
        return locked(token, false) { principal, connection ->
            receipt(connection, principal, id)?.let { throw ReceiptConflict("session_committed", receiptId(it)) }
            val render: (ParsedImportSession) -> Map<String, Any?> = { current ->
                if (current.workflowKind != "import") throw CashImportPlanError("plan_expired")
                if (current.cancelled || current.plan == null || current.plan["plan_id"] != currentPlanId || !CashImportPlanViews.currentContract(current.plan))
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

    fun preview(token: String?, sessionId: String, expectedRevision: Long, previousPlanId: String?,
        input: Map<String, Any?>, timezoneName: String = "UTC"): Map<String, Any?> {
        if (expectedRevision < 1) throw CashImportPlanError("plan_changed")
        val previewInput = CashImportPlanViews.previewInput(input["account_selections"] ?: emptyList<Any?>(),
            input["row_choices"] ?: emptyList<Any?>(), input["relation_choices"] ?: emptyList<Any?>(), input["entry_choices"])
        try {
            return locked(token, true) { principal, connection ->
                receipt(connection, principal, sessionId)?.let { throw ReceiptConflict("session_committed", receiptId(it)) }
                CashImportMonthlySummaries.zone(timezoneName)
                val snapshot = staging.planUpdateSnapshot(sessionId, principal.workspaceId!!, principal.userId)
                val current = snapshot.session()
                if (current.workflowKind != "import") throw ParsedImportNotFound()
                if (current.revision != revision(expectedRevision) || current.cancelled || current.plan?.get("plan_id") != previousPlanId)
                    throw CashImportPlanError("plan_changed")
                val planId = "ip_$sessionId." + MutationPlanStore.secureUrlToken(12)
                @Suppress("UNCHECKED_CAST") val builderInput = CashRecordPolicy.copyValue(previewInput) as Map<String, Any?>
                val result = builder.build(CashSqlContext(connection, principal.workspaceId!!, database.sqlite),
                    current.files, builderInput, current.plan ?: CashImportChoiceContext.previous(current))
                val items = result["items"] as? List<*> ?: throw CashImportPlanError("plan_build_failed")
                if (result["summary"] !is Map<*, *> || result["can_commit"] !is Boolean) throw CashImportPlanError("plan_build_failed")
                val identifiers = mutableSetOf<String>()
                for (raw in items) {
                    val item = raw as? Map<*, *> ?: throw CashImportPlanError("plan_build_failed")
                    val id = item["preview_item_id"] as? String ?: throw CashImportPlanError("plan_build_failed")
                    if (!Regex("[A-Za-z0-9_:.~-]{1,128}").matches(id) || !identifiers.add(id) ||
                        item["confirmation_status"] !in setOf("confirmed", "pending")) throw CashImportPlanError("plan_build_failed")
                }
                val plan = result + linkedMapOf("plan_id" to planId, "session_id" to sessionId,
                    "session_revision" to current.revision + BigInteger.ONE, "expires_at" to ParsedImportCodec.timeText(current.expiresAt),
                    "contract_version" to 1, "item_counts" to listOf("confirmed", "pending").associateWith { status ->
                        items.count { (it as Map<*, *>)["confirmation_status"] == status }
                    })
                if (!CashImportPlanViews.currentContract(plan)) throw CashImportPlanError("plan_build_failed")
                CashImportPlanViews.validateReadable(plan)
                val view = CashImportPlanViews.view(plan, timezoneName)
                staging.replacePlan(snapshot, principal.workspaceId!!, principal.userId, current.revision,
                    previewInput, plan, CashImportChoiceContext.without(current))
                view
            }
        } catch (_: CashImportChoiceContextError) { throw CashImportPlanError("plan_build_failed") }
        catch (_: ParsedImportRevisionConflict) { throw CashImportPlanError("plan_changed") }
        catch (_: ParsedImportClosed) { throw CashImportPlanError("plan_changed") }
        catch (_: ParsedImportExpired) { throw CashImportPlanError("plan_expired") }
        catch (_: ParsedImportNotFound) { throw CashImportPlanError("plan_expired") }
    }

    fun plan(token: String?, planId: String?, timezoneName: String = "UTC"): Map<String, Any?> =
        readPlan(token, planId, listOf("plan", timezoneName)) { CashImportPlanViews.view(it, timezoneName) }

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

    fun commit(token: String?, planId: String, revision: Long, key: String?): JsonObject = commands.commit(token, planId, revision, key)

    fun resolve(token: String?, planId: String, key: String?): JsonObject = commands.resolve(token, planId, key)
}
