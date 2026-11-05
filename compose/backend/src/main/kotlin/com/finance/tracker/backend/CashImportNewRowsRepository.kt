package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import kotlinx.serialization.json.*
import org.jooq.Field

/** 有界全新正式流水；父项与现金部分按输入顺序取回 ID，失败交外层回滚。 */
internal class CashImportNewRowsRepository(private val context: CashSqlContext) {
    @Suppress("UNCHECKED_CAST") private fun map(value: Any?) = value as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") private fun maps(value: Any?) = value as List<Map<String, Any?>>
    private fun fail(code: String): Nothing = throw CashSourceBindingConflict(code)

    fun insert(entries: List<Pair<Map<String, Any?>, List<SourceOccurrence>>>): List<CashRecordWriteResult> {
        context.requireActive()
        require(entries.size in 1..100) { "new_import_batch_invalid" }
        val incoming = entries.flatMap { it.second }
        val occupied = CashSourceReadRepository(context).forKeys(incoming.flatMap { it.bindingSpecs }.distinct(), incoming.mapNotNull { it.stableEvidenceBindingKey }.distinct())
        if (occupied.isNotEmpty()) fail("source_identity_conflict")
        val values = entries.map { (operation, _) -> map(operation["values"]).mapValues { (key, value) -> CashRecordPolicy.normalize(key, value) } }
        val fact = CASH_TRANSACTIONS
        val parentFields = mapOf("state" to fact.STATE, "source_type" to fact.SOURCE_TYPE, "record_id" to fact.RECORD_ID,
            "counterparty" to fact.COUNTERPARTY, "counterparty_account" to fact.COUNTERPARTY_ACCOUNT, "counterparty_account_attrs" to fact.COUNTERPARTY_ACCOUNT_ATTRS,
            "note" to fact.NOTE, "category_id" to fact.CATEGORY_ID, "record_type" to fact.RECORD_TYPE, "record_subtype" to fact.RECORD_SUBTYPE,
            "source_payload" to fact.SOURCE_PAYLOAD, "amount" to fact.AMOUNT, "currency" to fact.CURRENCY, "occurred_at" to fact.OCCURRED_AT,
            "account_id" to fact.ACCOUNT_ID, "cash_granularity" to fact.CASH_GRANULARITY)
        val parents = entries.mapIndexed { index, (operation, _) ->
            val defaults = linkedMapOf<Field<*>, Any?>(fact.WORKSPACE_ID to context.workspace, fact.RECORD_ORIGIN to "import", fact.STATE to "posted",
                fact.CASH_GRANULARITY to "atomic", fact.ROW_REVISION to 1L, fact.SOURCE_TYPE to null, fact.RECORD_ID to "", fact.SOURCE_PAYLOAD to JsonNull,
                fact.RELATION_METADATA to sourceJson(operation["source_explanation"]), fact.MANUAL_OVERRIDES to JsonObject((operation["manual_fields"] as List<*>).associate { it.toString() to JsonPrimitive(true) }),
                fact.SOURCE_FINGERPRINT to null, fact.MANUAL_TRANSFER_ID to null, fact.ACCOUNT_ID to null, fact.AMOUNT to null, fact.CURRENCY to null,
                fact.OCCURRED_AT to null, fact.COUNTERPARTY to "", fact.COUNTERPARTY_ACCOUNT to "", fact.COUNTERPARTY_ACCOUNT_ATTRS to JsonArray(emptyList()),
                fact.NOTE to "", fact.CATEGORY_ID to null, fact.RECORD_TYPE to null, fact.RECORD_SUBTYPE to null, fact.CREATED_AT to context.now(),
                fact.DELETED_AT to null, fact.DELETED_BY to "", fact.DELETE_REASON to "")
            for ((key, field) in parentFields) if (key in values[index]) defaults[field] = when (key) {
                "account_id" -> values[index][key]?.toString()?.toLong()
                "amount" -> values[index][key]?.let { CashStoredDecimal(it.toString()) }
                "source_payload", "counterparty_account_attrs" -> sourceJson(values[index][key])
                else -> values[index][key]
            }
            defaults
        }
        val ids = parents.map { context.sql.insertInto(fact).set(it).returningResult(fact.ID).fetchSingle().value1()!! }
        val part = CASH_TRANSACTION_COMPONENTS
        val parts = linkedMapOf<Pair<Int, Long>, Long>()
        for ((index, value) in values.withIndex()) for (component in maps(value["components"])) {
            val ordinal = (component["ordinal"] as Number).toLong()
            val id = context.sql.insertInto(part).set(part.WORKSPACE_ID, context.workspace).set(part.CASH_TRANSACTION_ID, ids[index])
                .set(part.ORDINAL, ordinal).set(part.ACCOUNT_ID, component["account_id"].toString().toLong())
                .set(part.AMOUNT, CashStoredDecimal(component["amount"].toString())).set(part.CURRENCY, component["currency"] as String)
                .set(part.LABEL, "").set(part.SOURCE_KEY, "").set(part.METADATA_JSON, JsonObject(emptyMap())).set(part.CREATED_AT, context.now())
                .returningResult(part.ID).fetchSingle().value1()!!
            parts[index to ordinal] = id
        }
        val binding = CASH_SOURCE_BINDINGS
        val bindings = linkedMapOf<String, Map<Field<*>, Any?>>()
        val evidenceOwners = mutableMapOf<String, Long>()
        val owners = mutableMapOf<String, Long>()
        for ((index, entry) in entries.withIndex()) for (row in entry.second) {
            if (row.coverage.documentKey != null || row.documentPage != null) fail("source_document_conflict")
            val evidence = row.stableEvidenceBindingKey
            if (evidence != null && evidenceOwners.getOrPut(evidence) { ids[index] } != ids[index]) fail("source_identity_conflict")
            for (spec in row.bindingSpecs) {
                if (owners.getOrPut(spec.key) { ids[index] } != ids[index]) fail("source_identity_conflict")
                bindings[spec.key] = mapOf(binding.WORKSPACE_ID to context.workspace, binding.TRANSACTION_ID to ids[index], binding.SOURCE_TYPE to spec.sourceType,
                    binding.SOURCE_SCOPE_KIND to spec.scopeKind, binding.SOURCE_SCOPE_KEY to spec.scopeKey, binding.IDENTITY_KIND to spec.identityKind,
                    binding.IDENTITY_KEY to spec.identityKey, binding.IDENTITY_STRENGTH to if (spec.scopeKind == "file_local") "file_local" else if (spec.identityKind == "provider_id") "provider" else "validated",
                    binding.STRATEGY_VERSION to row.parserVersion, binding.COVERAGE to sourceJson(cashSourceCoverageFields(row.coverage)), binding.STABLE_EVIDENCE_KEY to if (spec.scopeKind == "account") evidence else null,
                    binding.REVISION to 1L, binding.CREATED_AT to context.now(), binding.UPDATED_AT to context.now())
            }
        }
        if (bindings.isNotEmpty()) context.sql.batch(bindings.values.map { context.sql.insertInto(binding).set(it) }).execute()
        for ((index, entry) in entries.withIndex()) entry.first["entry_edit"]?.let { raw ->
            val edit = map(raw)
            context.entries.applyEdit(ids[index], edit["patch"], edit["revision"]?.toString()?.toLong())
        }
        return entries.mapIndexed { index, (operation, _) -> CashRecordWriteResult(ids[index], maps(operation["preview_components"]).associate {
            it["component_id"].toString() to parts.getValue(index to (it["ordinal"] as Number).toLong()).toString()
        }, null, map(operation["values"]), emptySet()) }
    }
}
