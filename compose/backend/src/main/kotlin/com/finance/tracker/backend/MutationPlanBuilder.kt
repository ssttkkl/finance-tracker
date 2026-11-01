package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import com.finance.tracker.business.contracts.CashImportIssueDto
import java.math.BigInteger
import java.time.Instant
import kotlinx.serialization.json.*

internal data class PreparedCashMaintenanceOperations(val operations: List<Map<String, Any?>>, val transferRelations: List<Map<String, Any?>>)

/** 维护操作及计划视图；普通草稿保存复用相同准备规则。 */
internal class MutationPlanBuilder(private val context: CashSqlContext) {
    private val sql get() = context.sql
    private val workspace get() = context.workspace

    @Suppress("UNCHECKED_CAST")
    fun prepareDirectDraft(transactionId: Long, patch: Map<String, Any?>): Map<String, Any?> {
        val row = record(transactionId)
        if (row.state != "draft") throw MutationPlanFailure("draft_required")
        if (patch.keys.any { it !in editableCashRecordFields }) throw CashPolicyError("field_not_editable", emptyList())
        return cashSourceValue(prepare(row, fields(row), patch, entry(transactionId), existingIssues = issues(transactionId))) as Map<String, Any?>
    }

    fun prepareCurrentOperation(transactionId: Long, patch: Map<String, Any?>): Map<String, Any?> {
        val row = record(transactionId)
        validatePatch(row, patch)
        val operation = prepare(row, fields(row), patch, entry(transactionId), existingIssues = issues(transactionId))
        return decorate(row, operation)
    }

    fun prepareMaintenanceOperation(request: MutationPlanRequest): Map<String, Any?> {
        val row = record(request.transactionId.toLong())
        if (request.intent in setOf("save_draft", "finalize_draft") && row.state != "draft") throw MutationPlanFailure("draft_required")
        if (request.intent in setOf("edit_record", "edit_entry") && row.state != "posted") throw MutationPlanFailure("posted_required")
        val entry = entry(row.id)
        if (request.intent == "edit_entry" && (entry == null || entry.id != request.entryId || entry.revision != request.expectedEntryRevision))
            throw CashPolicyError("entry_changed", emptyList())
        if (request.issueResolutions.any { it.resolution == "merge_into" }) return CashDraftMergePlanner(context, this).prepare(request)
        val patch = request.patch.mapValues { cashSourceValue(it.value) }
        val existing = issues(row.id)
        if (request.issueResolutions.any { it.resolution !in setOf("apply_source", "keep_existing", "create_independent") }) throw MutationPlanFailure("plan_input_invalid")
        val resolved = if (request.issueResolutions.isEmpty()) null else prepareCashSourceResolutions(fields(row), existing,
            request.issueResolutions.map { CashSourceResolution(it.issueId, it.expectedIssueRevision, it.resolution) }, patch, protectedFields(row))
        validatePatch(row, patch)
        var operation = prepare(row, fields(row), patch, entry, sourcePatch = resolved?.patch.orEmpty(),
            resolutions = resolved?.resolutions.orEmpty(), existingIssues = existing)
        if (resolved != null) operation = JsonObject(operation + mapOf(
            "issue_resolutions" to JsonArray(resolved.resolutions.map { resolution -> buildJsonObject {
                put("issue_id", resolution.choice.issueId); put("expected_issue_revision", resolution.choice.expectedRevision)
                put("resolution", resolution.choice.resolution); put("body_digest", resolution.bodyDigest)
            } }),
            "resolved_issue_ids" to JsonArray(resolved.resolutions.map { JsonPrimitive(it.choice.issueId) }.sortedBy { it.content })))
        if (request.issueResolutions.any { it.resolution == "create_independent" }) operation = JsonObject(operation +
            ("expected_source_bindings" to sourceJson(CashSourceWriteRepository(context).independentIdentitySnapshot(row.id))))
        if (request.intent == "finalize_draft") {
            operation.getValue("issues").jsonArray.firstOrNull()?.jsonObject?.let { issue -> throw CashPolicyError(
                issue.getValue("code").jsonPrimitive.content, listOfNotNull(issue["field_path"]?.jsonPrimitive?.contentOrNull)) }
            val retained = operation.getValue("retained_issue_ids").jsonArray.toSet()
            operation.getValue("expected_issues").jsonArray.firstOrNull { it.jsonObject["issue_id"] in retained &&
                it.jsonObject.getValue("code").jsonPrimitive.content in setOf("source_identity_ambiguous", "source_update_pending") }?.let {
                throw CashPolicyError(it.jsonObject.getValue("code").jsonPrimitive.content, emptyList())
            }
            operation = JsonObject(operation + mapOf("values" to JsonObject(operation.getValue("values").jsonObject + ("state" to JsonPrimitive("posted"))),
                "planned_action" to JsonPrimitive("finalize_draft")))
        }
        return decorate(row, operation)
    }

    fun prepareMaintenanceOperations(request: MutationPlanRequest): PreparedCashMaintenanceOperations {
        val primary = prepareMaintenanceOperation(request).toMutableMap()
        val operations = mutableListOf<Map<String, Any?>>(primary)
        val updates = mutableListOf<Map<String, Any?>>()
        if (request.memberPatches.isNotEmpty()) {
            val row = record(request.transactionId.toLong())
            val entry = entry(row.id) ?: throw CashPolicyError("field_not_editable", emptyList())
            val memberIds = sql.select(CASH_ENTRY_MEMBERS.TRANSACTION_ID).from(CASH_ENTRY_MEMBERS)
                .where(CASH_ENTRY_MEMBERS.WORKSPACE_ID.eq(workspace)).and(CASH_ENTRY_MEMBERS.ENTRY_ID.eq(entry.id))
                .fetch(CASH_ENTRY_MEMBERS.TRANSACTION_ID).filterNotNull().toSet()
            if (row.origin != "manual_transfer" || memberIds.size != 2 || request.memberPatches.size != 2 ||
                request.memberPatches.map { it.transactionId.toLong() }.toSet() != memberIds)
                throw CashPolicyError("field_not_editable", emptyList())
            val relation = TRANSACTION_RELATIONS
            val activeIds = sql.select(relation.ID).from(relation).where(relation.WORKSPACE_ID.eq(workspace))
                .and(relation.ACTIVE.isTrue).and(relation.PRIMARY_TRANSACTION_ID.eq(row.id)
                    .or(relation.SECONDARY_TRANSACTION_ID.eq(row.id)).or(relation.ANCHOR_TRANSACTION_ID.eq(row.id)))
                .fetch(relation.ID).filterNotNull().toSet()
            val relations = CurrentCashRelationWriteRepository(context).planningRows(activeIds)
            val edge = relations.singleOrNull()?.takeIf { it["rule_id"] == "manual.transfer.v1" && it["state"] == "confirmed" }
                ?: throw CashPolicyError("manual_relation_forbidden", emptyList())
            val identifiers = listOf(edge["primary_transaction_id"].toString(), edge["secondary_transaction_id"].toString())
            val patches = request.memberPatches.associate { it.transactionId to it.patch.mapValues { item -> cashSourceValue(item.value) } }
            if (patches.keys != identifiers.toSet()) throw CashPolicyError("field_not_editable", emptyList())
            val members = identifiers.map { record(it.toLong()) }
            val values = members.map { member ->
                val patch = patches.getValue(member.id.toString())
                if (member.origin != "manual_transfer" || member.manualTransferId != row.manualTransferId || patch.keys.any { it in commonCashFields })
                    throw CashPolicyError("field_not_editable", emptyList())
                fields(member) + patch.mapValues { (key, value) -> CashRecordPolicy.normalize(key, value) }
            }
            val accounts = values.map { value ->
                val available = try { accounts(setOfNotNull(value["account_id"] as? String)) }
                    catch (failure: NumberFormatException) { throw CashRecordReadUnavailable(failure) }
                available[value["account_id"]]?.takeIf { it.type !in setOf("security", "crypto") }
                    ?: throw CashPolicyError("account_unavailable", listOf("account_id"))
            }
            val exchange = values[0]["currency"] != values[1]["currency"]
            val repayment = !exchange && accounts.any { it.type == "loan" }
            val types = if (exchange) listOf("fx_out", "fx_in") else if (repayment) listOf("repayment", "repayment") else listOf("transfer_out", "transfer_in")
            for ((index, member) in members.withIndex()) {
                val patch = patches.getValue(member.id.toString())
                val derived = mapOf("record_type" to types[index], "record_subtype" to defaultCashRecordSubtype(types[index]))
                if (derived.any { (key, value) -> key in patch && patch[key] != value })
                    throw CashPolicyError("relation_endpoint_incompatible", listOf("record_type", "record_subtype"))
                validatePatch(member, patch + derived, allowGroupFinancial = true)
                val extra = decorate(member, prepare(member, fields(member), patch + derived, entry(member.id), existingIssues = issues(member.id), allowGroupFinancial = true))
                if (member.id == row.id) {
                    val action = primary["planned_action"]
                    @Suppress("UNCHECKED_CAST")
                    val diffs = ((extra["field_diffs"] as List<Map<String, Any?>>) +
                        (primary["field_diffs"] as List<Map<String, Any?>>)).associateBy { it.getValue("field") as String }
                    primary.putAll(extra.filterKeys { it != "entry_edit" })
                    if (action != "duplicate") primary["planned_action"] = action
                    primary["field_diffs"] = diffs.keys.sortedWith(cashRelationStringOrder).map { diffs.getValue(it) }
                } else operations += extra
            }
            updates += mapOf("relation_id" to edge["id"], "expected_revision" to edge["row_revision"],
                "subtype" to if (exchange) "currency_exchange" else if (repayment) "credit_repayment" else "ordinary_transfer")
        }
        return PreparedCashMaintenanceOperations(operations.mapIndexed { index, operation -> operation + ("operation_ref" to "op_${index + 1}") }, updates)
    }

    private fun validatePatch(row: PlanRecord, patch: Map<String, Any?>, allowGroupFinancial: Boolean = false) {
        val transactionId = row.id
        if (patch.keys.any { it !in editableCashRecordFields }) throw CashPolicyError("field_not_editable", emptyList())
        if (row.state == "posted") {
            if (row.origin == "manual_transfer" && !allowGroupFinancial && patch.keys.any { it !in commonCashFields })
                throw CashPolicyError("field_not_editable", patch.keys.filter { it !in commonCashFields }.sortedWith(cashRelationStringOrder))
            if ("source_payload" in patch) throw CashPolicyError("field_not_editable", listOf("source_payload"))
            val member = context.entries.forTransactions(listOf(transactionId)).first[transactionId]
            if (member != null && !allowGroupFinancial && patch.keys.any { it !in commonCashFields } && sql.selectCount().from(CASH_ENTRY_MEMBERS)
                    .where(CASH_ENTRY_MEMBERS.WORKSPACE_ID.eq(workspace)).and(CASH_ENTRY_MEMBERS.ENTRY_ID.eq(member.entryId)).fetchSingle().value1() > 1)
                throw CashPolicyError("field_not_editable", patch.keys.filter { it !in commonCashFields }.sortedWith(cashRelationStringOrder))
        }
    }

    fun decorate(row: PlanRecord, operation: JsonObject): Map<String, Any?> {
        val transactionId = row.id
        val scope = CashImportPreviewSnapshotBuilder(context).scopes(setOf(transactionId))[transactionId.toString() to row.sourceType] ?: "unbound:$transactionId"
        @Suppress("UNCHECKED_CAST")
        return cashSourceValue(JsonObject(operation + mapOf("operation_ref" to JsonPrimitive("op_1"), "row_refs" to JsonArray(emptyList()),
            "source_scope_key" to JsonPrimitive(scope), "matching_record_id" to JsonPrimitive("occurrence:$transactionId"),
            "matching_source_explanation" to sourceJson(extractCashSourceExplanation(row.relationMetadata?.let(::cashSourceValue)))))) as Map<String, Any?>
    }

    fun build(transactionId: Long, request: MutationPlanRequest, previous: JsonObject? = null): JsonObject {
        if (transactionId.toString() != request.transactionId) throw MutationPlanFailure("plan_input_invalid")
        return CurrentCashMaintenancePlanBuilder(context).build(request, previous)
    }

    data class PlanRecord(
        val id: Long, val state: String, val revision: Long, val origin: String, val sourceType: String?,
        val recordId: String, val manualTransferId: String?, val counterparty: String, val counterpartyAccount: String,
        val attrs: JsonElement?, val note: String, val categoryId: String?, val recordType: String?, val recordSubtype: String?,
        val sourcePayload: JsonElement?, val amount: CashStoredDecimal?, val currency: String?, val occurredAt: Instant?,
        val accountId: Long?, val granularity: String, val manualOverrides: JsonElement?, val relationMetadata: JsonElement?,
        val createdAt: Instant,
    )

    fun record(id: Long): PlanRecord {
        val tx = CASH_TRANSACTIONS
        val row = sql.select(tx.ID, tx.STATE, tx.ROW_REVISION, tx.RECORD_ORIGIN, tx.SOURCE_TYPE, tx.RECORD_ID,
                tx.MANUAL_TRANSFER_ID, tx.COUNTERPARTY, tx.COUNTERPARTY_ACCOUNT, tx.COUNTERPARTY_ACCOUNT_ATTRS, tx.NOTE,
                tx.CATEGORY_ID, tx.RECORD_TYPE, tx.RECORD_SUBTYPE, tx.SOURCE_PAYLOAD, tx.AMOUNT, tx.CURRENCY,
                tx.OCCURRED_AT, tx.ACCOUNT_ID, tx.CASH_GRANULARITY, tx.MANUAL_OVERRIDES, tx.RELATION_METADATA, tx.CREATED_AT)
            .from(tx).where(tx.WORKSPACE_ID.eq(workspace)).and(tx.ID.eq(id)).and(tx.DELETED_AT.isNull).fetchOne()
            ?: throw MutationPlanFailure("record_not_found")
        return PlanRecord(row[tx.ID]!!, row[tx.STATE]!!, row[tx.ROW_REVISION]!!, row[tx.RECORD_ORIGIN]!!, row[tx.SOURCE_TYPE],
            row[tx.RECORD_ID]!!, row[tx.MANUAL_TRANSFER_ID], row[tx.COUNTERPARTY]!!, row[tx.COUNTERPARTY_ACCOUNT]!!,
            row[tx.COUNTERPARTY_ACCOUNT_ATTRS], row[tx.NOTE]!!, row[tx.CATEGORY_ID], row[tx.RECORD_TYPE], row[tx.RECORD_SUBTYPE],
            row[tx.SOURCE_PAYLOAD], row[tx.AMOUNT], row[tx.CURRENCY], row[tx.OCCURRED_AT], row[tx.ACCOUNT_ID],
            row[tx.CASH_GRANULARITY]!!, row[tx.MANUAL_OVERRIDES], row[tx.RELATION_METADATA], row[tx.CREATED_AT]!!)
    }

    fun fields(record: PlanRecord): MutableMap<String, Any?> = linkedMapOf(
        "counterparty" to record.counterparty, "counterparty_account" to record.counterpartyAccount,
        "counterparty_account_attrs" to record.attrs?.let(::cashSourceValue), "note" to record.note,
        "category_id" to record.categoryId, "record_type" to record.recordType, "record_subtype" to record.recordSubtype,
        "source_payload" to record.sourcePayload?.let(::cashSourceValue),
        "amount" to record.amount?.let { CashAmount.parseOrNull(it.text) }, "currency" to record.currency,
        "occurred_at" to record.occurredAt, "account_id" to record.accountId?.toString(),
        "cash_granularity" to record.granularity, "state" to record.state, "source_type" to record.sourceType,
        "record_id" to record.recordId, "record_origin" to record.origin, "manual_transfer_id" to record.manualTransferId,
        "components" to components(record.id).map { part -> linkedMapOf<String, Any?>(
            "component_id" to part.id.toString(), "account_id" to part.accountId?.toString(),
            "amount" to part.amount?.let { CashAmount.parseOrNull(it.text) }, "currency" to part.currency,
            "ordinal" to part.ordinal.toInt()) },
    )

    private data class Part(val id: Long, val accountId: Long?, val amount: CashStoredDecimal?, val currency: String?, val ordinal: Long)
    private fun components(id: Long): List<Part> {
        val part = CASH_TRANSACTION_COMPONENTS
        return sql.select(part.ID, part.ACCOUNT_ID, part.AMOUNT, part.CURRENCY, part.ORDINAL).from(part)
            .where(part.WORKSPACE_ID.eq(workspace)).and(part.CASH_TRANSACTION_ID.eq(id)).orderBy(part.ORDINAL)
            .fetch { Part(it[part.ID]!!, it[part.ACCOUNT_ID], it[part.AMOUNT], it[part.CURRENCY], it[part.ORDINAL]!!) }
    }

    fun issues(id: Long): List<CashSourceIssue> {
        val issue = CASH_TRANSACTION_ISSUES
        return sql.select(issue.ID, issue.REVISION, issue.CODE, issue.FIELD_PATH, issue.ISSUE_KEY,
                issue.PENDING_FIELDS, issue.PENDING_SOURCE_PAYLOAD, issue.ORIGIN).from(issue)
            .where(issue.WORKSPACE_ID.eq(workspace)).and(issue.TRANSACTION_ID.eq(id)).orderBy(issue.ID)
            .fetch { CashSourceIssue(it[issue.ID]!!, it[issue.REVISION]!!, it[issue.CODE]!!, it[issue.FIELD_PATH],
                it[issue.PENDING_FIELDS], it[issue.PENDING_SOURCE_PAYLOAD], it[issue.ORIGIN], it[issue.ISSUE_KEY]!!) }
    }

    data class EntryRow(val id: String, val revision: Long, val categoryId: String?, val note: String,
        val counterparty: String, val counterpartyAccount: String, val attrs: JsonElement?, val manualFields: List<String>)
    fun entry(id: Long): EntryRow? {
        val member = CASH_ENTRY_MEMBERS
        val entryId = sql.select(member.ENTRY_ID).from(member).where(member.WORKSPACE_ID.eq(workspace))
            .and(member.TRANSACTION_ID.eq(id)).fetchOne(member.ENTRY_ID) ?: return null
        val entry = CASH_ENTRIES
        val row = sql.select(entry.ID, entry.REVISION, entry.CATEGORY_ID, entry.NOTE, entry.COUNTERPARTY,
                entry.COUNTERPARTY_ACCOUNT, entry.COUNTERPARTY_ACCOUNT_ATTRS, entry.MANUAL_FIELDS).from(entry)
            .where(entry.WORKSPACE_ID.eq(workspace)).and(entry.ID.eq(entryId)).fetchOne() ?: return null
        val manual = (row[entry.MANUAL_FIELDS] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
        return EntryRow(row[entry.ID]!!, row[entry.REVISION]!!, row[entry.CATEGORY_ID], row[entry.NOTE]!!,
            row[entry.COUNTERPARTY]!!, row[entry.COUNTERPARTY_ACCOUNT]!!, row[entry.COUNTERPARTY_ACCOUNT_ATTRS], manual)
    }

    private data class AccountRow(val name: String, val type: String, val active: Boolean, val currencies: Set<String>)
    private fun accounts(ids: Set<String>? = null): Map<String, AccountRow> {
        val account = ACCOUNTS
        val batches = ids?.sorted()?.chunked(500) ?: listOf(null)
        return batches.flatMap { batch ->
            val query = sql.select(account.ID, account.NAME, account.TYPE, account.ACTIVE, account.CURRENCIES).from(account).where(account.WORKSPACE_ID.eq(workspace))
            (if (batch == null) query else query.and(account.ID.`in`(batch.map(String::toLong)))).fetch()
        }.associate {
                it[account.ID]!!.toString() to AccountRow(it[account.NAME]!!, it[account.TYPE]!!, it[account.ACTIVE]!!,
                    (it[account.CURRENCIES] as? JsonArray)?.mapNotNull { p -> (p as? JsonPrimitive)?.contentOrNull }?.toSet() ?: emptySet())
            }
    }

    fun prepare(record: PlanRecord, current: MutableMap<String, Any?>, patch: Map<String, Any?>, entry: EntryRow?,
        sourcePatch: Map<String, Any?> = emptyMap(), resolutions: List<PreparedCashSourceResolution> = emptyList(),
        existingIssues: List<CashSourceIssue> = emptyList(), allowGroupFinancial: Boolean = false): JsonObject {
        val protected = protectedFields(record)
        val storedExplanation = record.relationMetadata?.let(::cashSourceValue)
        var base = LinkedHashMap(current)
        var explanation: Map<String, Any?>? = null
        var sourceProtectedFields = emptySet<String>()
        if (sourcePatch.isNotEmpty()) {
            val sourceUpdate = CashRecordPolicy.prepare(current, sourcePatch, protected)
            sourceProtectedFields = sourceUpdate.protectedFields.toSet()
            base.putAll(sourceUpdate.values)
            val sourceEffects = prepareCashSourceEffects(base, sourcePatch, record.sourceType ?: "",
                storedExplanation, protected)
            base = LinkedHashMap(sourceEffects.values)
            explanation = sourceEffects.explanation
        }
        validatePatch(record, patch, allowGroupFinancial)
        var policyPatch = LinkedHashMap(patch)
        val publicPatch = CashRecordPolicy.prepare(mapOf("state" to "draft"),
            patch.filterKeys { it in commonCashFields }).values
        if (publicPatch.isNotEmpty()) {
            val category = publicPatch["category_id"] as? String
            if (category != null && !sql.fetchExists(CASH_CATEGORIES, CASH_CATEGORIES.WORKSPACE_ID.eq(workspace)
                    .and(CASH_CATEGORIES.ID.eq(category)))) throw CashPolicyError("category_unavailable", listOf("category_id"))
            if (entry != null && record.state == "posted") policyPatch = LinkedHashMap(policyPatch.filterKeys { it !in commonCashFields })
        }
        val update = CashRecordPolicy.prepare(base, policyPatch, protected)
        base.putAll(update.values)
        val effects = prepareCashSourceEffects(base, patch, record.sourceType ?: "",
            explanation ?: storedExplanation, protected)
        base = LinkedHashMap(effects.values)
        if (effects.explanation != null) explanation = effects.explanation
        val parts = (base["components"] as? List<Map<String, Any?>>) ?: emptyList()
        val linked = linkedMapOf<String, Any?>()
        if ("components" !in policyPatch) for (field in listOf("amount", "currency", "account_id"))
            if (field in policyPatch) linked[field] = policyPatch[field]
        if (linked.isNotEmpty() && "components" !in policyPatch && base["cash_granularity"] == "atomic" && parts.size <= 1) {
            val effective = LinkedHashMap(base).apply { putAll(policyPatch) }
            val applied = if (parts.isEmpty()) listOf(linkedMapOf<String, Any?>(
                "ordinal" to 0, "amount" to effective["amount"], "currency" to effective["currency"],
                "account_id" to effective["account_id"])) else parts
            policyPatch["components"] = applied.map { part -> part + linked }
        }
        val prepared = CashRecordPolicy.prepare(base, policyPatch)
        val values = LinkedHashMap(base).apply { putAll(prepared.values) }
        val effects2 = prepareCashSourceEffects(values, patch, record.sourceType ?: "", explanation ?: storedExplanation, protected)
        values.putAll(effects2.values)
        if (effects2.explanation != null) explanation = effects2.explanation
        if ("components" in policyPatch || "components" in patch) {
            @Suppress("UNCHECKED_CAST")
            val requested = values["components"] as? List<Map<String, Any?>> ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            validateCashSourceComponentIds(current["components"] as List<Map<String, Any?>>, requested)
            if (requested.any { "ordinal" !in it }) throw CashPolicyError("invalid_field", listOf("components"))
            if (requested.map { it["ordinal"] }.distinct().size != requested.size)
                throw CashPolicyError("invalid_field", listOf("components"))
        }
        val categoryId = values["category_id"] as? String
        if (categoryId != null && !sql.fetchExists(CASH_CATEGORIES, CASH_CATEGORIES.WORKSPACE_ID.eq(workspace)
                .and(CASH_CATEGORIES.ID.eq(categoryId)))) throw CashPolicyError("category_unavailable", listOf("category_id"))
        val issues = mutableListOf<JsonObject>()
        val resolvedIds = resolutions.map { it.choice.issueId }.toSet()
        val remainingIssues = existingIssues.filter { it.id.toString() !in resolvedIds }
        val validatesAccounts = record.state == "draft" ||
            record.origin in setOf("manual", "manual_transfer") && patch.keys.any { it in financialFields }
        var retainedIssues = remainingIssues
        var accountDependencies = emptyList<JsonObject>()
        if (validatesAccounts) {
            if (patch.keys.any { it in financialFields }) CashRecordPolicy.validateConsistency(values)
            val references = (listOfNotNull(values["account_id"] as? String) +
                (values["components"] as List<Map<String, Any?>>).mapNotNull { it["account_id"] as? String }).toSet()
            references.forEach { if (!it.matches(Regex("[0-9]{1,19}")) || it.toLongOrNull()?.let { id -> id > 0 } != true) throw MutationPlanFailure("record_input_invalid") }
            val (accountIssues, dependencies) = draftAccounts(values, accounts(references))
            issues += accountIssues
            accountDependencies = dependencies
            @Suppress("UNCHECKED_CAST")
            val explicitAccounts = listOfNotNull(patch["account_id"] as? String) +
                (patch["components"] as? List<Map<String, Any?>> ?: emptyList()).mapNotNull { it["account_id"] as? String }
            if (dependencies.any { dependency ->
                    (dependency.getValue("account_id").jsonPrimitive.content in explicitAccounts || "currency" in patch) &&
                        (!dependency.getValue("exists").jsonPrimitive.boolean || !dependency.getValue("active").jsonPrimitive.boolean ||
                            dependency["type"]?.jsonPrimitive?.contentOrNull !in setOf("cash", "loan", "lend") ||
                            dependency.getValue("currency_support").jsonObject.values.any { !it.jsonPrimitive.boolean })
                }) throw CashPolicyError("account_unavailable", listOf("account_id"))
            try { CashRecordPolicy.validateForPosting(values) } catch (failure: CashPolicyError) {
                issues += issueJson(cashImportIssue(failure.code, fieldPath = failure.fields.firstOrNull()))
            }
            retainedIssues = remainingIssues.filter { it.code !in recomputedIssues + parsedIssues || it.issueKey.startsWith("relation:") }
            val manualValues = sourcePatch + patch
            issues += remainingIssues.filter { it.code in parsedIssues && it !in retainedIssues }
                .filter { remainsParsedIssue(it.code, values, manualValues) }
                .map { issueJson(cashImportIssue(it.code, fieldPath = it.fieldPath)) }
        }
        val displayRetained = retainedIssues.map { issue ->
            issueJson(cashImportIssue(issue.code, fieldPath = issue.fieldPath, afterImportAction = "open_home_item"))
        }
        if (record.state == "posted" && issues.isNotEmpty()) {
            val issue = issues.first()
            throw CashPolicyError(issue.getValue("code").jsonPrimitive.content,
                listOfNotNull(issue["field_path"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull))
        }
        val manual = if (record.state == "draft") patch.keys.filter { it in commonCashFields }.sortedWith(cashRelationStringOrder) else emptyList()
        val entryEdit = entry != null && publicPatch.isNotEmpty()
        val explanationChanged = explanation != null && !CashRecordPolicy.valuesEqual(explanation,
            record.relationMetadata?.let(::cashSourceValue) ?: emptyMap<String, Any?>())
        val action = if (valuesEqual(values, current) && manual.isEmpty() && !entryEdit && !explanationChanged) "duplicate" else "update_${record.state}"
        val diffs = fieldDiffs(current, values, publicPatch, record, entry, sourcePatch, sourceProtectedFields, patch.keys)
        return buildJsonObject {
            put("transaction_id", record.id.toString()); put("expected_revision", record.revision)
            put("planned_action", action); put("current", wireObject(current)); put("values", wireObject(values))
            put("manual_fields", JsonArray(manual.map(::JsonPrimitive)))
            put("type_changed", listOf("record_type", "record_subtype").any { !CashRecordPolicy.valuesEqual(values[it], current[it]) })
            if (explanation != null) put("source_explanation", sourceJson(explanation))
            put("field_diffs", JsonArray(diffs))
            put("issues", JsonArray(issues))
            put("display_retained_issues", JsonArray(displayRetained))
            put("expected_issues", JsonArray(existingIssues.map { issue -> buildJsonObject {
                put("issue_id", issue.id.toString()); put("revision", issue.revision); put("code", issue.code)
                put("field_path", issue.fieldPath?.let(::JsonPrimitive) ?: JsonNull); put("issue_key", issue.issueKey)
            } }))
            put("retained_issue_ids", JsonArray(retainedIssues.map { JsonPrimitive(it.id.toString()) }))
            put("expected_accounts", JsonArray(accountDependencies))
            put("entry_snapshot", JsonArray(context.entries.snapshot(listOf(record.id)).map {
                JsonArray(listOf(JsonPrimitive(it.transactionId), JsonPrimitive(it.entryId),
                    JsonPrimitive(it.revision), JsonPrimitive(it.digest)))
            }))
            put("preview_components", JsonArray((values["components"] as? List<Map<String, Any?>> ?: emptyList()).map {
                buildJsonObject { put("ordinal", (it["ordinal"] as? Int) ?: 0)
                    put("component_id", (it["component_id"] as? String) ?: "new_${it["ordinal"]}") }
            }))
            put("source_updates", JsonArray(emptyList())); put("pending_fields", JsonObject(emptyMap()))
            put("candidate_ids", JsonArray(emptyList()))
            if (entry != null && publicPatch.isNotEmpty()) put("entry_edit", buildJsonObject {
                put("entry_id", entry.id); put("revision", entry.revision)
                put("patch", wireObject(publicPatch))
            })
        }
    }

    fun entryValue(entry: EntryRow, field: String): JsonElement = when (field) {
        "category_id" -> entry.categoryId?.let(::JsonPrimitive) ?: JsonNull
        "counterparty" -> JsonPrimitive(entry.counterparty)
        "counterparty_account" -> JsonPrimitive(entry.counterpartyAccount)
        "counterparty_account_attrs" -> entry.attrs ?: JsonArray(emptyList())
        "note" -> JsonPrimitive(entry.note)
        else -> JsonNull
    }

    private fun draftAccounts(values: MutableMap<String, Any?>, accounts: Map<String, AccountRow>): Pair<List<JsonObject>, List<JsonObject>> {
        val required = linkedMapOf<Long?, MutableSet<String>>()
        @Suppress("UNCHECKED_CAST")
        val parts = values["components"] as? List<Map<String, Any?>> ?: emptyList()
        for (part in parts) {
            val account = (part["account_id"] as? String)?.toLongOrNull()
            val currencies = required.getOrPut(account) { linkedSetOf() }
            (part["currency"] as? String)?.let { currencies += it }
        }
        (values["account_id"] as? String)?.toLongOrNull()?.let { account ->
            val currencies = required.getOrPut(account) { linkedSetOf() }
            (values["currency"] as? String)?.let { currencies += it }
        }
        val issues = mutableListOf<JsonObject>()
        val dependencies = mutableListOf<JsonObject>()
        if (null in required || required.isEmpty())
            issues += issueJson(cashImportIssue("account_missing", afterImportAction = "open_home_item"))
        val unavailable = mutableSetOf<Long?>()
        for (identifier in required.keys.filterNotNull().sorted()) {
            val account = accounts[identifier.toString()]
            dependencies += buildJsonObject {
                put("account_id", identifier.toString()); put("exists", account != null)
                put("active", account?.active ?: false); put("type", account?.type?.let(::JsonPrimitive) ?: JsonNull)
                put("currency_support", JsonObject(required.getValue(identifier).sortedWith(cashRelationStringOrder)
                    .associateWith { JsonPrimitive(account != null && it in account.currencies) }))
            }
            if (account == null || !account.active || account.type !in setOf("cash", "loan", "lend")) {
                issues += issueJson(cashImportIssue("account_unavailable", afterImportAction = "open_home_item"))
                if (account == null) unavailable += identifier
                continue
            }
            if (required.getValue(identifier).any { it !in account.currencies })
                issues += issueJson(cashImportIssue("account_currency_missing", afterImportAction = "open_home_item"))
        }
        if ((values["account_id"] as? String)?.toLongOrNull() in unavailable) values["account_id"] = null
        values["components"] = parts.map { part ->
            if ((part["account_id"] as? String)?.toLongOrNull() in unavailable) part + ("account_id" to null) else part
        }
        return issues to dependencies
    }

    private fun fieldDiffs(current: MutableMap<String, Any?>, values: MutableMap<String, Any?>,
        patch: Map<String, Any?>, record: PlanRecord, entry: EntryRow?, sourcePatch: Map<String, Any?>,
        sourceProtectedFields: Set<String>, explicitFields: Set<String>): List<JsonObject> {
        val diffs = linkedMapOf<String, JsonObject>()
        for ((field, value) in sourcePatch) {
            if (field !in editableCashRecordFields || CashRecordPolicy.valuesEqual(value, current[field]) && field !in sourceProtectedFields) continue
            diffs[field] = buildJsonObject {
                put("field", field); put("current", cashValueJson(current[field]))
                put("incoming", cashValueJson(value)); put("effective", cashValueJson(values[field]))
                put("resolution", if (field in sourceProtectedFields && field !in explicitFields) "protected" else "apply")
            }
        }
        for ((field, value) in values) {
            if (field !in editableCashRecordFields) continue
            if (!CashRecordPolicy.valuesEqual(value, current[field]) && (field in explicitFields || field !in diffs)) diffs[field] = buildJsonObject {
                put("field", field); put("current", cashValueJson(current[field]))
                put("incoming", cashValueJson(value)); put("effective", cashValueJson(value)); put("resolution", "apply")
            }
        }
        if (entry != null) for (field in patch.keys.filter { it in commonCashFields }) {
            diffs[field] = buildJsonObject {
                put("field", field); put("current", entryValue(entry, field))
                put("incoming", cashValueJson(patch[field])); put("effective", cashValueJson(patch[field]))
                put("resolution", "apply")
            }
        }
        return diffs.keys.sortedWith(cashRelationStringOrder).map { diffs.getValue(it) }
    }

    private fun valuesEqual(left: Map<String, Any?>, right: Map<String, Any?>): Boolean {
        if (left.keys != right.keys) return false
        return left.all { (field, value) -> CashRecordPolicy.valuesEqual(value, right[field]) }
    }

    private val commonCashFields = setOf("category_id", "counterparty", "counterparty_account", "counterparty_account_attrs", "note")
    private val financialFields = setOf("amount", "currency", "occurred_at", "account_id", "account_name", "components", "cash_granularity")
    private val recomputedIssues = setOf("account_missing", "account_unavailable", "account_changed", "account_currency_missing",
        "posting_incomplete", "incomplete_record", "component_total_mismatch", "component_account_mismatch",
        "component_currency_mismatch", "component_direction_mismatch", "duplicate_component_ordinal")
    private val parsedIssues = setOf("allocation_missing", "amount_unreadable", "time_unreadable", "currency_unrecognized",
        "direction_unrecognized", "funding_unrecognized", "allocation_amount_invalid")
    private fun remainsParsedIssue(code: String, values: Map<String, Any?>, manual: Map<String, Any?>): Boolean {
        @Suppress("UNCHECKED_CAST")
        val parts = values["components"] as? List<Map<String, Any?>> ?: emptyList()
        return when (code) {
            "allocation_missing" -> !parts.all { it["amount"] != null }
            "funding_unrecognized" -> parts.isEmpty() || !parts.all { it["account_id"] != null }
            "allocation_amount_invalid" -> "components" !in manual
            "amount_unreadable" -> values["amount"] == null
            "time_unreadable" -> values["occurred_at"] == null
            "currency_unrecognized" -> values["currency"] == null
            "direction_unrecognized" -> manual.keys.none { it in setOf("amount", "record_type", "record_subtype") }
            else -> true
        }
    }

    fun protectedFields(record: PlanRecord, includeCommon: Boolean = false): Set<String> {
        val overrides = record.manualOverrides as? JsonObject ?: return emptySet()
        return overrides.filterValues { value -> when (value) {
            JsonNull -> false
            is JsonObject -> value.isNotEmpty()
            is JsonArray -> value.isNotEmpty()
            is JsonPrimitive -> if (value.isString) value.content.isNotEmpty() else value.booleanOrNull ?: (value.content.toBigDecimalOrNull()?.signum() != 0)
        } }.keys - (if (includeCommon) emptySet() else commonCashFields)
    }

    private fun wireObject(values: Map<String, Any?>): JsonObject = JsonObject(values.mapValues { cashValueJson(it.value) })

    private fun cashValueJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is BigInteger -> JsonPrimitive(value)
        is CashAmount -> JsonPrimitive(cashAmountText(value))
        is Instant -> JsonPrimitive(manualInstantText(value))
        is Map<*, *> -> JsonObject(value.entries.associate { it.key as String to cashValueJson(it.value) })
        is List<*> -> JsonArray(value.map(::cashValueJson))
        else -> throw CashPolicyError("invalid_field", emptyList())
    }

    private fun cashAmountText(amount: CashAmount): String {
        val raw = amount.toString()
        val decimal = java.math.BigDecimal(raw)
        val negativeZero = raw.startsWith('-') && decimal.signum() == 0
        return (if (negativeZero) "-" else "") + decimal.toPlainString()
    }

    fun issueJson(issue: CashImportIssueDto): JsonObject = buildJsonObject {
        put("code", issue.code); put("message", issue.message)
        put("field_path", issue.fieldPath?.let(::JsonPrimitive) ?: JsonNull)
        put("blocking_scope", issue.blockingScope); put("next_action", issue.nextAction)
        issue.afterImportAction?.let { put("after_import_action", JsonPrimitive(it)) }
    }
}
