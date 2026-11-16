package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*

internal class LocalCashRecordRead(snapshot: LedgerSnapshotDto, renderer: LocalCashProjectionViews) {
    private val facts = snapshot.facts
    private val version = snapshot.ledgerSeq.toLong()
    private val records = facts.cashTransactions.associateBy { it.id }
    private val components = facts.cashTransactionComponents.groupBy { it.cashTransactionId }.mapValues { (_, parts) ->
        parts.sortedWith(compareBy<LedgerCashComponentFactDto> { it.ordinal.toLong() }.thenBy { it.id.toLong() })
    }
    private val entries = facts.cashEntries.associateBy { it.id }
    private val members = facts.cashEntryMembers.associateBy { it.transactionId }
    private val memberCounts = facts.cashEntryMembers.groupingBy { it.entryId }.eachCount()
    private val issues = facts.cashTransactionIssues.groupBy { it.transactionId }
    private val accounts = facts.accounts.associateBy { it.id }
    private val categories = facts.cashCategories.map { it.id }.toSet()
    private val views = renderer.entries.zip(renderer.build()).flatMap { (entry, view) ->
        entry.projection.memberIds.map { it.toString() to view }
    }.toMap()
    private val relationCandidates = facts.cashRelationCandidates.groupBy { it.candidateTransactionId }
    private val relationsById = facts.transactionRelations.associateBy { it.id }
    private val relationsByParent = buildMap<String, MutableList<LedgerCashRelationFactDto>> {
        for (relation in facts.transactionRelations) for (id in listOfNotNull(relation.primaryTransactionId,
            relation.secondaryTransactionId, relation.anchorTransactionId).distinct()) getOrPut(id) { mutableListOf() }.add(relation)
    }
    private val identityCandidates = facts.cashIssueCandidates.groupBy { it.issueId }
    private val bindings = facts.cashSourceBindings.groupBy { it.transactionId }
    private val bindingOwners = facts.cashSourceBindings.groupBy(::bindingKey).mapValues { (_, items) -> items.map { it.transactionId }.toSet() }
    private val evidenceOwners = facts.cashSourceBindings.filter { !it.stableEvidenceKey.isNullOrEmpty() }
        .groupBy { it.stableEvidenceKey }.mapValues { (_, items) -> items.map { it.transactionId }.toSet() }
    private val commonFields = setOf("category_id", "counterparty", "counterparty_account", "counterparty_account_attrs", "note")

    fun read(identifier: String, editable: Boolean): JsonObject {
        if (identifier.length !in 1..19 || identifier.any { it !in '0'..'9' } || identifier.toLongOrNull()?.let { it > 0 } != true)
            throw CashProjectionDomainFailure("record_not_found")
        val id = identifier.toLong().toString()
        val row = records[id]?.takeIf { it.deletedAt == null } ?: throw CashProjectionDomainFailure("record_not_found")
        val currentIssues = issues[id].orEmpty().sortedBy { it.id.toLong() }
        val sourceIssues = currentIssues.map(::sourceIssue)
        val currentRelations = (relationsByParent[id].orEmpty() + relationCandidates[id].orEmpty().map { relationsById.getValue(it.relationId) })
            .distinctBy { it.id }.sortedBy { it.id.toLong() }
        val pending = currentIssues.isNotEmpty() || currentRelations.any {
            it.active && it.state == "draft" && (it.primaryTransactionId == id || it.secondaryTransactionId == id)
        }
        val candidateRows = currentIssues.filter { it.code == "source_identity_ambiguous" }.associate { issue ->
            issue.id to identityCandidates[issue.id].orEmpty().mapNotNull { records[it.candidateTransactionId]?.takeIf { it.deletedAt == null } }
                .sortedBy { it.id.toLong() }
        }
        val identityChoices = if (editable && currentIssues.any { it.code == "source_identity_ambiguous" })
            availableIdentityResolutions(row, candidateRows.values.flatten().isNotEmpty()) else emptyList()
        val entry = members[id]?.entryId?.let(entries::get)
        val count = entry?.id?.let(memberCounts::get) ?: 0
        val editableFields = if (!editable || row.state == "posted" && entry != null && count > 1) emptySet() else when {
            row.state == "draft" -> editableCashRecordFields
            row.recordOrigin == "manual" -> (mutableCashFields + financialCashFields) - setOf("source_payload", "account_name")
            else -> emptySet()
        }
        val entryFields = if (row.recordOrigin == "manual" || row.recordOrigin == "manual_transfer" && entry != null && count == 2)
            (commonFields + financialCashFields + setOf("record_type", "record_subtype")) - "account_name" else commonFields
        return buildJsonObject {
            put("record_origin", row.recordOrigin)
            put("entry", entry?.let { entryJson(row, it, count, if (editable) entryFields else emptySet()) } ?: JsonNull)
            put("transaction_id", row.id); put("state", row.state); put("row_revision", row.rowRevision.toLong())
            put("confirmation_status", if (row.state == "draft" || pending) "pending" else "confirmed")
            put("fields", fieldsJson(row)); put("editable_fields", strings(editableFields))
            put("issues", JsonArray(currentIssues.map { issue -> issueJson(row, issue, sourceIssues, editable, identityChoices, candidateRows[issue.id].orEmpty()) }))
            put("relations", JsonArray(currentRelations.map { relation -> buildJsonObject {
                put("relation_id", relation.id); put("kind", relation.kind); put("state", relation.state); put("active", relation.active)
                put("row_revision", relation.rowRevision.toLong()); put("decision_group_id", text(relation.decisionGroupId))
            } }))
            put("projection_ref", views[id]?.let { view -> buildJsonObject {
                put("transaction_id", id); put("projection_id", view.projectionId)
                put("attention_count", view.attentionCount); put("projection_version", version)
            } } ?: JsonNull)
        }
    }

    private fun fieldsJson(row: LedgerCashTransactionFactDto) = buildJsonObject {
        put("counterparty", row.counterparty); put("counterparty_account", row.counterpartyAccount)
        put("counterparty_account_attrs", row.counterpartyAccountAttrs); put("note", row.note); put("category_id", text(row.categoryId))
        put("record_type", text(row.recordType)); put("record_subtype", text(row.recordSubtype)); put("source_payload", row.sourcePayload)
        put("amount", amount(row.amount)); put("currency", text(row.currency)); put("occurred_at", timestamp(row.occurredAt))
        put("account_id", text(row.accountId)); put("cash_granularity", row.cashGranularity); put("state", row.state)
        put("source_type", text(row.sourceType)); put("record_id", row.recordId); put("record_origin", row.recordOrigin)
        put("manual_transfer_id", text(row.manualTransferId))
        put("components", JsonArray(components[row.id].orEmpty().map { part -> buildJsonObject {
            put("component_id", part.id); put("ordinal", part.ordinal.toLong()); put("account_id", text(part.accountId))
            put("amount", amount(part.amount)); put("currency", text(part.currency))
        } }))
    }

    private fun entryJson(row: LedgerCashTransactionFactDto, entry: LedgerCashEntryFactDto, count: Int, fields: Set<String>) = buildJsonObject {
        put("entry_id", entry.id); put("revision", entry.revision.toLong())
        put("fields", buildJsonObject {
            put("category_id", text(entry.categoryId)); put("counterparty", entry.counterparty); put("counterparty_account", entry.counterpartyAccount)
            put("counterparty_account_attrs", entry.counterpartyAccountAttrs); put("note", entry.note)
        })
        put("manual_fields", JsonArray((entry.manualFields as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(::JsonPrimitive) }))
        val original = records[entry.rootTransactionId]
        val note = original?.let { cashSourceNote(it.sourceType.orEmpty(), decodeLocalCashValue(it.sourcePayload)) }.orEmpty()
        put("original_note", note)
        put("original_note_token", original?.let { text(sha256Hex(legacyReferenceUtf8("[${it.id}, ${it.rowRevision}, ${JsonPrimitive(note)}]"))) } ?: JsonNull)
        put("editable_fields", strings(fields)); put("supports_transfer_edit", row.recordOrigin == "manual_transfer" && count == 2)
    }

    private fun sourceIssue(issue: LedgerCashIssueFactDto) = CashSourceIssue(issue.id.toLong(), issue.revision.toLong(), issue.code,
        issue.fieldPath, issue.pendingFields, issue.pendingSourcePayload, issue.origin, issue.issueKey)

    private fun issueJson(row: LedgerCashTransactionFactDto, issue: LedgerCashIssueFactDto, all: List<CashSourceIssue>, editable: Boolean,
        identityChoices: List<String>, candidates: List<LedgerCashTransactionFactDto>): JsonObject {
        val reason = try { cashImportIssue(issue.code, fieldPath = issue.fieldPath) } catch (_: IllegalArgumentException) { cashImportIssue("posting_incomplete") }
        val choices = when {
            !editable -> emptyList()
            issue.code == "source_update_pending" -> availableSourceResolutions(row, issue, all)
            issue.code == "source_identity_ambiguous" -> identityChoices
            else -> emptyList()
        }
        val pendingFields = when (val pending = issue.pendingFields) {
            JsonNull -> emptyMap()
            is JsonObject -> pending.filterKeys { it in editableCashRecordFields }
            is JsonArray -> if (pending.isEmpty()) emptyMap() else error("invalid pending fields")
            else -> error("invalid pending fields")
        }
        val pending = try {
            val patch = pendingLocalCashSourceIssuePatch(all.first { it.id.toString() == issue.id })
            ("source_payload" in patch) to (patch["source_payload"] ?: JsonNull)
        } catch (_: CashPolicyError) { null to issue.pendingSourcePayload }
        return buildJsonObject {
            put("code", reason.code); put("message", reason.message); put("field_path", text(reason.fieldPath))
            put("blocking_scope", reason.blockingScope); put("next_action", reason.nextAction)
            reason.afterImportAction?.let { put("after_import_action", it) }
            put("issue_id", issue.id); put("revision", issue.revision.toLong()); put("allowed_resolutions", JsonArray(choices.map(::JsonPrimitive)))
            put("pending_fields", JsonObject(pendingFields)); put("pending_source_payload", pending.second)
            put("pending_source_payload_present", pending.first?.let(::JsonPrimitive) ?: JsonNull)
            if (issue.code == "source_identity_ambiguous") put("candidates", JsonArray(candidates.map { candidate -> buildJsonObject {
                put("transaction_id", candidate.id); put("row_revision", candidate.rowRevision.toLong()); put("state", candidate.state)
                put("display", buildJsonObject {
                    put("occurred_at", timestamp(candidate.occurredAt)); put("amount", amount(candidate.amount)); put("currency", text(candidate.currency))
                    put("counterparty", candidate.counterparty); put("note", candidate.note)
                    put("accounts", JsonArray(components[candidate.id].orEmpty().mapNotNull { accounts[it.accountId]?.name }.distinct().map(::JsonPrimitive)))
                })
            } }))
        }
    }

    private fun currentFields(row: LedgerCashTransactionFactDto): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        val result = (decodeLocalCashValue(fieldsJson(row)) as Map<String, Any?>).toMutableMap()
        result["amount"] = row.amount?.let(CashAmount::parse)
        result["occurred_at"] = row.occurredAt?.let(::parsePythonCashTimestamp)
        result["components"] = components[row.id].orEmpty().map { part -> mapOf("component_id" to part.id,
            "ordinal" to SourceJsonInteger(part.ordinal.toLong().toInt()), "account_id" to part.accountId, "amount" to part.amount?.let(CashAmount::parse), "currency" to part.currency) }
        return result
    }

    private fun availableSourceResolutions(row: LedgerCashTransactionFactDto, issue: LedgerCashIssueFactDto, all: List<CashSourceIssue>): List<String> = try {
        val current = currentFields(row)
        val protected = (row.manualOverrides as? JsonObject)?.filterValues(::truthy)?.keys.orEmpty() - commonFields
        val prepared = prepareLocalCashSourceIssueResolutions(fieldsJson(row), all,
            listOf(CashSourceResolution(issue.id, issue.revision.toLong(), "apply_source")), JsonObject(emptyMap()), protected)
        preparedPreconditions(row, current, prepared.patch, protected)
        listOf("apply_source", "keep_existing")
    } catch (_: CashPolicyError) { listOf("keep_existing") }
      catch (_: CashSourceIssueChanged) { listOf("keep_existing") }

    private fun preparedPreconditions(row: LedgerCashTransactionFactDto, current: Map<String, Any?>, patch: Map<String, Any?>, protected: Set<String>) {
        val update = LocalCashRecordValues.prepare(current, patch, protected)
        val base = LinkedHashMap(current).apply { putAll(update.values) }
        base.putAll(prepareCashSourceEffects(base, patch, row.sourceType.orEmpty(), row.relationMetadata, protected).values)
        var values: Map<String, Any?> = base
        var policyPatch = emptyMap<String, Any?>()
        if (row.state == "draft") {
            val linked = patch.keys intersect financialCashFields
            @Suppress("UNCHECKED_CAST") val parts = base["components"] as? List<Map<String, Any?>> ?: emptyList()
            if (linked.isNotEmpty() && "components" !in patch && base["cash_granularity"] == "atomic" && parts.size <= 1) {
                val applied = parts.ifEmpty { listOf(mapOf("ordinal" to SourceJsonInteger(0), "amount" to base["amount"], "currency" to base["currency"], "account_id" to base["account_id"])) }
                policyPatch = mapOf("components" to applied.map { part -> part + linked.associateWith { base[it] } })
            }
            val second = LocalCashRecordValues.prepare(base, policyPatch)
            values = LinkedHashMap(base).apply { putAll(second.values) }
        }
        if ("components" in policyPatch || "components" in patch) {
            @Suppress("UNCHECKED_CAST") val previous = current["components"] as? List<Map<String, Any?>> ?: emptyList()
            @Suppress("UNCHECKED_CAST") val requested = values["components"] as? List<Map<String, Any?>> ?: emptyList()
            validateLocalCashSourceComponentIds(previous, requested)
            if (requested.any { "ordinal" !in it } || requested.map { it["ordinal"] }.distinct().size != requested.size)
                throw CashPolicyError("invalid_field", listOf("components"))
        }
        val category = values["category_id"] as? String
        if (category != null && category !in categories) throw CashPolicyError("category_unavailable", listOf("category_id"))
        if (row.state == "draft") {
            @Suppress("UNCHECKED_CAST") val parts = values["components"] as? List<Map<String, Any?>> ?: emptyList()
            for (reference in parts.mapNotNull { it["account_id"] } + listOfNotNull(values["account_id"])) {
                val id = when (reference) { is String -> reference; is Long -> reference.toString(); is Int -> reference.toString(); else -> null }
                if (id == null || id.length !in 1..19 || id.any { it !in '0'..'9' } || id.toLongOrNull()?.let { it > 0 } != true)
                    throw CashPolicyError("record_input_invalid", emptyList())
            }
            try { LocalCashRecordValues.validateForPosting(values) } catch (_: CashPolicyError) { }
        }
    }

    private fun truthy(value: JsonElement): Boolean = when (value) {
        JsonNull -> false
        is JsonObject -> value.isNotEmpty()
        is JsonArray -> value.isNotEmpty()
        is JsonPrimitive -> when { value.isString -> value.content.isNotEmpty(); value.booleanOrNull != null -> value.boolean
            else -> decimalTruthy(value.content) }
    }

    private fun decimalTruthy(value: String): Boolean {
        val match = Regex("^[+-]?([0-9]+)(?:\\.([0-9]*))?(?:[eE]([+-]?[0-9]+))?$").matchEntire(value) ?: return true
        val exponent = if (match.groupValues[3].isEmpty()) 0L else match.groupValues[3].toLongOrNull() ?: return true
        val fraction = match.groupValues[2].length.toLong()
        if (exponent !in (fraction - Int.MAX_VALUE.toLong())..(fraction - Int.MIN_VALUE.toLong())) return true
        return (match.groupValues[1] + match.groupValues[2]).any { it != '0' }
    }

    private fun availableIdentityResolutions(row: LedgerCashTransactionFactDto, hasCandidates: Boolean): List<String> {
        if (row.state != "draft") return emptyList()
        if (!identitySnapshotAvailable(row)) return emptyList()
        return if (hasCandidates) listOf("create_independent", "merge_into") else listOf("create_independent")
    }

    private fun identitySnapshotAvailable(row: LedgerCashTransactionFactDto): Boolean {
        val selected = bindings[row.id].orEmpty()
        val accountBindings = selected.filter { it.sourceScopeKind == "account" }
        if (selected.any { it.sourceType != row.sourceType } || accountBindings.map { it.sourceScopeKey }.distinct().size > 1 ||
            accountBindings.filter { it.identityKind == "provider_id" }.map { it.identityKey }.distinct().size > 1 ||
            selected.mapNotNull { it.stableEvidenceKey?.takeIf(String::isNotEmpty) }.distinct().size > 1) return false
        val positions = mutableMapOf<String, MutableSet<String>>()
        val pages = mutableMapOf<Triple<String, String, String>, MutableMap<String, String>>()
        for (binding in selected) {
            val (document, fingerprints) = coverage(binding.coverage)
            if (document == null) continue
            if (binding.sourceScopeKind == "file_local" && binding.identityKind == "file_occurrence" && binding.sourceScopeKey ==
                localCashSourceDigest(listOf("source-document-v1", binding.sourceType, binding.strategyVersion, document)))
                positions.getOrPut(binding.sourceScopeKey) { mutableSetOf() }.add(binding.identityKey)
            val current = pages.getOrPut(Triple(binding.sourceType, binding.strategyVersion, document)) { mutableMapOf() }
            for ((page, fingerprint) in fingerprints) {
                val previous = current.put(page, fingerprint)
                if (previous != null && previous != fingerprint) return false
            }
        }
        if (positions.values.any { it.size > 1 }) return false
        val keys = selected.map(::bindingKey).toSet()
        val evidence = selected.mapNotNull { it.stableEvidenceKey?.takeIf(String::isNotEmpty) }.toSet()
        val owners = mutableSetOf<String>()
        for (key in keys) owners.addAll(bindingOwners[key].orEmpty())
        for (key in evidence) owners.addAll(evidenceOwners[key].orEmpty())
        return owners.none { it != row.id && records.getValue(it).deletedAt == null }
    }

    private fun bindingKey(binding: LedgerSourceBindingFactDto) = listOf(binding.sourceType, binding.sourceScopeKind,
        binding.sourceScopeKey, binding.identityKind, binding.identityKey)

    private fun coverage(value: JsonElement): Pair<String?, Map<String, String>> {
        if (value == JsonNull) return null to emptyMap()
        val item = value as? JsonObject ?: error("invalid source coverage")
        val allowed = setOf("start", "end", "complete", "evidence", "filters", "start_inclusive", "end_inclusive", "time_precision", "strategy_version", "document_key", "page_total", "page_fingerprints")
        check(item.keys.all { it in allowed }) { "invalid source coverage" }
        val documentValue = item["document_key"]?.takeIf { it != JsonNull }
        if (documentValue == null) {
            check(item["page_total"]?.takeIf { it != JsonNull } == null) { "invalid source coverage" }
            val fingerprints = item["page_fingerprints"]?.takeIf { it != JsonNull }
            check(fingerprints == null || fingerprints is JsonObject && fingerprints.isEmpty()) { "invalid source coverage" }
            return null to emptyMap()
        }
        val document = (documentValue as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("invalid source coverage")
        val total = (item["page_total"] as? JsonPrimitive)?.takeIf { !it.isString && it.booleanOrNull == null }?.content?.toIntOrNull() ?: error("invalid source coverage")
        val raw = item["page_fingerprints"] as? JsonObject ?: error("invalid source coverage")
        val fingerprints = raw.mapValues { (_, value) -> (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("invalid source coverage") }
        check(document.matches(Regex("[0-9a-f]{64}")) && total in 1..2000 && fingerprints.isNotEmpty() && fingerprints.size <= total &&
            fingerprints.all { (page, fingerprint) -> page.matches(Regex("[1-9][0-9]{0,3}")) && page.toInt() <= total && fingerprint.matches(Regex("[0-9a-f]{64}")) }) { "invalid source coverage" }
        return document to fingerprints
    }

    private fun strings(values: Collection<String>) = JsonArray(values.sortedWith(::pythonStringOrder).map(::JsonPrimitive))
    private fun timestamp(value: String?): JsonElement = value?.let { JsonPrimitive(formatPythonCashTimestamp(parsePythonCashTimestamp(it))) } ?: JsonNull
    private fun amount(value: String?): JsonElement = value?.let {
        val parsed = CashAmount.parse(it)
        val decimal = LedgerArithmeticValue.fromCashAmount(parsed)
        JsonPrimitive((if (parsed.representation.negative && decimal.signum == 0) "-" else "") + decimal.toPlainString())
    } ?: JsonNull
    private fun text(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
}
