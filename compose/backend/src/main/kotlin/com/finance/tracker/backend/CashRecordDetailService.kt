package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.time.Instant
import kotlinx.serialization.json.*
import org.jooq.impl.DSL

/** 当前单条流水详情的稳定错误；具体状态由维护路由按原合同映射。 */
internal class CashRecordDetailFailure(val code: String) : RuntimeException(code)

/** 存储或内部故障停止读取，不返回部分详情，也不修复当前状态。 */
internal class CashRecordReadUnavailable(cause: Throwable) : RuntimeException("import_unavailable", cause)

internal data class CashRecordDetailResult(val editable: Boolean, val json: JsonObject)

class CashRecordDetailService(
    private val database: Database,
    private val now: () -> Instant = Instant::now,
    private val beforeReturn: () -> Unit = {},
) {
    private val reads = ReadServices(database, now)

    fun detail(token: String?, identifier: String?, requestPrincipal: Principal? = null): JsonObject {
        val principal = reads.currentContext(token)
        if (requestPrincipal != null) sameContext(requestPrincipal, principal)
        val result = try {
            database.read { connection ->
                if (!database.sqlite) connection.createStatement().use { it.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ") }
                CashRecordDetailRepository(CashSqlContext(connection, principal.workspaceId!!, database.sqlite, now), principal).read(identifier)
            }
        } catch (failure: AccessFailure) {
            throw failure
        } catch (failure: CashRecordDetailFailure) {
            throw failure
        } catch (failure: StorageFailure) {
            throw failure
        } catch (failure: CashRecordReadUnavailable) {
            throw failure
        } catch (failure: Exception) {
            throw CashRecordReadUnavailable(failure)
        }
        beforeReturn()
        val after = reads.currentContext(token)
        sameContext(principal, after)
        return if (result.editable && after.role?.canWrite != true) withoutEditCapabilities(result.json) else result.json
    }

    private fun sameContext(expected: Principal, actual: Principal) {
        if (expected.workspaceId != actual.workspaceId || expected.userId != actual.userId) throw AccessFailure(true)
    }

    /** 返回前已确认角色不再是编辑者时，不保留旧编辑能力。 */
    private fun withoutEditCapabilities(json: JsonObject): JsonObject = buildJsonObject {
        for ((key, value) in json) when (key) {
            "editable_fields" -> put(key, JsonArray(emptyList()))
            "entry" -> put(key, (value as? JsonObject)?.let { entry ->
                JsonObject(entry + ("editable_fields" to JsonArray(emptyList())))
            } ?: value)
            "issues" -> put(key, JsonArray((value as? JsonArray ?: JsonArray(emptyList())).map { item ->
                JsonObject(item.jsonObject + ("allowed_resolutions" to JsonArray(emptyList())))
            }))
            else -> put(key, value)
        }
    }
}

private data class DetailRecord(
    val id: Long, val state: String, val recordOrigin: String, val rowRevision: Long,
    val sourceType: String?, val recordId: String, val manualTransferId: String?,
    val counterparty: String, val counterpartyAccount: String, val attrs: JsonElement?,
    val note: String, val categoryId: String?, val recordType: String?, val recordSubtype: String?,
    val sourcePayload: JsonElement?, val amount: CashStoredDecimal?, val currency: String?,
    val occurredAt: Instant?, val accountId: Long?, val granularity: String,
    val manualOverrides: JsonElement?, val relationMetadata: JsonElement?,
)

private data class DetailComponent(
    val id: Long, val ordinal: Long, val accountId: Long?, val amount: CashStoredDecimal?, val currency: String?,
)

private data class DetailIssue(
    val id: Long, val revision: Long, val code: String, val fieldPath: String?, val issueKey: String,
    val pendingFields: JsonElement?, val pendingPayload: JsonElement?, val origin: JsonElement?,
)

private data class DetailRelation(
    val id: Long, val kind: String, val subtype: String, val state: String, val active: Boolean,
    val rowRevision: Long, val decisionGroup: String?, val primary: Long, val secondary: Long?,
)

private data class DetailEntry(
    val id: String, val revision: Long, val categoryId: String?, val note: String, val counterparty: String,
    val counterpartyAccount: String, val attrs: JsonElement?, val manualFields: List<String>, val memberCount: Long,
)

private data class DetailCandidate(
    val transactionId: Long, val rowRevision: Long, val state: String, val occurredAt: Instant?,
    val amount: CashStoredDecimal?, val currency: String?, val counterparty: String, val note: String,
    val accounts: List<String>,
)

private class DetailFields(
    val values: MutableMap<String, Any?>,
    val components: List<Map<String, Any?>>,
)

internal class CashRecordDetailRepository(
    private val context: CashSqlContext,
    private val principal: Principal,
) {
    private val sql get() = context.sql
    private val workspace get() = context.workspace
    private val commonCashFields = listOf("category_id", "counterparty", "counterparty_account", "counterparty_account_attrs", "note")
    private val commonCashFieldSet = commonCashFields.toSet()

    fun read(identifier: String?): CashRecordDetailResult {
        val id = recordIdentifier(identifier)
        val role = sql.select(WORKSPACE_MEMBERSHIPS.ROLE).from(WORKSPACE_MEMBERSHIPS)
            .where(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID.eq(workspace)).and(WORKSPACE_MEMBERSHIPS.USER_ID.eq(principal.userId))
            .fetchOne(WORKSPACE_MEMBERSHIPS.ROLE) ?: throw CashRecordDetailFailure("workspace_forbidden")
        val editable = role == "admin" || role == "editor"
        val record = record(id)
        val components = components(id)
        val fields = fields(record, components)
        val issues = issues(id)
        val sourceIssues = issues.map {
            CashSourceIssue(it.id, it.revision, it.code, it.fieldPath, it.pendingFields, it.pendingPayload, it.origin, it.issueKey)
        }
        val candidates = identityCandidates(issues)
        val relations = relations(id)
        val pending = issues.isNotEmpty() || relations.any {
            it.active && it.state == "draft" && (it.primary == id || it.secondary == id)
        }
        val identityResolutions = if (editable && issues.any { it.code == "source_identity_ambiguous" }) {
            availableIdentityResolutions(record, candidates.values.flatten().map { it.transactionId }.toSet())
        } else emptyList()
        val entry = entry(id)
        val projectionRef = projectionReference(id)
        val state = record.state
        val editableFields = if (!editable) emptyList() else {
            val available = when {
                state == "draft" -> editableCashRecordFields
                record.recordOrigin == "manual" -> (mutableCashFields + financialCashFields) - setOf("source_payload", "account_name")
                else -> emptySet()
            }
            if (state == "posted" && entry != null && entry.memberCount > 1L) emptyList()
            else available.sortedWith(cashRelationStringOrder)
        }
        val entryFields = if (record.recordOrigin == "manual" ||
            record.recordOrigin == "manual_transfer" && entry != null && entry.memberCount == 2L)
            (commonCashFieldSet + financialCashFields + setOf("record_type", "record_subtype")) - "account_name"
        else commonCashFieldSet
        val json = buildJsonObject {
            put("record_origin", record.recordOrigin)
            put("entry", entry?.let { entryJson(record, it, if (editable) entryFields.sortedWith(cashRelationStringOrder) else emptyList()) } ?: JsonNull)
            put("transaction_id", record.id.toString())
            put("state", state)
            put("row_revision", record.rowRevision)
            put("confirmation_status", if (state == "draft" || pending) "pending" else "confirmed")
            put("fields", fieldsJson(record, components))
            put("editable_fields", JsonArray(editableFields.map(::JsonPrimitive)))
            put("issues", JsonArray(issues.map { issue ->
                issueJson(record, issue, sourceIssues, fields, editable, identityResolutions,
                    candidates[issue.id.toString()] ?: emptyList())
            }))
            put("relations", JsonArray(relations.map { relation ->
                buildJsonObject {
                    put("relation_id", relation.id.toString()); put("kind", relation.kind)
                    put("subtype", relation.subtype); put("state", relation.state); put("active", relation.active)
                    put("row_revision", relation.rowRevision)
                    put("decision_group_id", relation.decisionGroup?.let(::JsonPrimitive) ?: JsonNull)
                }
            }))
            put("projection_ref", projectionRef ?: JsonNull)
        }
        return CashRecordDetailResult(editable, json)
    }

    private fun recordIdentifier(value: String?): Long {
        if (value == null || value.length !in 1..19 || value.any { it !in '0'..'9' })
            throw CashRecordDetailFailure("record_not_found")
        val identifier = value.toLongOrNull() ?: throw CashRecordDetailFailure("record_not_found")
        if (identifier <= 0) throw CashRecordDetailFailure("record_not_found")
        return identifier
    }

    private fun record(id: Long): DetailRecord {
        val tx = CASH_TRANSACTIONS
        val row = sql.select(tx.ID, tx.STATE, tx.RECORD_ORIGIN, tx.ROW_REVISION, tx.SOURCE_TYPE, tx.RECORD_ID,
                tx.MANUAL_TRANSFER_ID, tx.COUNTERPARTY, tx.COUNTERPARTY_ACCOUNT, tx.COUNTERPARTY_ACCOUNT_ATTRS,
                tx.NOTE, tx.CATEGORY_ID, tx.RECORD_TYPE, tx.RECORD_SUBTYPE, tx.SOURCE_PAYLOAD, tx.AMOUNT,
                tx.CURRENCY, tx.OCCURRED_AT, tx.ACCOUNT_ID, tx.CASH_GRANULARITY, tx.MANUAL_OVERRIDES, tx.RELATION_METADATA)
            .from(tx).where(tx.WORKSPACE_ID.eq(workspace)).and(tx.ID.eq(id)).and(tx.DELETED_AT.isNull).fetchOne()
            ?: throw CashRecordDetailFailure("record_not_found")
        return DetailRecord(row[tx.ID]!!, row[tx.STATE]!!, row[tx.RECORD_ORIGIN]!!, row[tx.ROW_REVISION]!!,
            row[tx.SOURCE_TYPE], row[tx.RECORD_ID]!!, row[tx.MANUAL_TRANSFER_ID], row[tx.COUNTERPARTY]!!,
            row[tx.COUNTERPARTY_ACCOUNT]!!, row[tx.COUNTERPARTY_ACCOUNT_ATTRS], row[tx.NOTE]!!, row[tx.CATEGORY_ID],
            row[tx.RECORD_TYPE], row[tx.RECORD_SUBTYPE], row[tx.SOURCE_PAYLOAD], row[tx.AMOUNT], row[tx.CURRENCY],
            row[tx.OCCURRED_AT], row[tx.ACCOUNT_ID], row[tx.CASH_GRANULARITY]!!, row[tx.MANUAL_OVERRIDES],
            row[tx.RELATION_METADATA])
    }

    private fun components(id: Long): List<DetailComponent> {
        val part = CASH_TRANSACTION_COMPONENTS
        return sql.select(part.ID, part.ORDINAL, part.ACCOUNT_ID, part.AMOUNT, part.CURRENCY).from(part)
            .where(part.WORKSPACE_ID.eq(workspace)).and(part.CASH_TRANSACTION_ID.eq(id)).orderBy(part.ORDINAL)
            .fetch { DetailComponent(it[part.ID]!!, it[part.ORDINAL]!!, it[part.ACCOUNT_ID], it[part.AMOUNT], it[part.CURRENCY]) }
    }

    private fun fields(record: DetailRecord, components: List<DetailComponent>): DetailFields {
        val parts: List<Map<String, Any?>> = components.map { part ->
            linkedMapOf<String, Any?>(
                "component_id" to part.id.toString(), "ordinal" to part.ordinal.toInt(),
                "account_id" to part.accountId?.toString(),
                "amount" to part.amount?.let { value -> CashAmount.parseOrNull(value.text) ?: invalidCash() },
                "currency" to part.currency,
            )
        }
        val values = linkedMapOf<String, Any?>(
            "counterparty" to record.counterparty,
            "counterparty_account" to record.counterpartyAccount,
            "counterparty_account_attrs" to record.attrs?.let(::cashSourceValue),
            "note" to record.note,
            "category_id" to record.categoryId,
            "record_type" to record.recordType,
            "record_subtype" to record.recordSubtype,
            "source_payload" to record.sourcePayload?.let(::cashSourceValue),
            "amount" to record.amount?.let { value -> CashAmount.parseOrNull(value.text) ?: invalidCash() },
            "currency" to record.currency,
            "occurred_at" to record.occurredAt,
            "account_id" to record.accountId?.toString(),
            "cash_granularity" to record.granularity,
            "state" to record.state,
            "source_type" to record.sourceType,
            "record_id" to record.recordId,
            "record_origin" to record.recordOrigin,
            "manual_transfer_id" to record.manualTransferId,
            "components" to parts,
        )
        return DetailFields(values, parts)
    }

    private fun invalidCash(): Nothing = throw CashRecordReadUnavailable(IllegalStateException("invalid cash amount"))

    private fun fieldsJson(record: DetailRecord, components: List<DetailComponent>) = buildJsonObject {
        put("counterparty", record.counterparty)
        put("counterparty_account", record.counterpartyAccount)
        put("counterparty_account_attrs", record.attrs ?: JsonArray(emptyList()))
        put("note", record.note)
        put("category_id", record.categoryId?.let(::JsonPrimitive) ?: JsonNull)
        put("record_type", record.recordType?.let(::JsonPrimitive) ?: JsonNull)
        put("record_subtype", record.recordSubtype?.let(::JsonPrimitive) ?: JsonNull)
        put("source_payload", record.sourcePayload ?: JsonNull)
        put("amount", record.amount?.let { JsonPrimitive(fixedCashDecimal(it)) } ?: JsonNull)
        put("currency", record.currency?.let(::JsonPrimitive) ?: JsonNull)
        put("occurred_at", record.occurredAt?.let { JsonPrimitive(manualInstantText(it)) } ?: JsonNull)
        put("account_id", record.accountId?.toString()?.let(::JsonPrimitive) ?: JsonNull)
        put("cash_granularity", record.granularity)
        put("state", record.state)
        put("source_type", record.sourceType?.let(::JsonPrimitive) ?: JsonNull)
        put("record_id", record.recordId)
        put("record_origin", record.recordOrigin)
        put("manual_transfer_id", record.manualTransferId?.let(::JsonPrimitive) ?: JsonNull)
        put("components", JsonArray(components.map { part -> buildJsonObject {
            put("component_id", part.id.toString())
            put("ordinal", part.ordinal)
            put("account_id", part.accountId?.toString()?.let(::JsonPrimitive) ?: JsonNull)
            put("amount", part.amount?.let { JsonPrimitive(fixedCashDecimal(it)) } ?: JsonNull)
            put("currency", part.currency?.let(::JsonPrimitive) ?: JsonNull)
        } }))
    }

    private fun issues(id: Long): List<DetailIssue> {
        val issue = CASH_TRANSACTION_ISSUES
        return sql.select(issue.ID, issue.REVISION, issue.CODE, issue.FIELD_PATH, issue.ISSUE_KEY,
                issue.PENDING_FIELDS, issue.PENDING_SOURCE_PAYLOAD, issue.ORIGIN).from(issue)
            .where(issue.WORKSPACE_ID.eq(workspace)).and(issue.TRANSACTION_ID.eq(id)).orderBy(issue.ID)
            .fetch { DetailIssue(it[issue.ID]!!, it[issue.REVISION]!!, it[issue.CODE]!!, it[issue.FIELD_PATH],
                it[issue.ISSUE_KEY]!!, it[issue.PENDING_FIELDS], it[issue.PENDING_SOURCE_PAYLOAD], it[issue.ORIGIN]) }
    }

    /** 候选只读当前流水与账户值，不刷新保存的修订证明。 */
    private fun identityCandidates(issues: List<DetailIssue>): Map<String, List<DetailCandidate>> {
        val ambiguous = issues.filter { it.code == "source_identity_ambiguous" }.map { it.id }.sorted()
        if (ambiguous.isEmpty()) return emptyMap()
        val candidate = CASH_ISSUE_CANDIDATES.`as`("candidate")
        val parent = CASH_TRANSACTIONS.`as`("parent")
        val found = linkedMapOf<Long, DetailCandidate>()
        val groups = linkedMapOf<String, MutableList<Long>>()
        for (batch in ambiguous.chunked(500)) {
            sql.select(candidate.ISSUE_ID, parent.ID, parent.ROW_REVISION, parent.STATE, parent.OCCURRED_AT, parent.AMOUNT,
                    parent.CURRENCY, parent.COUNTERPARTY, parent.NOTE)
                .from(candidate).join(parent).on(parent.WORKSPACE_ID.eq(candidate.WORKSPACE_ID))
                .and(parent.ID.eq(candidate.CANDIDATE_TRANSACTION_ID))
                .where(candidate.WORKSPACE_ID.eq(workspace)).and(candidate.ISSUE_ID.`in`(batch))
                .and(parent.DELETED_AT.isNull).orderBy(candidate.ISSUE_ID, parent.ID).fetch().forEach { row ->
                    val identifier = row[parent.ID]!!
                    found[identifier] = DetailCandidate(identifier, row[parent.ROW_REVISION]!!, row[parent.STATE]!!,
                        row[parent.OCCURRED_AT], row[parent.AMOUNT], row[parent.CURRENCY], row[parent.COUNTERPARTY]!!,
                        row[parent.NOTE]!!, emptyList())
                    groups.getOrPut(row[candidate.ISSUE_ID]!!.toString()) { mutableListOf() } += identifier
                }
        }
        val accounts = linkedMapOf<Long, MutableList<String>>()
        if (found.isNotEmpty()) {
            val part = CASH_TRANSACTION_COMPONENTS
            val account = ACCOUNTS
            for (batch in found.keys.sorted().chunked(400)) {
                sql.select(part.CASH_TRANSACTION_ID, account.NAME).from(part)
                    .join(account).on(account.WORKSPACE_ID.eq(part.WORKSPACE_ID)).and(account.ID.eq(part.ACCOUNT_ID))
                    .where(part.WORKSPACE_ID.eq(workspace)).and(part.CASH_TRANSACTION_ID.`in`(batch))
                    .orderBy(part.CASH_TRANSACTION_ID, part.ORDINAL).fetch().forEach { row ->
                        val names = accounts.getOrPut(row[part.CASH_TRANSACTION_ID]!!) { mutableListOf() }
                        val name = row[account.NAME]!!
                        if (name !in names) names += name
                    }
            }
        }
        return groups.mapValues { (_, identifiers) -> identifiers.map { identifier ->
            found.getValue(identifier).copy(accounts = accounts[identifier] ?: emptyList())
        } }
    }

    private fun relations(id: Long): List<DetailRelation> {
        val relation = TRANSACTION_RELATIONS
        val candidate = CASH_RELATION_CANDIDATES
        val candidateRelations = DSL.select(candidate.RELATION_ID).from(candidate)
            .where(candidate.WORKSPACE_ID.eq(workspace)).and(candidate.CANDIDATE_TRANSACTION_ID.eq(id))
        return sql.select(relation.ID, relation.KIND, relation.SUBTYPE, relation.STATE, relation.ACTIVE,
                relation.ROW_REVISION, relation.DECISION_GROUP_ID, relation.PRIMARY_TRANSACTION_ID,
                relation.SECONDARY_TRANSACTION_ID)
            .from(relation).where(relation.WORKSPACE_ID.eq(workspace))
            .and(relation.PRIMARY_TRANSACTION_ID.eq(id).or(relation.SECONDARY_TRANSACTION_ID.eq(id))
                .or(relation.ANCHOR_TRANSACTION_ID.eq(id)).or(relation.ID.`in`(candidateRelations)))
            .orderBy(relation.ID)
            .fetch { DetailRelation(it[relation.ID]!!, it[relation.KIND]!!, it[relation.SUBTYPE]!!, it[relation.STATE]!!,
                it[relation.ACTIVE]!!, it[relation.ROW_REVISION]!!, it[relation.DECISION_GROUP_ID],
                it[relation.PRIMARY_TRANSACTION_ID]!!, it[relation.SECONDARY_TRANSACTION_ID]) }
    }

    private fun entry(id: Long): DetailEntry? {
        val member = CASH_ENTRY_MEMBERS
        val entryId = sql.select(member.ENTRY_ID).from(member)
            .where(member.WORKSPACE_ID.eq(workspace)).and(member.TRANSACTION_ID.eq(id)).fetchOne(member.ENTRY_ID)
            ?: return null
        val entry = CASH_ENTRIES
        val row = sql.select(entry.ID, entry.REVISION, entry.CATEGORY_ID, entry.NOTE, entry.COUNTERPARTY,
                entry.COUNTERPARTY_ACCOUNT, entry.COUNTERPARTY_ACCOUNT_ATTRS, entry.MANUAL_FIELDS).from(entry)
            .where(entry.WORKSPACE_ID.eq(workspace)).and(entry.ID.eq(entryId)).fetchOne() ?: return null
        val count = sql.selectCount().from(member)
            .where(member.WORKSPACE_ID.eq(workspace)).and(member.ENTRY_ID.eq(entryId)).fetchOne(0, Long::class.java) ?: 0L
        val manualFields = (row[entry.MANUAL_FIELDS] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
        return DetailEntry(row[entry.ID]!!, row[entry.REVISION]!!, row[entry.CATEGORY_ID], row[entry.NOTE]!!,
            row[entry.COUNTERPARTY]!!, row[entry.COUNTERPARTY_ACCOUNT]!!, row[entry.COUNTERPARTY_ACCOUNT_ATTRS],
            manualFields, count)
    }

    private fun entryJson(record: DetailRecord, entry: DetailEntry, editableFields: List<String>) = buildJsonObject {
        put("entry_id", entry.id)
        put("revision", entry.revision)
        put("fields", buildJsonObject {
            put("category_id", entry.categoryId?.let(::JsonPrimitive) ?: JsonNull)
            put("counterparty", entry.counterparty)
            put("counterparty_account", entry.counterpartyAccount)
            put("counterparty_account_attrs", entry.attrs ?: JsonArray(emptyList()))
            put("note", entry.note)
        })
        put("manual_fields", JsonArray(entry.manualFields.map(::JsonPrimitive)))
        put("editable_fields", JsonArray(editableFields.map(::JsonPrimitive)))
        put("supports_transfer_edit", record.recordOrigin == "manual_transfer" && entry.memberCount == 2L)
    }

    private fun projectionReference(id: Long): JsonObject? {
        val state = CASH_PROJECTION_STATES
        val stateRow = sql.select(state.ACTIVE_DATASET_ID, state.PROJECTION_VERSION).from(state)
            .where(state.WORKSPACE_ID.eq(workspace)).fetchOne() ?: return null
        val dataset = stateRow[state.ACTIVE_DATASET_ID] ?: return null
        val member = CASH_PROJECTION_MEMBERS.`as`("member")
        val projection = CASH_PROJECTIONS.`as`("projection")
        val row = sql.select(projection.PROJECTION_ID, projection.ATTENTION_COUNT).from(member)
            .join(projection).on(projection.WORKSPACE_ID.eq(member.WORKSPACE_ID)).and(projection.ID.eq(member.PROJECTION_ROW_ID))
            .where(member.WORKSPACE_ID.eq(workspace)).and(member.DATASET_ID.eq(dataset))
            .and(member.CASH_TRANSACTION_ID.eq(id)).and(projection.WORKSPACE_ID.eq(workspace))
            .and(projection.DATASET_ID.eq(dataset)).fetchOne() ?: return null
        return buildJsonObject {
            put("transaction_id", id.toString()); put("projection_id", row[projection.PROJECTION_ID]!!)
            put("attention_count", row[projection.ATTENTION_COUNT]!!)
            put("projection_version", stateRow[state.PROJECTION_VERSION]!!)
        }
    }

    private fun issueJson(record: DetailRecord, issue: DetailIssue, allIssues: List<CashSourceIssue>,
        fields: DetailFields, editable: Boolean, identityResolutions: List<String>,
        candidates: List<DetailCandidate>): JsonObject {
        val reason = try { cashImportIssue(issue.code, fieldPath = issue.fieldPath) }
            catch (_: IllegalArgumentException) { cashImportIssue("posting_incomplete") }
        val resolutions = when {
            !editable -> emptyList()
            issue.code == "source_update_pending" -> availableSourceResolutions(record, issue, allIssues, fields)
            issue.code == "source_identity_ambiguous" -> identityResolutions
            else -> emptyList()
        }
        val rawPending = when (val pending = issue.pendingFields) {
            null, JsonNull -> null
            is JsonObject -> pending
            // Python 把空列表视作缺省；非空容器仍是损坏状态。
            is JsonArray -> if (pending.isEmpty()) null else throw CashRecordReadUnavailable(IllegalStateException("invalid pending fields"))
            else -> throw CashRecordReadUnavailable(IllegalStateException("invalid pending fields"))
        }
        val pendingFields = rawPending?.filterKeys { it in editableCashRecordFields } ?: emptyMap()
        val pending = try {
            val patch = pendingCashSourcePatch(allIssues.first { it.id == issue.id })
            ("source_payload" in patch) to (patch["source_payload"] ?: JsonNull)
        } catch (_: CashPolicyError) {
            null to (issue.pendingPayload ?: JsonNull)
        }
        return buildJsonObject {
            put("code", reason.code); put("message", reason.message)
            put("field_path", reason.fieldPath?.let(::JsonPrimitive) ?: JsonNull)
            put("blocking_scope", reason.blockingScope); put("next_action", reason.nextAction)
            reason.afterImportAction?.let { put("after_import_action", JsonPrimitive(it)) }
            put("issue_id", issue.id.toString()); put("revision", issue.revision)
            put("allowed_resolutions", JsonArray(resolutions.map(::JsonPrimitive)))
            put("pending_fields", JsonObject(pendingFields))
            put("pending_source_payload", pending.second)
            put("pending_source_payload_present", pending.first?.let(::JsonPrimitive) ?: JsonNull)
            if (issue.code == "source_identity_ambiguous") put("candidates", JsonArray(candidates.map { candidate -> buildJsonObject {
                put("transaction_id", candidate.transactionId.toString()); put("row_revision", candidate.rowRevision)
                put("state", candidate.state)
                put("display", buildJsonObject {
                    put("occurred_at", candidate.occurredAt?.let { JsonPrimitive(manualInstantText(it)) } ?: JsonNull)
                    put("amount", candidate.amount?.let { JsonPrimitive(fixedCashDecimal(it)) } ?: JsonNull)
                    put("currency", candidate.currency?.let(::JsonPrimitive) ?: JsonNull)
                    put("counterparty", candidate.counterparty); put("note", candidate.note)
                    put("accounts", JsonArray(candidate.accounts.map(::JsonPrimitive)))
                })
            } }))
        }
    }

    /** 只读准备；任何原准备失败都只保留 keep_existing，不写库也不推进修订。 */
    private fun availableSourceResolutions(record: DetailRecord, issue: DetailIssue,
        issues: List<CashSourceIssue>, fields: DetailFields): List<String> {
        return try {
            val protected = protectedFields(record)
            val choice = CashSourceResolution(issue.id.toString(), issue.revision, "apply_source")
            val prepared = prepareCashSourceResolutions(fields.values, issues, listOf(choice), emptyMap(), protected)
            preparedPreconditions(record, fields, prepared.patch, protected)
            listOf("apply_source", "keep_existing")
        } catch (_: CashPolicyError) {
            listOf("keep_existing")
        } catch (_: ReceiptConflict) {
            listOf("keep_existing")
        } catch (_: CashRecordDetailFailure) {
            listOf("keep_existing")
        }
    }

    private fun preparedPreconditions(record: DetailRecord, fields: DetailFields, sourcePatch: Map<String, Any?>,
        protected: Set<String>) {
        val update = CashRecordPolicy.prepare(fields.values, sourcePatch, protected)
        var base = LinkedHashMap<String, Any?>(fields.values).apply { putAll(update.values) }
        base = LinkedHashMap(prepareCashSourceEffects(base, sourcePatch, record.sourceType ?: "",
            record.relationMetadata, protected).values)
        var values: Map<String, Any?> = base
        var policyPatch = emptyMap<String, Any?>()
        if (record.state == "draft") {
            val linked = sourcePatch.keys intersect financialCashFields
            val parts = (base["components"] as? List<Map<String, Any?>>) ?: emptyList()
            if (linked.isNotEmpty() && "components" !in sourcePatch && base["cash_granularity"] == "atomic" && parts.size <= 1) {
                val effective = base
                val applied = if (parts.isEmpty()) listOf(mapOf<String, Any?>(
                    "ordinal" to 0, "amount" to effective["amount"], "currency" to effective["currency"],
                    "account_id" to effective["account_id"],
                )) else parts
                policyPatch = mapOf("components" to applied.map { part -> part + linked.associateWith { effective[it] } })
            }
            val second = CashRecordPolicy.prepare(base, policyPatch)
            values = LinkedHashMap(base).apply { putAll(second.values) }
        }
        if ("components" in policyPatch || "components" in sourcePatch) {
            val currentParts = fields.components
            @Suppress("UNCHECKED_CAST")
            val requested = values["components"] as? List<Map<String, Any?>> ?: emptyList()
            validateCashSourceComponentIds(currentParts, requested)
            if (requested.any { "ordinal" !in it }) throw CashPolicyError("invalid_field", listOf("components"))
            if (requested.map { it["ordinal"] }.distinct().size != requested.size)
                throw CashPolicyError("invalid_field", listOf("components"))
        }
        val categoryId = values["category_id"] as? String
        if (categoryId != null && !sql.fetchExists(CASH_CATEGORIES, CASH_CATEGORIES.WORKSPACE_ID.eq(workspace)
                .and(CASH_CATEGORIES.ID.eq(categoryId)))) {
            throw CashPolicyError("category_unavailable", listOf("category_id"))
        }
        if (record.state == "draft") {
            @Suppress("UNCHECKED_CAST")
            val parts = values["components"] as? List<Map<String, Any?>> ?: emptyList()
            for (reference in parts.mapNotNull { it["account_id"] } + listOfNotNull(values["account_id"]))
                identifierReference(reference)
            // 原准备把未补齐的草稿记录为当前问题，而不是拒绝来源动作。
            try { CashRecordPolicy.validateForPosting(values) } catch (_: CashPolicyError) { }
        }
    }

    private fun identifierReference(value: Any?): Long {
        val identifier = when (value) {
            is Long -> value
            is Int -> value.toLong()
            is String -> if (value.length in 1..19 && value.all { it in '0'..'9' }) value.toLongOrNull() else null
            else -> null
        }
        return identifier?.takeIf { it > 0 } ?: throw CashRecordDetailFailure("record_input_invalid")
    }

    private fun protectedFields(record: DetailRecord): Set<String> {
        val overrides = record.manualOverrides as? JsonObject ?: return emptySet()
        return overrides.filterValues(::pythonTruthy).keys - commonCashFieldSet
    }

    private fun pythonTruthy(value: JsonElement): Boolean = when (value) {
        JsonNull -> false
        is JsonObject -> value.isNotEmpty()
        is JsonArray -> value.isNotEmpty()
        is JsonPrimitive -> when {
            value.isString -> value.content.isNotEmpty()
            value.booleanOrNull != null -> value.boolean
            else -> value.content.toBigDecimalOrNull()?.signum() != 0
        }
    }

    private fun availableIdentityResolutions(record: DetailRecord, candidateIds: Set<Long>): List<String> {
        if (record.state != "draft") return emptyList()
        return try {
            identitySnapshotAvailable(record)
            if (candidateIds.isNotEmpty()) listOf("create_independent", "merge_into") else listOf("create_independent")
        } catch (_: SourceBindingConflict) {
            emptyList()
        }
    }

    private class SourceBindingConflict : RuntimeException("source_binding_conflict")

    private data class Binding(val id: Long, val sourceType: String, val scopeKind: String, val scopeKey: String,
        val identityKind: String, val identityKey: String, val evidenceKey: String?, val strategyVersion: String,
        val coverage: JsonElement?)

    /** 只读当前绑定及活动归属；损坏的覆盖证据停止读取，不从可改来源正文恢复身份。 */
    private fun identitySnapshotAvailable(record: DetailRecord) {
        val binding = CASH_SOURCE_BINDINGS
        val bindings = sql.select(binding.ID, binding.SOURCE_TYPE, binding.SOURCE_SCOPE_KIND, binding.SOURCE_SCOPE_KEY,
                binding.IDENTITY_KIND, binding.IDENTITY_KEY, binding.STABLE_EVIDENCE_KEY, binding.STRATEGY_VERSION, binding.COVERAGE)
            .from(binding).where(binding.WORKSPACE_ID.eq(workspace)).and(binding.TRANSACTION_ID.eq(record.id))
            .orderBy(binding.ID).fetch { Binding(it[binding.ID]!!, it[binding.SOURCE_TYPE]!!, it[binding.SOURCE_SCOPE_KIND]!!,
                it[binding.SOURCE_SCOPE_KEY]!!, it[binding.IDENTITY_KIND]!!, it[binding.IDENTITY_KEY]!!,
                it[binding.STABLE_EVIDENCE_KEY], it[binding.STRATEGY_VERSION]!!, it[binding.COVERAGE]) }
        val account = bindings.filter { it.scopeKind == "account" }
        if (bindings.any { it.sourceType != record.sourceType } ||
            account.map { it.scopeKey }.distinct().size > 1 ||
            account.filter { it.identityKind == "provider_id" }.map { it.identityKey }.distinct().size > 1 ||
            bindings.mapNotNull { it.evidenceKey?.takeIf(String::isNotEmpty) }.distinct().size > 1)
            throw SourceBindingConflict()
        val positions = linkedMapOf<String, MutableSet<String>>()
        val pages = linkedMapOf<Triple<String, String, String>, MutableMap<String, String>>()
        for (item in bindings) {
            val evidence = coverage(item.coverage)
            val document = evidence.documentKey ?: continue
            if (item.scopeKind == "file_local" && item.identityKind == "file_occurrence" &&
                item.scopeKey == sourceDocumentScope(item.sourceType, item.strategyVersion, document)) {
                positions.getOrPut(item.scopeKey) { linkedSetOf() }.add(item.identityKey)
            }
            val fingerprints = pages.getOrPut(Triple(item.sourceType, item.strategyVersion, document)) { linkedMapOf() }
            for ((page, fingerprint) in evidence.pageFingerprints) {
                val previous = fingerprints.putIfAbsent(page, fingerprint)
                if (previous != null && previous != fingerprint) throw SourceBindingConflict()
            }
        }
        if (positions.values.any { it.size > 1 }) throw SourceBindingConflict()
        val keys = bindings.map { listOf(it.sourceType, it.scopeKind, it.scopeKey, it.identityKind, it.identityKey) }
        val evidenceKeys = bindings.mapNotNull { it.evidenceKey?.takeIf(String::isNotEmpty) }.distinct().sorted()
        val others = linkedSetOf<Long>()
        for (batch in keys.chunked(125)) others += bindingTransactions(batch, emptyList())
        for (batch in evidenceKeys.chunked(125)) others += bindingTransactions(emptyList(), batch)
        others -= record.id
        for (batch in others.sorted().chunked(500)) {
            val tx = CASH_TRANSACTIONS
            if (sql.fetchExists(tx, tx.WORKSPACE_ID.eq(workspace).and(tx.ID.`in`(batch)).and(tx.DELETED_AT.isNull)))
                throw SourceBindingConflict()
        }
    }

    private fun bindingTransactions(keys: List<List<String>>, evidence: List<String>): List<Long> {
        if (keys.isEmpty() && evidence.isEmpty()) return emptyList()
        val binding = CASH_SOURCE_BINDINGS
        var condition: org.jooq.Condition = DSL.falseCondition()
        for (key in keys) {
            val one = binding.SOURCE_TYPE.eq(key[0]).and(binding.SOURCE_SCOPE_KIND.eq(key[1]))
                .and(binding.SOURCE_SCOPE_KEY.eq(key[2])).and(binding.IDENTITY_KIND.eq(key[3]))
                .and(binding.IDENTITY_KEY.eq(key[4]))
            condition = condition.or(one)
        }
        if (evidence.isNotEmpty()) condition = condition.or(binding.STABLE_EVIDENCE_KEY.`in`(evidence))
        return sql.select(binding.TRANSACTION_ID).from(binding)
            .where(binding.WORKSPACE_ID.eq(workspace)).and(condition).fetch(binding.TRANSACTION_ID).filterNotNull()
    }

    private data class Coverage(val documentKey: String?, val pageFingerprints: Map<String, String>)

    /** 与 Python SourceCoverage 相同的结构边界；未知或损坏证据停止读取。 */
    private fun coverage(value: JsonElement?): Coverage {
        if (value == null || value == JsonNull) return Coverage(null, emptyMap())
        val objectValue = value as? JsonObject ?: throw invalidCoverage()
        val allowed = setOf("start", "end", "complete", "evidence", "filters", "start_inclusive", "end_inclusive",
            "time_precision", "strategy_version", "document_key", "page_total", "page_fingerprints")
        if (objectValue.keys.any { it !in allowed }) throw invalidCoverage()
        val documentValue = objectValue["document_key"]?.takeIf { it != JsonNull } ?: return coverageWithoutDocument(objectValue)
        val document = (documentValue as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw invalidCoverage()
        val total = (objectValue["page_total"] as? JsonPrimitive)?.takeIf { !it.isString && it.booleanOrNull == null }
            ?.contentOrNull?.toIntOrNull() ?: throw invalidCoverage()
        val fingerprintsValue = objectValue["page_fingerprints"] as? JsonObject ?: throw invalidCoverage()
        val fingerprints = fingerprintsValue.mapValues { (_, item) ->
            (item as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw invalidCoverage()
        }
        if (!document.matches(Regex("[0-9a-f]{64}")) || total !in 1..2000 || fingerprints.isEmpty() ||
            fingerprints.size > total || fingerprints.any { (key, fingerprint) ->
                !key.matches(Regex("[1-9][0-9]{0,3}")) || key.toInt() > total || !fingerprint.matches(Regex("[0-9a-f]{64}"))
            }) throw invalidCoverage()
        return Coverage(document, fingerprints)
    }

    private fun coverageWithoutDocument(value: JsonObject): Coverage {
        // 真实绑定以 asdict(SourceCoverage) 保存：没有文档时键仍在，但值为 null/空对象。
        if (value["page_total"]?.takeIf { it != JsonNull } != null) throw invalidCoverage()
        val fingerprints = value["page_fingerprints"]?.takeIf { it != JsonNull }
        if (fingerprints != null) {
            val objectValue = fingerprints as? JsonObject ?: throw invalidCoverage()
            if (objectValue.isNotEmpty()) throw invalidCoverage()
        }
        return Coverage(null, emptyMap())
    }

    private fun invalidCoverage() = CashRecordReadUnavailable(IllegalStateException("invalid source coverage"))

    private fun sourceDocumentScope(sourceType: String, strategyVersion: String, document: String): String =
        cashSourceDigest(JsonPrimitive("source-document-v1"), JsonPrimitive(sourceType),
            JsonPrimitive(strategyVersion), JsonPrimitive(document))
}
