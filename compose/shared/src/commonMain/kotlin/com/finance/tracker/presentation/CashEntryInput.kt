package com.finance.tracker.presentation

import com.finance.tracker.domain.*

internal val cashPublicEntryFields = setOf("category_id", "note", "counterparty", "counterparty_account", "counterparty_account_attrs")

internal fun cashEntryEditableFields(record: CashCurrentRecord): List<String> {
    if (record.state != "posted") return emptyList()
    val entry = record.entry ?: return emptyList()
    val supported = if (record.recordOrigin == "manual") cashDraftScalarFields.toSet() + "components" else cashPublicEntryFields
    return entry.editableFields.filter { it in supported }
}
internal fun cashEntryInitialValues(record: CashCurrentRecord): Map<String, String> = cashDraftInitialValues(
    record.copy(fields = StructuredObject(record.fields.fields + record.entry?.fields?.fields.orEmpty()), editableFields = cashEntryEditableFields(record)))

internal fun cashEntryRequest(record: CashCurrentRecord, changes: Map<String, String>, timeZoneId: String,
    allocations: List<CashDraftAllocation>? = null): CashMaintenanceRequest {
    val editable = cashEntryEditableFields(record)
    val entry = record.entry
    if (entry == null || editable.isEmpty()) throw DomainFailure("field_not_editable", 0, FailureCategory.RECOVERABLE)
    val patch = cashRecordFieldPatch(editable, changes, timeZoneId, allocations)
    return CashMaintenanceRequest("edit_entry", record.transactionId, record.rowRevision, patch,
        entryId = entry.entryId, expectedEntryRevision = entry.revision)
}
