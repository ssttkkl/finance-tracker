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
                throw MutationPlanFieldFailure(failure.code, failure.fields)
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
        val contextId = context?.id ?: store.create(principal.workspaceId!!, principal.userId, request.transactionId).id
        val current = store.get(contextId, principal.workspaceId!!, principal.userId)
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
            store.replace(contextId, principal.workspaceId!!, principal.userId, current.revision, plan,
                requestJson(request))
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
