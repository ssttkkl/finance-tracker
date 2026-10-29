package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import com.finance.tracker.business.contracts.CashImportIssueDto
import java.math.BigInteger
import java.time.Instant
import kotlinx.serialization.json.*
import org.jooq.impl.DSL

/** 首个维护计划构建切片：当前草稿的 save_draft；其余意图在后续切片接入。 */
internal class MutationPlanBuilder(private val context: CashSqlContext) {
    private val sql get() = context.sql
    private val workspace get() = context.workspace

    fun build(transactionId: Long, request: MutationPlanRequest): JsonObject {
        if (request.intent != "save_draft") {
            if (request.intent in setOf("edit_record", "edit_entry")) throw MutationPlanFailure("posted_required")
            throw MutationPlanFailure("plan_input_invalid")
        }
        val record = record(transactionId)
        if (record.state != "draft") throw MutationPlanFailure("draft_required")
        val current = fields(record)
        val patch = request.patch.mapValues { cashSourceValue(it.value) }
        val operation = prepareDraft(record, current, patch)
        val entry = entry(transactionId)
        val entryPublic = entryPublic(operation, entry, current, patch)
        val contextRows = CashRelationContextService(context).load(setOf(transactionId))
        val oldEdges = contextRows.relations.filter {
            it.primaryTransactionId?.toString() == transactionId.toString() ||
                it.secondaryTransactionId?.toString() == transactionId.toString()
        }
        val (editRef, _) = MutationPlanEntryTarget.target(EntryTargetInput(
            listOf(transactionId.toString()),
            contextRows.transactionRevisions.filter { it.first == transactionId }.map { JsonArray(listOf(JsonPrimitive(it.first), JsonPrimitive(it.second))) },
            contextRows.entryVersions.filter { it.transactionId == transactionId }.map {
                JsonArray(listOf(JsonPrimitive(it.transactionId), JsonPrimitive(it.entryId), JsonPrimitive(it.revision), JsonPrimitive(it.digest)))
            },
            oldEdges.mapNotNull { edge ->
                strictCashInteger(edge.id)?.let { id -> contextRows.relationRevisions.firstOrNull { it.first == id.toLong() } }
            }.map { JsonArray(listOf(JsonPrimitive(it.first), JsonPrimitive(it.second))) },
            emptyList(), oldEdges.map(MutationPlanEntryTarget::edge), mapOf(transactionId.toString() to emptyList()),
        ))
        val operationJson = operationJson(operation, current, editRef)
        val issues = operation.getValue("issues").jsonArray
        val displayRetained = operation.getValue("display_retained_issues").jsonArray
        val attention = JsonArray(issues.toList() + displayRetained.toList())
        val fact = CashProjectionFact.create(transactionId, record.accountId, record.occurredAt,
            operationAmountText(operation), operation.getValue("values").jsonObject["currency"]?.jsonPrimitive?.contentOrNull,
            entryPublic["counterparty"]?.jsonPrimitive?.contentOrNull ?: "", entryPublic["category_id"]?.let { if (it == JsonNull) null else it.jsonPrimitive.contentOrNull },
            entryPublic["note"]?.jsonPrimitive?.contentOrNull ?: "", record.sourceType, record.recordId, null,
            record.granularity, "draft", record.createdAt, issues.isNotEmpty())
        val projection = buildCashProjections(listOf(fact), emptyList()).projections.singleOrNull()
            ?: throw MutationPlanFailure("plan_build_failed")
        val accounts = accounts()
        val accountNames = displayAccounts(operation.getValue("values").jsonObject, accounts)
        val item = buildJsonObject {
            put("preview_item_id", "pi_1")
            put("confirmation_status", "pending")
            put("anticipated_state", "draft")
            put("display", buildJsonObject {
                put("occurred_at", projection.occurredAt?.let { JsonPrimitive(manualInstantText(it)) } ?: JsonNull)
                put("amount", projection.netAmount?.let { JsonPrimitive(cashAmountText(it)) } ?: JsonNull)
                put("currency", fact.currency?.let(::JsonPrimitive) ?: JsonNull)
                put("counterparty", entryPublic.getValue("counterparty"))
                put("note", entryPublic.getValue("note"))
                put("category_id", entryPublic.getValue("category_id"))
                put("counterparty_account", entryPublic.getValue("counterparty_account"))
                put("counterparty_account_attrs", entryPublic.getValue("counterparty_account_attrs"))
                put("accounts", JsonArray(accountNames.map(::JsonPrimitive)))
            })
            put("statistics_eligible", false)
            put("member_refs", JsonArray(listOf(buildJsonObject { put("transaction_id", transactionId.toString()) })))
            put("attention_reasons", attention)
            put("detail_available", true)
        }
        val editable = editableCashRecordFields.sortedWith(cashRelationStringOrder)
        val detail = buildJsonObject {
            put("entry", buildJsonObject {
                put("fields", JsonObject(entryPublic))
                put("editable_fields", JsonArray(commonCashFields.sortedWith(cashRelationStringOrder).map(::JsonPrimitive)))
                put("edit_ref", editRef)
            })
            put("members", JsonArray(listOf(buildJsonObject {
                put("transaction_id", transactionId.toString())
                put("planned_action", operation.getValue("planned_action"))
                put("fields_ref", buildJsonObject { put("operation_ref", "op_1") })
                put("editable_fields", JsonArray(editable.map(::JsonPrimitive)))
                put("allowed_actions", JsonArray(listOf(JsonPrimitive("save_draft"), JsonPrimitive("finalize_draft"))))
                put("row_revision", request.expectedRevision)
            })))
            put("relation_members", JsonArray(emptyList()))
            put("field_diffs", JsonArray(operation.getValue("field_diffs").jsonArray.map { diff ->
                JsonObject(diff.jsonObject + ("transaction_id" to JsonPrimitive(transactionId.toString())))
            }))
            put("issues", issues)
            put("relation_choices", JsonArray(emptyList()))
            put("relation_effects", JsonArray(emptyList()))
        }
        val summary = summary(operation.getValue("planned_action").jsonPrimitive.content)
        val result = buildJsonObject {
            put("items", JsonArray(listOf(item)))
            put("item_details", buildJsonObject { put("pi_1", detail) })
            put("detail_values", JsonObject(emptyMap()))
            put("entry_targets", JsonObject(emptyMap()))
            put("processing_details", JsonArray(emptyList()))
            put("file_results", JsonArray(emptyList()))
            put("summary", summary)
            put("can_commit", true)
            put("invalidated_choices", JsonArray(emptyList()))
            put("operations", JsonArray(listOf(operationJson)))
            put("rows", JsonArray(listOf(buildJsonObject {
                put("transaction_id", transactionId.toString()); put("operation_ref", "op_1")
                put("planned_action", operation.getValue("planned_action"))
            })))
        }
        return JsonObject(result + ("additional_effects" to MutationPlanEffects.additionalEffects(request, result)))
    }

    private data class PlanRecord(
        val id: Long, val state: String, val revision: Long, val origin: String, val sourceType: String?,
        val recordId: String, val manualTransferId: String?, val counterparty: String, val counterpartyAccount: String,
        val attrs: JsonElement?, val note: String, val categoryId: String?, val recordType: String?, val recordSubtype: String?,
        val sourcePayload: JsonElement?, val amount: CashStoredDecimal?, val currency: String?, val occurredAt: Instant?,
        val accountId: Long?, val granularity: String, val manualOverrides: JsonElement?, val relationMetadata: JsonElement?,
        val createdAt: Instant,
    )

    private fun record(id: Long): PlanRecord {
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

    private fun fields(record: PlanRecord): MutableMap<String, Any?> = linkedMapOf(
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

    private data class EntryRow(val id: String, val revision: Long, val categoryId: String?, val note: String,
        val counterparty: String, val counterpartyAccount: String, val attrs: JsonElement?, val manualFields: List<String>)
    private fun entry(id: Long): EntryRow? {
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
    private fun accounts(): Map<String, AccountRow> {
        val account = ACCOUNTS
        return sql.select(account.ID, account.NAME, account.TYPE, account.ACTIVE, account.CURRENCIES).from(account)
            .where(account.WORKSPACE_ID.eq(workspace)).fetch().associate {
                it[account.ID]!!.toString() to AccountRow(it[account.NAME]!!, it[account.TYPE]!!, it[account.ACTIVE]!!,
                    (it[account.CURRENCIES] as? JsonArray)?.mapNotNull { p -> (p as? JsonPrimitive)?.contentOrNull }?.toSet() ?: emptySet())
            }
    }

    private fun prepareDraft(record: PlanRecord, current: MutableMap<String, Any?>, patch: Map<String, Any?>): JsonObject {
        val protected = protectedFields(record)
        val update = CashRecordPolicy.prepare(current, patch, protected)
        var base = LinkedHashMap(current).apply { putAll(update.values) }
        val effects = prepareCashSourceEffects(base, patch, record.sourceType ?: "", record.relationMetadata, protected)
        base = LinkedHashMap(effects.values)
        var policyPatch = LinkedHashMap(patch)
        val publicPatch = CashRecordPolicy.prepare(mapOf("state" to "draft"),
            patch.filterKeys { it in commonCashFields }).values
        if (publicPatch.isNotEmpty()) {
            val category = publicPatch["category_id"] as? String
            if (category != null && !sql.fetchExists(CASH_CATEGORIES, CASH_CATEGORIES.WORKSPACE_ID.eq(workspace)
                    .and(CASH_CATEGORIES.ID.eq(category)))) throw CashPolicyError("category_unavailable", listOf("category_id"))
        }
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
        val effects2 = prepareCashSourceEffects(values, patch, record.sourceType ?: "", record.relationMetadata, protected)
        values.putAll(effects2.values)
        if ("components" in policyPatch || "components" in patch) {
            @Suppress("UNCHECKED_CAST")
            val requested = values["components"] as? List<Map<String, Any?>> ?: emptyList()
            validateCashSourceComponentIds(parts, requested)
            if (requested.any { "ordinal" !in it }) throw CashPolicyError("invalid_field", listOf("components"))
            if (requested.map { it["ordinal"] }.distinct().size != requested.size)
                throw CashPolicyError("invalid_field", listOf("components"))
        }
        val categoryId = values["category_id"] as? String
        if (categoryId != null && !sql.fetchExists(CASH_CATEGORIES, CASH_CATEGORIES.WORKSPACE_ID.eq(workspace)
                .and(CASH_CATEGORIES.ID.eq(categoryId)))) throw CashPolicyError("category_unavailable", listOf("category_id"))
        val issues = mutableListOf<JsonObject>()
        val accountIssues = draftAccounts(values, accounts())
        issues += accountIssues
        try { CashRecordPolicy.validateForPosting(values) } catch (failure: CashPolicyError) {
            issues += issueJson(cashImportIssue(failure.code, fieldPath = failure.fields.firstOrNull()))
        }
        val manual = if (record.state == "draft") patch.keys.filter { it in commonCashFields }.sortedWith(cashRelationStringOrder) else emptyList()
        val action = if (valuesEqual(values, current) && manual.isEmpty()) "duplicate" else "update_draft"
        val diffs = fieldDiffs(current, values, patch, record)
        return buildJsonObject {
            put("transaction_id", record.id.toString()); put("expected_revision", record.revision)
            put("planned_action", action); put("current", wireObject(current)); put("values", wireObject(values))
            put("manual_fields", JsonArray(manual.map(::JsonPrimitive)))
            put("type_changed", false)
            put("field_diffs", JsonArray(diffs))
            put("issues", JsonArray(issues))
            put("display_retained_issues", JsonArray(emptyList()))
            put("expected_issues", JsonArray(emptyList()))
            put("retained_issue_ids", JsonArray(emptyList()))
            put("expected_accounts", JsonArray(emptyList()))
            put("preview_components", JsonArray((values["components"] as? List<Map<String, Any?>> ?: emptyList()).map {
                buildJsonObject { put("ordinal", (it["ordinal"] as? Int) ?: 0); put("component_id", "new_${it["ordinal"]}") }
            }))
            put("source_updates", JsonArray(emptyList())); put("pending_fields", JsonObject(emptyMap()))
            put("candidate_ids", JsonArray(emptyList()))
        }
    }

    private fun operationJson(operation: JsonObject, current: MutableMap<String, Any?>, editRef: String): JsonObject =
        JsonObject(operation + mapOf(
            "operation_ref" to JsonPrimitive("op_1"), "row_refs" to JsonArray(emptyList()),
            "source_scope_key" to JsonPrimitive("unbound:${operation.getValue("transaction_id").jsonPrimitive.content}"),
            "matching_record_id" to JsonPrimitive("occurrence:${operation.getValue("transaction_id").jsonPrimitive.content}"),
            "matching_source_explanation" to JsonObject(emptyMap()),
        ))

    private fun entryPublic(operation: JsonObject, entry: EntryRow?, current: MutableMap<String, Any?>,
        patch: Map<String, Any?>): Map<String, JsonElement> {
        val values = operation.getValue("values").jsonObject
        val result = linkedMapOf<String, JsonElement>()
        for (field in commonCashFields.sortedWith(cashRelationStringOrder)) {
            val chosen = if (entry != null && field in entry.manualFields) entryValue(entry, field)
            else values[field] ?: JsonNull
            result[field] = chosen
        }
        if (entry == null) for (field in patch.keys.filter { it in commonCashFields })
            result[field] = cashValueJson(patch[field])
        return result
    }

    private fun entryValue(entry: EntryRow, field: String): JsonElement = when (field) {
        "category_id" -> entry.categoryId?.let(::JsonPrimitive) ?: JsonNull
        "counterparty" -> JsonPrimitive(entry.counterparty)
        "counterparty_account" -> JsonPrimitive(entry.counterpartyAccount)
        "counterparty_account_attrs" -> entry.attrs ?: JsonArray(emptyList())
        "note" -> JsonPrimitive(entry.note)
        else -> JsonNull
    }

    private fun draftAccounts(values: MutableMap<String, Any?>, accounts: Map<String, AccountRow>): List<JsonObject> {
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
        if (null in required || required.isEmpty())
            issues += issueJson(cashImportIssue("account_missing", afterImportAction = "open_home_item"))
        val unavailable = mutableSetOf<Long?>()
        for (identifier in required.keys.filterNotNull().sorted()) {
            val account = accounts[identifier.toString()]
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
        return issues
    }

    private fun fieldDiffs(current: MutableMap<String, Any?>, values: MutableMap<String, Any?>,
        patch: Map<String, Any?>, record: PlanRecord): List<JsonObject> {
        val diffs = linkedMapOf<String, JsonObject>()
        for ((field, value) in values) {
            if (field !in editableCashRecordFields) continue
            if (!CashRecordPolicy.valuesEqual(value, current[field])) diffs[field] = buildJsonObject {
                put("field", field); put("current", cashValueJson(current[field]))
                put("incoming", cashValueJson(value)); put("effective", cashValueJson(value)); put("resolution", "apply")
            }
        }
        return diffs.keys.sortedWith(cashRelationStringOrder).map { diffs.getValue(it) }
    }

    private fun valuesEqual(left: Map<String, Any?>, right: Map<String, Any?>): Boolean {
        if (left.keys != right.keys) return false
        return left.all { (field, value) -> CashRecordPolicy.valuesEqual(value, right[field]) }
    }

    private fun summary(action: String): JsonObject = buildJsonObject {
        val zeroKeys = listOf("duplicate", "skip", "relations_accepted", "relations_deferred", "relations_ignored",
            "relations_removed", "relations_failed")
        put(action, 1)
        for (key in zeroKeys) if (key != action) put(key, 0)
    }

    private fun displayAccounts(values: JsonObject, accounts: Map<String, AccountRow>): List<String> {
        val result = linkedSetOf<String>()
        for (part in values["components"]?.jsonArray ?: JsonArray(emptyList())) {
            val account = part.jsonObject["account_id"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull
            account?.let { accounts[it] }?.let { result += it.name }
        }
        return result.toList()
    }

    private val commonCashFields = setOf("category_id", "counterparty", "counterparty_account", "counterparty_account_attrs", "note")

    private fun protectedFields(record: PlanRecord): Set<String> {
        val overrides = record.manualOverrides as? JsonObject ?: return emptySet()
        return overrides.filterValues { value -> when (value) {
            JsonNull -> false
            is JsonObject -> value.isNotEmpty()
            is JsonArray -> value.isNotEmpty()
            is JsonPrimitive -> if (value.isString) value.content.isNotEmpty() else value.booleanOrNull ?: (value.content.toBigDecimalOrNull()?.signum() != 0)
        } }.keys - commonCashFields
    }

    private fun operationAmountText(operation: JsonObject): String? =
        operation.getValue("values").jsonObject["amount"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull

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
        val decimal = java.math.BigDecimal(amount.numericText())
        val negativeZero = amount.toString().startsWith('-') && decimal.signum() == 0
        return (if (negativeZero) "-" else "") + decimal.toPlainString()
    }

    private fun issueJson(issue: CashImportIssueDto): JsonObject = buildJsonObject {
        put("code", issue.code); put("message", issue.message)
        put("field_path", issue.fieldPath?.let(::JsonPrimitive) ?: JsonNull)
        put("blocking_scope", issue.blockingScope); put("next_action", issue.nextAction)
        issue.afterImportAction?.let { put("after_import_action", JsonPrimitive(it)) }
    }
}
