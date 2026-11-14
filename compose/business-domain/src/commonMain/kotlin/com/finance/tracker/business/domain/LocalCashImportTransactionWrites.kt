package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*

/** Build the source transaction fragment of a completed preview; relation and final entry fragments are composed separately. */
fun buildLocalCashImportTransactionWrites(source: LedgerSnapshotDto, plan: JsonObject,
    rowChoices: JsonArray = JsonArray(emptyList())): List<CashTransactionWriteDto> {
    check(plan["can_commit"] == JsonPrimitive(true)) { "local_import_plan_not_committable" }
    val parents = source.facts.cashTransactions.associateBy { it.id }
    val parts = source.facts.cashTransactionComponents.associateBy { it.id }
    val entries = source.facts.cashEntries.associateBy { it.id }
    val entryMembers = source.facts.cashEntryMembers.associateBy { it.transactionId }
    @Suppress("UNCHECKED_CAST")
    val choices = (prepareLocalCashImportInput(JsonArray(emptyList()), rowChoices, JsonArray(emptyList())).getValue("row_choices") as List<Map<String, Any?>>)
        .associateBy { it.getValue("row_ref") as String }
    return plan.getValue("operations").jsonArray.map { raw ->
        val operation = raw.jsonObject
        val values = operation.getValue("values").jsonObject
        val id = operation.writeText("transaction_id")
        val old = id?.let(parents::getValue)
        val expected = operation.writeText("expected_revision")
        check(expected == old?.rowRevision) { "local_import_transaction_revision_changed" }
        val previewId = operation.getValue("preview_transaction_id").jsonPrimitive.content
        check(old == null || previewId == old.id) { "local_import_transaction_reference_changed" }
        val target = CashWriteVersionedReferenceDto(old?.let { ExistingCashWriteReferenceDto(it.id) }
            ?: NewCashWriteReferenceDto(previewId), expected)
        val previewParts = operation.getValue("preview_components").jsonArray.map { it.jsonObject }
        val partReferences = previewParts.associate { it.getValue("ordinal").jsonPrimitive.content to it.getValue("component_id").jsonPrimitive.content }
        check(partReferences.size == previewParts.size) { "local_import_component_ordinal_repeated" }
        val components = values.getValue("components").jsonArray.map { item ->
            val part = item.jsonObject
            val previous = part["component_id"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content?.let(parts::getValue)
            check(previous == null || previous.cashTransactionId == id) { "local_import_component_owner_changed" }
            val ordinal = part.getValue("ordinal").jsonPrimitive.content
            val reference = partReferences.getValue(ordinal)
            check(previous == null || reference == previous.id) { "local_import_component_reference_changed" }
            CashComponentWriteDto(previous?.let { ExistingCashWriteReferenceDto(it.id) } ?: NewCashWriteReferenceDto(reference),
                part.writeText("account_id"), part.writeText("amount"), part.writeText("currency"), ordinal,
                previous?.label ?: "", previous?.sourceKey ?: "", previous?.metadataJson ?: JsonObject(emptyMap()))
        }
        check(components.size == partReferences.size) { "local_import_component_reference_missing" }
        val selected = operation.getValue("row_refs").jsonArray.firstNotNullOfOrNull { choices[it.jsonPrimitive.content] }
        val edited = if (old?.state != "posted" && selected?.get("action") != "keep_existing") {
            (selected?.get("patch") as? Map<*, *>)?.keys.orEmpty().map { it as String }
                .filter { it in editableCashRecordFields && it !in commonCashFields }.sortedWith(cashRelationStringOrder)
        } else emptyList()
        val pending = operation.getValue("pending_fields").jsonObject.isNotEmpty()
        val metadata = if (pending || operation["planned_action"] == JsonPrimitive("duplicate")) old?.relationMetadata ?: JsonNull
            else operation["source_explanation"] ?: old?.relationMetadata ?: JsonNull
        val entryEdit = operation["entry_edit"]?.takeIf { it != JsonNull }?.jsonObject?.let { edit ->
            val entryId = edit.writeText("entry_id")
            val revision = edit.writeText("revision")
            val entry = entryId?.let(entries::getValue)
            check(entry?.revision == revision && entryMembers[id]?.entryId == entryId) { "local_import_entry_observation_changed" }
            CashEntryBeforeRelationEditDto(entry?.let { CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(it.id), revision) },
                edit.getValue("patch").jsonObject)
        }
        CashTransactionWriteDto(target, CashTransactionWriteValuesDto(
            values.writeText("account_id"), values.getValue("state").jsonPrimitive.content,
            old?.recordOrigin ?: "import", old?.manualTransferId, values.getValue("cash_granularity").jsonPrimitive.content,
            values.writeText("source_type"), values.getValue("record_id").jsonPrimitive.content,
            values.getValue("source_payload"), metadata, old?.sourceFingerprint, old?.manualOverrides ?: JsonObject(emptyMap()),
            values.writeText("occurred_at"), values.writeText("amount"), values.writeText("currency"),
            values.getValue("counterparty").jsonPrimitive.content, values.getValue("counterparty_account").jsonPrimitive.content,
            values.getValue("counterparty_account_attrs"), values.getValue("note").jsonPrimitive.content,
            values.writeText("category_id"), values.writeText("record_type"), values.writeText("record_subtype")),
            components, edited, entryEdit)
    }
}

private fun JsonObject.writeText(key: String): String? = getValue(key).takeIf { it != JsonNull }?.jsonPrimitive?.content
