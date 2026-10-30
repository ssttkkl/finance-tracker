package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.time.Instant
import kotlinx.serialization.json.*

internal class MutationPlanFieldFailure(val code: String, val fields: List<String>) : RuntimeException(code)

class MutationPlanService internal constructor(
    private val database: Database,
    private val now: () -> Instant = Instant::now,
    private val staging: ParsedImportStore = InMemoryParsedImportStore(
        ttlSeconds = MutationPlanStore.ttlFromEnvironment(System.getenv("FT_IMPORT_STAGING_TTL_SECONDS")), clock = now),
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
        val key = idempotencyKey?.takeIf { it.matches(Regex("[A-Za-z0-9_:.~-]{1,255}")) }
            ?: throw MutationPlanFailure("idempotency_key_required")
        if (expectedPlanRevision < 1) throw MutationPlanFailure("plan_input_invalid")
        val principal = reads.currentContext(token)
        if (requestPrincipal != null) sameContext(requestPrincipal, principal)
        var cleanup: MutationPlanStore.Context? = null
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
            val action = operation.getValue("planned_action").jsonPrimitive.content
            val outcome = when (action) {
                "update_posted" -> {
                    val entryEdit = operation["entry_edit"]?.takeIf { it != JsonNull }?.jsonObject
                        ?: throw MutationPlanFailure("plan_input_invalid")
                    val patch = entryEdit.getValue("patch").jsonObject.mapValues { cashSourceValue(it.value) }
                    CashEntryRepository(connection, workspace, database.sqlite).patch(transaction, patch,
                        entryEdit.getValue("revision").jsonPrimitive.long)
                    "updated_posted"
                }
                "finalize_draft" -> {
                    val context = CashSqlContext(connection, workspace, database.sqlite, now)
                    saveOperationRow(connection, workspace, transaction, operation, context, balance = true)
                    "finalized_draft"
                }
                "update_draft" -> {
                    val context = CashSqlContext(connection, workspace, database.sqlite, now)
                    saveOperationRow(connection, workspace, transaction, operation, context, balance = false)
                    "saved_draft"
                }
                "duplicate" -> {
                    saveIssues(connection, workspace, transaction, operation)
                    "duplicate"
                }
                else -> throw MutationPlanFailure("plan_input_invalid")
            }
            val context = CashSqlContext(connection, workspace, database.sqlite, now)
            val projectionState = CashProjectionRepository(context).requireReadyStateLock()
            val maintained = CashProjectionService.maintainInSession(context, setOf(transaction), projectionState)
            val version = maintained["projection_version"]?.jsonPrimitive?.longOrNull ?: projectionState.projectionVersion
            val references = CashProjectionRepository(context).currentReferences(setOf(transaction), projectionState.datasetId)
            val pendingItems = references.values.filter {
                (it["attention_count"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L) > 0
            }.mapNotNull { it["projection_id"]?.jsonPrimitive?.contentOrNull }.distinct().size
            val resolvedIssues = operation["resolved_issue_ids"]?.jsonArray?.size ?: 0
            val summary = buildJsonObject {
                for (field in listOf("updated_posted", "finalized_draft", "saved_draft", "merged_draft", "duplicate",
                    "skipped", "relations_confirmed", "relations_deferred", "relations_ignored", "relations_removed",
                    "relations_failed", "resolved_issues")) put(field, 0)
                put(outcome, 1)
                if (resolvedIssues > 0) put("resolved_issues", resolvedIssues)
            }
            val attention = attentionCount(connection, workspace, transaction)
            val resultSummary = buildJsonObject {
                put("status", if (outcome != "duplicate" || resolvedIssues > 0) "completed" else "no_changes"); put("summary", summary)
                put("retained_transaction_id", transaction.toString())
                put("projection_version", version); put("needs_attention_count", attention); put("pending_item_count", pendingItems)
            }
            val results = JsonArray(listOf(buildJsonObject {
                put("result_kind", "row"); put("item_ref", transaction.toString())
                put("outcome", outcome); put("operation_ref", "op_1")
                put("transaction_id", transaction.toString())
            }))
            val created = repository.create(current.userId, key, digest, "mutation", resultSummary, results,
                planId = planId, editContextDigest = contextDigest)
            cleanup = stored
            created
        }
        cleanup?.let { context ->
            try { store.cancel(context.id, context.workspaceId, context.userId, context.revision) }
            catch (_: Exception) { /* The committed receipt fences writes; fixed TTL clears any retained body. */ }
        }
        val after = reads.currentContext(token)
        sameContext(principal, after)
        return ReceiptRepositoryView.strip(receipt)
    }

    private fun saveIssues(connection: java.sql.Connection, workspace: String, transaction: Long, operation: JsonObject) {
        val sql = connection.jooq(database.sqlite)
        val issue = CASH_TRANSACTION_ISSUES
        val existing = sql.select(issue.ID, issue.ISSUE_KEY, issue.CODE, issue.FIELD_PATH).from(issue)
            .where(issue.WORKSPACE_ID.eq(workspace)).and(issue.TRANSACTION_ID.eq(transaction)).fetch()
        val retained = (operation["retained_issue_ids"]?.jsonArray ?: JsonArray(emptyList()))
            .map { it.jsonPrimitive.content }.toMutableSet()
        for (raw in operation["issues"]?.jsonArray ?: JsonArray(emptyList())) {
            val value = raw.jsonObject
            val code = value.getValue("code").jsonPrimitive.content
            val fieldPath = value["field_path"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull
            val old = existing.firstOrNull { it[issue.CODE] == code && it[issue.FIELD_PATH] == fieldPath }
            if (old != null) {
                retained += old[issue.ID]!!.toString()
                sql.update(issue).set(issue.REVISION, Math.addExact(old[issue.REVISION]!!, 1L)).set(issue.UPDATED_AT, now())
                    .where(issue.WORKSPACE_ID.eq(workspace)).and(issue.ID.eq(old[issue.ID]!!)).execute()
            } else {
                val key = "import:" + cashSourceDigest(JsonPrimitive(code), fieldPath?.let(::JsonPrimitive))
                sql.insertInto(issue).set(issue.WORKSPACE_ID, workspace).set(issue.TRANSACTION_ID, transaction)
                    .set(issue.ISSUE_KEY, key).set(issue.CODE, code).set(issue.FIELD_PATH, fieldPath)
                    .set(issue.PENDING_FIELDS, JsonObject(emptyMap())).setNull(issue.PENDING_SOURCE_PAYLOAD)
                    .set(issue.ORIGIN, JsonObject(emptyMap())).set(issue.REVISION, 1L)
                    .set(issue.CREATED_AT, now()).set(issue.UPDATED_AT, now()).execute()
            }
        }
        for (row in existing) if (row[issue.ID]!!.toString() !in retained) sql.deleteFrom(issue)
            .where(issue.WORKSPACE_ID.eq(workspace)).and(issue.ID.eq(row[issue.ID]!!)).execute()
    }

    private fun attentionCount(connection: java.sql.Connection, workspace: String, transaction: Long): Int {
        val sql = connection.jooq(database.sqlite)
        val issue = CASH_TRANSACTION_ISSUES
        val relation = TRANSACTION_RELATIONS
        val hasIssue = sql.fetchExists(issue, issue.WORKSPACE_ID.eq(workspace).and(issue.TRANSACTION_ID.eq(transaction)))
        val draftRelation = sql.fetchExists(relation, relation.WORKSPACE_ID.eq(workspace).and(relation.ACTIVE.isTrue)
            .and(relation.STATE.eq("draft")).and(relation.PRIMARY_TRANSACTION_ID.eq(transaction)
                .or(relation.SECONDARY_TRANSACTION_ID.eq(transaction))))
        return if (hasIssue || draftRelation) 1 else 0
    }

    private fun saveOperationRow(connection: java.sql.Connection, workspace: String, transaction: Long,
        operation: JsonObject, context: CashSqlContext, balance: Boolean) {
        val values = operation.getValue("values").jsonObject
        val current = operation.getValue("current").jsonObject
        val manual = operation.getValue("manual_fields").jsonArray.map { it.jsonPrimitive.content }
        val sql = connection.jooq(database.sqlite)
        val overrides = linkedMapOf<String, JsonElement>()
        (current["manual_overrides"] as? JsonObject)?.forEach { (key, value) -> overrides[key] = value }
        manual.forEach { overrides[it] = JsonPrimitive(true) }
        val updated = sql.update(CASH_TRANSACTIONS)
            .set(CASH_TRANSACTIONS.NOTE, values.getValue("note").jsonPrimitive.content)
            .set(CASH_TRANSACTIONS.COUNTERPARTY, values.getValue("counterparty").jsonPrimitive.content)
            .set(CASH_TRANSACTIONS.COUNTERPARTY_ACCOUNT, values.getValue("counterparty_account").jsonPrimitive.content)
            .set(CASH_TRANSACTIONS.COUNTERPARTY_ACCOUNT_ATTRS, values["counterparty_account_attrs"] ?: JsonArray(emptyList()))
            .set(CASH_TRANSACTIONS.CATEGORY_ID, values["category_id"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull)
            .set(CASH_TRANSACTIONS.RECORD_TYPE, values["record_type"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull)
            .set(CASH_TRANSACTIONS.RECORD_SUBTYPE, values["record_subtype"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull)
            .set(CASH_TRANSACTIONS.SOURCE_PAYLOAD, values["source_payload"] ?: JsonNull)
            .set(CASH_TRANSACTIONS.AMOUNT, values["amount"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull?.let(::CashStoredDecimal))
            .set(CASH_TRANSACTIONS.CURRENCY, values["currency"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull)
            .set(CASH_TRANSACTIONS.OCCURRED_AT, values["occurred_at"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull?.let(java.time.Instant::parse))
            .set(CASH_TRANSACTIONS.ACCOUNT_ID, values["account_id"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull?.toLong())
            .set(CASH_TRANSACTIONS.CASH_GRANULARITY, values.getValue("cash_granularity").jsonPrimitive.content)
            .set(CASH_TRANSACTIONS.STATE, values.getValue("state").jsonPrimitive.content)
            .set(CASH_TRANSACTIONS.SOURCE_TYPE, values["source_type"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull)
            .set(CASH_TRANSACTIONS.RECORD_ID, values.getValue("record_id").jsonPrimitive.content)
            .set(CASH_TRANSACTIONS.RECORD_ORIGIN, values.getValue("record_origin").jsonPrimitive.content)
            .set(CASH_TRANSACTIONS.MANUAL_TRANSFER_ID, values["manual_transfer_id"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull)
            .set(CASH_TRANSACTIONS.ROW_REVISION, Math.addExact(operation.getValue("expected_revision").jsonPrimitive.long, 1L))
            .set(CASH_TRANSACTIONS.MANUAL_OVERRIDES, JsonObject(overrides))
            .where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(workspace)).and(CASH_TRANSACTIONS.ID.eq(transaction)).execute()
        check(updated == 1)
        val existing = sql.select(CASH_TRANSACTION_COMPONENTS.ID, CASH_TRANSACTION_COMPONENTS.ORDINAL)
            .from(CASH_TRANSACTION_COMPONENTS).where(CASH_TRANSACTION_COMPONENTS.WORKSPACE_ID.eq(workspace))
            .and(CASH_TRANSACTION_COMPONENTS.CASH_TRANSACTION_ID.eq(transaction)).fetch()
        val wanted = mutableSetOf<Long>()
        for (raw in values["components"]?.jsonArray ?: JsonArray(emptyList())) {
            val part = raw.jsonObject
            val identifier = part["component_id"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull?.toLong()
            val accountId = part["account_id"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull?.toLong()
            val amount = part["amount"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull?.let(::CashStoredDecimal)
            val currency = part["currency"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull
            val ordinal = part.getValue("ordinal").jsonPrimitive.content.toLong()
            if (identifier != null) {
                wanted += identifier
                sql.update(CASH_TRANSACTION_COMPONENTS).set(CASH_TRANSACTION_COMPONENTS.ACCOUNT_ID, accountId)
                    .set(CASH_TRANSACTION_COMPONENTS.AMOUNT, amount).set(CASH_TRANSACTION_COMPONENTS.CURRENCY, currency)
                    .set(CASH_TRANSACTION_COMPONENTS.ORDINAL, ordinal)
                    .where(CASH_TRANSACTION_COMPONENTS.WORKSPACE_ID.eq(workspace))
                    .and(CASH_TRANSACTION_COMPONENTS.ID.eq(identifier)).execute()
            } else {
                sql.insertInto(CASH_TRANSACTION_COMPONENTS).set(CASH_TRANSACTION_COMPONENTS.WORKSPACE_ID, workspace)
                    .set(CASH_TRANSACTION_COMPONENTS.CASH_TRANSACTION_ID, transaction)
                    .set(CASH_TRANSACTION_COMPONENTS.ACCOUNT_ID, accountId).set(CASH_TRANSACTION_COMPONENTS.AMOUNT, amount)
                    .set(CASH_TRANSACTION_COMPONENTS.CURRENCY, currency).set(CASH_TRANSACTION_COMPONENTS.ORDINAL, ordinal)
                    .set(CASH_TRANSACTION_COMPONENTS.LABEL, "").set(CASH_TRANSACTION_COMPONENTS.SOURCE_KEY, "")
                    .set(CASH_TRANSACTION_COMPONENTS.METADATA_JSON, JsonObject(emptyMap()))
                    .set(CASH_TRANSACTION_COMPONENTS.CREATED_AT, now()).execute()
            }
        }
        for (row in existing) if (row.value1() !in wanted) sql.deleteFrom(CASH_TRANSACTION_COMPONENTS)
            .where(CASH_TRANSACTION_COMPONENTS.WORKSPACE_ID.eq(workspace))
            .and(CASH_TRANSACTION_COMPONENTS.ID.eq(row.value1())).execute()
        saveIssues(connection, workspace, transaction, operation)
        if (!balance) return
        val account = ACCOUNTS
        val accountRow = (values["components"]?.jsonArray ?: JsonArray(emptyList())).firstOrNull()?.jsonObject
            ?.get("account_id")?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull?.toLong()?.let { id ->
                sql.select(account.NAME, account.TYPE).from(account).where(account.WORKSPACE_ID.eq(workspace))
                    .and(account.ID.eq(id)).fetchOne()
            }
        val componentsJson = JsonArray((values["components"]?.jsonArray ?: JsonArray(emptyList())).map { raw ->
            val part = raw.jsonObject
            buildJsonObject {
                part["account_id"]?.let { put("account_id", it) }
                put("account_name", accountRow?.value1()?.let(::JsonPrimitive) ?: JsonNull)
                put("account_type", accountRow?.value2()?.let(::JsonPrimitive) ?: JsonNull)
                part["amount"]?.let { put("amount", it) }
                part["currency"]?.let { put("currency", it) }
            }
        })
        val balanceRow = buildJsonObject {
            put("record_origin", values.getValue("record_origin"))
            put("record_type", values["record_type"] ?: JsonNull)
            put("state", values.getValue("state"))
            put("components", componentsJson)
        }
        val document = context.snapshot.load(lock = true)
        CashBalanceService(context).applyComponents(document, balanceRow, 1)
        document.value = JsonObject(document.value + ("updated_at" to (values["occurred_at"] ?: JsonNull)))
        context.snapshot.save(document)
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

    private fun currentStored(connection: java.sql.Connection, id: String, principal: Principal): MutationPlanStore.Context {
        if (!id.matches(Regex("[A-Za-z0-9_-]{43}"))) throw MutationPlanStore.NotFound()
        ReceiptRepository(connection, principal.workspaceId!!, database.sqlite, now)
            .forContext(principal.userId, editContextDigest = tokenDigest(id))?.let {
                throw ReceiptConflict("edit_context_committed", it.getValue("receipt_id").jsonPrimitive.content)
            }
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
