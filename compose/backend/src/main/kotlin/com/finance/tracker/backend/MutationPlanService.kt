package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.CASH_TRANSACTIONS
import java.time.Instant
import kotlinx.serialization.json.*

internal class MutationPlanFieldFailure(val code: String, val fields: List<String>) : RuntimeException(code)

class MutationPlanService(
    private val database: Database,
    private val now: () -> Instant = Instant::now,
) {
    private val reads = ReadServices(database, now)
    private val store = MutationPlanStore(
        MutationPlanStore.ttlFromEnvironment(System.getenv("FT_IMPORT_STAGING_TTL_SECONDS")), now)

    fun preview(token: String?, body: JsonObject, requestPrincipal: Principal? = null): JsonObject {
        val request = MutationPlanRequest.parse(body)
        val result = database.read { connection ->
            val principal = planContext(connection, token, write = true)
            if (requestPrincipal != null) sameContext(requestPrincipal, principal)
            val context = if (request.metadata.editContextId != null) {
                val current = store.get(request.metadata.editContextId, principal.workspaceId!!, principal.userId)
                if (current.cancelled) throw MutationPlanFailure("plan_changed")
                if (current.transactionId != request.transactionId) throw MutationPlanFailure("plan_changed")
                checkedPlan(current, request.metadata.previousPlanId, request.metadata.expectedPlanRevision)
                current
            } else null
            val transaction = request.transactionId.toLong()
            val row = CASH_TRANSACTIONS.let { tx ->
                connection.jooq(database.sqlite).select(tx.ID, tx.ROW_REVISION).from(tx)
                    .where(tx.WORKSPACE_ID.eq(principal.workspaceId!!)).and(tx.ID.eq(transaction))
                    .and(tx.DELETED_AT.isNull).fetchOne()
            } ?: throw MutationPlanFailure("record_not_found")
            if (row.value2() != request.expectedRevision) throw MutationPlanFailure("record_changed")
            val built = try {
                MutationPlanBuilder(CashSqlContext(connection, principal.workspaceId!!, database.sqlite, now))
                    .build(transaction, request)
            } catch (failure: CashPolicyError) {
                throw MutationPlanFieldFailure(failure.code, failure.fields).also { it.addSuppressed(failure) }
            } catch (_: ReceiptConflict) {
                throw MutationPlanFailure("issue_changed")
            }
            MutationPlanView.validateBuild(built)
            Triple(principal, context, built)
        }
        val (principal, context, built) = result
        val itemCounts = buildJsonObject {
            val items = built.getValue("items").jsonArray
            for (status in listOf("confirmed", "pending"))
                put(status, items.count { it.jsonObject.getValue("confirmation_status").jsonPrimitive.content == status })
        }
        val workspace = principal.workspaceId ?: throw AccessFailure(true)
        val contextId = context?.id ?: store.create(workspace, principal.userId, request.transactionId).id
        val current = store.get(contextId, workspace, principal.userId)
        val previous = current.plan
        val planRevision = previous?.get("plan_revision")?.jsonPrimitive?.longOrNull?.plus(1) ?: 1
        val plan = JsonObject(built + mapOf(
            "plan_id" to JsonPrimitive(store.planId(contextId)),
            "edit_context_id" to JsonPrimitive(contextId),
            "plan_revision" to JsonPrimitive(planRevision),
            "context_revision" to JsonPrimitive(current.revision + 1),
            "contract_version" to JsonPrimitive(1),
            "expires_at" to JsonPrimitive(manualInstantText(current.expiresAt)),
            "item_counts" to itemCounts,
        ))
        try {
            store.replace(contextId, workspace, principal.userId, current.revision, plan, requestJson(request))
        } catch (_: MutationPlanStore.RevisionConflict) {
            throw MutationPlanFailure("plan_changed")
        }
        return MutationPlanView.view(plan)
    }

    fun plan(token: String?, planId: String?, requestPrincipal: Principal? = null): JsonObject =
        read(token, requestPrincipal) { principal ->
            val id = MutationPlanStore.contextId(planId)
            val current = store.get(id, principal.workspaceId!!, principal.userId)
            MutationPlanView.view(checkedPlan(current, planId, null))
        }

    fun currentPlan(token: String?, editContextId: String?, requestPrincipal: Principal? = null): JsonObject =
        read(token, requestPrincipal) { principal ->
            if (editContextId == null || !editContextId.matches(Regex("[A-Za-z0-9_-]{43}")))
                throw MutationPlanFailure("plan_expired")
            val current = store.get(editContextId, principal.workspaceId!!, principal.userId)
            MutationPlanView.view(checkedPlan(current, null, null))
        }

    fun items(token: String?, planId: String?, cursor: String?, limit: Int, confirmationStatus: String?,
        requestPrincipal: Principal? = null): JsonObject = read(token, requestPrincipal) { principal ->
        val current = store.get(MutationPlanStore.contextId(planId), principal.workspaceId!!, principal.userId)
        MutationPlanView.page(checkedPlan(current, planId, null), "items", cursor, limit, confirmationStatus)
    }

    fun item(token: String?, planId: String?, previewItemId: String?, requestPrincipal: Principal? = null): JsonObject =
        read(token, requestPrincipal) { principal ->
            val current = store.get(MutationPlanStore.contextId(planId), principal.workspaceId!!, principal.userId)
            MutationPlanView.item(checkedPlan(current, planId, null), previewItemId)
        }

    fun commit(token: String?, planId: String?, expectedPlanRevision: Long, idempotencyKey: String?,
        requestPrincipal: Principal? = null): JsonObject {
        val key = idempotencyKey?.takeIf { it.matches(Regex("[A-Za-z0-9_:.~-]{1,255}")) }
            ?: throw MutationPlanFailure("idempotency_key_required")
        if (expectedPlanRevision < 1) throw MutationPlanFailure("plan_input_invalid")
        val principal = reads.currentContext(token)
        if (requestPrincipal != null) sameContext(requestPrincipal, principal)
        val receipt = database.write { connection ->
            val current = planContext(connection, token, write = true)
            if (requestPrincipal != null) sameContext(requestPrincipal, current)
            val workspace = current.workspaceId ?: throw AccessFailure(true)
            val repository = ReceiptRepository(connection, workspace, database.sqlite, now)
            val digest = cashSourceDigest(JsonPrimitive("cash-mutation-commit-v1"), JsonPrimitive(planId),
                JsonPrimitive(expectedPlanRevision))
            repository.replay(current.userId, key, digest, "mutation", planId)?.let { return@write it }
            val contextId = MutationPlanStore.contextId(planId)
            val contextDigest = tokenDigest(contextId)
            repository.forContext(current.userId, editContextDigest = contextDigest)?.let {
                throw ReceiptConflict("edit_context_committed", it.getValue("receipt_id").jsonPrimitive.content)
            }
            val stored = store.get(contextId, workspace, current.userId)
            val plan = checkedPlan(stored, planId, expectedPlanRevision)
            if (plan["can_commit"]?.jsonPrimitive?.booleanOrNull != true) throw MutationPlanFailure("plan_input_invalid")
            val input = stored.previewInput ?: throw MutationPlanFailure("plan_changed")
            val transaction = input.getValue("transaction_id").jsonPrimitive.content.toLong()
            val expectedRevision = input.getValue("expected_revision").jsonPrimitive.long
            val sql = connection.jooq(database.sqlite)
            val rowRevision = sql.select(CASH_TRANSACTIONS.ROW_REVISION).from(CASH_TRANSACTIONS)
                .where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(workspace)).and(CASH_TRANSACTIONS.ID.eq(transaction))
                .and(CASH_TRANSACTIONS.DELETED_AT.isNull).fetchOne(CASH_TRANSACTIONS.ROW_REVISION)
                ?: throw MutationPlanFailure("record_not_found")
            if (rowRevision != expectedRevision) throw MutationPlanFailure("record_changed")
            val operation = plan.getValue("operations").jsonArray.single().jsonObject
            if (operation.getValue("planned_action").jsonPrimitive.content != "update_posted")
                throw MutationPlanFailure("plan_input_invalid")
            val entryEdit = operation["entry_edit"]?.takeIf { it != JsonNull }?.jsonObject
                ?: throw MutationPlanFailure("plan_input_invalid")
            val patch = entryEdit.getValue("patch").jsonObject.mapValues { cashSourceValue(it.value) }
            CashEntryRepository(connection, workspace, database.sqlite).patch(transaction, patch,
                entryEdit.getValue("revision").jsonPrimitive.long)
            val context = CashSqlContext(connection, workspace, database.sqlite, now)
            val projectionState = CashProjectionRepository(context).requireReadyStateLock()
            val maintained = CashProjectionService.maintainInSession(context, setOf(transaction), projectionState)
            val version = maintained["projection_version"]?.jsonPrimitive?.longOrNull ?: projectionState.projectionVersion
            val references = CashProjectionRepository(context).currentReferences(setOf(transaction), projectionState.datasetId)
            val pendingItems = references.values.filter {
                (it["attention_count"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L) > 0
            }.mapNotNull { it["projection_id"]?.jsonPrimitive?.contentOrNull }.distinct().size
            val summary = buildJsonObject {
                for (field in listOf("updated_posted", "finalized_draft", "saved_draft", "merged_draft", "duplicate",
                    "skipped", "relations_confirmed", "relations_deferred", "relations_ignored", "relations_removed",
                    "relations_failed", "resolved_issues")) put(field, 0)
                put("updated_posted", 1)
            }
            val resultSummary = buildJsonObject {
                put("status", "completed"); put("summary", summary)
                put("retained_transaction_id", transaction.toString())
                put("projection_version", version); put("needs_attention_count", 0); put("pending_item_count", pendingItems)
            }
            val results = JsonArray(listOf(buildJsonObject {
                put("result_kind", "row"); put("item_ref", transaction.toString())
                put("outcome", "updated_posted"); put("operation_ref", "op_1")
                put("transaction_id", transaction.toString())
            }))
            val created = repository.create(current.userId, key, digest, "mutation", resultSummary, results,
                planId = planId, editContextDigest = contextDigest)
            store.consume(contextId)
            created
        }
        val after = reads.currentContext(token)
        sameContext(principal, after)
        return ReceiptRepositoryView.strip(receipt)
    }

    private fun <T> read(token: String?, requestPrincipal: Principal?, block: (Principal) -> T): T {
        val principal = reads.currentContext(token)
        if (requestPrincipal != null) sameContext(requestPrincipal, principal)
        val result = database.read { connection ->
            val current = planContext(connection, token, write = false)
            if (requestPrincipal != null) sameContext(requestPrincipal, current)
            block(current)
        }
        val after = reads.currentContext(token)
        sameContext(principal, after)
        return result
    }

    private fun planContext(connection: java.sql.Connection, token: String?, write: Boolean): Principal {
        val principal = ReadServices(database, now).context(connection, token, write)
        if (principal.workspaceId == null) throw AccessFailure(true)
        return principal
    }

    private fun checkedPlan(current: MutationPlanStore.Context, planId: String?, expectedRevision: Long?): JsonObject {
        val plan = current.plan ?: throw MutationPlanFailure("plan_changed")
        if (current.cancelled) throw MutationPlanFailure("plan_changed")
        if (planId != null && plan["plan_id"]?.jsonPrimitive?.contentOrNull != planId) throw MutationPlanFailure("plan_changed")
        if (plan["edit_context_id"]?.jsonPrimitive?.contentOrNull != current.id) throw MutationPlanFailure("plan_changed")
        if (plan["context_revision"]?.jsonPrimitive?.longOrNull != current.revision) throw MutationPlanFailure("plan_changed")
        val revision = plan["plan_revision"]?.jsonPrimitive?.longOrNull ?: throw MutationPlanFailure("plan_changed")
        if (revision < 1) throw MutationPlanFailure("plan_changed")
        if (expectedRevision != null && expectedRevision != revision) throw MutationPlanFailure("plan_changed")
        return plan
    }

    private fun requestJson(request: MutationPlanRequest): JsonObject = buildJsonObject {
        put("intent", request.intent); put("transaction_id", request.transactionId)
        put("expected_revision", request.expectedRevision); put("patch", request.patch)
        put("relation_choices", JsonArray(request.relationChoices))
        put("issue_resolutions", JsonArray(request.issueResolutions.map {
            buildJsonObject {
                put("issue_id", it.issueId); put("expected_issue_revision", it.expectedIssueRevision)
                put("resolution", it.resolution)
                it.targetTransactionId?.let { value -> put("target_transaction_id", value) }
                it.expectedTargetRevision?.let { value -> put("expected_target_revision", value) }
            }
        }))
        request.entryId?.let { put("entry_id", it) }
        request.expectedEntryRevision?.let { put("expected_entry_revision", it) }
        put("member_patches", JsonArray(request.memberPatches.map {
            buildJsonObject { put("transaction_id", it.transactionId); put("patch", it.patch) }
        }))
        put("relation_additions", JsonArray(request.relationAdditions))
    }

    private fun sameContext(expected: Principal, actual: Principal) {
        if (expected.workspaceId != actual.workspaceId || expected.userId != actual.userId) throw AccessFailure(true)
    }
}

internal object ReceiptRepositoryView {
    fun strip(receipt: JsonObject): JsonObject = JsonObject(receipt.filterKeys {
        it !in setOf("request_digest", "session_digest", "edit_context_digest")
    })
}
