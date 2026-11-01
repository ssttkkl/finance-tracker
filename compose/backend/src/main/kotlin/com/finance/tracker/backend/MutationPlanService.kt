package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.time.Instant
import kotlinx.serialization.json.*

internal class MutationPlanFieldFailure(val code: String, val fields: List<String>, var commitState: String? = null) : RuntimeException(code)

internal fun interface CashMutationPlanBuilder {
    fun build(context: CashSqlContext, request: MutationPlanRequest, previous: JsonObject?): JsonObject
}

class MutationPlanService internal constructor(
    private val database: Database,
    private val now: () -> Instant = Instant::now,
    private val staging: ParsedImportStore = InMemoryParsedImportStore(
        ttlSeconds = MutationPlanStore.ttlFromEnvironment(System.getenv("FT_IMPORT_STAGING_TTL_SECONDS")), clock = now),
    private val beforeCommitWrite: () -> Unit = {},
    private val beforeCommitWorkspaceLock: () -> Unit = {},
    private val afterCommitStep: (String) -> Unit = {},
    private val planBuilder: CashMutationPlanBuilder = CashMutationPlanBuilder { context, request, previous ->
        MutationPlanBuilder(context).build(request.transactionId.toLong(), request, previous)
    },
) {
    private val reads = ReadServices(database, now)
    private val store = MutationPlanStore(staging = staging)

    fun preview(token: String?, body: JsonObject, requestPrincipal: Principal? = null): JsonObject {
        val request = MutationPlanRequest.parse(body)
        val result = database.read { connection ->
            val principal = planContext(connection, token, write = true)
            if (requestPrincipal != null) sameContext(requestPrincipal, principal)
            val context = if (request.metadata.editContextId != null) {
                val current = currentStored(connection, request.metadata.editContextId, principal)
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
                planBuilder.build(CashSqlContext(connection, principal.workspaceId!!, database.sqlite, now), request, context?.plan)
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
        val current = context ?: store.create(workspace, principal.userId, request.transactionId)
        val contextId = current.id
        val previous = current.plan
        val planRevision = previous?.get("plan_revision")?.jsonPrimitive?.longOrNull?.plus(1) ?: 1
        val plan = JsonObject(built + mapOf(
            "plan_id" to JsonPrimitive(store.planId(contextId)),
            "edit_context_id" to JsonPrimitive(contextId),
            "plan_revision" to JsonPrimitive(planRevision),
            "context_revision" to sourceJson(current.revision + java.math.BigInteger.ONE),
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
        read(token, requestPrincipal) { connection, principal ->
            val id = MutationPlanStore.contextId(planId)
            val current = currentStored(connection, id, principal)
            MutationPlanView.view(checkedPlan(current, planId, null))
        }

    fun currentPlan(token: String?, editContextId: String?, requestPrincipal: Principal? = null): JsonObject =
        read(token, requestPrincipal) { connection, principal ->
            if (editContextId == null || !editContextId.matches(Regex("[A-Za-z0-9_-]{43}")))
                throw MutationPlanFailure("plan_expired")
            val current = currentStored(connection, editContextId, principal)
            MutationPlanView.view(checkedPlan(current, null, null))
        }

    internal fun cancel(token: String?, editContextId: String, expectedPlanRevision: Long) {
        try {
            database.read { connection ->
                val principal = planContext(connection, token, write = true)
                val current = currentStored(connection, editContextId, principal)
                if (expectedPlanRevision < 1) throw MutationPlanFailure("plan_changed")
                checkedPlan(current, null, expectedPlanRevision)
                store.cancel(editContextId, principal.workspaceId!!, principal.userId, current.revision)
            }
        } catch (_: MutationPlanStore.RevisionConflict) { throw MutationPlanFailure("plan_changed") }
        catch (_: MutationPlanStore.Closed) { throw MutationPlanFailure("plan_changed") }
    }

    internal fun currentForCommit(connection: java.sql.Connection, principal: Principal, planId: String?,
        expectedPlanRevision: Any?, beforeBodyRead: () -> Unit = {}): MutationPlanStore.Context {
        val current = currentStored(connection, MutationPlanStore.contextId(planId), principal, beforeBodyRead)
        val revision = strictCashInteger(expectedPlanRevision)?.takeIf {
            it.signum() > 0 && it <= java.math.BigInteger.valueOf(Long.MAX_VALUE)
        }?.toLong() ?: throw MutationPlanFailure("plan_changed")
        checkedPlan(current, planId, revision)
        return current
    }

    fun items(token: String?, planId: String?, cursor: String?, limit: Int, confirmationStatus: String?,
        requestPrincipal: Principal? = null): JsonObject = read(token, requestPrincipal) { connection, principal ->
        val current = currentStored(connection, MutationPlanStore.contextId(planId), principal)
        MutationPlanView.page(checkedPlan(current, planId, null), "items", cursor, limit, confirmationStatus)
    }

    fun item(token: String?, planId: String?, previewItemId: String?, requestPrincipal: Principal? = null): JsonObject =
        read(token, requestPrincipal) { connection, principal ->
            val current = currentStored(connection, MutationPlanStore.contextId(planId), principal)
            MutationPlanView.item(checkedPlan(current, planId, null), previewItemId)
        }

    fun commit(token: String?, planId: String?, expectedPlanRevision: Long, idempotencyKey: String?,
        requestPrincipal: Principal? = null): JsonObject {
        val boundary = CashImportCommitBoundary(afterCommitStep)
        try { return commitGuarded(token, planId, expectedPlanRevision, idempotencyKey, requestPrincipal, boundary) }
        catch (failure: MutationPlanFailure) { if (boundary.commitState == "not_committed") failure.commitState = boundary.commitState; throw failure }
        catch (failure: MutationPlanFieldFailure) { if (boundary.commitState == "not_committed") failure.commitState = boundary.commitState; throw failure }
        catch (failure: CashPolicyError) { throw MutationPlanFieldFailure(failure.code, failure.fields, boundary.commitState.takeIf { it == "not_committed" }) }
        catch (failure: ReceiptConflict) { if (boundary.commitState == "not_committed") failure.commitState = boundary.commitState; throw failure }
        catch (failure: AccessFailure) { throw failure }
        catch (failure: Exception) { throw CashImportCommitFailure(boundary.commitState, boundary.diagnostic(), failure) }
    }
    private fun commitGuarded(token: String?, planId: String?, expectedPlanRevision: Long, idempotencyKey: String?,
        requestPrincipal: Principal?, boundary: CashImportCommitBoundary): JsonObject {
        val key = idempotencyKey?.takeIf { it.matches(Regex("[A-Za-z0-9_:.~-]{1,255}")) }
            ?: throw MutationPlanFailure("idempotency_key_required")
        if (expectedPlanRevision < 1) throw MutationPlanFailure("plan_input_invalid")
        val principal = reads.currentContext(token)
        if (requestPrincipal != null) sameContext(requestPrincipal, principal)
        var cleanup: MutationPlanStore.Context? = null
        beforeCommitWrite()
        val receipt = database.writeGuarded(boundary::committing) { connection ->
            val workspace = principal.workspaceId ?: throw AccessFailure(true)
            beforeCommitWorkspaceLock()
            if (!connection.lockWorkspace(database.sqlite, workspace)) throw AccessFailure(true)
            val current = reads.lockedContext(connection, token, workspace, write = true)
            sameContext(principal, current)
            if (requestPrincipal != null) sameContext(requestPrincipal, current)
            val repository = ReceiptRepository(connection, workspace, database.sqlite, now)
            val digest = cashSourceDigest(JsonPrimitive("cash-mutation-commit-v1"), JsonPrimitive(planId),
                JsonPrimitive(expectedPlanRevision))
            repository.replay(current.userId, key, digest, "mutation", planId)?.let { return@writeGuarded it }
            val contextId = MutationPlanStore.contextId(planId)
            val contextDigest = tokenDigest(contextId)
            val stored = currentForCommit(connection, current, planId, expectedPlanRevision, boundary::noPriorResult)
            val plan = stored.plan!!
            if (plan["can_commit"]?.jsonPrimitive?.booleanOrNull != true) throw MutationPlanFailure("plan_input_invalid")
            val input = stored.previewInput ?: throw MutationPlanFailure("plan_changed")
            val request = MutationPlanRequest.fromStored(input)
            val transaction = request.transactionId.toLong()
            val expectedRevision = request.expectedRevision
            val sql = connection.jooq(database.sqlite)
            val rowRevision = sql.select(CASH_TRANSACTIONS.ROW_REVISION).from(CASH_TRANSACTIONS)
                .where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(workspace)).and(CASH_TRANSACTIONS.ID.eq(transaction))
                .and(CASH_TRANSACTIONS.DELETED_AT.isNull).fetchOne(CASH_TRANSACTIONS.ROW_REVISION)
                ?: throw MutationPlanFailure("record_not_found")
            if (rowRevision != expectedRevision) throw MutationPlanFailure("record_changed")
            val fresh = MutationPlanBuilder(CashSqlContext(connection, workspace, database.sqlite, now))
                .build(transaction, request, plan)
            MutationPlanRevalidation.validate(plan, fresh)
            val context = CashSqlContext(connection, workspace, database.sqlite, now)
            val result = CurrentCashMaintenanceCommitWriter(context, current.userId, boundary).apply(plan, fresh, request)
            val created = repository.create(current.userId, key, digest, "mutation", result.summary, result.results,
                planId = planId, editContextDigest = contextDigest)
            boundary.done("receipt")
            sameContext(principal, reads.lockedContext(connection, token, workspace, write = true))
            cleanup = stored
            created
        }
        cleanup?.let { context ->
            try { store.cancel(context.id, context.workspaceId, context.userId, context.revision) }
            catch (_: Exception) { /* The committed receipt fences writes; fixed TTL clears any retained body. */ }
        }
        return ReceiptRepositoryView.strip(receipt)
    }

    fun resolve(token: String?, planId: String?, idempotencyKey: String?, requestPrincipal: Principal? = null): JsonObject {
        val key = idempotencyKey?.takeIf { it.matches(Regex("[A-Za-z0-9_:.~-]{1,255}")) }
            ?: throw MutationPlanFailure("idempotency_key_required")
        val contextId = MutationPlanStore.contextId(planId)
        val principal = reads.currentContext(token)
        if (requestPrincipal != null) sameContext(requestPrincipal, principal)
        return try {
            database.write { connection ->
                val current = planContext(connection, token, write = true)
                if (requestPrincipal != null) sameContext(requestPrincipal, current)
                val workspace = current.workspaceId ?: throw AccessFailure(true)
                val repository = ReceiptRepository(connection, workspace, database.sqlite, now)
                val byKey = repository.byKey(current.userId, key)
                if (byKey != null && (byKey["operation_kind"]?.jsonPrimitive?.contentOrNull != "mutation" ||
                        byKey["plan_id"]?.jsonPrimitive?.contentOrNull != planId)) {
                    throw ReceiptConflict("idempotency_conflict", byKey.getValue("receipt_id").jsonPrimitive.content)
                }
                val receipt = byKey ?: repository.forContext(current.userId, editContextDigest = tokenDigest(contextId))
                if (receipt != null) return@write buildJsonObject {
                    put("status", "committed"); put("receipt", ReceiptRepositoryView.strip(receipt))
                }
                val stored = try { store.get(contextId, workspace, current.userId) }
                    catch (_: MutationPlanStore.Expired) { return@write buildJsonObject { put("status", "not_committed") } }
                    catch (_: MutationPlanStore.NotFound) { return@write buildJsonObject { put("status", "not_committed") } }
                if (stored.cancelled || stored.plan == null ||
                    stored.plan["plan_id"]?.jsonPrimitive?.contentOrNull != planId)
                    buildJsonObject { put("status", "not_committed") }
                else buildJsonObject { put("status", "retry_original") }
            }
        } catch (failure: StorageFailure) {
            if (failure.code == "storage.busy") buildJsonObject { put("status", "pending") } else throw failure
        }
    }

    private fun <T> read(token: String?, requestPrincipal: Principal?, block: (java.sql.Connection, Principal) -> T): T {
        val principal = reads.currentContext(token)
        if (requestPrincipal != null) sameContext(requestPrincipal, principal)
        val result = database.read { connection ->
            val current = planContext(connection, token, write = false)
            if (requestPrincipal != null) sameContext(requestPrincipal, current)
            block(connection, current)
        }
        val after = reads.currentContext(token)
        sameContext(principal, after)
        return result
    }

    private fun currentStored(connection: java.sql.Connection, id: String, principal: Principal,
        beforeBodyRead: () -> Unit = {}): MutationPlanStore.Context {
        if (!id.matches(Regex("[A-Za-z0-9_-]{43}"))) throw MutationPlanStore.NotFound()
        ReceiptRepository(connection, principal.workspaceId!!, database.sqlite, now)
            .forContext(principal.userId, editContextDigest = tokenDigest(id))?.let {
                throw ReceiptConflict("edit_context_committed", it.getValue("receipt_id").jsonPrimitive.content)
            }
        beforeBodyRead()
        return store.get(id, principal.workspaceId, principal.userId)
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
        if (plan["context_revision"]?.jsonPrimitive?.contentOrNull?.toBigIntegerOrNull() != current.revision) throw MutationPlanFailure("plan_changed")
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
