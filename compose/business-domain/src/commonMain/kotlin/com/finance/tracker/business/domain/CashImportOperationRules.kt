package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.CashImportIssueDto

/** Immutable read projections of persistent objects; no mutable repository entities cross this boundary. */
data class CashImportEntryVersion(val id: String, val revision: Long)
data class CashImportOperationTarget(val recordId: String, val sourceType: String, val relationMetadata: Map<String, Any?>?)

interface CashImportOperationRead {
    fun source(files: List<ParsedImportFile>): PreparedCashSourcePlan
    fun accounts(selections: Map<String, Map<String, Any?>>, records: List<SourceRecord>, choices: Map<String, Map<String, Any?>>): Map<String, Map<String, Any?>>
    fun entries(transactionIds: Collection<String>): Pair<Map<String, CashImportEntryVersion>, Map<String, List<Any?>>>
    fun targets(records: Map<String, SourceRecord>): Pair<Map<String, CashImportOperationTarget>, Map<String, List<Map<String, Any?>>>>
    fun categories(choices: Map<String, Map<String, Any?>>): Set<String>
}

data class PreparedCashImportOperations(val rows: List<Map<String, Any?>>, val operations: List<Map<String, Any?>>,
    val source: PreparedCashSourcePlan, val scan: CashFundingScan, val candidateSets: Map<String, List<String>>)

private class SourceCandidateSets(private val digestValues: (List<Any?>) -> String) {
    private val values = linkedMapOf<String, List<String>>()
    private val groups = linkedMapOf<String, String>()
    fun reference(candidates: List<String>, groupRef: String? = null): String {
        if (groupRef != null && groupRef in groups) return groups.getValue(groupRef)
        val key = digestValues(listOf("cash-source-candidates-v1", candidates))
        if (key !in values) values[key] = candidates
        if (groupRef != null) groups[groupRef] = key
        return key
    }
    fun bind(value: MutableMap<String, Any?>, candidates: List<String>, groupRef: String? = null) {
        value["candidate_ids"] = emptyList<String>()
        value.remove("candidate_set_ref")
        if (candidates.isNotEmpty()) value["candidate_set_ref"] = reference(candidates, groupRef)
    }
    fun values(): Map<String, List<String>> = values.toMap()
}

/** Read-only per-row operation decisions shared by native and local import composition. */
class CashImportOperationRules(
    private val reads: CashImportOperationRead,
    private val recordValues: CashRecordValues,
    private val previewInput: (List<Any?>, List<Any?>) -> Map<String, Any?>,
    private val scanFunding: (List<ParsedImportFile>) -> CashFundingScan,
    private val resolveFunding: (CashFundingScan, Map<String, Map<String, Any?>>, Map<String, Map<String, Any?>>) -> List<CashFundingResolution>,
    private val postingValidator: () -> ((Map<*, *>) -> Unit),
    private val wireValue: (Any?) -> Any?,
    private val digestValues: (List<Any?>) -> String,
    private val allocateRecordId: (SourceOccurrence) -> String,
    private val issueValue: (String, String?, String?) -> CashImportIssueDto,
    private val integerValue: (Int) -> Any = { it },
) {
    private val importFields = mutableCashFields + financialCashFields
    private val importAndIdentity = importFields + setOf("record_id", "source_type", "source_scope_key",
        "source_account_key", "record_origin", "manual_transfer_id")

    private fun plainIssue(code: String, field: String? = null): Map<String, Any?> {
        val value = issueValue(code, field, null)
        return linkedMapOf<String, Any?>("code" to value.code, "message" to value.message,
            "field_path" to value.fieldPath, "blocking_scope" to value.blockingScope,
            "next_action" to value.nextAction) + (value.afterImportAction?.let {
            mapOf<String, Any?>("after_import_action" to it) } ?: emptyMap())
    }
    private fun issue(code: String, field: String? = null): Map<String, Any?> {
        val value = issueValue(code, field, "open_home_item")
        return linkedMapOf<String, Any?>("code" to value.code, "message" to value.message,
            "field_path" to value.fieldPath, "blocking_scope" to value.blockingScope, "next_action" to value.nextAction) +
            (value.afterImportAction?.let { mapOf<String, Any?>("after_import_action" to it) } ?: emptyMap())
    }
    fun prepare(files: List<ParsedImportFile>, accountSelections: List<Any?> = emptyList(),
        rowChoices: List<Any?> = emptyList(), recordIds: Map<String, String> = emptyMap(),
        sourcePlan: PreparedCashSourcePlan? = null, wireValues: Boolean = true): PreparedCashImportOperations {
        val validate = postingValidator()
        val filesList = files
        val preview = previewInput(accountSelections, rowChoices)
        val scan = scanFunding(filesList)
        val choices = (preview.getValue("row_choices") as List<Any?>).associateBy {
            (it as Map<*, *>)["row_ref"] as String
        }.mapValues { (_, raw) -> (raw as Map<*, *>).entries.associate { entry -> entry.key as String to entry.value } }
        val selections = (preview.getValue("account_selections") as List<Any?>).associateBy {
            (it as Map<*, *>)["source_account_ref"] as String
        }.mapValues { (_, raw) -> (raw as Map<*, *>).filterKeys { key -> key != "source_account_ref" }
            .entries.associate { entry -> entry.key as String to entry.value } }
        val allRows = linkedMapOf<String, ParsedCashRow>()
        for (file in filesList) {
            val document = file.document
            if (file.status == "parsed" && document != null)
                for (row in document.rows) allRows["${file.fileRef}:r${row.position}"] = row
        }
        if ((choices.keys - allRows.keys).isNotEmpty() ||
            (selections.keys - scan.groups.map { it.sourceAccountRef }.toSet()).isNotEmpty())
            throw CashImportPlanError("plan_input_invalid")
        val source = sourcePlan ?: reads.source(filesList)
        val accounts = reads.accounts(selections, source.records, choices)
        val funding = resolveFunding(scan, selections, accounts).associateBy { it.rowRef }
        val records = source.records.associateBy { it.transactionId }
        val entryState = reads.entries(records.keys)
        entries = entryState.first
        entrySnapshots = entryState.second
        val (targets, savedIssues) = reads.targets(records)
        val sourceResults = source.plan.rows.associateBy { it.rowRef }
        val groups = source.plan.groups.associateBy { it.groupRef }
        val operations = mutableListOf<MutableMap<String, Any?>>()
        val decisions = linkedMapOf<String, Map<String, Any?>>()
        val candidateSets = SourceCandidateSets(digestValues)
        val members = linkedMapOf<String, MutableList<String>>()
        for (resolution in source.plan.rows) members.getOrPut(resolution.duplicateOf ?: resolution.rowRef) { mutableListOf() } += resolution.rowRef
        val categories = reads.categories(choices)
        for ((root, related) in members) {
            val requested = related.mapNotNull { choices[it] }
            if (requested.size > 1) throw CashImportPlanError("plan_input_invalid")
            if (requested.isEmpty()) continue
            val choiceValue = requested.single()
            val resolution = sourceResults.getValue(root)
            val record = records[resolution.transactionId]
            val manual = (choiceValue["patch"] as? Map<String, Any?>) ?: emptyMap()
            val action = choiceValue["action"]
            if (action == "keep_existing" && (record == null || manual.isNotEmpty())) throw CashImportPlanError("plan_input_invalid")
            if (resolution.action in setOf("batch_duplicate", "duplicate") && record == null && (manual.isNotEmpty() || action != "apply"))
                throw CashImportPlanError("plan_input_invalid")
            val allowed = if (record != null && record.fields["state"] == "posted") commonCashFields else importFields
            if ((manual.keys - allowed).isNotEmpty()) throw CashImportPlanError("plan_input_invalid")
            try {
                recordValues.prepare(mapOf("state" to "draft"), manual)
                if ("components" in manual) {
                    val parts = recordValues.normalize("components", manual["components"]) as List<*>
                    val ordinals = parts.map { (it as Map<*, *>)["ordinal"] }
                    if (ordinals.any { it == null } || ordinals.distinct().size != ordinals.size)
                        throw CashPolicyError("invalid_field", listOf("components"))
                    validateComponentIds((record?.let { currentValues(it)["components"] } as? List<*>) ?: emptyList<Any?>(),
                        parts.filterIsInstance<Map<*, *>>())
                }
            } catch (_: CashPolicyError) { throw CashImportPlanError("plan_input_invalid") }
            catch (_: InvalidCashValue) { throw CashImportPlanError("plan_input_invalid") }
            val category = manual["category_id"]
            if (category != null && category !in categories) throw CashImportPlanError("plan_input_invalid")
        }
        if ((choices.keys - sourceResults.keys).isNotEmpty()) throw CashImportPlanError("plan_input_invalid")
        for (resolution in source.plan.rows) {
            if (resolution.duplicateOf != null) continue
            val ref = resolution.rowRef
            val related = members.getValue(ref)
            val requested = related.mapNotNull { choices[it] }
            val choiceValue = requested.firstOrNull() ?: mapOf("action" to "apply")
            val record = records[resolution.transactionId]
            var candidates: List<String> = emptyList()
            var candidateGroup: String? = null
            try {
                if (resolution.action == "conflict") {
                    if (record != null && resolution.reason == "fixed_field_conflict") {
                        val incoming = source.rows.rows.getValue(ref).fields.filterKeys { it in importFields }
                        recordValues.prepare(currentValues(record), incoming)
                    }
                    throw CashPolicyError(resolution.reason, emptyList())
                }
                if (resolution.action == "batch_duplicate") {
                    decisions[ref] = linkedMapOf("row_ref" to ref, "operation_ref" to null,
                        "planned_action" to "duplicate_in_batch", "source_group_ref" to resolution.groupRef)
                    continue
                }
                val operation: MutableMap<String, Any?>
                if (choiceValue["action"] == "keep_existing") {
                    operation = keep(record!!, targets.getValue(record.transactionId).recordId,
                        source.rows.rows.getValue(ref), savedIssues[record.transactionId].orEmpty()).toMutableMap()
                } else if (resolution.action == "duplicate" && record == null) {
                    decisions[ref] = linkedMapOf("row_ref" to ref, "operation_ref" to null,
                        "planned_action" to "duplicate", "source_group_ref" to resolution.groupRef)
                    continue
                } else {
                    candidates = resolution.candidateIds
                    if (resolution.groupRef in groups) {
                        candidates = groups.getValue(resolution.groupRef!!).candidateIds
                        candidateGroup = resolution.groupRef
                    }
                    operation = operation(ref, record, resolution.transactionId?.let { targets[it] },
                        resolution.transactionId?.let { savedIssues[it] }.orEmpty(), source.rows.rows.getValue(ref), resolution,
                        funding.getValue(ref), selections, choiceValue, candidates, categories,
                        allRows.getValue(ref).sourceExplanation, accounts, validate).toMutableMap()
                }
                candidateSets.bind(operation, candidates, candidateGroup)
                val occurrenceKey = source.rows.rows.getValue(ref).occurrenceKey
                operation["occurrence_key"] = occurrenceKey
                if (record == null && occurrenceKey in recordIds) {
                    (operation["values"] as MutableMap<String, Any?>)["record_id"] = recordIds.getValue(occurrenceKey)
                }
                operation["operation_ref"] = "op_${operations.size + 1}"
                operation["source_updates"] = related.map { item ->
                    linkedMapOf<String, Any?>("row_ref" to item,
                        "bind" to (sourceResults.getValue(item).bind || resolution.action == "pending"),
                        "uncertain" to (operation.getValue("issues") as List<*>).any {
                            (it as Map<*, *>)["code"] == "source_identity_ambiguous"
                        })
                }
                operation["row_refs"] = related
                operations += operation
                decisions[ref] = linkedMapOf("row_ref" to ref, "operation_ref" to operation["operation_ref"],
                    "planned_action" to operation["planned_action"], "transaction_id" to operation["transaction_id"])
            } catch (error: CashImportPlanError) { throw error }
            catch (error: CashPolicyError) {
                val skip = skip(ref, error.code, error.fields).toMutableMap()
                if (record != null && error.code == "fixed_field_conflict") {
                    val incoming = (source.rows.rows.getValue(ref).fields + (choiceValue["patch"] as? Map<String, Any?>).orEmpty())
                    skip["field_diffs"] = wire(((error.fields).filter { it in incoming && it in financialCashFields + identityFields() }
                        .map { field -> linkedMapOf<String, Any?>("field" to field, "current" to record.fields[field],
                            "incoming" to incoming[field], "effective" to record.fields[field], "resolution" to "fixed_conflict") }))
                }
                decisions[ref] = skip
            }
        }
        for (resolution in source.plan.rows) {
            val duplicateOf = resolution.duplicateOf ?: continue
            val root = decisions.getValue(duplicateOf)
            val duplicated = LinkedHashMap(root)
            duplicated["row_ref"] = resolution.rowRef
            if (root["planned_action"] != "skip") duplicated["planned_action"] = "duplicate_in_batch"
            decisions[resolution.rowRef] = duplicated
        }
        for ((ref, row) in allRows) if (ref !in decisions) decisions[ref] = skip(ref, row.errorCode ?: "row_unreadable")
        sharedEntryEdits(operations)
        val output = if (wireValues) operations.map { wireMap(it)!! } else operations
        return PreparedCashImportOperations(allRows.keys.map { decisions.getValue(it) }, output,
            source, scan, candidateSets.values())
    }

    private fun identityFields() = setOf("record_id", "source_type", "source_scope_key", "source_account_key",
        "record_origin", "manual_transfer_id")

    private fun sharedEntryEdits(operations: List<MutableMap<String, Any?>>) {
        val shared = linkedMapOf<String, MutableList<MutableMap<String, Any?>>>()
        for (operation in operations) {
            val edit = operation["entry_edit"] as? MutableMap<String, Any?> ?: continue
            val identifier = edit["entry_id"] as? String ?: continue
            shared.getOrPut(identifier) { mutableListOf() } += operation
        }
        for ((entryId, members) in shared) {
            if (members.size < 2) continue
            val public = linkedMapOf<String, Any?>()
            for (operation in members) {
                val edit = operation["entry_edit"] as Map<String, Any?>
                for ((field, value) in edit["patch"] as Map<String, Any?>) {
                    if (field in public && public[field] != value) throw CashImportPlanError("plan_input_invalid")
                    public[field] = value
                }
                operation["entry_edit_group"] = entryId
            }
            ((members[0]["entry_edit"] as MutableMap<String, Any?>)["patch"]) = public
            for (operation in members.drop(1)) operation.remove("entry_edit")
        }
    }

    private fun currentValues(record: SourceRecord): Map<String, Any?> =
        record.fields.mapValues { (field, value) -> recordValues.normalize(field, value) }

    private fun componentFinances(components: Any?): List<Any?> = (components as? List<*> ?: emptyList<Any?>()).map { raw ->
        val item = raw as? Map<*, *> ?: throw CashPolicyError("invalid_field", listOf("components"))
        linkedMapOf<String, Any?>("ordinal" to item["ordinal"], "account_id" to item["account_id"],
            "amount" to item["amount"], "currency" to item["currency"])
    }

    private fun skip(ref: String, code: String, fields: List<String> = emptyList()): Map<String, Any?> {
        val field = fields.firstOrNull { it in importAndIdentity }
        val issue = try { plainIssue(code, field) } catch (_: IllegalArgumentException) {
            return linkedMapOf("row_ref" to ref, "operation_ref" to null, "planned_action" to "skip",
                "code" to "row_unreadable", "issues" to listOf(plainIssue("row_unreadable")))
        }
        return linkedMapOf("row_ref" to ref, "operation_ref" to null, "planned_action" to "skip",
            "code" to code, "issues" to listOf(issue))
    }

    private fun keep(record: SourceRecord, recordId: String, occurrence: SourceOccurrence,
        savedIssues: List<Map<String, Any?>>): Map<String, Any?> {
        val values = (currentValues(record) + mapOf("record_id" to recordId, "source_type" to record.sourceType)).toMutableMap()
        return linkedMapOf(
            "transaction_id" to record.transactionId, "expected_revision" to integerValue(record.rowRevision),
            "planned_action" to "duplicate", "current" to recordValues.copyValue(values), "values" to values,
            "manual_fields" to emptyList<Any?>(), "field_diffs" to occurrence.fields.filterKeys { it in mutableCashFields }
                .filter { (field, incoming) -> incoming != values[field] }.map { (field, incoming) ->
                    linkedMapOf<String, Any?>("field" to field, "current" to values[field], "incoming" to incoming,
                        "effective" to values[field], "resolution" to "keep_existing")
                },
            "issues" to savedIssues.map { issue(it["code"] as String, it["field_path"] as? String) },
            "pending_fields" to emptyMap<String, Any?>(), "type_changed" to false, "keep_existing" to true,
            "expected_issues" to savedIssues, "retained_issue_ids" to savedIssues.map { it["issue_id"] },
            "expected_accounts" to emptyList<Any?>())
    }

    private fun operation(ref: String, record: SourceRecord?, target: CashImportOperationTarget?, savedIssues: List<Map<String, Any?>>,
        occurrence: SourceOccurrence, resolution: SourceBatchResolution, funding: CashFundingResolution,
        selections: Map<String, Map<String, Any?>>, choice: Map<String, Any?>, candidates: List<String>,
        categories: Set<String>, explanation: Map<String, Any?>?, accounts: Map<String, Map<String, Any?>>,
        validate: (Map<*, *>) -> Unit): Map<String, Any?> {
        val isPosted = record != null && record.fields["state"] == "posted"
        val current = (if (record != null) currentValues(record) else linkedMapOf(
            "state" to "draft", "amount" to null, "currency" to null, "occurred_at" to null, "account_id" to null,
            "cash_granularity" to "atomic", "components" to emptyList<Any?>(), "record_type" to null,
            "record_subtype" to null, "counterparty" to "", "counterparty_account" to "",
            "counterparty_account_attrs" to emptyList<String>(), "note" to "", "category_id" to null,
            "source_payload" to null)).toMutableMap()
        if (target != null) { current["record_id"] = target.recordId; current["source_type"] = target.sourceType }
        val patch = occurrence.fields.filterKeys { it in importFields }.toMutableMap()
        if (!isPosted) {
            funding.errorCode?.let { throw CashPolicyError(it, emptyList()) }
            if (record == null || funding.groupRefs.any { it in selections }) {
                funding.fields.filterKeys { it in financialCashFields }.forEach { (key, value) -> patch[key] = value }
                if (record != null) patch["components"] = retainComponentIds(current["components"] as List<*>,
                    patch["components"])
            }
        } else {
            val selected = funding.groupRefs.filter { it in selections }.map { selections.getValue(it)["account_id"] }
            val existing = (current["components"] as List<*>).mapNotNull { (it as Map<*, *>)["account_id"]?.toString() }.toSet()
            if (selected.any { it != null && it.toString() !in existing }) throw CashPolicyError("fixed_field_conflict", listOf("account_id"))
            if (selected.isNotEmpty() && selected.size == funding.groupRefs.size && selected.all { it != null } && funding.ready) {
                val incoming = componentFinances(funding.fields["components"])
                val old = componentFinances(current["components"])
                val normalized = recordValues.prepare(mapOf("state" to "draft"), mapOf("components" to incoming)).values["components"]
                val expected = recordValues.prepare(mapOf("state" to "draft"), mapOf("components" to old)).values["components"]
                if (recordValues.valuesEqual(normalized, expected).not()) throw CashPolicyError("fixed_field_conflict", listOf("components"))
            }
        }
        val sourceUpdate = recordValues.prepare(current, patch, record?.protectedFields?.toSet() ?: emptySet())
        val sourceValues = (current + sourceUpdate.values).toMutableMap()
        val manual = (choice["patch"] as? Map<String, Any?>) ?: emptyMap()
        if (isPosted && (manual.keys - commonCashFields).isNotEmpty())
            throw CashPolicyError("field_not_editable", (manual.keys - commonCashFields).sorted())
        var publicPatch = manual.filterKeys { it in commonCashFields }
        if (publicPatch.isNotEmpty()) publicPatch = recordValues.prepare(mapOf("state" to "draft"), publicPatch).values
        val sourceManual = manual.filterKeys { it !in commonCashFields }
        val manualValues = try {
            if (sourceManual.isNotEmpty()) recordValues.prepare(sourceValues, sourceManual).values else emptyMap<String, Any?>()
        } catch (_: CashPolicyError) { throw CashImportPlanError("plan_input_invalid") }
        val entry = record?.let { entriesFor(record.transactionId) }
        var entryEdit = if (publicPatch.isNotEmpty()) linkedMapOf<String, Any?>("entry_id" to entry?.id,
            "revision" to entry?.revision, "patch" to publicPatch) else null
        if ("components" in manual) validateComponentIds(current["components"] as List<*>,
            (recordValues.normalize("components", manual["components"]) as List<*>).filterIsInstance<Map<*, *>>())
        if (manual["category_id"] != null && manual["category_id"] !in categories)
            throw CashPolicyError("category_unavailable", listOf("category_id"))
        var values = (sourceValues + manualValues).toMutableMap()
        var explanationValue = explanation
        val effects = prepareCashSourceEffects(values, sourceManual, occurrence.sourceType, explanation,
            record?.protectedFields?.toSet() ?: emptySet())
        values = effects.values.toMutableMap()
        effects.explanation?.let { explanationValue = it }
        if ((values["components"] as List<*>).any { (it as Map<*, *>)["ordinal"] == null })
            throw CashPolicyError("invalid_field", listOf("components"))
        val protected = mutableListOf<String>()
        val issues = mutableListOf<Map<String, Any?>>()
        var pending: Map<String, Any?> = emptyMap()
        var expectedAccounts: List<Any?> = emptyList()
        if (choice["action"] == "defer" && isPosted) {
            pending = values.filter { (key, value) -> value != current[key] }
            values = recordValues.copyValue(current) as MutableMap<String, Any?>
            entryEdit = null
            if (pending.isNotEmpty()) issues += issue("source_update_pending")
        } else if (!isPosted) {
            if (manual.keys.any { it in financialCashFields }) {
                try { recordValues.validateConsistency(values) }
                catch (_: CashPolicyError) { throw CashImportPlanError("plan_input_invalid") }
            }
            val (accountIssues, dependencies) = draftAccounts(values, accounts)
            val explicit = mutableSetOf<String>()
            manual["account_id"]?.let { explicit += it.toString() }
            (manual["components"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
                .forEach { part -> part["account_id"]?.let { explicit += it.toString() } }
            if (dependencies.any { dependency ->
                    ((dependency["account_id"] as String) in explicit || "currency" in manual) &&
                        (!(dependency["exists"] as Boolean) || !(dependency["active"] as Boolean) ||
                            dependency["type"] !in setOf("cash", "loan", "lend") ||
                            (dependency["currency_support"] as Map<*, *>).values.any { it != true })
                }) throw CashImportPlanError("plan_input_invalid")
            issues += accountIssues
            expectedAccounts = dependencies
            try { validate(values) }
            catch (error: CashPolicyError) { issues += issue(error.code, error.fields.firstOrNull()) }
            for (code in remainingFundingIssues(funding.issueCodes, values, manual)) {
                if (record == null || funding.groupRefs.any { it in selections }) issues += issue(code)
            }
            if (resolution.action == "pending" || savedIssues.any { it["code"] == "source_identity_ambiguous" })
                issues += issue("source_identity_ambiguous")
            if (choice["action"] == "defer" && issues.isEmpty()) issues += issue("posting_incomplete")
            values["state"] = if (issues.isEmpty()) "posted" else "draft"
        }
        for (saved in savedIssues) {
            val issueKey = saved["issue_key"] as? String ?: ""
            if (issueKey.startsWith("relation:")) issues += issue(saved["code"] as String, saved["field_path"] as? String)
        }
        val action = when {
            record == null -> {
                values["record_id"] = allocateRecordId(occurrence)
                values["source_type"] = occurrence.sourceType
                "create_${values["state"]}"
            }
            current["state"] == "draft" && values["state"] == "posted" -> "finalize_draft"
            values != current || entryEdit != null || pending.isNotEmpty() ||
                (choice["action"] != "defer" && explanationValue != (target?.relationMetadata ?: emptyMap<String, Any?>())) ->
                "update_${current["state"]}"
            else -> "duplicate"
        }
        val diffs = mutableListOf<Map<String, Any?>>()
        for ((field, incoming) in patch) {
            if (record != null && (incoming != current[field] || field in sourceUpdate.protectedFields)) {
                diffs += linkedMapOf("field" to field, "current" to current[field], "incoming" to incoming,
                    "effective" to values[field], "resolution" to when {
                        pending.isNotEmpty() -> "keep_existing"
                        field in sourceUpdate.protectedFields && field !in manual -> "protected"
                        else -> "apply"
                    })
            }
        }
        val used = mutableListOf<Map<String, Any?>>()
        if ((!isPosted || choice["action"] != "defer") && manual.keys.none { it in setOf("account_id", "components", "cash_granularity") }) {
            fun comparable(parts: Any?): Any? = recordValues.prepare(mapOf("state" to "draft"),
                mapOf("components" to componentFinances(parts))).values["components"]
            if (!isPosted || recordValues.valuesEqual(comparable(values["components"]),
                    comparable(funding.fields["components"]))) {
                val required = linkedMapOf<String, MutableSet<String>>()
                for (part in values["components"] as List<*>) {
                    val item = part as Map<*, *>
                    val currency = item["currency"] as? String ?: continue
                    required.getOrPut(item["account_id"]?.toString() ?: "") { linkedSetOf() } += currency
                }
                val accountId = values["account_id"]?.toString(); val currency = values["currency"] as? String
                if (accountId != null && currency != null) required.getOrPut(accountId) { linkedSetOf() } += currency
                for ((group, account) in funding.selectedGroups) {
                    val supported = (accounts[account]?.get("currencies") as? List<*>)?.toSet() ?: emptySet<Any?>()
                    if (required[account].orEmpty().all { it in supported })
                        used += linkedMapOf("source_account_ref" to group, "source_type" to occurrence.sourceType, "account_id" to account)
                }
            }
        }
        val result = linkedMapOf<String, Any?>()
        entryEdit?.let { result["entry_edit"] = it }
        result["entry_snapshot"] = record?.let { snapshotsFor(it.transactionId) } ?: emptyList<Any?>()
        result["transaction_id"] = record?.transactionId
        result["expected_revision"] = record?.rowRevision?.let(integerValue)
        result["planned_action"] = action
        result["current"] = if (record != null) current else null
        result["values"] = values
        result["manual_fields"] = protected
        result["field_diffs"] = diffs
        result["issues"] = linkedMapOf<Pair<String?, String?>, Map<String, Any?>>().apply {
            for (item in issues) put(item["code"]?.toString() to item["field_path"]?.toString(), item)
        }.values.toList()
        result["pending_fields"] = pending
        result["type_changed"] = listOf("record_type", "record_subtype").any { values[it] != current[it] }
        result["candidate_ids"] = candidates
        result["source_group_ref"] = resolution.groupRef
        result["source_explanation"] = recordValues.copyValue(explanationValue)
        result["expected_issues"] = savedIssues
        result["expected_accounts"] = expectedAccounts
        result["used_account_selections"] = used
        result["retained_issue_ids"] = savedIssues.filter { it["code"] == "source_identity_ambiguous" ||
            (it["issue_key"] as? String ?: "").startsWith("relation:") }.map { it["issue_id"] }
        return result
    }

    private fun entriesFor(transactionId: String): CashImportEntryVersion? = entries[transactionId]
    private fun snapshotsFor(transactionId: String): List<Any?> = entrySnapshots[transactionId] ?: emptyList()
    private var entries: Map<String, CashImportEntryVersion> = emptyMap()
    private var entrySnapshots: Map<String, List<Any?>> = emptyMap()

    private fun validateComponentIds(current: List<*>, requested: List<Map<*, *>>) {
        val owned = current.mapNotNull { (it as Map<*, *>)["component_id"]?.toString() }.toSet()
        val references = requested.mapNotNull { it["component_id"]?.toString() }
        if (references.any { it !in owned } || references.size != references.distinct().size)
            throw CashPolicyError("component_reference_invalid", listOf("components"))
        val allowed = setOf("component_id", "account_id", "ordinal", "amount", "currency")
        if (requested.any { it.keys.any { key -> key !in allowed } })
            throw CashPolicyError("invalid_field", listOf("components"))
    }

    private fun retainComponentIds(current: List<*>, incoming: Any?): List<Any?> {
        val normalized = recordValues.normalize("components", incoming) as List<*>
        val available = linkedMapOf<String, Map<*, *>>()
        for (part in current) {
            val item = part as Map<*, *>
            val identifier = item["component_id"]?.toString() ?: continue
            available[identifier] = item
        }
        val byFinance = linkedMapOf<List<Any?>, MutableList<String>>()
        for ((identifier, part) in available) {
            val key = listOf(part["account_id"], part["amount"], part["currency"])
            byFinance.getOrPut(key) { mutableListOf() } += identifier
        }
        for (raw in normalized) {
            @Suppress("UNCHECKED_CAST") val part = raw as MutableMap<String, Any?>
            val key = listOf(part["account_id"], part["amount"], part["currency"])
            val options = byFinance[key].orEmpty().filter { it in available }
            if (options.isEmpty()) continue
            val chosen = options.firstOrNull { available.getValue(it)["ordinal"] == part["ordinal"] } ?: options.first()
            part["component_id"] = chosen
            available.remove(chosen)
        }
        val byOrdinal = linkedMapOf<Any?, Any?>()
        for ((identifier, part) in available) byOrdinal[part["ordinal"]] = identifier
        for (raw in normalized) {
            @Suppress("UNCHECKED_CAST") val part = raw as MutableMap<String, Any?>
            if ("component_id" !in part && part["ordinal"] in byOrdinal) {
                part["component_id"] = byOrdinal[part["ordinal"]]!!
            }
        }
        return normalized
    }

    private fun draftAccounts(values: MutableMap<String, Any?>, accounts: Map<String, Map<String, Any?>>):
        Pair<List<Map<String, Any?>>, List<Map<String, Any?>>> {
        val required = linkedMapOf<String?, MutableSet<String>>()
        for (part in values["components"] as? List<*> ?: emptyList<Any?>()) {
            val item = part as? Map<*, *> ?: continue
            val key = item["account_id"]?.toString()
            val set = required.getOrPut(key) { linkedSetOf() }
            (item["currency"] as? String)?.let { set += it }
        }
        val parentAccount = values["account_id"]?.toString()
        if (parentAccount != null) required.getOrPut(parentAccount) { linkedSetOf() }
            .also { (values["currency"] as? String)?.let { currency -> it += currency } }
        val issues = mutableListOf<Map<String, Any?>>()
        val dependencies = mutableListOf<Map<String, Any?>>()
        if (null in required || required.isEmpty()) issues += issue("account_missing")
        val unavailable = mutableSetOf<String>()
        for (identifier in required.keys.filterNotNull().sortedWith(cashRelationStringOrder)) {
            val account = accounts[identifier]
            val supported = required.getValue(identifier).sortedWith(cashRelationStringOrder).associateWith { currency ->
                account != null && (account["currencies"] as? List<*>)?.contains(currency) == true
            }
            dependencies += linkedMapOf("account_id" to identifier, "exists" to (account != null),
                "active" to (account?.get("active") as? Boolean ?: false), "type" to account?.get("type"),
                "currency_support" to supported)
            if (account == null || account["active"] != true || account["type"] !in setOf("cash", "loan", "lend")) {
                issues += issue("account_unavailable")
                if (account == null) unavailable += identifier
                continue
            }
            if (supported.values.any { !it }) issues += issue("account_currency_missing")
        }
        if (values["account_id"]?.toString() in unavailable) values["account_id"] = null
        @Suppress("UNCHECKED_CAST")
        values["components"] = (values["components"] as List<*>).map { raw ->
            val item = (raw as Map<*, *>).toMutableMap()
            if (item["account_id"]?.toString() in unavailable) item["account_id"] = null
            item
        }
        return issues to dependencies
    }

    private fun remainingFundingIssues(codes: List<String>, values: Map<String, Any?>, manual: Map<String, Any?>): List<String> {
        val accountCodes = setOf("account_missing", "account_unavailable", "account_changed", "account_currency_missing")
        val recomputed = setOf("incomplete_record", "component_total_mismatch", "component_account_mismatch",
            "component_currency_mismatch", "component_direction_mismatch", "duplicate_component_ordinal")
        val components = values["components"] as? List<*> ?: emptyList<Any?>()
        val result = mutableListOf<String>()
        for (code in codes) {
            if (code in accountCodes) { if (manual.keys.none { it in setOf("account_id", "components") }) result += code; continue }
            if (code in recomputed) continue
            if (code == "allocation_missing" && components.all { (it as Map<*, *>)["amount"] != null }) continue
            if (code == "funding_unrecognized" && components.isNotEmpty() && components.all { (it as Map<*, *>)["account_id"] != null }) continue
            if (code == "allocation_amount_invalid" && "components" in manual) continue
            val corrected = mapOf("amount_unreadable" to "amount", "time_unreadable" to "occurred_at", "currency_unrecognized" to "currency")[code]
            if (corrected != null && values[corrected] != null) continue
            if (code == "direction_unrecognized" && manual.keys.any { it in setOf("amount", "record_type", "record_subtype") }) continue
            result += code
        }
        return result
    }

    private fun wire(value: Any?): Any? = wireValue(value)
    @Suppress("UNCHECKED_CAST")
    private fun wireMap(value: Map<String, Any?>): Map<String, Any?>? = wire(value) as? Map<String, Any?>
}
